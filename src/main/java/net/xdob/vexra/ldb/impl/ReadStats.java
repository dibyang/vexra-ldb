package net.xdob.vexra.ldb.impl;

public class ReadStats {
  private int seekFileLevel = -1;
  private FileMetaData seekFile;
  private long candidateFiles;
  private long filterSkips;
  private long tableReads;
  private long candidateEntryHits;
  private long candidateEntryMisses;
  private long bloomFalsePositives;

  public void clear() {
    seekFileLevel = -1;
    seekFile = null;
    candidateFiles = 0;
    filterSkips = 0;
    tableReads = 0;
    candidateEntryHits = 0;
    candidateEntryMisses = 0;
    bloomFalsePositives = 0;
  }

  public int getSeekFileLevel() {
    return seekFileLevel;
  }

  public void setSeekFileLevel(int seekFileLevel) {
    this.seekFileLevel = seekFileLevel;
  }

  public FileMetaData getSeekFile() {
    return seekFile;
  }

  public void setSeekFile(FileMetaData seekFile) {
    this.seekFile = seekFile;
  }

  public long getCandidateFiles() {
    return candidateFiles;
  }

  public void recordCandidateFile() {
    candidateFiles++;
  }

  public long getFilterSkips() {
    return filterSkips;
  }

  public void recordFilterSkip() {
    filterSkips++;
  }

  public long getTableReads() {
    return tableReads;
  }

  public void recordTableRead() {
    tableReads++;
  }

  public long getCandidateEntryHits() {
    return candidateEntryHits;
  }

  public void recordCandidateEntryHit() {
    candidateEntryHits++;
  }

  public long getCandidateEntryMisses() {
    return candidateEntryMisses;
  }

  public void recordCandidateEntryMiss() {
    candidateEntryMisses++;
  }

  public long getBloomFalsePositives() {
    return bloomFalsePositives;
  }

  public void recordBloomFalsePositive() {
    bloomFalsePositives++;
  }
}
