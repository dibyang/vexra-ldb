package net.xdob.vexra.ldb.longrun.instance;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class WorkDirLockTest {
  @Test
  void rejectsSecondLockForSameWorkDir() throws Exception {
    File workDir = Files.createTempDirectory("longrun-lock").toFile();
    try (WorkDirLock ignored = WorkDirLock.acquire(workDir)) {
      assertThrows(Exception.class, () -> WorkDirLock.acquire(workDir));
    }
  }
}
