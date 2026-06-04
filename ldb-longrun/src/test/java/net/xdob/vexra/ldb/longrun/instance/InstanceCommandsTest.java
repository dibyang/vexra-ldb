package net.xdob.vexra.ldb.longrun.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstanceCommandsTest {
  @Test
  void formatsWorkerProgressWithBar() {
    String line = "PROGRESS timeMillis=1 progressPercent=50.00 operations=10";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertEquals("PROGRESS [##########----------]  50% timeMillis=1 operations=10", formatted);
  }

  @Test
  void formatsParentProgressWithBar() {
    String line = "[parent pid=2249684] PROGRESS percent=80 elapsedMillis=240018 operations=5496000";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertTrue(formatted.startsWith("[parent pid=2249684] PROGRESS [################----]  80%"));
    assertTrue(formatted.contains("elapsedMillis=240018"));
    assertTrue(!formatted.contains("percent=80 "));
  }

  @Test
  void keepsProgressLineWithoutPercentUnchanged() {
    String line = "PROGRESS operations=10";

    assertEquals(line, InstanceCommands.formatProgressLineForConsole(line));
  }
}
