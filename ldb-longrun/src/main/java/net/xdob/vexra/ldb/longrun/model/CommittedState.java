package net.xdob.vexra.ldb.longrun.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 已确认提交状态。
 *
 * <p>P2 阶段使用内存 map 加 properties 文件保存活跃 key 的最后 sequence。后续 crash
 * 阶段会把 checkpoint 与 committed-state 的语义进一步分离。
 */
public final class CommittedState {
  private final Map<Long, Long> active = new LinkedHashMap<>();
  private long lastSequence;
  private long operations;
  private long reads;
  private long writes;
  private long removes;
  private long commits;

  public long nextSequence() {
    lastSequence++;
    operations++;
    return lastSequence;
  }

  public void recordRead() {
    operations++;
    reads++;
  }

  public void recordWrite(long keyId, long sequence) {
    writes++;
    active.put(keyId, sequence);
  }

  public void recordRemove(long keyId) {
    removes++;
    active.remove(keyId);
  }

  public void recordCommit() {
    commits++;
  }

  public Map<Long, Long> active() {
    return active;
  }

  public long lastSequence() {
    return lastSequence;
  }

  public long operations() {
    return operations;
  }

  public long reads() {
    return reads;
  }

  public long writes() {
    return writes;
  }

  public long removes() {
    return removes;
  }

  public long commits() {
    return commits;
  }

  public void save(File stateDir) throws IOException {
    if (!stateDir.exists() && !stateDir.mkdirs()) {
      throw new IOException("failed to create state dir: " + stateDir);
    }
    Properties p = new Properties();
    p.setProperty("lastSequence", Long.toString(lastSequence));
    p.setProperty("operations", Long.toString(operations));
    p.setProperty("reads", Long.toString(reads));
    p.setProperty("writes", Long.toString(writes));
    p.setProperty("removes", Long.toString(removes));
    p.setProperty("commits", Long.toString(commits));
    p.setProperty("activeKeys", Integer.toString(active.size()));
    for (Map.Entry<Long, Long> entry : active.entrySet()) {
      p.setProperty("key." + entry.getKey(), Long.toString(entry.getValue()));
    }
    File tmp = new File(stateDir, "committed-state.tmp");
    File target = new File(stateDir, "committed-state.properties");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      p.store(out, "ldb-longrun committed state");
    }
    if (target.exists() && !target.delete()) {
      throw new IOException("failed to replace state: " + target);
    }
    if (!tmp.renameTo(target)) {
      throw new IOException("failed to publish state: " + target);
    }
  }

  public static CommittedState load(File stateDir) throws IOException {
    CommittedState state = new CommittedState();
    File file = new File(stateDir, "committed-state.properties");
    if (!file.isFile()) {
      return state;
    }
    Properties p = new Properties();
    try (FileInputStream in = new FileInputStream(file)) {
      p.load(in);
    }
    state.lastSequence = Long.parseLong(p.getProperty("lastSequence", "0"));
    state.operations = Long.parseLong(p.getProperty("operations", "0"));
    state.reads = Long.parseLong(p.getProperty("reads", "0"));
    state.writes = Long.parseLong(p.getProperty("writes", "0"));
    state.removes = Long.parseLong(p.getProperty("removes", "0"));
    state.commits = Long.parseLong(p.getProperty("commits", "0"));
    for (String name : p.stringPropertyNames()) {
      if (name.startsWith("key.")) {
        state.active.put(Long.parseLong(name.substring(4)), Long.parseLong(p.getProperty(name)));
      }
    }
    return state;
  }
}
