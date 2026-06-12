# vexra-ldb 0.4.0 Upgrade Fixture

This directory documents the release-gate fixture for opening data created by
`net.xdob.vexra:vexra-ldb:0.4.0`.

The fixture is generated during `LdbUpgradeCompatibilityTest` from the local
Maven cache jar:

```text
~/.m2/repository/net/xdob/vexra/vexra-ldb/0.4.0/vexra-ldb-0.4.0.jar
```

Expected data:

- default column family: `upgrade:basic` -> `from-0.4.0`
- runtime column family `44:upgrade-cf`: `upgrade:cf` -> `runtime-0.4.0`

The production `releaseGate` treats the old jar as required. Ordinary unit tests
skip this compatibility fixture when the jar is not available, so a clean
developer environment can still run the default test suite.
