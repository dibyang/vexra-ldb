# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 键入自动补齐（命令 + `-c/--config`）:

```powershell
Register-ArgumentCompleter -CommandName longrun -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)

  $commands = @('run', 'start', 'watch', 'stop', 'status', 'logs', 'restart', 'report', 'worker')
  $elements = $commandAst.CommandElements

  if ($elements.Count -eq 1) {
    return
  }

  $argCount = $elements.Count - 1
  $commandToken = $elements[1].ToString()
  $current = $elements[$elements.Count - 1].ToString()
  $previous = if ($elements.Count -ge 2) { $elements[$elements.Count - 2].ToString() } else { '' }
  $base = if ($wordToComplete) { $wordToComplete } else { "" }

  if ($argCount -eq 1 -and $commandToken -notlike '-*') {
    $commands | ForEach-Object {
      if (# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€? -like "$base*") {
        [System.Management.Automation.CompletionResult]::new(# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?, # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?, 'Command', "command # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?")
      }
    }
    return
  }

  if ($current -like '-*') {
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
    $options | ForEach-Object {
      if (# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€? -like "$base*") {
        [System.Management.Automation.CompletionResult]::new(# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?, # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?, 'ParameterName', # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?)
      }
    }
    if ($current -in @('-c', '--config', '-p', '--profile')) {
      $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
      $profiles | ForEach-Object {
        if (# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€? -like "$base*") {
          [System.Management.Automation.CompletionResult]::new(
            # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?,
            # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?,
            'ParameterValue',
            "profile # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?"
          )
        }
      }
    }
    return
  }

  if ($previous -in @('-c', '--config', '-p', '--profile')) {
    $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
    $profiles | ForEach-Object {
      if (# ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€? -like "$base*") {
        [System.Management.Automation.CompletionResult]::new(
          # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?,
          # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?,
          'ParameterValue',
          "profile # ldb-longrun

[English](README.en.md) | 涓枃

`ldb-longrun` 鏄?LDB 鐨勭嫭绔嬮暱绋冲帇娴嬩笌鏁呴殰娉ㄥ叆娴嬭瘯宸ュ叿銆傚畠浣滀负鐙珛 Gradle 瀛愰」鐩瀯寤哄拰鍙戝竷锛屼笉杩涘叆涓讳骇鍝侀粯璁よ繍琛岃矾寰勶紝涔熶笉杩涘叆榛樿 CI 闀胯窇銆?
## 鏋勫缓

```powershell
.\gradlew.bat :ldb-longrun:distZip
.\gradlew.bat :ldb-longrun:distTar
```

鏋勫缓浜х墿浣嶄簬 `ldb-longrun/build/distributions/`锛屽寘鎷?`ldb-longrun-<version>.zip` 鍜?`ldb-longrun-<version>.tar.gz`銆備袱涓綊妗ｈВ鍘嬪悗鍙寘鍚竴涓《灞傜洰褰曪紝鍥哄畾涓?`ldb-longrun/`銆?
## 鍛戒护

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

### 鏇存柟渚跨殑杈撳叆鏂瑰紡

`--config` 鏀寔 profile 绠€鍐欏悕锛堜細鑷姩琛ラ綈鍒?`.properties`锛夛細

```text
longrun watch --config smoke
longrun watch --config nightly
longrun watch --config config/fault-injection
```

涔熸敮鎸?`-c` 鐭弬鏁帮紝鏂逛究蹇€熻緭鍏ワ細

```powershell
longrun watch -c smoke -d 5m
```

`--config` 绛夌煭鍙傛暟涓€瑙堬細

```text
-c/--config                 => config
-p/--profile                => profile锛堝吋瀹瑰埆鍚嶏級
-i/--instance               => instance
-I/--run.instance           => run.instance
-w/--workDir                => workDir锛堝吋瀹癸級
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
-e/--crash.enabled          => crash.enabled
-z/--crash.interval         => crash.interval
-l/--crash.cycles           => crash.cycles
-f/--fault.enabled          => fault.enabled
-g/--fault.interval         => fault.interval
-j/--fault.kinds            => fault.kinds
-b/--fault.maxBytes         => fault.maxBytes
-h/--fault.retainedCopies   => fault.retainedCopies
-L/--limits.maxDbSizeGb     => limits.maxDbSizeGb
```

report 鍛戒护涔熸敮鎸?`-w <dir>` 绛変环浜?`--workDir <dir>`銆?
PowerShell 鍙厤涓€涓弬鏁拌ˉ鍏紙Tab锛夛細

```powershell
Register-ArgumentCompleter -CommandName longrun -ParameterName c -ScriptBlock {
  param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
  $profiles = Get-ChildItem -Path .\config\*.properties, .\src\main\resources\profiles\*.properties, .\build\resources\main\profiles\*.properties -ErrorAction SilentlyContinue | Select-Object -ExpandProperty BaseName
  $base = if ($wordToComplete) { $wordToComplete } else { "" }
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
```

閰嶅ソ鍚庢墽琛?`longrun watch -c <TAB>` 鍗冲彲鍒楀嚭 profile 鍚嶇О銆?
## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?"
        )
      }
    }
  }
}
```

示例：

- `longrun <TAB>` => `run start watch ...`
- `longrun watch <TAB>` => 补全常用参数
- `longrun watch -c <TAB>` => profile 名称

## Profile

榛樿 profile 浣嶄簬鍙戣鍖?`config/`锛?
- `smoke.properties`锛氶粯璁?5 鍒嗛挓蹇€熼獙璇併€?- `nightly.properties`锛氶粯璁?12 灏忔椂澶滈棿闀胯窇銆?- `soak.properties`锛氶粯璁?7 澶╅暱绋冲帇娴嬶紝鍙€氳繃 `--run.duration=30d` 瑕嗙洊銆?- `reopen.properties`锛氬懆鏈熸€?close/open 骞舵牎楠?`reopenChecks`銆?- `crash.properties`锛氱埗瀛愯繘绋?crash/recovery锛岃褰?`recoveryChecks`銆?- `fault-injection.properties`锛歝opy-based 鏂囦欢鎹熷潖娉ㄥ叆锛岄粯璁や笉娣峰叆鍏朵粬 profile銆?- `comprehensive.properties`锛氳鍐欏帇鍔涖€乺eopen銆乧rash/recovery 鍜岀┖闂村洖鏀惰瀵燂紝榛樿涓嶅惎鐢?fault銆?
## 鍗曞疄渚嬪拰澶氬疄渚?
鍚屼竴涓?`run.instance` 榛樿涓嶈兘閲嶅鍚姩銆傚悗鍙板惎鍔ㄤ細鍐欙細

```text
run/<instance>.pid
logs/<instance>.out
```

姣忔 `start` 浼氭妸鏃ф棩蹇楄疆杞负 `logs/<instance>.out.1`銆乣.2` 绛夛紝骞朵负鏂版祴璇曞垱寤烘柊鐨?`logs/<instance>.out`銆傚疄渚嬪惎鍔ㄥ悗浼氬厛杈撳嚭 `START` 鍜屽琛?`CONFIG`锛岃繍琛屼腑浼氭寜 `metrics.interval` 杈撳嚭甯?`progressPercent` 鐨?`PROGRESS` 琛屻€俙logs` 鍜?`watch` 鍦ㄦ帶鍒跺彴璺熼殢杩愯瀹炰緥鏃朵細鎶?`PROGRESS` 琛屽師鍦板埛鏂颁负甯﹀瓧绗﹁繘搴︽潯鐨勫崟琛岃繘搴︼紝渚嬪 `PROGRESS [##########----------]  50% ...`锛涙棩蹇楁枃浠朵粛淇濈暀瀹屾暣閫愯璁板綍銆?
鏅€?workload 榛樿鏄?fresh run锛歚resume=false` 鏃朵細鍦ㄨ幏鍙?workDir 閿佸悗娓呯悊 `db/`銆乣state/`銆乣metrics/`銆乣report/`銆乣fault/`锛岄伩鍏嶅鐢ㄤ笂涓€娆℃湭瀹屾垚杩愯鐨勫簱鏂囦欢鍜岀姸鎬佹枃浠躲€俢rash/recovery worker 浣跨敤 `--resume=true` 鏃朵笉浼氭竻鐞嗭紝浼氬鐢ㄥ悓涓€ workDir 鎵ц鎭㈠鏍￠獙銆?
鍗曞疄渚嬶細

```bash
bin/longrun start --config config/smoke.properties
bin/longrun status --config config/smoke.properties
bin/longrun logs --config config/smoke.properties
bin/longrun stop --config config/smoke.properties
```

`watch` 绛変环浜庡疄渚嬫湭杩愯鏃跺厛 `start`锛屽啀璺熼殢 `logs`锛涘疄渚嬪凡杩愯鏃剁洿鎺ヨ窡闅忓綋鍓嶆棩蹇楋細

```bash
bin/longrun watch --config config/smoke.properties
```

澶氬疄渚嬪繀椤讳娇鐢ㄤ笉鍚?instance 鍜屼笉鍚?workDir锛?
```bash
bin/longrun start --config config/smoke.properties --run.instance=smoke-a --run.workDir=work/smoke-a
bin/longrun start --config config/smoke.properties --run.instance=smoke-b --run.workDir=work/smoke-b
```

## 鏁呴殰娉ㄥ叆

`fault-injection.properties` 浣跨敤 copy-based 妯″紡锛?
1. commit 骞舵牎楠屼富搴撱€?2. 鍏抽棴涓诲簱銆?3. 澶嶅埗鏁翠釜 DB 鐩綍鍒?`work/<profile>/fault/fault-N/`銆?4. 鍙牬鍧忓壇鏈€?5. 鍙鎵撳紑鍓湰骞跺垎绫汇€?6. 閲嶆柊鎵撳紑涓诲簱骞舵牎楠屻€?
鏀寔 `truncate`銆乣bit-flip`銆乣zero-range`銆乣random-range`銆乣partial-page`銆俙fault.retainedCopies` 鎺у埗鍓湰淇濈暀涓婇檺锛屽巻鍙?fault metrics 涓嶄細琚垹闄ゃ€?
## 鎶ュ憡

姝ｅ父缁撴潫浼氳嚜鍔ㄧ敓鎴愶細

```text
work/<profile>/report/summary.md
work/<profile>/report/summary.properties
```

涔熷彲浠ユ墜鍔ㄩ噸璺戯細

```bash
bin/longrun report --workDir work/smoke
```

鏍稿績鎸囨爣鍖呮嫭 Operations銆丆ommits銆丷eopen Checks銆丷ecovery Checks銆丱ps/s銆乀hroughput Drop Ratio銆丗inal Size Bytes銆丼ize Amplification銆丷eclamation Events銆丗ault Injection Events銆丼uspicious Log Lines銆丗ailures 鍜?Warnings銆?
## 鍙戝竷楠屾敹

寤鸿鍙戝竷鍓嶆樉寮忔墽琛岋細

- smoke锛? 鍒嗛挓 PASS銆?- reopen锛? 灏忔椂 PASS锛宍reopenChecks > 0`銆?- crash/recovery锛?0 鍒嗛挓 PASS锛宍recoveryChecks > 0`銆?- fault injection锛?0 鍒嗛挓锛寀nexpected 涓?0銆?- comprehensive锛?2 灏忔椂 PASS銆?- soak锛?4 灏忔椂鎴?72 灏忔椂 PASS銆?- 姝ｅ紡鐗堟湰寤鸿鑷冲皯涓€杞?7 澶?PASS銆?
鎶ュ憡褰掓。寤鸿鏀惧湪鍙戝竷娴佺▼鎸囧畾鐩綍锛屼緥濡?`work/<profile>/report/` 鐨勫帇缂╁壇鏈垨鍙戝竷瀵硅薄瀛樺偍璺緞銆? 澶╅暱璺戠殑鏈€缁堝綊妗ｄ綅缃敱鍙戝竷璐熻矗浜虹‘璁ゃ€?`logs` 鍦ㄥ疄渚嬭繍琛屼腑浼氭寔缁窡闅忔棩蹇楋紱瀹炰緥缁撴潫鍚庝細鎵撳嵃鏈€杩戞棩蹇楀苟閫€鍑恒€?
