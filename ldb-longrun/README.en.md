# ldb-longrun

English | [中文](README.md)

`ldb-longrun` is the standalone long-running stress and fault-injection test tool for LDB. It is built and published as an independent Gradle subproject, and it is not part of the main product runtime path or default long-running CI.

## Build

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

Artifacts are generated under `ldb-longrun/build/distributions/`, including `ldb-longrun-<version>.zip` and `ldb-longrun-<version>.tar.gz`. Both archives contain exactly one top-level directory, fixed as `ldb-longrun/`.

## Commands

```text
longrun run      --config config/smoke.properties
longrun start    --config config/nightly.properties
longrun stop     --instance smoke-1
longrun status   --instance smoke-1
longrun logs     --instance smoke-1
longrun restart  --config config/reopen.properties
longrun watch    --config config/smoke.properties
longrun report   --workDir work/smoke
longrun worker   --config config/crash.properties --resume=true
```

### Faster input

`--config` also supports short name shorthand:

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

Both local profile files and packaged profiles will be resolved automatically.

You can also use `-c` for shorter input:

```powershell
longrun watch -c smoke -d 5m
```

Other common shorthand options:

```text
-c/--config                 => config
-p/--profile                => profile (legacy alias)
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir (legacy)
-W/--run.workDir            => run.workDir
-d/--run.duration           => run.duration
-n/--run.name               => run.name
-s/--run.seed               => run.seed
-r/--resume                 => resume
-k/--workload.keySpace      => workload.keySpace
-m/--workload.mode          => workload.mode
-v/--workload.valueSizeMin  => workload.valueSizeMin
-V/--workload.valueSizeMax  => workload.valueSizeMax
-a/--workload.commitEveryOps=> workload.commitEveryOps
-q/--workload.readRatio     => workload.readRatio
-y/--workload.writeRatio    => workload.writeRatio
-x/--workload.removeRatio   => workload.removeRatio
-t/--metrics.interval       => metrics.interval
-u/--state.interval         => state.interval
-o/--check.reopenInterval   => check.reopenInterval
-e/--crash.enabled         => crash.enabled
-z/--crash.interval        => crash.interval
-l/--crash.cycles          => crash.cycles
-f/--fault.enabled         => fault.enabled
-g/--fault.interval        => fault.interval
-j/--fault.kinds           => fault.kinds
-b/--fault.maxBytes        => fault.maxBytes
-h/--fault.retainedCopies  => fault.retainedCopies
-L/--limits.maxDbSizeGb    => limits.maxDbSizeGb
```

For `report`, `-w <dir>` is equivalent to `--workDir <dir>`.

Bash completion helper (command + `-c/--config`):

```bash
bash ./install-completion.bash
```

Open a new shell after installation. To enable it in the current shell immediately, run the `source` command printed by the installer.

Examples:

- `./bin/longrun <TAB>` => `run start watch ...`
- `./bin/longrun watch <TAB>` => options
- `./bin/longrun watch -c <TAB>` => profile names
- `./bin/longrun watch --config <TAB>` => profile names

The same completion is registered for `longrun`, `bin/longrun`, and `./bin/longrun`.
Use a space before `<TAB>` after `./bin/longrun`; without the space, Bash is still completing the executable path.

PowerShell completion helper (command + `-c/--config`):

