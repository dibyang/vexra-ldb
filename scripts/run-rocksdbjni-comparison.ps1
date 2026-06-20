param(
  [string]$RocksDbJniVersion = "10.10.1",
  [string]$JarPath = "",
  [string]$OutputDir = "build/reports/rocksdbjni-comparison",
  [string]$ExistingLdbSummary = "ldb-longrun/build/reports/ldb-db-bench/ldb-db-bench-summary.json",
  [string]$Benchmarks = "fillseq,warm_readrandom,multiget_random,overwrite,readwhilewriting",
  [int]$Num = 200000,
  [int]$Reads = 200000,
  [int]$ValueSize = 100,
  [int]$BatchSize = 64,
  [int]$Runs = 1,
  [switch]$Sync
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return $Path
  }
  return Join-Path (Get-Location).Path $Path
}

function Ensure-RocksDbJniJar([string]$Version, [string]$RequestedPath, [string]$CacheDir) {
  if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
    $resolved = Resolve-RepoPath $RequestedPath
    if (-not (Test-Path $resolved)) {
      throw "RocksDB JNI jar not found: $resolved"
    }
    return $resolved
  }
  New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
  $jar = Join-Path $CacheDir "rocksdbjni-$Version.jar"
  if (-not (Test-Path $jar)) {
    $url = "https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/$Version/rocksdbjni-$Version.jar"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $jar
  }
  return $jar
}

function Compile-Bench([string]$Jar, [string]$ClassesDir) {
  New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
  & javac -encoding UTF-8 -cp $Jar -d $ClassesDir scripts/RocksDbJniBench.java
  if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
  }
}

function Write-ComparisonRows([object]$LdbSummary, [object]$RocksSummary, [int]$Run) {
  $rows = @()
  foreach ($result in $LdbSummary.results) {
    $rocks = $RocksSummary.results | Where-Object { $_.name -eq $result.name } | Select-Object -First 1
    $ratio = ""
    if ($rocks -and [double]$rocks.opsPerSecond -gt 0) {
      $ratio = [Math]::Round(([double]$result.opsPerSecond) / ([double]$rocks.opsPerSecond), 4)
    }
    $rows += [pscustomobject]@{
      run = $Run
      engine = "ldb"
      benchmark = $result.name
      opsPerSecond = [double]$result.opsPerSecond
      ratioToRocksDb = $ratio
      num = $LdbSummary.num
      reads = $LdbSummary.reads
      valueSize = $LdbSummary.valueSize
      sync = $LdbSummary.sync
      readProfile = if ($LdbSummary.PSObject.Properties.Name -contains "readProfile") { $LdbSummary.readProfile } else { "" }
      batchSize = if ($LdbSummary.PSObject.Properties.Name -contains "batchSize") { $LdbSummary.batchSize } else { "" }
      sstReadStats = if ($result.PSObject.Properties.Name -contains "sstReadStats") { $result.sstReadStats } else { "" }
      blockCacheStats = if ($result.PSObject.Properties.Name -contains "blockCacheStats") { $result.blockCacheStats } else { "" }
      notes = "LDB db_bench-style runner"
    }
  }
  foreach ($result in $RocksSummary.results) {
    $rows += [pscustomobject]@{
      run = $Run
      engine = "rocksdbjni"
      benchmark = $result.name
      opsPerSecond = [double]$result.opsPerSecond
      ratioToRocksDb = 1
      num = $RocksSummary.num
      reads = $RocksSummary.reads
      valueSize = $RocksSummary.valueSize
      sync = $RocksSummary.sync
      readProfile = ""
      batchSize = if ($RocksSummary.PSObject.Properties.Name -contains "batchSize") { $RocksSummary.batchSize } else { "" }
      sstReadStats = ""
      blockCacheStats = ""
      notes = "RocksDB JNI $($RocksSummary.rocksDbJniVersion)"
    }
  }
  return $rows
}

function Percentile([double[]]$Values, [double]$Percentile) {
  if ($Values.Count -eq 0) {
    return $null
  }
  $sorted = @($Values | Sort-Object)
  $index = [int][Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
  $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
  return [Math]::Round($sorted[$index], 4)
}

function Write-Comparison([object[]]$Rows, [string]$OutputRoot) {
  $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputRoot "comparison.csv")
  $rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $OutputRoot "comparison.json")
}

