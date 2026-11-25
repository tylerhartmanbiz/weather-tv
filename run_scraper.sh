#!/usr/bin/env bash
# Runs scrapper.py inside a macOS sandbox: network allowed, but it can only write the
# one output file and cannot launch any other program.
set -euo pipefail
cd "$(dirname "$0")"
OUT="$PWD/docs/videos.json"
touch "$OUT"
PYBIN=$(command -v python3)
PYPREFIX=$(python3 -I -c 'import sys,os;print(os.path.realpath(sys.base_prefix))')
OUT_REAL=$(cd "$(dirname "$OUT")" && pwd -P)/videos.json
sandbox-exec -p "(version 1)
(allow default)
(deny file-write*)
(allow file-write* (literal \"$OUT_REAL\") (literal \"/dev/null\") (literal \"/dev/stdout\") (literal \"/dev/stderr\"))
(deny process-exec)
(allow process-exec (subpath \"$PYPREFIX\") (literal \"$PYBIN\"))" \
  python3 -I -B scrapper.py "$OUT"

# ./run_scraper.sh --push  → also publish the new videos.json (git runs outside the sandbox
# and only ever commits this one JSON file).
if [ "${1:-}" = "--push" ] && ! git diff --quiet docs/videos.json; then
  git commit -q -m "Update videos.json" docs/videos.json && git push -q && echo "pushed; TV updates in a few minutes"
fi
