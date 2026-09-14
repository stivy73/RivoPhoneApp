#!/usr/bin/env bash
set -euo pipefail
# ShizuCallRecorder v1.3.3 / dd940fe2caa8aa1b4c7143ad5123c9923b343abd.
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
expected=84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a
url=https://github.com/Genymobile/scrcpy/releases/download/v4.0/scrcpy-server-v4.0
tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$url" -o "$tmp_file"
actual="$(shasum -a 256 "$tmp_file" | cut -d ' ' -f 1)"
if [[ "$actual" != "$expected" ]]; then
  echo 'scrcpy-server 4.0 checksum mismatch; existing asset was not replaced.' >&2
  exit 1
fi
cp "$tmp_file" "$repo_dir/app/src/main/assets/scrcpy-server"
echo "Verified scrcpy-server 4.0: $actual"
