package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LDB 插件增强回归测试。
 *
 * 这些用例约束插件提交前、提交后、checkpoint、统计属性和只读视图的行为边界，
 * 避免插件扩展点在后续演进中破坏写入提交语义。
 */
class LdbPluginTest {
  @TempDir
  File tempDir;

  @Test
  void shouldReportPostCommitAfterWriteFailureWithoutRollingBackData() throws Exception {
    File dbDir = new File(tempDir, "after-write-failure-db");
    FailingAfterWritePlugin plugin = new FailingAfterWritePlugin(LdbPluginFailurePolicy.FAIL_FAST);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true).addPlugin(plugin))) {
      DBException error = assertThrows(DBException.class,
          () -> db.put(bytes("committed"), bytes("value"), new WriteOptions().sync(true)));

      assertTrue(error.getMessage().contains("post-commit notification failed"), error.getMessage());
      assertTrue(error.getMessage().contains("data was committed"), error.getMessage());
      assertArrayEquals(bytes("value"), db.get(bytes("committed")));
      assertTrue(db.getProperty("ldb.pluginStats").contains("failures=1"));
      assertTrue(db.getProperty("ldb.plugin.lastFailure").contains("committed=true"));
    }

    try (LDB reopened = LDBFactory.factory.open(dbDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("value"), reopened.get(bytes("committed")));
    }
  }

  @Test
  void shouldRecordAndContinuePostCommitPluginFailureWhenPolicyAllowsIt() throws Exception {
    File dbDir = new File(tempDir, "record-continue-db");
    FailingAfterWritePlugin failing = new FailingAfterWritePlugin(LdbPluginFailurePolicy.RECORD_AND_CONTINUE);
    RecordingPlugin following = new RecordingPlugin("following");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .addPlugin(failing)
        .addPlugin(following))) {
      db.put(bytes("k"), bytes("v"));

      assertArrayEquals(bytes("v"), db.get(bytes("k")));
      assertTrue(following.events.contains("afterWrite"));
      assertTrue(db.getProperty("ldb.pluginStats").contains("failures=1"));
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("failurePolicy=RECORD_AND_CONTINUE"));
      assertTrue(db.getProperty("ldb.plugin.1.stats").contains("afterWrite.count=1"));
    }
  }

  @Test
  void shouldExposeReadOnlyOptionsViewAndWriteEvent() throws Exception {
    File dbDir = new File(tempDir, "readonly-plugin-view-db");
    EventOnlyPlugin plugin = new EventOnlyPlugin();

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .writeBufferSize(4096)
        .addPlugin(plugin))) {
      db.put(bytes("event"), bytes("value"), new WriteOptions().snapshot(true));

      assertEquals(4096, plugin.optionsView.writeBufferSize());
      assertThrows(UnsupportedOperationException.class,
          () -> plugin.optionsView.getColumnFamilies().clear());
      assertEquals(1, plugin.beforeBatchSize);
      assertFalse(plugin.beforeCommitted);
      assertTrue(plugin.afterCommitted);
      assertNotNull(plugin.afterSnapshot);
      assertTrue(db.getProperty("ldb.plugins").contains("event-only"));
    }
  }

  @Test
  void shouldReportCheckpointPostCommitFailureAndKeepCheckpointUsable() throws Exception {
    File dbDir = new File(tempDir, "checkpoint-plugin-source");
    File checkpointDir = new File(tempDir, "checkpoint-plugin-target");
    FailingAfterCheckpointPlugin plugin = new FailingAfterCheckpointPlugin();

    try (LDB db = LDBFactory.factory.open(dbDir, new Options().createIfMissing(true).addPlugin(plugin))) {
      db.put(bytes("before"), bytes("checkpoint"));

      DBException error = assertThrows(DBException.class,
          () -> db.checkpoint(checkpointDir.getAbsolutePath()));

      assertTrue(error.getMessage().contains("post-commit notification failed"), error.getMessage());
      assertTrue(new File(checkpointDir, "CHECKPOINT-REPORT.json").isFile());
      assertTrue(db.getProperty("ldb.plugin.lastFailure").contains("afterCheckpoint"));
    }

    try (LDB checkpoint = LDBFactory.factory.open(checkpointDir, new Options().createIfMissing(false))) {
      assertArrayEquals(bytes("checkpoint"), checkpoint.get(bytes("before")));
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static LdbPluginDescriptor pluginDescriptor(String name, LdbPluginFailurePolicy policy) {
    return new LdbPluginDescriptor(name, "test", 0, policy);
  }

  private static class FailingAfterWritePlugin implements LdbPlugin {
    private final LdbPluginFailurePolicy policy;

    private FailingAfterWritePlugin(LdbPluginFailurePolicy policy) {
      this.policy = policy;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor("failing-after-write", policy);
    }

    @Override
    public void afterWrite(WriteEvent event) {
      assertTrue(event.isCommitted());
      throw new DBException("after write failed");
    }
  }

  private static class FailingAfterCheckpointPlugin implements LdbPlugin {
    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor("failing-after-checkpoint", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void afterCheckpoint(File targetDir) {
      throw new DBException("after checkpoint failed");
    }
  }

  private static class RecordingPlugin implements LdbPlugin {
    private final String name;
    private final List<String> events = new ArrayList<>();

    private RecordingPlugin(String name) {
      this.name = name;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor(name, LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void afterWrite(WriteEvent event) {
      events.add("afterWrite");
    }
  }

  private static class EventOnlyPlugin implements LdbPlugin {
    private OptionsView optionsView;
    private int beforeBatchSize;
    private boolean beforeCommitted;
    private boolean afterCommitted;
    private Snapshot afterSnapshot;

    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor("event-only", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void onOpen(LdbPluginContext context) {
      this.optionsView = context.getOptionsView();
      context.getOptions().writeBufferSize(1);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      beforeBatchSize = event.getBatch().size();
      beforeCommitted = event.isCommitted();
      assertTrue(event.getBatch().getColumnFamilies().contains(LdbColumnFamily.DEFAULT));
    }

    @Override
    public void afterWrite(WriteEvent event) {
      afterCommitted = event.isCommitted();
      afterSnapshot = event.getSnapshot();
      assertEquals(1, event.getBatch().size());
    }
  }
}
