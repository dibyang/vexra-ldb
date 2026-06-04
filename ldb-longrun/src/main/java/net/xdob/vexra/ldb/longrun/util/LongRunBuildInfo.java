package net.xdob.vexra.ldb.longrun.util;

import net.xdob.vexra.ldb.impl.LDBFactory;

import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.util.Properties;

/**
 * longrun 构建信息读取器，用于在测试日志中标记被测组件版本。
 */
public final class LongRunBuildInfo {
  private static final String RESOURCE = "/META-INF/ldb-longrun/build.properties";
  private static final Properties PROPERTIES = load();

  private LongRunBuildInfo() {
  }

  /**
   * 返回被测组件版本日志行。
   *
   * @return 形如 COMPONENT name=... version=... 的日志内容
   */
  public static String testedComponentLine() {
    ComponentInfo info = testedComponent();
    return "COMPONENT name=" + info.name
        + " version=" + info.version
        + " source=" + info.source;
  }

  private static ComponentInfo testedComponent() {
    String name = property("tested.component.name");
    String source = codeSource(LDBFactory.class);
    String version = packageVersion(LDBFactory.class);
    if (isUnknown(version)) {
      version = mavenVersion();
    }
    if (isUnknown(version)) {
      version = versionFromSource(source, name);
    }
    if (isUnknown(version)) {
      version = property("tested.component.version");
    }
    return new ComponentInfo(name, version, source);
  }

  private static String packageVersion(Class<?> type) {
    Package pkg = type.getPackage();
    if (pkg == null) {
      return "unknown";
    }
    return valueOrUnknown(pkg.getImplementationVersion());
  }

  private static String mavenVersion() {
    String[] resources = {
        "/META-INF/maven/net.xdob.vexra/vexra-ldb/pom.properties",
        "/META-INF/maven/net.xdob.vexra/ldb/pom.properties"
    };
    for (String resource : resources) {
      Properties properties = new Properties();
      try (InputStream in = LongRunBuildInfo.class.getResourceAsStream(resource)) {
        if (in != null) {
          properties.load(in);
          String version = properties.getProperty("version");
          if (!isUnknown(version)) {
            return version.trim();
          }
        }
      } catch (Exception ignored) {
      }
    }
    return "unknown";
  }

  private static String versionFromSource(String source, String componentName) {
    if (isUnknown(source) || isUnknown(componentName)) {
      return "unknown";
    }
    String normalized = source.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    String prefix = componentName + "-";
    if (fileName.startsWith(prefix) && fileName.endsWith(".jar")) {
      return fileName.substring(prefix.length(), fileName.length() - ".jar".length());
    }
    return "unknown";
  }

  private static String codeSource(Class<?> type) {
    try {
      CodeSource codeSource = type.getProtectionDomain().getCodeSource();
      if (codeSource == null) {
        return "unknown";
      }
      URL location = codeSource.getLocation();
      if (location == null) {
        return "unknown";
      }
      return URLDecoder.decode(location.getPath(), "UTF-8");
    } catch (Exception e) {
      return "unknown";
    }
  }

  private static String property(String name) {
    String value = PROPERTIES.getProperty(name);
    return valueOrUnknown(value);
  }

  private static String valueOrUnknown(String value) {
    return isUnknown(value) ? "unknown" : value.trim();
  }

  private static boolean isUnknown(String value) {
    return value == null || value.trim().isEmpty() || "unknown".equals(value.trim());
  }

  private static Properties load() {
    Properties properties = new Properties();
    try (InputStream in = LongRunBuildInfo.class.getResourceAsStream(RESOURCE)) {
      if (in != null) {
        properties.load(in);
      }
    } catch (Exception ignored) {
    }
    return properties;
  }

  private static final class ComponentInfo {
    private final String name;
    private final String version;
    private final String source;

    private ComponentInfo(String name, String version, String source) {
      this.name = name;
      this.version = version;
      this.source = source;
    }
  }
}
