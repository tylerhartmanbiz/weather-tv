#!/usr/bin/env bash
# One command for everything:
#   ./helper.sh setup            first-time install (isolated, inside this folder)
#   ./helper.sh record           record a search on a website -> writes scrapper.py RECIPE
#   ./helper.sh search tornado   test the recorded search from the terminal
#   ./helper.sh open tt0993846   optional: test a site's Open link from this computer
#   ./helper.sh diagnose tt0993846 [season episode]   why won't it play? report -> debug/output/
#   ./helper.sh watch tt0993846 [season episode]      same, in a visible browser you can watch
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
  open)   shift; $PY -B scrapper.py --open "${1:?usage: ./helper.sh open tt0993846 [season episode]}" "${@:2}" ;;
  diagnose) shift; $PY -B debug/diagnose.py "${@:?usage: ./helper.sh diagnose tt0993846 [season episode]}" ;;
  watch)  shift; $PY -B debug/diagnose.py --watch "${@:?usage: ./helper.sh watch tt0993846 [season episode]}" ;;
  scrape) ./run_scraper.sh --push ;;
  *) sed -n '2,9p' "$0" ;;
esac
