<# 
PowerShell completion for longrun.
Load with:
  . "$PSScriptRoot\longrun-completion.ps1"
or add to your PowerShell profile.
#>

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
      return
    }
    return
  }

  # If command already fully specified before cursor (e.g., `longrun watch ...`), continue as parameter phase.
  $commandToken = if ($elements.Count -ge 2) { $elements[1].ToString() } else { '' }
  if ($commandToken -notin $commands) {
    return
  }

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
