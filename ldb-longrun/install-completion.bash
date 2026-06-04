#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source_file="$script_dir/longrun-completion.bash"

if [[ ! -f "$source_file" ]]; then
  echo "longrun-completion.bash not found next to install-completion.bash" >&2
  exit 1
fi

target_dir="${XDG_DATA_HOME:-$HOME/.local/share}/bash-completion/completions"
target_file="$target_dir/longrun"
bashrc="${HOME}/.bashrc"
source_line="source \"$target_file\""

mkdir -p "$target_dir"
cp "$source_file" "$target_file"
chmod 0644 "$target_file"

touch "$bashrc"
if ! grep -Fq "$source_line" "$bashrc"; then
  {
    echo
    echo "# longrun bash completion"
    echo "$source_line"
  } >> "$bashrc"
fi

echo "Installed longrun bash completion:"
echo "  $target_file"
echo
echo "Open a new shell, or run once now:"
echo "  source \"$target_file\""

