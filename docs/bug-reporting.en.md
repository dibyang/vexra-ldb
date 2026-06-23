# Bug Reporting Guide

English | [中文](bug-reporting.md)

Thank you for helping improve `vexra-ldb`. This guide is for ordinary bugs. If an issue may cause data exposure, unauthorized access, arbitrary file access, remotely triggerable denial of service, exploitable deserialization, privilege bypass, or dependency supply-chain risk, do not open a public issue. Use the private reporting path in [SECURITY.md](../SECURITY.md).

## Where To Report

- Ordinary bugs: use the GitHub `Bug report` issue form.
- Security issues: report privately according to [SECURITY.en.md](../SECURITY.en.md).
- Fix contributions: read [CONTRIBUTING.en.md](../CONTRIBUTING.en.md) first and link the PR to the relevant issue.

## Required Information

Please provide enough detail for maintainers to reproduce and diagnose the issue:

- Version, commit, or dependency coordinates, such as `net.xdob:vexra-ldb:<version>`.
- Affected module, such as WAL/recovery, SSTable, compaction, column family, snapshot cursor, check/repair/backup/restore, CLI, or plugin.
- Usage path: direct Java API usage, `LdbTool`, longrun plugin, test fixture, or upper-layer application integration.
- Expected behavior and actual behavior.
- Minimal reproducer, preferably a runnable JUnit test, small code snippet, or command sequence.
- First failure stack trace, `INFO_LOG` summary, `LdbTool` JSON output, or `getProperty` diagnostics.
- Runtime environment: JDK, operating system, file system, disk type, Gradle/IDE, container, or CI environment.

## Storage, Recovery, And Concurrency Issues

If the issue touches WAL, MANIFEST, SSTable, compaction, checkpoint, repair, backup, snapshot cursor, column families, or plugins, also include:

- Initial database directory state and whether it was upgraded from an older version.
- Key operation sequence before the failure, such as `put`, `write`, `deleteRange`, `compactRange`, restart, `check`, `repair`, `backup`, or `restore`.
- Thread count, iteration count, failure frequency, first failure time, and whether it reproduces reliably.
- Whether custom `Options`, column families, Bloom filters, plugins, throttling, or fault injection are enabled.
- Relevant diagnostics, such as `ldb.recoveryEvidence`, `ldb.fileCounts`, `ldb.compactionStats`, `ldb.snapshotCursorStats`, `ldb.storageFormat`, or `ldb.sstReadStats`.

## Minimal Reproducer Suggestions

Maintainers can act fastest on reports shaped like this:

```java
@Test
public void reproducesIssue() throws Exception {
  Options options = new Options()
      .createIfMissing(true)
      .verifyChecksums(true);

  try (LDB db = LDBFactory.factory.open(tempDir, options)) {
    // Arrange: write the smallest useful data set.
    // Act: run the shortest operation sequence that triggers the issue.
    // Assert: show the expected value and actual failure.
  }
}
```

If a test is not practical, provide the full command sequence, input scale, database directory summary, and shareable log snippets. Remove business data, credentials, private local paths, and other sensitive information before pasting logs.

## Content That Does Not Belong In Public Issues

Use the security process or sanitize first for:

- Directly exploitable vulnerability details, payloads, or bypass steps.
- Unsanitized business keys/values, database files, backup files, or logs.
- Secrets, tokens, signing files, private repository URLs, or internal service addresses.
- Information that exposes users, customers, or production environments.
