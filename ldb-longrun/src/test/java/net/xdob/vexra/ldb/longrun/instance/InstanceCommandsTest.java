package net.xdob.vexra.ldb.longrun.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstanceCommandsTest {
  @Test
  void formatsWorkerProgressWithBar() {
    String line = "PROGRESS timeMillis=1 progressPercent=50.00 operations=10";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertEquals("PROGRESS [##########----------]  50% ops=10", formatted);
  }

  @Test
  void formatsParentProgressWithBar() {
    String line = "[parent pid=2249684] PROGRESS percent=80 elapsedMillis=240018 operations=5496000";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertTrue(formatted.startsWith("[parent pid=2249684] PROGRESS [################----]  80%"));
    assertTrue(formatted.contains("ops=5496000"));
    assertTrue(!formatted.contains("percent=80 "));
    assertTrue(!formatted.contains("elapsedMillis=240018"));
  }

  @Test
  void formatsPerformanceProgressWithCompactRates() {
    String line = "PROGRESS timeMillis=1 progressPercent=50.00 operations=10 "
        + "windowOpsPerSecond=1234.560 avgOpsPerSecond=1000.400 "
        + "minOpsPerSecond=900.100 maxOpsPerSecond=1300.900 activeKeys=7 "
        + "ldbWriteSlowdownCount=2 ldbImmutableWaitCount=3 "
        + "ldbLevel0StopWaitCount=4 ldbCompactionRunCount=5 ldbCompactionBacklog=true";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertEquals("PROGRESS [##########----------]  50% ops=10 win=1235/s "
        + "avg=1000/s min=900/s max=1301/s keys=7 slow=2 imm=3 "
        + "l0wait=4 comp=5 backlog=true", formatted);
  }

  @Test
  void formatsCrashProgressWithPhaseAndCycles() {
    String line = "CRASH PROGRESS progressPercent=16.67 completedCycles=1 totalCycles=6 phase=recovery";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertEquals("CRASH PROGRESS [###-----------------]  17% phase=recovery cycles=1/6", formatted);
  }

  @Test
  void formatsFinalVerifyProgressWithCounts() {
    String line = "FINAL PROGRESS phase=verify progressPercent=50.00 verified=100 total=200 elapsedMillis=3000";

    String formatted = InstanceCommands.formatProgressLineForConsole(line);

    assertEquals("FINAL PROGRESS [##########----------]  50% verified=100 total=200 phase=verify", formatted);
  }

  @Test
  void keepsProgressLineWithoutPercentUnchanged() {
    String line = "PROGRESS operations=10";

    assertEquals(line, InstanceCommands.formatProgressLineForConsole(line));
  }
}
