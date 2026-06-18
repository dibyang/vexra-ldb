# Release Guide

English | [中文](release.md)

This document describes the release preparation and Maven publication flow for `vexra-ldb`. Before an actual release, confirm the version, tag, tests, and signing configuration.

## Pre-Release Checklist

1. Confirm the working tree is clean.
2. Update `CHANGELOG.md` and move `Unreleased` entries into the target version.
3. Confirm README, design documents, and API documents match the code.
4. Confirm external commitments and upgrade gates:

- `docs/vexra-ldb-external-commitment.md`
- `docs/ldb-production-readiness-plan.md`
- `docs/operations.md`
- `docs/ldb-plugin-docs-index.md`
- `docs/ldb-plugin-roadmap.md`
- `ldb-longrun/README.md`

5. Run the full test suite:

```bash
./gradlew clean test
```

Windows PowerShell:

```powershell
.\gradlew.bat clean test
```

6. Generate local publication artifacts:

```bash
./gradlew clean publishToMavenLocal
```

## 0.6.0 Pre-Release Verification Record

- Target version: `0.6.0`.
- Current development baseline: `gradle.properties` remains `version=0.6.0-SNAPSHOT`; the Gradle release plugin will switch to `0.6.0` during the release flow.
- RocksDB comparison baseline: before release, confirm `docs/ldb-rocksdb-gap-next-version-plan.md` and its English copy still record the current comparison version, work-package state, open-question default decisions, and non-goal boundaries.
- Changelog: `CHANGELOG.md` still keeps the current changes under `Unreleased`; move them into `0.6.0` before the formal release.
- Required gates: `.\gradlew.bat clean test`, `.\gradlew.bat releaseGate`, and `.\gradlew.bat clean publishToMavenLocal`.
- API compatibility evidence: confirm `LdbApiCompatibilityTest` covers `multiGet`, `ldb.api.rocksdbGapPlan`, `ldb.recoveryEvidence`, `ldb.backupEvidence`, `ldb.columnFamilyEvidence`, `ldb.prefixReadiness`, and `ldbToolScan`.
- Tool evidence: confirm `LdbToolTest` covers default `properties` export, read-only `scan <db> [limit]` base64 JSON, bad-argument exit codes, and no database-directory mutation.
- Recovery and backup evidence: archive `LDBFactory.check`, `repair-plan`, `checkBackup`, backup/restore, checkpoint, and object-store validation reports; failed samples must not be deleted.
- Operations closure: before release, confirm `docs/operations.md`, `docs/user-manual.md`, README, and the API compatibility design all document `scan`, `prefixReadiness`, RocksDB gap default decisions, and unsupported capability boundaries.
- Long-run policy: the short `releaseGate` is the hard gate; nightly/24h soak evidence is archived before release candidates, and any missing long-run evidence must be called out in the release note with a rerun plan.
- Do not publish if `releaseGate` fails, open-question defaults conflict with business confirmation, unsupported features are silently ignored by an adapter, or recovery/backup/scan evidence is missing.

## 0.5.0 Pre-Release Verification Record

- Target version: `0.5.0`.
- Current development baseline: `gradle.properties` remains `version=0.5.0-SNAPSHOT`; the Gradle release plugin will switch to `0.5.0` during the release flow.
- Changelog: `CHANGELOG.md` still keeps the current changes under `Unreleased`; move them into `0.5.0` before the formal release.
- Main release themes: plugin classloader isolation, plugin compatibility testkit, capability enforcement hardening, plugin runtime governance, longrun plugin integration, runtime column-family support, group commit, incremental backup, repair plan, and observability/reporting updates.
- Focused plugin verification: `.\gradlew.bat test --tests "*LdbPluginTest" --tests "*LongRunPluginResolverTest" --tests "*LongRunConfigTest" --tests "*SmokeRunnerTest" --tests "*ReportAnalyzerTest"` passed on Windows PowerShell.
- Full pre-release gate still required before publishing: `.\gradlew.bat clean test`.
- Local publication gate still required before publishing: `.\gradlew.bat clean publishToMavenLocal`.
- Upgrade compatibility gate: validate opening data created by `vexra-ldb:0.4.0` or document a clear migration error in the release note before publication.
- Longrun release gate: run at least the documented smoke/performance/plugin profiles and keep report archives according to `ldb-longrun/README.md`.
- Production release gate: confirm 18.1-18.6 in `docs/ldb-production-readiness-plan.md` are complete, then rerun `releaseGate`, old-database upgrade checks, backup corruption injection, column-family tombstone long stress, and the production-gate longrun profile. Do not publish a formal release until the gates pass and reports are archived.

## 0.2.0 Pre-Release Verification Record

- Version: `gradle.properties` is set to `version=0.2.0`.
- Tests: `.\gradlew.bat clean test` was run in Windows PowerShell and passed.
- Local publication: `.\gradlew.bat clean publishToMavenLocal` was run and passed.
- Local Maven artifacts: confirmed `vexra-ldb-0.2.0.jar`, `sources.jar`, `javadoc.jar`, `.pom`, `.module`, and matching `.asc` signature files were generated.
- CI: `.github/workflows/ci.yml` is included and covers Ubuntu/Windows JDK 8 `clean test` plus Ubuntu `clean publishToMavenLocal`.
- Changelog: `CHANGELOG.md` has moved this release's changes into `0.2.0`.

## Signing Configuration

The project reads the uncommitted `signing.properties` file from the repository root. This file is ignored by `.gitignore` and must not be committed.

Example:

```properties
signing.keyId=xxxxxxxx
signing.password=your-password
signing.secretKeyRingFile=/path/to/secring.gpg

ossrhUsername=your-username
ossrhPassword=your-password
```

## Publication Repository Configuration

Publication repositories can be configured through Gradle properties:

```properties
snapshotsRepository=https://s01.oss.sonatype.org/content/repositories/snapshots/
releasesRepository=https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
```

If these properties are not configured, Gradle publishes to `build/repos/snapshots` or `build/repos/releases` for local verification and does not upload to a remote repository.

## Version Release

The project uses the `net.researchgate.release` plugin. The release configuration requires the branch to match `master|v.*`.

Typical flow:

```bash
./gradlew release
```

After release, confirm:

- The Git tag has been created using the `v${version}` format.
- Maven artifacts include the main jar, sources jar, and javadoc jar.
- The POM includes project name, description, license, developer, SCM, and issue tracker metadata.
- Signature files are complete in the publication repository.

## Rollback

- If remote publication fails before becoming public, delete the staging repository or clean local `build/repos`.
- If the tag has been pushed but artifacts were not published, delete the incorrect tag and release again.
- If artifacts have already been publicly released, do not overwrite the same version. Publish a new fixed version instead.
