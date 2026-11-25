#!/usr/bin/env python3
"""Scrape videos from a website and write them to docs/videos.json for the TV app.

======================  TO CHANGE THE WEBSITE, EDIT THIS SECTION ONLY  ======================
"""
# The page to scrape. The first video found is the one the TV plays first.
SOURCE = "https://weather.com/"

# Hosts the TV is allowed to stream video/thumbnails from. Leave empty to allow only the
# hosts the videos were actually found on (https only). Fill it in to be stricter, e.g.
# VIDEO_HOSTS = {"v.w-x.co"} ; IMAGE_HOSTS = {"s.w-x.co"}
VIDEO_HOSTS = {"v.w-x.co"}
IMAGE_HOSTS = {"s.w-x.co"}

# Only direct video files work (HLS .m3u8 or .mp4). Pages that only embed YouTube/other
# players in an iframe are not supported, on purpose: that would load the other site.
VIDEO_EXTENSIONS = (".m3u8", ".mp4")
# ==========================================================================================
"""
How videos are found (in order, first one that finds anything wins):
  1. schema.org VideoObject data (<script type="application/ld+json">) - most news sites
  2. "contentUrl" entries inside the page's embedded JSON (Next.js sites like weather.com)
  3. og:video meta tags
  4. any https link to a .m3u8/.mp4 file in the page

Safety rules (every site is treated as untrusted):
  * Python standard library only. The page is read as TEXT into memory; nothing from the
    site is ever saved, executed, or rendered (no browser, no JavaScript, so no popups).
  * Redirects to a different website are refused (only same-site redirects are followed).
  * Response size is capped and only text/html is accepted.
  * Every URL kept must be https, end in a video extension, and be on an allowed host.
  * The only file written is the JSON we build ourselves.
Run it with ./run_scraper.sh (macOS sandbox: can only write docs/videos.json).
"""
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from html import unescape as html_unescape
from urllib.parse import urljoin, urlparse

MAX_BYTES = 5_000_000
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124 Safari/537.36")


def site_of(host):
    return ".".join((host or "").lower().split(".")[-2:])


class SameSiteRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = urlparse(newurl)
        if new.scheme != "https" or site_of(new.hostname) != site_of(urlparse(SOURCE).hostname):
            raise ValueError(f"blocked redirect to another site: {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url):
    opener = urllib.request.build_opener(SameSiteRedirects)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html"})
    with opener.open(req, timeout=20) as r:
        if "text/html" not in r.headers.get("Content-Type", ""):
            raise ValueError("refusing non-HTML response")
        data = r.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("response too large")
    return data.decode("utf-8", "replace")


# ---------- helpers ----------

def first(v):
    if isinstance(v, list):
        v = v[0] if v else ""
    if isinstance(v, dict):
        v = v.get("url") or v.get("contentUrl") or ""
    return v if isinstance(v, str) else ""


def jstr(s):
    try:
        return json.loads('"' + s + '"')
    except ValueError:
        return s


def clean_text(s, limit):
    return re.sub(r"\s+", " ", re.sub(r"[<>\x00-\x1f]", "", html_unescape(s or ""))).strip()[:limit]


def is_video(u):
    p = urlparse(u or "")
    return p.scheme == "https" and bool(p.hostname) and p.path.lower().endswith(VIDEO_EXTENSIONS)


def page_title(html):
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I)
    return m.group(1) if m else ""


def video(title, desc, thumb, src):
    return {"title": title, "description": desc, "thumbnail": thumb, "src": src}


# ---------- strategies ----------

def from_json_ld(html):
    out = []
    for block in re.findall(r'<script[^>]+application/ld\+json[^>]*>(.*?)</script>', html, re.S | re.I):
        try:
            data = json.loads(block)
        except ValueError:
            continue
        stack = [data]
        while stack:
            o = stack.pop(0)
            if isinstance(o, list):
                stack.extend(o)
            elif isinstance(o, dict):
                t = o.get("@type")
                if t == "VideoObject" or (isinstance(t, list) and "VideoObject" in t):
                    src = first(o.get("contentUrl")) or first(o.get("embedUrl"))
                    if is_video(src):
                        out.append(video(o.get("name", ""), o.get("description", ""),
                                         first(o.get("thumbnailUrl")), src))
                stack.extend(v for v in o.values() if isinstance(v, (dict, list)))
    return out


def from_embedded_json(html):
    text = html.replace('\\"', '"').replace("\\/", "/")
    out = []
    for m in re.finditer(r'"contentUrl":"(https:[^"]+)"', text):
        src = jstr(m.group(1))
        if not is_video(src):
            continue
        before = text[max(0, m.start() - 2000):m.start()]

        def last(key):
            hits = re.findall(r'"' + key + r'":"((?:[^"\\]|\\.)*)"', before)
            return jstr(hits[-1]) if hits else ""
        out.append(video(last("title") or last("name") or last("headline"),
                         last("description"), last("thumbnailUrl"), src))
    return out


def from_og_tags(html):
    def meta(prop):
        m = re.search(r'<meta[^>]+(?:property|name)=["\']' + re.escape(prop) +
                      r'["\'][^>]*content=["\']([^"\']+)', html, re.I)
        return html_unescape(m.group(1)) if m else ""
    src = meta("og:video:secure_url") or meta("og:video:url") or meta("og:video")
    if not is_video(src):
        return []
    return [video(meta("og:title") or page_title(html), meta("og:description"), meta("og:image"), src)]


def from_raw_links(html):
    text = html.replace("\\/", "/")
    exts = "|".join(re.escape(e) for e in VIDEO_EXTENSIONS)
    title = page_title(html)
    return [video(title, "", "", u) for u in
            re.findall(r'https://[^\s"\'<>\\]+?(?:' + exts + r')(?:\?[^\s"\'<>\\]*)?', text, re.I)]


STRATEGIES = [from_json_ld, from_embedded_json, from_og_tags, from_raw_links]


# ---------- main ----------

def scrape():
    html = fetch(SOURCE)
    for strategy in STRATEGIES:
        found = strategy(html)
        videos, seen = [], set()
        for v in found:
            src = urljoin(SOURCE, v["src"])
            host = urlparse(src).hostname
            if not is_video(src) or src in seen or (VIDEO_HOSTS and host not in VIDEO_HOSTS):
                continue
            seen.add(src)
            thumb = urljoin(SOURCE, v["thumbnail"] or "")
            tp = urlparse(thumb)
            if tp.scheme != "https" or (IMAGE_HOSTS and tp.hostname not in IMAGE_HOSTS):
                thumb = ""
            videos.append(video(clean_text(v["title"], 200), clean_text(v["description"], 500), thumb, src))
        if videos:
            return videos, strategy.__name__
    return [], None


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/videos.json"
    videos, how = scrape()
    if not videos:
        sys.exit("no videos found; leaving existing file untouched")
    hosts = sorted({urlparse(v["src"]).hostname for v in videos} |
                   {urlparse(v["thumbnail"]).hostname for v in videos if v["thumbnail"]})
    data = {"source": SOURCE, "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "allowedHosts": hosts, "videos": videos}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"wrote {len(videos)} videos to {out} (found via {how}); first: {videos[0]['title']}")


if __name__ == "__main__":
    main()