```powershell
Register-ArgumentCompleter -CommandName longrun -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)

  $commands = @('run', 'start', 'watch', 'stop', 'status', 'logs', 'restart', 'report', 'worker')
  $configOptions = @('-c', '--config', '-p', '--profile')
  $options = @(
    '-c', '--config',
    '-p', '--profile',
    '-i', '--instance',
    '-I', '--run.instance',
    '-W', '--run.workDir',
    '-d', '--run.duration',
    '-n', '--run.name',
    '-s', '--run.seed',
    '-r', '--resume',
    '-k', '--workload.keySpace',
    '-m', '--workload.mode',
    '-v', '--workload.valueSizeMin',
    '-V', '--workload.valueSizeMax',
    '-a', '--workload.commitEveryOps',
    '-q', '--workload.readRatio',
    '-y', '--workload.writeRatio',
    '-x', '--workload.removeRatio',
    '-t', '--metrics.interval',
    '-u', '--state.interval',
    '-o', '--check.reopenInterval',
    '-e', '--crash.enabled',
    '-z', '--crash.interval',
    '-l', '--crash.cycles',
    '-f', '--fault.enabled',
    '-g', '--fault.interval',
    '-j', '--fault.kinds',
    '-b', '--fault.maxBytes',
    '-h', '--fault.retainedCopies',
    '-L', '--limits.maxDbSizeGb'
  )

  function Get-ProfileCandidates {
    param([string]$base)
    $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
    $profiles | ForEach-Object {
      if ($_ -like "$base*") {
        [System.Management.Automation.CompletionResult]::new(
          $_,
          $_,
          'ParameterValue',
          "profile $_"
        )
      }
    }
  }

  $elements = $commandAst.CommandElements
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
  $current = if ($elements.Count -gt 0) { $elements[$elements.Count - 1].ToString() } else { '' }
  $previous = if ($elements.Count -ge 2) { $elements[$elements.Count - 2].ToString() } else { '' }

  # 1st position after command name: command itself.
  if ($elements.Count -eq 1) {
    $commands | ForEach-Object {
      if ($_ -like "$base*") {
        [System.Management.Automation.CompletionResult]::new($_, $_, 'Command', "command $_")
      }
    }
    return
  }

  if ($elements.Count -eq 2) {
    $commandToken = $elements[1].ToString()
    if ($commandToken -in $commands) {
      $options | ForEach-Object {
        if ($_ -like "$base*") {
          [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterName', $_)
        }
      }
      return
    }
    if ($commandToken -notlike '-*') {
      $commands | ForEach-Object {
        if ($_ -like "$base*") {
          [System.Management.Automation.CompletionResult]::new($_, $_, 'Command', "command $_")
        }
      }
    }
    return
  }

  # -c/--config after command, then profile candidates.
  if ($previous -in $configOptions) {
    Get-ProfileCandidates -base $base
    return
  }

  if ($current -like '-*') {
    $options | ForEach-Object {
      if ($_ -like "$base*") {
        [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterName', $_)
      }
    }
    return
  }
}
```

Examples:

- `longrun <TAB>` => `run start watch ...`
- `longrun watch <TAB>` => options
- `longrun watch -c <TAB>` => profile names
- `longrun watch --config <TAB>` => profile names

PowerShell completion script file:

```powershell
. "<path-to-repo>\ldb-longrun\longrun-completion.ps1"
```

## Profiles

Default profiles are shipped under `config/`:

- `smoke.properties`: 5-minute quick validation by default.
- `performance.properties`: 3-minute mixed small-value performance stress run with `workload.syncWrites=false` for overall engine throughput.
- `performance-write.properties`: 3-minute write-heavy small-value performance stress run with `workload.syncWrites=false` for write-path throughput.
- `performance-read.properties`: 3-minute read-heavy small-value performance stress run with `workload.syncWrites=false` for read-path throughput.
- `performance-large-value.properties`: 3-minute mixed large-value performance stress run with `workload.syncWrites=false` for large-value bandwidth and IO pressure.
- `performance-durable.properties`: 3-minute durable performance stress run with `workload.syncWrites=true` for durable write/fsync cost.
- `nightly.properties`: 12-hour nightly run by default.
- `soak.properties`: 7-day soak by default, override with `--run.duration=30d` when needed.
- `reopen.properties`: periodic close/open with `reopenChecks`.
- `crash.properties`: parent/worker crash recovery with `recoveryChecks`.
- `fault-injection.properties`: copy-based file corruption injection, not mixed into other profiles by default.
- `comprehensive.properties`: read/write pressure, reopen, crash/recovery, and reclamation observation; fault is disabled by default.

## Single And Multiple Instances

The same `run.instance` cannot be started twice by default. Default profiles use the test type as the instance, such as `smoke`, `reopen`, `crash`, and `fault-injection`, so different test types can run concurrently while the same test type allows only one default run.

