param(
  [string]$RocksDbJniVersion = "10.10.1",
  [string]$JarPath = "",
  [string]$OutputDir = "build/reports/rocksdbjni-comparison",
  [string]$ExistingLdbSummary = "ldb-longrun/build/reports/ldb-db-bench/ldb-db-bench-summary.json",
  [string]$Benchmarks = "fillseq,readrandom,overwrite,readwhilewriting",
  [int]$Num = 200000,
  [int]$Reads = 200000,
  [int]$ValueSize = 100,
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

function Write-Comparison([object]$LdbSummary, [object]$RocksSummary, [string]$OutputRoot) {
  $rows = @()
  foreach ($result in $LdbSummary.results) {
    $rocks = $RocksSummary.results | Where-Object { $_.name -eq $result.name } | Select-Object -First 1
    $ratio = ""
    if ($rocks -and [double]$rocks.opsPerSecond -gt 0) {
      $ratio = [Math]::Round(([double]$result.opsPerSecond) / ([double]$rocks.opsPerSecond), 4)
    }
    $rows += [pscustomobject]@{
      engine = "ldb"
      benchmark = $result.name
      opsPerSecond = [double]$result.opsPerSecond
      ratioToRocksDb = $ratio
      num = $LdbSummary.num
      reads = $LdbSummary.reads
      valueSize = $LdbSummary.valueSize
      sync = $LdbSummary.sync
      notes = "LDB db_bench-style runner"
    }
  }
  foreach ($result in $RocksSummary.results) {
    $rows += [pscustomobject]@{
      engine = "rocksdbjni"
      benchmark = $result.name
      opsPerSecond = [double]$result.opsPerSecond
      ratioToRocksDb = 1
      num = $RocksSummary.num
      reads = $RocksSummary.reads
      valueSize = $RocksSummary.valueSize
      sync = $RocksSummary.sync
      notes = "RocksDB JNI $($RocksSummary.rocksDbJniVersion)"
    }
  }
  $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputRoot "comparison.csv")
  $rows | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $OutputRoot "comparison.json")
}

$outputRoot = Resolve-RepoPath $OutputDir
$rocksOutput = Join-Path $outputRoot "rocksdbjni"
$rocksDbDir = Join-Path $rocksOutput "db"
$classesDir = Join-Path $outputRoot "classes"
$jarCache = Join-Path $outputRoot "jars"
New-Item -ItemType Directory -Force -Path $rocksOutput | Out-Null

$jar = Ensure-RocksDbJniJar $RocksDbJniVersion $JarPath $jarCache
Compile-Bench $jar $classesDir

$syncText = if ($Sync) { "true" } else { "false" }
& java -cp "$classesDir;$jar" RocksDbJniBench `
  --output $rocksOutput `
  --db $rocksDbDir `
  --benchmarks $Benchmarks `
  --num $Num `
  --reads $Reads `
  --value_size $ValueSize `
  --sync $syncText `
  --rocksdbjni_version $RocksDbJniVersion
if ($LASTEXITCODE -ne 0) {
  throw "RocksDB JNI benchmark failed with exit code $LASTEXITCODE"
}

$ldbSummaryPath = Resolve-RepoPath $ExistingLdbSummary
$ldbSummary = Get-Content -Raw -Path $ldbSummaryPath | ConvertFrom-Json
$rocksSummary = Get-Content -Raw -Path (Join-Path $rocksOutput "rocksdbjni-db-bench-summary.json") | ConvertFrom-Json
Write-Comparison $ldbSummary $rocksSummary $outputRoot
Write-Output "RocksDB JNI comparison report: $(Join-Path $outputRoot 'comparison.csv')"
