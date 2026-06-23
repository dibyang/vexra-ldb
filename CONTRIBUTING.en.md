# Contributing Guide

English | [中文](CONTRIBUTING.md)

Thank you for contributing to `vexra-ldb`. This is a Java local storage project, and changes often affect disk format, recovery semantics, concurrency control, or operational tooling. Please keep changes small, focused, and verifiable.

## Development Environment

- JDK 8
- Gradle Wrapper
- UTF-8 encoding

Run tests:

```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat test
```

## Contribution Flow

1. Create an issue first, or explain the problem in an existing issue.
2. For changes involving APIs, protocols, disk format, state machines, recovery flows, tool side effects, or compatibility, update the relevant design document under `docs/` first. Design documents must keep both Chinese and English copies.
3. Keep code changes focused and avoid mixing formatting, renaming, or unrelated refactors.
4. Add tests for behavior changes, covering at least the normal path and key failure paths.
5. In the PR, describe scope, compatibility impact, rollback strategy, and validation commands.

## Reporting Bugs

Use the GitHub `Bug report` issue form for ordinary defects, and read the [Bug Reporting Guide](docs/bug-reporting.en.md) before filing. A high-quality report usually includes:

- Version, commit, or dependency coordinates.
- Affected module and usage path, such as direct Java API usage, `LdbTool`, plugin, or upper-layer application integration.
- Expected behavior, actual behavior, and a minimal reproducer, preferably a runnable test.
- First failure stack trace, `INFO_LOG` summary, `LdbTool` JSON output, or `getProperty` diagnostics.
- For storage, recovery, or concurrency issues, thread count, iteration count, failure frequency, first failure time, and relevant options.

If the issue may involve data exposure, unauthorized access, arbitrary file access, remotely triggerable denial of service, exploitable deserialization, privilege bypass, or supply-chain risk, do not open a public issue. Report it privately according to [SECURITY.en.md](SECURITY.en.md).

## Code Style

- Keep JDK 8 compatibility.
- Public classes, interfaces, and key methods need clear comments.
- Logic touching WAL, SST, MANIFEST, compaction, checkpoint, repair, backup, snapshot, or concurrency control should explain key constraints.
- Do not commit local keys, signing files, private IDE settings, build artifacts, or temporary database files.

## Test Expectations

Choose tests based on the change scope:

- API and compatibility: `ApiTest`, `LdbApiCompatibilityTest`
- Core reads/writes: `DbImplTest`, `LdbCoreBehaviorTest`
- Recovery and WAL: `LdbWalLifecycleTest`, `LdbCrashRecoveryTest`, `LdbRecoveryMatrixTest`
- Maintenance tools: `LdbVerifyCheckTest`, `LdbRepairTest`, `LdbBackupTest`, `LdbToolTest`
- Snapshot, compaction, and fault injection: `LdbSnapshotIteratorTest`, `LdbObservabilityTest`, `LdbFaultInjectionTest`

## Pull Request Checklist

- [ ] Code and documentation use UTF-8.
- [ ] Relevant design documents are added or updated.
- [ ] `./gradlew test` or `.\gradlew.bat test` has been run.
- [ ] Compatibility, rollback, and potential risks are described.
- [ ] No secrets or local environment files are committed.
