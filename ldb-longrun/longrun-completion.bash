# Bash completion for longrun.
# Load with:
#   source ./longrun-completion.bash

_longrun_profile_candidates() {
  local cur="$1"
  local root dir file base
  local candidates=()

  root="$(_longrun_root_dir)"
  for dir in "$root/config" "$root/src/main/resources/profiles" "$root/build/resources/main/profiles"; do
    for file in "$dir"/*.properties; do
      [[ -f "$file" ]] || continue
      base="${file##*/}"
      base="${base%.properties}"
      candidates+=("$base")
      candidates+=("${file#$root/}")
    done
  done

  COMPREPLY=( $(compgen -W "${candidates[*]}" -- "$cur") )
}

_longrun_root_dir() {
  local cmd path root

  cmd="${COMP_WORDS[0]}"
  if [[ "$cmd" == */* ]]; then
    path="$cmd"
    [[ "$path" != /* ]] && path="$PWD/$path"
    root="$(cd "$(dirname "$path")/.." 2>/dev/null && pwd -P)"
    if [[ -n "$root" && -d "$root/config" ]]; then
      printf '%s\n' "$root"
      return 0
    fi
  elif path="$(command -v "$cmd" 2>/dev/null)"; then
    root="$(cd "$(dirname "$path")/.." 2>/dev/null && pwd -P)"
    if [[ -n "$root" && -d "$root/config" ]]; then
      printf '%s\n' "$root"
      return 0
    fi
  fi

  printf '%s\n' "$PWD"
}

_longrun_completion() {
  local cur prev command
  local commands="run start watch stop status logs restart report worker"
  local config_options="-c --config -p --profile"
  local options="-c --config -p --profile -i --instance -I --run.instance -w --workDir -W --run.workDir -d --run.duration -n --run.name -s --run.seed -r --resume -k --workload.keySpace -m --workload.mode -v --workload.valueSizeMin -V --workload.valueSizeMax -a --workload.commitEveryOps -q --workload.readRatio -y --workload.writeRatio -x --workload.removeRatio -t --metrics.interval -u --state.interval -o --check.reopenInterval -O --check.finalVerify -e --crash.enabled -z --crash.interval -l --crash.cycles -f --fault.enabled -g --fault.interval -j --fault.kinds -b --fault.maxBytes -h --fault.retainedCopies -L --limits.maxDbSizeGb -M --ldb.writeBufferSizeMb"

  cur="${COMP_WORDS[COMP_CWORD]}"
  prev="${COMP_WORDS[COMP_CWORD-1]}"

  if [[ "$cur" == --config=* ]]; then
    _longrun_profile_candidates "${cur#--config=}"
    COMPREPLY=( "${COMPREPLY[@]/#/--config=}" )
    return 0
  fi

  if [[ "$cur" == --profile=* ]]; then
    _longrun_profile_candidates "${cur#--profile=}"
    COMPREPLY=( "${COMPREPLY[@]/#/--profile=}" )
    return 0
  fi

  if [[ "$COMP_CWORD" -eq 1 ]]; then
    COMPREPLY=( $(compgen -W "$commands" -- "$cur") )
    return 0
  fi

  command="${COMP_WORDS[1]}"
  if [[ ! " $commands " == *" $command "* ]]; then
    COMPREPLY=()
    return 0
  fi

  if [[ " $config_options " == *" $prev "* ]]; then
    _longrun_profile_candidates "$cur"
    return 0
  fi

  if [[ "$cur" == -* ]]; then
    COMPREPLY=( $(compgen -W "$options" -- "$cur") )
    return 0
  fi

  COMPREPLY=()
  return 0
}

complete -F _longrun_completion longrun
complete -F _longrun_completion bin/longrun
complete -F _longrun_completion ./bin/longrun
