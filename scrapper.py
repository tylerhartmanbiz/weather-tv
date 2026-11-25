#!/usr/bin/env python3
"""Scrape video info from weather.com and write it to docs/videos.json.

Safety rules (the site is treated as untrusted):
  * Python standard library only. The page is read as TEXT into memory; nothing from the
    site is ever saved to disk, executed, or rendered (no browser, no JavaScript).
  * Response size is capped and only text/html is accepted.
  * Every URL we keep must be https and on an allowlisted host, and the video must be an
    HLS playlist (.m3u8). Anything else is dropped.
  * The only file written is the JSON we build ourselves.
Run it inside the sandbox with ./run_scraper.sh (macOS) or let GitHub Actions run it
(.github/workflows/scrape.yml), which keeps it off your computer entirely.
"""
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from urllib.parse import urlparse

SOURCE = "https://weather.com/"
MAX_BYTES = 5_000_000
VIDEO_HOSTS = {"v.w-x.co"}
IMAGE_HOSTS = {"s.w-x.co"}
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

STR = r'"((?:[^"\\]|\\.)*)"'
VIDEO_RE = re.compile(
    r'"seo":\{"title":' + STR + r',"description":' + STR +
    r',"schema":\{[^{}]*?"thumbnailUrl":' + STR + r'[^{}]*?"contentUrl":' + STR
)


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html"})
    with urllib.request.urlopen(req, timeout=20) as r:
        if "text/html" not in r.headers.get("Content-Type", ""):
            raise ValueError("refusing non-HTML response")
        data = r.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("response too large")
    return data.decode("utf-8", "replace")


def unescape(s):
    try:
        return json.loads('"' + s + '"')
    except ValueError:
        return ""


def safe_url(u, hosts, ext=None):
    p = urlparse(u)
    if p.scheme != "https" or p.hostname not in hosts:
        return None
    if ext and not p.path.endswith(ext):
        return None
    return u


def clean_text(s, limit):
    return re.sub(r"[<>\x00-\x1f]", "", s).strip()[:limit]


def scrape():
    html = fetch(SOURCE)
    # Next.js streams its data as JS strings, so quotes arrive escaped as \"
    text = html.replace('\\"', '"')
    videos, seen = [], set()
    for m in VIDEO_RE.finditer(text):
        title, desc, thumb, src = (unescape(g) for g in m.groups())
        src = safe_url(src, VIDEO_HOSTS, ".m3u8")
        if not src or src in seen:
            continue
        seen.add(src)
        videos.append({
            "title": clean_text(title, 200),
            "description": clean_text(desc, 500),
            "thumbnail": safe_url(thumb, IMAGE_HOSTS) or "",
            "src": src,
        })
    return videos


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/videos.json"
    videos = scrape()
    if not videos:
        sys.exit("no videos found; leaving existing file untouched")
    data = {"source": SOURCE, "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "videos": videos}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"wrote {len(videos)} videos to {out}; first: {videos[0]['title']}")


if __name__ == "__main__":
    main()