function Write-Stats([object[]]$Rows, [string]$OutputRoot) {
  $stats = @()
  $groups = $Rows | Group-Object engine, benchmark
  foreach ($group in $groups) {
    $first = $group.Group | Select-Object -First 1
    $ops = @($group.Group | ForEach-Object { [double]$_.opsPerSecond })
    $ratios = @($group.Group | Where-Object { $_.ratioToRocksDb -ne "" } | ForEach-Object { [double]$_.ratioToRocksDb })
    $ratioMin = ""
    $ratioMedian = ""
    $ratioP95 = ""
    $ratioMax = ""
    if ($ratios.Count -gt 0) {
      $ratioMin = [Math]::Round(($ratios | Measure-Object -Minimum).Minimum, 4)
      $ratioMedian = Percentile $ratios 50
      $ratioP95 = Percentile $ratios 95
      $ratioMax = [Math]::Round(($ratios | Measure-Object -Maximum).Maximum, 4)
    }
    $stats += [pscustomobject]@{
      engine = $first.engine
      benchmark = $first.benchmark
      runs = $group.Count
      opsMin = [Math]::Round(($ops | Measure-Object -Minimum).Minimum, 3)
      opsMedian = Percentile $ops 50
      opsP95 = Percentile $ops 95
      opsMax = [Math]::Round(($ops | Measure-Object -Maximum).Maximum, 3)
      ratioMin = $ratioMin
      ratioMedian = $ratioMedian
      ratioP95 = $ratioP95
      ratioMax = $ratioMax
    }
  }
  $stats | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputRoot "comparison-stats.csv")
  $stats | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $OutputRoot "comparison-stats.json")
}

if ($Runs -le 0) {
  throw "Runs must be > 0"
}

$outputRoot = Resolve-RepoPath $OutputDir
$rocksOutput = Join-Path $outputRoot "rocksdbjni"
$classesDir = Join-Path $outputRoot "classes"
$jarCache = Join-Path $outputRoot "jars"
New-Item -ItemType Directory -Force -Path $rocksOutput | Out-Null

$jar = Ensure-RocksDbJniJar $RocksDbJniVersion $JarPath $jarCache
Compile-Bench $jar $classesDir

$syncText = if ($Sync) { "true" } else { "false" }
$ldbSummaryPath = Resolve-RepoPath $ExistingLdbSummary
$ldbSummary = Get-Content -Raw -Path $ldbSummaryPath | ConvertFrom-Json
$rows = @()
$benchmarkItems = @($Benchmarks.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
$needsColdPrepare = $benchmarkItems -contains "cold_readrandom"
$runBenchmarks = ($benchmarkItems | ForEach-Object {
  if ($_ -eq "cold_readrandom") {
    "cold_readrandom_existing"
  } else {
    $_
  }
}) -join ","
for ($run = 1; $run -le $Runs; $run++) {
  $runOutput = if ($Runs -eq 1) { $rocksOutput } else { Join-Path $rocksOutput ("run-" + $run) }
  $rocksDbDir = Join-Path $runOutput "db"
  New-Item -ItemType Directory -Force -Path $runOutput | Out-Null
  if ($needsColdPrepare) {
    $prepareOutput = Join-Path $runOutput "cold-prepare"
    New-Item -ItemType Directory -Force -Path $prepareOutput | Out-Null
    & java -cp "$classesDir;$jar" RocksDbJniBench `
      --output $prepareOutput `
      --db $rocksDbDir `
      --benchmarks cold_readrandom_prepare `
      --num $Num `
      --reads $Reads `
      --value_size $ValueSize `
      --batch_size $BatchSize `
      --sync $syncText `
      --rocksdbjni_version $RocksDbJniVersion
    if ($LASTEXITCODE -ne 0) {
      throw "RocksDB JNI cold prepare failed with exit code $LASTEXITCODE on run $run"
    }
  }
  & java -cp "$classesDir;$jar" RocksDbJniBench `
    --output $runOutput `
    --db $rocksDbDir `
    --benchmarks $runBenchmarks `
    --num $Num `
    --reads $Reads `
    --value_size $ValueSize `
    --batch_size $BatchSize `
    --sync $syncText `
    --rocksdbjni_version $RocksDbJniVersion
  if ($LASTEXITCODE -ne 0) {
    throw "RocksDB JNI benchmark failed with exit code $LASTEXITCODE on run $run"
  }
  $rocksSummary = Get-Content -Raw -Path (Join-Path $runOutput "rocksdbjni-db-bench-summary.json") | ConvertFrom-Json
  $rows += Write-ComparisonRows $ldbSummary $rocksSummary $run
}
Write-Comparison $rows $outputRoot
Write-Stats $rows $outputRoot
Write-Output "RocksDB JNI comparison report: $(Join-Path $outputRoot 'comparison.csv')"
Write-Output "RocksDB JNI comparison stats: $(Join-Path $outputRoot 'comparison-stats.csv')"
