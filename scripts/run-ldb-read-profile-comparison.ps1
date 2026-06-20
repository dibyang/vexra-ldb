param(
  [string]$OutputDir = "build/reports/ldb-read-profile-comparison",
  [string]$Benchmarks = "warm_readrandom,cold_readrandom,multiget_random",
  [int]$Num = 200000,
  [int]$Reads = 200000,
  [int]$ValueSize = 100,
  [int]$BatchSize = 64,
  [int]$DefaultBlockCacheSize = 4096,
  [int]$ReadOptimizedBlockCacheSize = 65536,
  [string]$ExistingDefaultSummary = "",
  [string]$ExistingReadOptimizedSummary = "",
  [string]$GradleCommand = ".\gradlew.bat"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return $Path
  }
  return Join-Path (Get-Location).Path $Path
}

function Run-LdbProfile([string]$Profile, [string]$ProfileOutput, [int]$BlockCacheSize) {
  $profileDb = Join-Path $ProfileOutput "db"
  & $GradleCommand :ldb-longrun:ldbDbBenchReport `
    "-Pldb.dbBench.outputDir=$ProfileOutput" `
    "-Pldb.dbBench.dbDir=$profileDb" `
    "-Pldb.dbBench.benchmarks=$Benchmarks" `
    "-Pldb.dbBench.num=$Num" `
    "-Pldb.dbBench.reads=$Reads" `
    "-Pldb.dbBench.valueSize=$ValueSize" `
    "-Pldb.dbBench.batchSize=$BatchSize" `
    "-Pldb.dbBench.readProfile=$Profile" `
    "-Pldb.dbBench.blockCacheSize=$BlockCacheSize"
  if ($LASTEXITCODE -ne 0) {
    throw "LDB db_bench profile $Profile failed with exit code $LASTEXITCODE"
  }
  return Get-Content -Raw -Path (Join-Path $ProfileOutput "ldb-db-bench-summary.json") | ConvertFrom-Json
}

function Write-ProfileRows([object]$Summary) {
  $rows = @()
  foreach ($result in $Summary.results) {
    $rows += [pscustomobject]@{
      engine = "ldb"
      readProfile = $Summary.readProfile
      benchmark = $result.name
      opsPerSecond = [double]$result.opsPerSecond
      num = $Summary.num
      reads = $Summary.reads
      valueSize = $Summary.valueSize
      batchSize = $Summary.batchSize
      cacheBlocks = $Summary.cacheBlocks
      blockCacheSize = $Summary.blockCacheSize
      verifyChecksums = $Summary.verifyChecksums
      sstReadStats = $result.sstReadStats
      blockCacheStats = $result.blockCacheStats
    }
  }
  return $rows
}

$outputRoot = Resolve-RepoPath $OutputDir
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

$defaultOutput = Join-Path $outputRoot "default"
$readOptimizedOutput = Join-Path $outputRoot "read-optimized"
if ([string]::IsNullOrWhiteSpace($ExistingDefaultSummary)) {
  $defaultSummary = Run-LdbProfile "default" $defaultOutput $DefaultBlockCacheSize
} else {
  $defaultSummary = Get-Content -Raw -Path (Resolve-RepoPath $ExistingDefaultSummary) | ConvertFrom-Json
}
if ([string]::IsNullOrWhiteSpace($ExistingReadOptimizedSummary)) {
  $readOptimizedSummary = Run-LdbProfile "read_optimized" $readOptimizedOutput $ReadOptimizedBlockCacheSize
} else {
  $readOptimizedSummary = Get-Content -Raw -Path (Resolve-RepoPath $ExistingReadOptimizedSummary) | ConvertFrom-Json
}

$rows = @()
$rows += Write-ProfileRows $defaultSummary
$rows += Write-ProfileRows $readOptimizedSummary

$rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $outputRoot "profile-comparison.csv")
$rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $outputRoot "profile-comparison.json")

Write-Output "LDB read profile comparison report: $(Join-Path $outputRoot 'profile-comparison.csv')"
