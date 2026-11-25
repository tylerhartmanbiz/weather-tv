#!/usr/bin/env bash
# One command for everything:
#   ./helper.sh setup            first-time install (isolated, inside this folder)
#   ./helper.sh record           record a search on a website -> writes scrapper.py RECIPE
#   ./helper.sh search tornado   test the recorded search from the terminal
#   ./helper.sh serve            run the search server the TV uses (keep it running)
#   ./helper.sh scrape           refresh the TV's default video list (sandboxed) and push
set -euo pipefail
cd "$(dirname "$0")"
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.venv/browsers"
PY=.venv/bin/python
setup() {
  uv venv -q --python 3.12 .venv
  uv pip install -q --python $PY playwright
  $PY -m playwright install chromium
}
[ -x $PY ] || [ "${1:-}" = setup ] || setup
case "${1:-}" in
  setup)  setup ;;
  record) $PY -B record.py ;;
  search) shift; $PY -B scrapper.py --search "$@" ;;
  serve)  $PY -B server.py "${@:2}" ;;
  scrape) ./run_scraper.sh --push ;;
  *) sed -n '2,7p' "$0" ;;
esac
