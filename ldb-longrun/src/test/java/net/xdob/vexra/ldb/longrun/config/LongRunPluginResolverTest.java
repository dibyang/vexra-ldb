package net.xdob.vexra.ldb.longrun.config;

import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginDescriptor;
import net.xdob.vexra.ldb.LdbPluginFailurePolicy;
import net.xdob.vexra.ldb.LdbPluginLoader;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunPluginResolverTest {
  @Test
  void resolvesBuiltInPluginByName() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "diagnostic"
    });

    List<LdbPlugin> plugins = new LongRunPluginResolver().resolve(config);

    assertEquals(1, plugins.size());
    assertEquals("diagnostic", plugins.get(0).descriptor().name());
  }

  @Test
  void skipsDisabledPlugin() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "diagnostic",
        "--ldb.plugin.diagnostic.enabled=false"
    });

    assertTrue(new LongRunPluginResolver().resolve(config).isEmpty());
  }

  @Test
  void resolvesExplicitClasspathPlugin() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", ClasspathPlugin.class.getName()
    });

    List<LdbPlugin> plugins = new LongRunPluginResolver().resolve(config);

    assertEquals(1, plugins.size());
    assertEquals("classpath-plugin", plugins.get(0).descriptor().name());
  }

  @Test
  void doesNotUseServiceLoaderProviderWhenDiscoveryIsDisabled() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider"
    });

    assertThrows(IllegalArgumentException.class, () -> new LongRunPluginResolver().resolve(config));
  }

  @Test
  void resolvesServiceLoaderProviderWhenDiscoveryIsEnabled() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider",
        "--ldb.plugin.discovery.enabled=true",
        "--ldb.plugin.test-provider.version=resolver-test"
    });

    LongRunPluginResolver resolver = new LongRunPluginResolver();
    List<LdbPlugin> plugins = resolver.resolve(config);

    assertTrue(resolver.discoveredProviderNames().contains("test-provider"));
    assertEquals(1, plugins.size());
    assertEquals("test-provider-plugin", plugins.get(0).descriptor().name());
    assertEquals("resolver-test", plugins.get(0).descriptor().version());
  }

  @Test
  void resolvesPackagedSampleProviderWhenDiscoveryIsEnabled() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "sample-audit",
        "--ldb.plugin.discovery.enabled=true",
        "--ldb.plugin.sample-audit.version=resolver-sample"
    });

    LongRunPluginResolver resolver = new LongRunPluginResolver();
    List<LdbPlugin> plugins = resolver.resolve(config);

    assertTrue(resolver.discoveredProviderNames().contains("sample-audit"));
    assertEquals(1, plugins.size());
    assertEquals("sample-audit", plugins.get(0).descriptor().name());
    assertEquals("resolver-sample", plugins.get(0).descriptor().version());
  }

  @Test
  void rejectsServiceLoaderProviderWhenVersionRangeDoesNotMatch() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider",
        "--ldb.plugin.discovery.enabled=true",
        "--ldb.plugin.test-provider.versionRange=[2.0.0,3.0.0)"
    });

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new LongRunPluginResolver().resolve(config));

    assertTrue(error.getMessage().contains("version mismatch"));
    assertTrue(error.getMessage().contains("test-provider"));
  }

  @Test
  void acceptsServiceLoaderProviderWhenVersionRangeMatches() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider",
        "--ldb.plugin.discovery.enabled=true",
        "--ldb.plugin.test-provider.versionRange=[1.0.0,2.0.0)"
    });

    assertEquals(1, new LongRunPluginResolver().resolve(config).size());
  }

  @Test
  void resolvesProviderFromExplicitExternalPluginDirectory() throws Exception {
    File pluginDir = Files.createTempDirectory("longrun-plugin-dir").toFile();
    writeProviderJar(pluginDir);
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider",
        "--ldb.plugin.external.enabled=true",
        "--ldb.plugin.dir=" + pluginDir.getAbsolutePath()
    });

    List<LdbPlugin> plugins = new LongRunPluginResolver().resolve(config);

    assertEquals(1, plugins.size());
    assertEquals("test-provider-plugin", plugins.get(0).descriptor().name());
    assertTrue(plugins.get(0).toString().contains("isolated=true"));
  }

  @Test
  void externalDiscoveryExposesIsolatedLifecycleHandle() throws Exception {
    File pluginDir = Files.createTempDirectory("longrun-plugin-dir-handle").toFile();
    writeProviderJar(pluginDir);

    LdbPluginLoader.Discovery discovery = LdbPluginLoader.discoverProvidersWithHandle(pluginDir);

    assertTrue(discovery.isolated());
    assertTrue(discovery.source().contains(pluginDir.getName()));
    assertTrue(containsProvider(discovery.providers(), "test-provider"));
    discovery.close();
  }

  private static boolean containsProvider(List<net.xdob.vexra.ldb.LdbPluginProvider> providers, String name) {
    for (net.xdob.vexra.ldb.LdbPluginProvider provider : providers) {
      if (name.equals(provider.name())) {
        return true;
      }
    }
    return false;
  }

  @Test
  void resolvesExternalProviderWithVersionRangeAndOrderOverride() throws Exception {
    File pluginDir = Files.createTempDirectory("longrun-plugin-dir-composed").toFile();
    writeProviderJar(pluginDir);
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider",
        "--ldb.plugin.external.enabled=true",
        "--ldb.plugin.dir=" + pluginDir.getAbsolutePath(),
        "--ldb.plugin.test-provider.versionRange=[1.0.0,2.0.0)",
        "--ldb.plugin.test-provider.order=-7"
    });

    List<LdbPlugin> plugins = new LongRunPluginResolver().resolve(config);

    assertEquals(1, plugins.size());
    assertEquals("test-provider-plugin", plugins.get(0).descriptor().name());
    assertEquals(-7, plugins.get(0).descriptor().order());
  }

  @Test
  void doesNotScanExternalPluginDirectoryWhenDisabled() throws Exception {
    File pluginDir = Files.createTempDirectory("longrun-plugin-dir-disabled").toFile();
    writeProviderJar(pluginDir);
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "test-provider",
        "--ldb.plugin.dir=" + pluginDir.getAbsolutePath()
    });

    assertThrows(IllegalArgumentException.class, () -> new LongRunPluginResolver().resolve(config));
  }

  @Test
  void appliesOrderOverrideToResolvedPlugin() throws Exception {
    LongRunConfig config = LongRunConfig.load(new String[] {
        "-c", "smoke",
        "-P", "diagnostic," + ClasspathPlugin.class.getName(),
        "--ldb.plugin.diagnostic.order=10",
        "--ldb.plugin." + ClasspathPlugin.class.getName() + ".order=-10"
    });

    List<LdbPlugin> plugins = new LongRunPluginResolver().resolve(config);

    assertEquals(10, plugins.get(0).descriptor().order());
    assertEquals(-10, plugins.get(1).descriptor().order());
  }

  public static final class ClasspathPlugin implements LdbPlugin {
    @Override
    public LdbPluginDescriptor descriptor() {
      return new LdbPluginDescriptor("classpath-plugin", "test", 0, LdbPluginFailurePolicy.FAIL_FAST);
    }
  }

  private static void writeProviderJar(File pluginDir) throws Exception {
    File jar = new File(pluginDir, "test-provider.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
      out.putNextEntry(new JarEntry("META-INF/services/net.xdob.vexra.ldb.LdbPluginProvider"));
      out.write(TestLongRunPluginProvider.class.getName().getBytes(StandardCharsets.UTF_8));
      out.write('\n');
      out.closeEntry();
    }
  }
}
