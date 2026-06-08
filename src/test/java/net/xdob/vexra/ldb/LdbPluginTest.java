package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

  @Test
  void shouldExposeCapabilitiesAndCompatibilityMarker() throws Exception {
    File dbDir = new File(tempDir, "plugin-capabilities-db");
    CapabilityPlugin capable = new CapabilityPlugin();
    RecordingPlugin compatibility = new RecordingPlugin("compatibility-plugin");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .addPlugin(capable)
        .addPlugin(compatibility))) {
      db.put(bytes("capability"), bytes("value"));

      assertTrue(db.getProperty("ldb.plugins").contains("capabilities=METADATA_READ|OBSERVE_WRITE"));
      assertTrue(db.getProperty("ldb.plugins").contains("compatibility-plugin:order=0:capabilities=compatibility"));
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("capabilities=METADATA_READ|OBSERVE_WRITE"));
      assertTrue(db.getProperty("ldb.plugin.1.stats").contains("capabilities=compatibility"));
    }
  }

  @Test
  void shouldRunPluginsInDescriptorOrderAndKeepRegistrationOrderForTies() throws Exception {
    File dbDir = new File(tempDir, "plugin-order-db");
    File checkpointDir = new File(tempDir, "plugin-order-checkpoint");
    List<String> events = new ArrayList<>();

    OrderedPlugin zeroA = new OrderedPlugin("zero-a", 0, events);
    OrderedPlugin high = new OrderedPlugin("high", 10, events);
    OrderedPlugin low = new OrderedPlugin("low", -10, events);
    OrderedPlugin zeroB = new OrderedPlugin("zero-b", 0, events);

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .addPlugin(zeroA)
        .addPlugin(high)
        .addPlugin(low)
        .addPlugin(zeroB))) {
      db.put(bytes("ordered"), bytes("value"));
      db.checkpoint(checkpointDir.getAbsolutePath());

      assertTrue(db.getProperty("ldb.plugins").startsWith(
          "0:low:order=-10:capabilities=compatibility,1:zero-a:order=0:capabilities=compatibility,"
              + "2:zero-b:order=0:capabilities=compatibility,3:high:order=10:capabilities=compatibility"));
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("name=low"));
      assertTrue(db.getProperty("ldb.plugin.1.stats").contains("name=zero-a"));
    }

    assertPhaseOrder(events, "onOpen", "low", "zero-a", "zero-b", "high");
    assertPhaseOrder(events, "beforeWrite", "low", "zero-a", "zero-b", "high");
    assertPhaseOrder(events, "afterWrite", "low", "zero-a", "zero-b", "high");
    assertPhaseOrder(events, "beforeCheckpoint", "low", "zero-a", "zero-b", "high");
    assertPhaseOrder(events, "afterCheckpoint", "low", "zero-a", "zero-b", "high");
    assertPhaseOrder(events, "beforeClose", "low", "zero-a", "zero-b", "high");
    assertPhaseOrder(events, "close", "low", "zero-a", "zero-b", "high");
  }

  @Test
  void shouldAllowLegacyBatchMutationWhenCapabilityEnforcementIsDisabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-mutation-compat-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .addPlugin(new MutatingPlugin("legacy-mutator", false)))) {
      db.put(bytes("original"), bytes("value"));

      assertArrayEquals(bytes("value"), db.get(bytes("original")));
      assertArrayEquals(bytes("plugin"), db.get(bytes("mutated")));
    }
  }

  @Test
  void shouldRejectBatchMutationWithoutCapabilityWhenEnforcementIsEnabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-mutation-rejected-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new MutatingPlugin("unsafe-mutator", false)))) {
      DBException error = assertThrows(DBException.class,
          () -> db.put(bytes("original"), bytes("value")));

      assertTrue(error.getMessage().contains("without MUTATE_WRITE_BATCH capability"));
      assertNull(db.get(bytes("original")));
      assertNull(db.get(bytes("mutated")));
    }
  }

  @Test
  void shouldAllowBatchMutationWithCapabilityWhenEnforcementIsEnabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-mutation-allowed-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new MutatingPlugin("safe-mutator", true)))) {
      db.put(bytes("original"), bytes("value"));

      assertArrayEquals(bytes("value"), db.get(bytes("original")));
      assertArrayEquals(bytes("plugin"), db.get(bytes("mutated")));
    }
  }

  @Test
  void shouldRecordSlowPluginTimeoutAndDisableLaterCallbacks() throws Exception {
    File dbDir = new File(tempDir, "plugin-timeout-db");
    SlowPlugin plugin = new SlowPlugin();

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCallbackTimeoutMillis(1)
        .pluginAutoDisableOnTimeout(true)
        .addPlugin(plugin))) {
      db.put(bytes("first"), bytes("value"));
      db.put(bytes("second"), bytes("value"));

      assertEquals(1, plugin.beforeWrites);
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("timeouts=1"));
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("disabled=true"));
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("degradationReason=timeout:beforeWrite"));
      assertEquals("true", db.getProperty("ldb.plugin.degraded"));
      assertEquals("0:slow-plugin", db.getProperty("ldb.plugin.disabled"));
      assertEquals("false", db.getProperty("ldb.plugin.sandbox"));
    }
  }

  @Test
  void shouldRunAfterWriteAsynchronouslyAndWaitOnClose() throws Exception {
    File dbDir = new File(tempDir, "plugin-async-after-write-db");
    AsyncAfterWritePlugin plugin = new AsyncAfterWritePlugin();
    LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginAsyncEnabled(true)
        .pluginAsyncCloseTimeoutMillis(1000)
        .addPlugin(plugin));

    db.put(bytes("async"), bytes("value"));

    assertArrayEquals(bytes("value"), db.get(bytes("async")));
    assertTrue(db.getProperty("ldb.plugin.executionPolicy").contains("asyncEnabled=true"));
    assertTrue(db.getProperty("ldb.plugin.asyncStats").contains("enabled=true"));
    assertTrue(db.getProperty("ldb.plugin.asyncStats").contains("submitted=1"));

    db.close();

    assertTrue(plugin.completed.await(1, TimeUnit.SECONDS));
    assertEquals(1, plugin.afterWrites);
  }

  @Test
  void shouldKeepBeforeWriteSynchronousWhenAsyncIsEnabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-async-before-write-db");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginAsyncEnabled(true)
        .addPlugin(new FailingBeforeWritePlugin()))) {
      assertThrows(DBException.class, () -> db.put(bytes("sync-before"), bytes("value")));
      assertNull(db.get(bytes("sync-before")));
    }
  }

  @Test
  void shouldRejectMetadataAccessWithoutCapabilityWhenEnforcementIsEnabled() {
    File dbDir = new File(tempDir, "plugin-metadata-rejected-db");

    DBException error = assertThrows(DBException.class, () -> LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new MetadataReadingPlugin(false))));

    assertTrue(error.getMessage().contains("requires METADATA_READ capability"));
  }

  @Test
  void shouldAllowMetadataAccessWithCapabilityWhenEnforcementIsEnabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-metadata-allowed-db");
    MetadataReadingPlugin plugin = new MetadataReadingPlugin(true);

    try (LDB ignored = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(plugin))) {
      assertTrue(plugin.opened);
    }
  }

  @Test
  void shouldRejectCheckpointHookWithoutCapabilityWhenEnforcementIsEnabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-checkpoint-rejected-db");
    File checkpointDir = new File(tempDir, "plugin-checkpoint-rejected-target");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new CheckpointPlugin(false)))) {
      DBException error = assertThrows(DBException.class,
          () -> db.checkpoint(checkpointDir.getAbsolutePath()));

      assertTrue(error.getMessage().contains("requires CHECKPOINT_HOOK capability"));
    }
  }

  @Test
  void shouldNotRequireCheckpointCapabilityForPluginsWithoutCheckpointHooks() throws Exception {
    File dbDir = new File(tempDir, "plugin-checkpoint-nohook-db");
    File checkpointDir = new File(tempDir, "plugin-checkpoint-nohook-target");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new CapabilityPlugin()))) {
      db.put(bytes("k"), bytes("v"));
      db.checkpoint(checkpointDir.getAbsolutePath());

      assertTrue(new File(checkpointDir, "CHECKPOINT-REPORT.json").isFile());
    }
  }

  @Test
  void shouldAllowCheckpointHookWithCapabilityWhenEnforcementIsEnabled() throws Exception {
    File dbDir = new File(tempDir, "plugin-checkpoint-allowed-db");
    File checkpointDir = new File(tempDir, "plugin-checkpoint-allowed-target");

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new CheckpointPlugin(true)))) {
      db.checkpoint(checkpointDir.getAbsolutePath());

      assertTrue(new File(checkpointDir, "CHECKPOINT-REPORT.json").isFile());
    }
  }

  @Test
  void shouldRejectContextBatchCreationWithoutCapabilityWhenEnforcementIsEnabled() {
    File dbDir = new File(tempDir, "plugin-context-batch-rejected-db");

    DBException error = assertThrows(DBException.class, () -> LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginCapabilityEnforcement(true)
        .addPlugin(new ContextBatchPlugin(false))));

    assertTrue(error.getMessage().contains("requires MUTATE_WRITE_BATCH capability"));
  }

  @Test
  void shouldDisablePluginAfterTotalCallbackBudgetIsExceeded() throws Exception {
    File dbDir = new File(tempDir, "plugin-total-budget-db");
    SlowPlugin plugin = new SlowPlugin();

    try (LDB db = LDBFactory.factory.open(dbDir, new Options()
        .createIfMissing(true)
        .pluginMaxTotalCallbackMillis(1)
        .addPlugin(plugin))) {
      db.put(bytes("first"), bytes("value"));
      db.put(bytes("second"), bytes("value"));

      assertEquals(1, plugin.beforeWrites);
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("disabled=true"));
      assertTrue(db.getProperty("ldb.plugin.0.stats").contains("degradationReason=total-callback-budget:beforeWrite"));
      assertTrue(db.getProperty("ldb.plugin.executionPolicy").contains("maxTotalCallbackMillis=1"));
    }
  }

  @Test
  void shouldVerifyPluginCompatibilityWithTestkit() {
    LdbPluginCompatibility.Report report = LdbPluginCompatibility.verifyProvider(new CompatibleProvider(),
        java.util.Collections.<String, String>emptyMap());

    assertTrue(report.compatible());
    report.throwIfIncompatible();

    LdbPluginCompatibility.Report bad = LdbPluginCompatibility.verifyPlugin(new UnstableDescriptorPlugin());
    assertFalse(bad.compatible());
    assertTrue(bad.failures().contains("descriptor.name must be stable"));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static LdbPluginDescriptor pluginDescriptor(String name, LdbPluginFailurePolicy policy) {
    return new LdbPluginDescriptor(name, "test", 0, policy);
  }

  private static void assertPhaseOrder(List<String> events, String phase, String... expectedNames) {
    List<String> actual = new ArrayList<>();
    for (String event : events) {
      if (event.startsWith(phase + ":")) {
        actual.add(event.substring(phase.length() + 1));
      }
    }
    assertEquals(java.util.Arrays.asList(expectedNames), actual, phase);
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

  private static class CapabilityPlugin implements LdbPlugin {
    @Override
    public LdbPluginDescriptor descriptor() {
      return new LdbPluginDescriptor("capability-plugin", "test", 0, LdbPluginFailurePolicy.FAIL_FAST,
          LdbPluginCapability.OBSERVE_WRITE, LdbPluginCapability.METADATA_READ);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      assertFalse(event.isCommitted());
    }
  }

  private static class OrderedPlugin implements LdbPlugin {
    private final String name;
    private final int order;
    private final List<String> events;

    private OrderedPlugin(String name, int order, List<String> events) {
      this.name = name;
      this.order = order;
      this.events = events;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      return new LdbPluginDescriptor(name, "test", order, LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void onOpen(LdbPluginContext context) {
      events.add("onOpen:" + name);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      events.add("beforeWrite:" + name);
    }

    @Override
    public void afterWrite(WriteEvent event) {
      events.add("afterWrite:" + name);
    }

    @Override
    public void beforeCheckpoint(File targetDir) {
      events.add("beforeCheckpoint:" + name);
    }

    @Override
    public void afterCheckpoint(File targetDir) {
      events.add("afterCheckpoint:" + name);
    }

    @Override
    public void beforeClose() {
      events.add("beforeClose:" + name);
    }

    @Override
    public void close() {
      events.add("close:" + name);
    }
  }

  private static class MutatingPlugin implements LdbPlugin {
    private final String name;
    private final boolean declaresMutation;

    private MutatingPlugin(String name, boolean declaresMutation) {
      this.name = name;
      this.declaresMutation = declaresMutation;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      if (declaresMutation) {
        return new LdbPluginDescriptor(name, "test", 0, LdbPluginFailurePolicy.FAIL_FAST,
            LdbPluginCapability.MUTATE_WRITE_BATCH);
      }
      return pluginDescriptor(name, LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      ((LdbWriteBatch) event.getBatch()).put(bytes("mutated"), bytes("plugin"));
    }
  }

  private static class SlowPlugin implements LdbPlugin {
    private int beforeWrites;

    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor("slow-plugin", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      beforeWrites++;
      try {
        Thread.sleep(5L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static class AsyncAfterWritePlugin implements LdbPlugin {
    private final CountDownLatch completed = new CountDownLatch(1);
    private int afterWrites;

    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor("async-after-write", LdbPluginFailurePolicy.RECORD_AND_CONTINUE);
    }

    @Override
    public void afterWrite(WriteEvent event) {
      try {
        Thread.sleep(25L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      afterWrites++;
      completed.countDown();
    }
  }

  private static class FailingBeforeWritePlugin implements LdbPlugin {
    @Override
    public LdbPluginDescriptor descriptor() {
      return pluginDescriptor("failing-before-write", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void beforeWrite(WriteEvent event) {
      throw new DBException("before write must remain synchronous");
    }
  }

  private static class MetadataReadingPlugin implements LdbPlugin {
    private final boolean declaresMetadata;
    private boolean opened;

    private MetadataReadingPlugin(boolean declaresMetadata) {
      this.declaresMetadata = declaresMetadata;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      if (declaresMetadata) {
        return new LdbPluginDescriptor("metadata-reader", "test", 0, LdbPluginFailurePolicy.FAIL_FAST,
            LdbPluginCapability.METADATA_READ);
      }
      return pluginDescriptor("metadata-reader", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void onOpen(LdbPluginContext context) {
      opened = !context.getColumnFamilies().isEmpty();
    }
  }

  private static class CheckpointPlugin implements LdbPlugin {
    private final boolean declaresCheckpoint;

    private CheckpointPlugin(boolean declaresCheckpoint) {
      this.declaresCheckpoint = declaresCheckpoint;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      if (declaresCheckpoint) {
        return new LdbPluginDescriptor("checkpoint-plugin", "test", 0, LdbPluginFailurePolicy.FAIL_FAST,
            LdbPluginCapability.CHECKPOINT_HOOK);
      }
      return pluginDescriptor("checkpoint-plugin", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void beforeCheckpoint(File targetDir) {
    }
  }

  private static class ContextBatchPlugin implements LdbPlugin {
    private final boolean declaresMutation;

    private ContextBatchPlugin(boolean declaresMutation) {
      this.declaresMutation = declaresMutation;
    }

    @Override
    public LdbPluginDescriptor descriptor() {
      if (declaresMutation) {
        return new LdbPluginDescriptor("context-batch", "test", 0, LdbPluginFailurePolicy.FAIL_FAST,
            LdbPluginCapability.MUTATE_WRITE_BATCH);
      }
      return pluginDescriptor("context-batch", LdbPluginFailurePolicy.FAIL_FAST);
    }

    @Override
    public void onOpen(LdbPluginContext context) {
      try {
        context.createWriteBatch().close();
      } catch (java.io.IOException e) {
        throw new DBException("failed to close context batch", e);
      }
    }
  }

  private static class CompatibleProvider implements LdbPluginProvider {
    @Override
    public String name() {
      return "compatible-provider";
    }

    @Override
    public String version() {
      return "1.0.0";
    }

    @Override
    public LdbPlugin create(java.util.Map<String, String> config) {
      return new CapabilityPlugin();
    }
  }

  private static class UnstableDescriptorPlugin implements LdbPlugin {
    private int calls;

    @Override
    public LdbPluginDescriptor descriptor() {
      calls++;
      return pluginDescriptor("unstable-" + calls, LdbPluginFailurePolicy.FAIL_FAST);
    }
  }
}
