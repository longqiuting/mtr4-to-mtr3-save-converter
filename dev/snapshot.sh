#!/usr/bin/env bash
# Read-only SHA-256 snapshot of the protected saves. Writes only to $OUT.
set -u
OUT="$1"; shift
: > "$OUT"
for d in "$@"; do
  echo "## $d" >> "$OUT"
  ( cd "$d" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum ) >> "$OUT"
done
echo "wrote $OUT"