Background start writes:

```text
run/<instance>.pid
logs/<instance>.out
```

Every `start` rotates the previous log to `logs/<instance>.out.1`, `.2`, and so on, then creates a fresh `logs/<instance>.out` for the new run. The instance first prints `START` and multiple `CONFIG` lines, and the running workload prints `PROGRESS` lines with `progressPercent`, `windowOpsPerSecond`, `avgOpsPerSecond`, `minOpsPerSecond`, and `maxOpsPerSecond` at `metrics.interval`. When `logs` or `watch` follows a running instance, `PROGRESS` lines are refreshed in place as a single console progress line with a character bar, for example `PROGRESS [##########----------]  50% ...`, while the log file keeps the complete line-by-line history. After completion, the main log prints a `SUMMARY` line with avg/min/max, p05/p50/p95, throughputDropRatio, finalSizeBytes, and sizeAmplification.

Crash/recovery mode writes `CRASH PROGRESS` to the main log, which shows parent-process progress by `crash.cycles`. Worker `START`, `CONFIG`, and `PROGRESS` lines are written to `logs/<instance>-worker.out` for debugging individual worker phases.

Regular workloads are fresh runs by default: when `resume=false`, the runner acquires the workDir lock and then clears `db/`, `state/`, `metrics/`, `report/`, and `fault/` so a new run does not reuse database files or state files from a previous incomplete run. A crash/recovery worker uses `--resume=true`, keeps the same workDir, and performs recovery verification.

Single instance:

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` means “start the instance if it is not running, then follow logs”. If the instance is already running, it follows the current log directly:

```bash
bin/longrun watch --config config/smoke.properties
```

Pressing `Ctrl+C` during `watch` only stops log following. The background instance keeps running; stop it explicitly with `bin/longrun stop --config config/smoke.properties`.

Multiple instances must use different instances and different work directories:

```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## Fault Injection

`fault-injection.properties` uses copy-based injection:

1. Commit and verify the main DB.
2. Close the main DB.
3. Copy the whole DB directory to `work/<profile>/fault/fault-N/`.
4. Corrupt only the copy.
5. Open the copy read-only and classify the result.
6. Reopen and verify the main DB.

Supported kinds are `truncate`, `bit-flip`, `zero-range`, `random-range`, and `partial-page`. `fault.retainedCopies` controls retained copies. Historical fault metrics are kept.

## Reports

Normal completion generates:

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

Manual re-analysis:

```bash
bin/longrun report --workDir work/smoke
```

Core metrics include Operations, Commits, Reopen Checks, Recovery Checks, Avg/Min/Max Ops/s, P05/P50/P95 Ops/s, Throughput Drop Ratio, Final Size Bytes, Size Amplification, Reclamation Events, Fault Injection Events, Suspicious Log Lines, Failures, and Warnings.

Run both performance profiles when comparing throughput:

```bash
bin/longrun watch -c performance
bin/longrun watch -c performance-write
bin/longrun watch -c performance-read
bin/longrun watch -c performance-large-value
bin/longrun watch -c performance-durable
```

`performance` uses asynchronous writes and a mixed small-value workload for overall engine throughput. `performance-write` focuses on the write path. `performance-read` focuses on the read path. `performance-large-value` keeps the larger-value pressure profile. `performance-durable` uses synchronous writes to observe durable write and fsync cost.

## Release Acceptance

Recommended explicit release runs:

- smoke: 5 minutes PASS.
- reopen: 1 hour PASS with `reopenChecks > 0`.
- crash/recovery: 30 minutes PASS with `recoveryChecks > 0`.
- fault injection: 30 minutes with zero unexpected results.
- comprehensive: 12 hours PASS.
- soak: 24 hours or 72 hours PASS.
- Formal releases should include at least one recommended 7-day PASS.

Report archives should be stored in the release-process location, such as a compressed copy of `work/<profile>/report/` or release object storage. The final 7-day archive location is owned by the release process.
`logs` follows the log while the instance is running. After the instance exits, it prints recent log lines and returns.
