# Security Policy

English | [中文](SECURITY.md)

## Supported Versions

This project is still in the `0.x` phase. By default, security fixes are accepted for the latest main branch and the latest released version.

| Version | Support status |
| --- | --- |
| `0.2.x` | Supported |
| `0.1.x` | Limited support |
| Earlier versions | No support commitment |

## Reporting a Security Issue

Please do not disclose exploitable security issues in public issues. Report them privately through:

- Email: `dib.yang@gmail.com`
- GitHub private vulnerability reporting: <https://github.com/dibyang/vexra-ldb/security/advisories/new>

If private channels are temporarily unavailable, you may open a public placeholder issue without vulnerability details. Only state that a security issue needs private coordination. Do not include reproduction steps, payloads, logs, or affected data.

Please include as much as possible:

- Affected version or commit.
- Reproduction steps or minimal reproduction code.
- Impact scope, such as data corruption, unauthorized read, arbitrary file write, denial of service, or dependency supply-chain risk.
- Mitigation suggestions you believe are safe.

Treat the following categories as security-sensitive:

- Data exposure, unauthorized reads, or access-control bypass.
- Arbitrary file read, write, delete, or path traversal.
- Exploitable deserialization, plugin loading, or dependency supply-chain risk.
- Denial of service reliably triggered by remote or untrusted input.
- Attack paths that can corrupt persistent data, bypass recovery, or poison backups.

## Response Process

1. Maintainers acknowledge the report.
2. Maintainers assess impact and fix priority.
3. The fix, tests, and release preparation are completed privately.
4. A fixed version or patch note is released.
5. A summary is disclosed publicly at an appropriate time.

## Scope Notes

The following are usually treated as ordinary bugs unless they create clear security impact:

- Failures affecting only test code.
- Exceptions that require a fully trusted local caller.
- Ordinary crashes that do not expose sensitive data or corrupt persistent data.
