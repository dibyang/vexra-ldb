package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第十八阶段旧版本升级兼容测试。
 *
 * 该测试通过隔离 classloader 调用本地 Maven 缓存中的 0.4.0 版本创建真实旧库样本，
 * 再用当前实现验证打开、读取、check、backup/restore 的升级闭环。
 */
class LdbUpgradeCompatibilityTest {
  private static final String OLD_VERSION = "0.4.0";
  private static final int RUNTIME_CF_ID = 44;
  private static final String RUNTIME_CF_NAME = "upgrade-cf";

  @TempDir
  File tempDir;

  @Test
  void shouldOpenAndMaintainBackupFor040Database() throws Exception {
    File oldJar = oldVersionJar();
    boolean required = Boolean.getBoolean("ldb.upgrade.fixture.required");
    if (required) {
      assertTrue(oldJar.isFile(), "Missing required upgrade fixture jar: " + oldJar);
    } else {
      assumeTrue(oldJar.isFile(), "Missing optional upgrade fixture jar: " + oldJar);
    }

    File dbDir = new File(tempDir, "db-created-by-0.4.0");
    File backupRoot = new File(tempDir, "upgrade-backups");
    File restoreDir = new File(tempDir, "upgrade-restore");

    createDatabaseWithOldVersion(oldJar, dbDir);

    LDBFactory.CheckReport check = LDBFactory.factory.check(dbDir, new Options().createIfMissing(false));
    assertTrue(check.isOk(), check.toString());

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("from-0.4.0"), db.get(bytes("upgrade:basic")));
      LdbColumnFamily cf = db.getColumnFamily(RUNTIME_CF_ID);
      assertEquals(RUNTIME_CF_NAME, cf.getName());
      assertArrayEquals(bytes("runtime-0.4.0"), db.get(cf, bytes("upgrade:cf")));
    }

    LDBFactory.BackupReport backup =
        LDBFactory.factory.createBackup(dbDir, backupRoot, new Options().createIfMissing(false));
    assertTrue(backup.isOk(), backup.toString());
    LDBFactory.BackupReport restore =
        LDBFactory.factory.restoreBackup(backup.getTargetDir(), restoreDir, new Options().createIfMissing(false));
    assertTrue(restore.isOk(), restore.toString());

    try (LDB restored = LDBFactory.factory.open(restoreDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("from-0.4.0"), restored.get(bytes("upgrade:basic")));
      LdbColumnFamily cf = restored.getColumnFamily(RUNTIME_CF_ID);
      assertArrayEquals(bytes("runtime-0.4.0"), restored.get(cf, bytes("upgrade:cf")));
    }
  }

  private static File oldVersionJar() {
    String configured = System.getProperty("ldb.upgrade.0_4_0.jar");
    if (configured != null && !configured.trim().isEmpty()) {
      return new File(configured);
    }
    return new File(new File(System.getProperty("user.home"), ".m2/repository"),
        "net/xdob/vexra/vexra-ldb/" + OLD_VERSION + "/vexra-ldb-" + OLD_VERSION + ".jar");
  }

  private static void createDatabaseWithOldVersion(File oldJar, File dbDir) throws Exception {
    List<URL> urls = oldRuntimeUrls(oldJar);
    try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
      Class<?> factoryClass = loader.loadClass("net.xdob.vexra.ldb.impl.LDBFactory");
      Class<?> optionsClass = loader.loadClass("net.xdob.vexra.ldb.Options");
      Object options = optionsClass.getConstructor().newInstance();
      optionsClass.getMethod("createIfMissing", boolean.class).invoke(options, true);
      optionsClass.getMethod("writeBufferSize", int.class).invoke(options, 128);

      Object factory = factoryClass.getField("factory").get(null);
      Object db = factoryClass.getMethod("open", File.class, optionsClass).invoke(factory, dbDir, options);
      try {
        invoke(db, "put", new Class<?>[]{byte[].class, byte[].class},
            bytes("upgrade:basic"), bytes("from-0.4.0"));
        Object cf = invoke(db, "createColumnFamily", new Class<?>[]{int.class, String.class},
            RUNTIME_CF_ID, RUNTIME_CF_NAME);
        Class<?> cfClass = loader.loadClass("net.xdob.vexra.ldb.LdbColumnFamily");
        invoke(db, "put", new Class<?>[]{cfClass, byte[].class, byte[].class},
            cf, bytes("upgrade:cf"), bytes("runtime-0.4.0"));
      } finally {
        invoke(db, "close", new Class<?>[0]);
      }
    }
  }

  private static List<URL> oldRuntimeUrls(File oldJar) throws Exception {
    List<URL> urls = new ArrayList<>();
    urls.add(oldJar.toURI().toURL());
    String[] classPath = System.getProperty("java.class.path", "").split(File.pathSeparator);
    for (String entry : classPath) {
      File file = new File(entry);
      String name = file.getName();
      if (file.isFile() && name.endsWith(".jar")
          && (name.startsWith("guava-") || name.startsWith("lz4-java-") || name.startsWith("slf4j-api-"))) {
        urls.add(file.toURI().toURL());
      }
    }
    return urls;
  }

  private static Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Exception {
    Method handle = target.getClass().getMethod(method, types);
    try {
      return handle.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
