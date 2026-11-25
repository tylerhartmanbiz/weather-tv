#!/usr/bin/env python3
"""Search server for the TV:  ./helper.sh serve

The TV asks this computer to:
  * /api/imdb/search?q=wolf of w   IMDb titles as you type (imdb.py)
  * /api/imdb/title?id=tt0993846    poster, cast, description, seasons (imdb.py)
  * /api/imdb/episodes?id=tt0903747&season=1   episode names for one season (imdb.py)
  * /api/open?id=tt0993846          scrape the video from OPEN_URL (scrapper.open_title)
  * /api/open?id=tt0903747&s=1&e=2  same, one TV episode (OPEN_URL_TV)
  * /api/search?q=tornado           replay the recorded RECIPE (scrapper.search)
Only video links + titles go back to the TV as JSON. The TV never visits the website itself.
Keep this running while you use search on the TV (same Wi-Fi network).
"""
import json
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import imdb
import scrapper

PORT = 8765
ROOT = Path(__file__).parent
LOCK = threading.Lock()
CACHE = {}  # query -> (time, result)
CACHE_SECONDS = 600


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the LAN interface
        return s.getsockname()[0]
    finally:
        s.close()


def publish_address(addr):
    """Tell the TV app where this server is (docs/config.json in the repo)."""
    cfg = ROOT / "docs" / "config.json"
    data = {"searchServer": addr}
    if cfg.exists() and json.loads(cfg.read_text() or "{}") == data:
        return
    cfg.write_text(json.dumps(data, indent=2) + "\n")
    subprocess.run(["git", "-C", str(ROOT), "commit", "-q", "-m", "Update search server address", "docs/config.json"])
    subprocess.run(["git", "-C", str(ROOT), "push", "-q"])
    print("Published new address. Reopen the app on the TV in a few minutes.")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/api/ping":
            return self.send_json({"ok": True})
        arg = lambda k: (parse_qs(u.query).get(k) or [""])[0].strip()[:100]
        if u.path == "/api/imdb/search":
            return self.cached("imdb-search", arg("q").lower(), imdb.search_result, lock=False)
        if u.path == "/api/imdb/title":
            return self.cached("imdb-title", arg("id"), imdb.title_result, lock=False)
        if u.path == "/api/imdb/episodes":
            return self.cached("imdb-episodes", f"{arg('id')}:{arg('season')}", imdb.episodes_result, lock=False)
        if u.path == "/api/open":
            key = arg("id") + (f":{arg('s')}:{arg('e')}" if arg("s") or arg("e") else "")
            return self.cached("open", key, open_result, lock=True)
        if u.path != "/api/search":
            return self.send_json({"error": "not found"}, 404)
        q = arg("q")
        if not q:
            return self.send_json({"error": "empty search"}, 400)
        key = q.lower()
        with LOCK:  # one search at a time
            hit = CACHE.get(key)
            if hit and time.time() - hit[0] < CACHE_SECONDS:
                result = hit[1]
            else:
                try:
                    videos = scrapper.search(q)
                    result = {"query": q, "videos": videos, "allowedHosts": scrapper.allowed_hosts(videos)}
                except Exception as e:
                    print(f"search failed for {q!r}: {e}")
                    result = {"query": q, "videos": [], "allowedHosts": [], "error": "search failed"}
                CACHE[key] = (time.time(), result)
        print(f"search {q!r}: {len(result['videos'])} videos")
        self.send_json(result)

    def cached(self, kind, key, fn, lock):
        if not key:
            return self.send_json({"error": "missing parameter"})
        hit = CACHE.get((kind, key))
        if hit and time.time() - hit[0] < CACHE_SECONDS:
            return self.send_json(hit[1])
        try:
            if lock:
                with LOCK:  # one browser at a time
                    result = fn(key)
            else:
                result = fn(key)
        except Exception as e:
            print(f"{kind} {key!r} failed: {e}")
            result = {"error": f"{kind} failed"}  # TV shows a message; errors aren't cached
            return self.send_json(result)
        CACHE[(kind, key)] = (time.time(), result)
        if kind == "open":
            print(f"open {key}: {len(result.get('videos', []))} video(s) {result.get('allowedHosts')}")
        self.send_json(result)

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def open_result(key):
    imdb_id, *ep = key.split(":")
    if not scrapper.IMDB_ID_RE.match(imdb_id):
        return {"error": "bad IMDb id"}
    if ep and not (len(ep) == 2 and all(x.isdigit() and len(x) <= 4 for x in ep)):
        return {"error": "bad season/episode"}
    return scrapper.open_title(imdb_id, *ep) if ep else scrapper.open_title(imdb_id)


def main():
    if not scrapper.RECIPE:
        print("Note: no search RECIPE recorded yet; IMDb search + Open still work.")
    addr = f"http://{lan_ip()}:{PORT}"
    if "--no-publish" not in sys.argv:
        publish_address(addr)
    print(f"Search server running at {addr}  (Ctrl+C to stop)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
