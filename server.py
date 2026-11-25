#!/usr/bin/env python3
"""Search server for the TV:  ./helper.sh serve

The TV asks this computer to search (GET /api/search?q=tornado). It replays the recorded
RECIPE from scrapper.py in the locked-down browser (safe_browser.py) and sends back only the
video links + titles as JSON. The TV never visits the website itself.
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
        if u.path != "/api/search":
            return self.send_json({"error": "not found"}, 404)
        q = (parse_qs(u.query).get("q") or [""])[0].strip()[:100]
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

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    if not scrapper.RECIPE:
        sys.exit("No recipe yet. Run ./helper.sh record first.")
    addr = f"http://{lan_ip()}:{PORT}"
    if "--no-publish" not in sys.argv:
        publish_address(addr)
    print(f"Search server running at {addr}  (Ctrl+C to stop)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
