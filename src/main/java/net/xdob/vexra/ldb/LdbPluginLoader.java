package net.xdob.vexra.ldb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * LDB 插件发现工具。
 *
 * <p>该工具只负责发现 classpath 上的 provider，不自动启用插件，避免隐式改变写入路径。
 */
public final class LdbPluginLoader {
  private LdbPluginLoader() {
  }

  /**
   * 插件发现结果句柄。
   *
   * <p>外部插件目录会持有独立的 URLClassLoader。调用方在插件生命周期结束后必须关闭该句柄，
   * 避免 jar 文件句柄和隔离加载器长期滞留。classpath 发现返回的句柄是 no-op。
   */
  public static final class Discovery implements Closeable {
    private final List<LdbPluginProvider> providers;
    private final ClassLoader classLoader;
    private final boolean isolated;
    private final String source;
    private final Closeable closeable;

    private Discovery(List<LdbPluginProvider> providers, ClassLoader classLoader,
                      boolean isolated, String source, Closeable closeable) {
      this.providers = providers;
      this.classLoader = classLoader;
      this.isolated = isolated;
      this.source = source;
      this.closeable = closeable;
    }

    /**
     * 返回本次发现到的 provider 快照。
     */
    public List<LdbPluginProvider> providers() {
      return providers;
    }

    /**
     * 返回用于发现 provider 的 classloader。
     */
    public ClassLoader classLoader() {
      return classLoader;
    }

    /**
     * 返回是否来自外部隔离 classloader。
     */
    public boolean isolated() {
      return isolated;
    }

    /**
     * 返回发现来源，供诊断和测试使用。
     */
    public String source() {
      return source;
    }

    /**
     * 关闭外部发现资源；classpath 发现为 no-op。
     */
    @Override
    public void close() throws IOException {
      if (closeable != null) {
        closeable.close();
      }
    }
  }

  /**
   * 使用当前线程上下文 classloader 发现插件 provider。
   *
   * @return provider 快照，按 provider 名称稳定排序
   */
  public static List<LdbPluginProvider> discoverProviders() {
    return discoverProviders(Thread.currentThread().getContextClassLoader());
  }

  /**
   * 使用指定 classloader 发现插件 provider。
   *
   * @param classLoader classloader，可为空
   * @return provider 快照，按 provider 名称稳定排序
   */
  public static List<LdbPluginProvider> discoverProviders(ClassLoader classLoader) {
    ServiceLoader<LdbPluginProvider> loader = classLoader == null
        ? ServiceLoader.load(LdbPluginProvider.class)
        : ServiceLoader.load(LdbPluginProvider.class, classLoader);
    List<LdbPluginProvider> providers = new ArrayList<>();
    for (LdbPluginProvider provider : loader) {
      providers.add(provider);
    }
    Collections.sort(providers, new java.util.Comparator<LdbPluginProvider>() {
      @Override
      public int compare(LdbPluginProvider left, LdbPluginProvider right) {
        return left.name().compareTo(right.name());
      }
    });
    return Collections.unmodifiableList(providers);
  }

  /**
   * 使用当前线程上下文 classloader 发现 provider，并返回生命周期句柄。
   */
  public static Discovery discoverProvidersWithHandle() {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    return new Discovery(discoverProviders(classLoader), classLoader, false, "classpath", null);
  }

  /**
   * 从显式配置的外部 jar 目录发现 provider。该方法只发现 provider，不自动启用插件。
   *
   * <p>新代码应优先使用 {@link #discoverProvidersWithHandle(File)}，由调用方在插件关闭时释放外部加载器。
   *
   * @param pluginDir 外部插件 jar 目录
   * @return provider 快照
   */
  public static List<LdbPluginProvider> discoverProviders(File pluginDir) {
    return discoverProvidersWithHandle(pluginDir).providers();
  }

  /**
   * 从外部 jar 目录发现 provider，并返回隔离 classloader 的生命周期句柄。
   *
   * @param pluginDir 外部插件 jar 目录
   * @return provider 快照和可关闭的发现句柄
   */
  public static Discovery discoverProvidersWithHandle(File pluginDir) {
    if (pluginDir == null) {
      return new Discovery(Collections.<LdbPluginProvider>emptyList(), null, false, "", null);
    }
    if (!pluginDir.isDirectory()) {
      throw new IllegalArgumentException("plugin dir is not a directory: " + pluginDir);
    }
    File[] jars = pluginDir.listFiles(new java.io.FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.endsWith(".jar");
      }
    });
    if (jars == null || jars.length == 0) {
      return new Discovery(Collections.<LdbPluginProvider>emptyList(), null, true,
          pluginDir.getAbsolutePath(), null);
    }
    try {
      List<URL> urls = new ArrayList<>();
      for (File jar : jars) {
        urls.add(jar.toURI().toURL());
      }
      ClassLoader parent = Thread.currentThread().getContextClassLoader();
      URLClassLoader classLoader = new ExternalPluginClassLoader(urls.toArray(new URL[0]), parent);
      return new Discovery(discoverProviders(classLoader), classLoader, true,
          pluginDir.getAbsolutePath(), classLoader);
    } catch (java.net.MalformedURLException e) {
      throw new IllegalArgumentException("failed to load plugin dir: " + pluginDir, e);
    }
  }

  private static final class ExternalPluginClassLoader extends URLClassLoader {
    private ExternalPluginClassLoader(URL[] urls, ClassLoader parent) {
      super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          if (parentFirst(name)) {
            loaded = loadParentFirst(name);
          } else {
            try {
              loaded = findClass(name);
            } catch (ClassNotFoundException ignored) {
              loaded = loadParentFirst(name);
            }
          }
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    private Class<?> loadParentFirst(String name) throws ClassNotFoundException {
      ClassLoader parent = getParent();
      return parent == null ? findSystemClass(name) : parent.loadClass(name);
    }

    private boolean parentFirst(String name) {
      return name.startsWith("java.")
          || name.startsWith("javax.")
          || name.startsWith("sun.")
          || name.startsWith("com.sun.")
          || name.startsWith("jdk.")
          || name.startsWith("net.xdob.vexra.ldb.");
    }
  }
}
