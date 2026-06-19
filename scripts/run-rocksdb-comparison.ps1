param(
  [string]$DbBenchPath = "db_bench",
  [string]$OutputDir = "build/reports/rocksdb-comparison",
  [string]$GradleCommand = ".\gradlew.bat",
  [string]$ExistingLdbSummary = "",
  [string]$Benchmarks = "fillseq,readrandom,overwrite,readwhilewriting",
  [int]$Num = 200000,
  [int]$Reads = 200000,
  [int]$ValueSize = 100,
  [switch]$Sync,
  [switch]$GroupCommit,
  [switch]$SkipRocksDb
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return $Path
  }
  return Join-Path (Get-Location).Path $Path
}

function Get-RocksDbBenchmarks([string]$Benchmark) {
  if ($Benchmark -eq "fillseq") {
    return "fillseq"
  }
  return "fillseq,$Benchmark"
}

function Parse-DbBenchOps([string[]]$Lines, [string]$Benchmark) {
  $pattern = "^\s*$([regex]::Escape($Benchmark))\s*:\s*([0-9.]+)\s+micros/op\s+([0-9.]+)\s+ops/sec"
  foreach ($line in $Lines) {
    $match = [regex]::Match($line, $pattern)
    if ($match.Success) {
      return [double]$match.Groups[2].Value
    }
  }
  return $null
}

$outputRoot = Resolve-RepoPath $OutputDir
$ldbOutput = Join-Path $outputRoot "ldb"
$ldbDb = Join-Path $ldbOutput "db"
$rocksOutput = Join-Path $outputRoot "rocksdb"
New-Item -ItemType Directory -Force -Path $ldbOutput | Out-Null
New-Item -ItemType Directory -Force -Path $rocksOutput | Out-Null

$syncText = if ($Sync) { "true" } else { "false" }
$groupCommitText = if ($GroupCommit) { "true" } else { "false" }

if ([string]::IsNullOrWhiteSpace($ExistingLdbSummary)) {
  & $GradleCommand :ldb-longrun:ldbDbBenchReport `
    "-Pldb.dbBench.outputDir=$ldbOutput" `
    "-Pldb.dbBench.dbDir=$ldbDb" `
    "-Pldb.dbBench.benchmarks=$Benchmarks" `
    "-Pldb.dbBench.num=$Num" `
    "-Pldb.dbBench.reads=$Reads" `
    "-Pldb.dbBench.valueSize=$ValueSize" `
    "-Pldb.dbBench.sync=$syncText" `
    "-Pldb.dbBench.groupCommit=$groupCommitText"

  if ($LASTEXITCODE -ne 0) {
    throw "LDB db_bench task failed with exit code $LASTEXITCODE"
  }
  $summaryPath = Join-Path $ldbOutput "ldb-db-bench-summary.json"
} else {
  $summaryPath = Resolve-RepoPath $ExistingLdbSummary
  Copy-Item -LiteralPath $summaryPath -Destination (Join-Path $ldbOutput "ldb-db-bench-summary.json") -Force
}
$ldbSummary = Get-Content -Raw -Path $summaryPath | ConvertFrom-Json
$rows = @()
foreach ($result in $ldbSummary.results) {
  $rows += [pscustomobject]@{
    engine = "ldb"
    benchmark = $result.name
    opsPerSecond = [double]$result.opsPerSecond
    ratioToRocksDb = ""
    num = $Num
    reads = $Reads
    valueSize = $ValueSize
    sync = $syncText
    notes = "LDB local db_bench-style runner"
  }
}

$dbBenchCommand = Get-Command $DbBenchPath -ErrorAction SilentlyContinue
if (-not $SkipRocksDb -and $dbBenchCommand) {
  foreach ($benchmark in ($Benchmarks -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
    $rocksDbDir = Join-Path $rocksOutput "db-$benchmark"
    if (Test-Path $rocksDbDir) {
      $resolvedRoot = (Resolve-Path -LiteralPath $outputRoot).Path
      $resolvedTarget = (Resolve-Path -LiteralPath $rocksDbDir).Path
      if (-not $resolvedTarget.StartsWith($resolvedRoot)) {
        throw "Unexpected RocksDB benchmark path: $resolvedTarget"
      }
      Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $rocksDbDir | Out-Null
    $rocksLog = Join-Path $rocksOutput "$benchmark.log"
    $rocksBenchmarks = Get-RocksDbBenchmarks $benchmark
    $rocksArgs = @(
      "--benchmarks=$rocksBenchmarks",
      "--db=$rocksDbDir",
      "--num=$Num",
      "--reads=$Reads",
      "--value_size=$ValueSize"
    )
    if ($Sync) {
      $rocksArgs += "--sync"
    }
    $lines = & $DbBenchPath @rocksArgs 2>&1
    $lines | Set-Content -Encoding UTF8 -Path $rocksLog
    $ops = Parse-DbBenchOps $lines $benchmark
    if ($null -ne $ops) {
      $ldbRow = $rows | Where-Object { $_.engine -eq "ldb" -and $_.benchmark -eq $benchmark } | Select-Object -First 1
      if ($ldbRow) {
        $ldbRow.ratioToRocksDb = [Math]::Round($ldbRow.opsPerSecond / $ops, 4)
      }
      $rows += [pscustomobject]@{
        engine = "rocksdb"
        benchmark = $benchmark
        opsPerSecond = $ops
        ratioToRocksDb = "1"
        num = $Num
        reads = $Reads
        valueSize = $ValueSize
        sync = $syncText
        notes = "RocksDB db_bench"
      }
    }
  }
} else {
  $reason = if ($SkipRocksDb) { "SkipRocksDb was set" } else { "db_bench not found on PATH" }
  Set-Content -Encoding UTF8 -Path (Join-Path $rocksOutput "README.txt") -Value $reason
}

$comparisonCsv = Join-Path $outputRoot "comparison.csv"
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $comparisonCsv
$rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $outputRoot "comparison.json")
Write-Output "Comparison report: $comparisonCsv"
