#!/usr/bin/env python3
"""Scrape videos from a website for the TV app (default list + search).

======================  TO CHANGE THE WEBSITE, EDIT THIS SECTION ONLY  ======================
"""
# The website. Its videos are the TV's default list (./helper.sh scrape), and
# ./helper.sh record starts here. The first video found is the one the TV plays first.
SOURCE = "https://example.com/"   # <-- put your website here

# Hosts the TV is allowed to stream video/thumbnails from. Leave empty to allow only the
# hosts the videos were actually found on (https only). Fill in to be stricter, e.g.
# VIDEO_HOSTS = {"video.example-cdn.com"} ; IMAGE_HOSTS = {"img.example-cdn.com"}
VIDEO_HOSTS = set()
IMAGE_HOSTS = set()

# Only direct video files work (HLS .m3u8 or .mp4). Pages that only embed YouTube/other
# players in an iframe are not supported, on purpose: that would load the other site.
VIDEO_EXTENSIONS = (".m3u8", ".mp4")

# The "Open" button's website lives in docs/config.json (the TV reads it from there, so
# changing it never needs a reinstall). ./helper.sh open uses the same addresses to test.
import json as _json, pathlib as _pathlib
_DOCS = _pathlib.Path(__file__).parent / "docs"
_CFG_FILE = _DOCS / "config-noclaude.json"   # your settings; config.json is the example
_CFG = _json.loads((_CFG_FILE if _CFG_FILE.exists() else _DOCS / "config.json").read_text())
OPEN_URL = _CFG["openUrl"]        # {imdb_id} becomes e.g. tt0993846
OPEN_URL_TV = _CFG["openUrlTv"]   # + {season} and {episode}

# How to SEARCH the site (used when someone searches on the TV). Don't write this by hand:
# run ./helper.sh record, do one search in the browser, and it fills this in for you.
# {query} is replaced by whatever the person searches for. You can still edit it.
# >>> RECIPE (written by record.py) >>>
RECIPE = None
# <<< END RECIPE <<<
# ==========================================================================================
"""
How videos are found (in order, first one that finds anything wins):
  1. schema.org VideoObject data (<script type="application/ld+json">) - most news sites
  2. "contentUrl" entries inside the page's embedded JSON (e.g. Next.js sites)
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


# ---------- search (replays the recorded RECIPE in the locked-down browser) ----------

def search(query, recipe=None):
    """Run the recorded search for `query` and return the videos found (best first)."""
    from urllib.parse import quote, quote_plus
    from safe_browser import SafeBrowser
    recipe = recipe or RECIPE
    if not recipe:
        raise RuntimeError("no RECIPE yet: run ./helper.sh record first")
    query = re.sub(r"[\x00-\x1f]", "", query).strip()[:100]
    with SafeBrowser(recipe["start"]) as b:
        page = b.page
        mark = 0
        for step in recipe["steps"]:
            mark = len(b.videos)  # videos from the final step are the ones we want
            a, sel = step["action"], step.get("selector")
            if a == "goto":
                enc = {"plus": quote_plus, "percent": quote}.get(step.get("encode"), lambda x: x)
                page.goto(step["url"].replace("{query}", enc(query)), wait_until="domcontentloaded", timeout=30000)
            elif a == "fill":
                page.locator(sel).first.fill(step["value"].replace("{query}", query), timeout=10000)
            elif a == "press":
                (page.locator(sel).first.press if sel else page.keyboard.press)(step["key"], timeout=10000)
            elif a == "click":
                page.locator(sel).first.click(timeout=10000)
            try:
                page.wait_for_load_state("domcontentloaded", timeout=10000)
            except Exception:
                pass
            page.wait_for_timeout(700)
        for _ in range(60):  # wait up to ~15s for the video to show up
            if len(b.videos) > mark:
                break
            page.wait_for_timeout(250)
        info = b.page_info()
        html = page.content()
        urls = b.videos[mark:] or b.videos
        hosts = set(recipe.get("video_hosts") or [])
        urls = [u for u in urls if urlparse(u).hostname in hosts] or urls  # skip ad videos
        urls += [u for u in b.dom_videos() if u not in urls]
    found = [video(info["title"], info["description"], info["thumbnail"], u) for u in urls]
    for strategy in STRATEGIES[:3]:  # also pick up any listed on the final page
        found += strategy(html)
    out, seen = [], set()
    for v in found:
        if v["src"].startswith("https://") and v["src"] not in seen:
            seen.add(v["src"])
            thumb = v["thumbnail"] if (v["thumbnail"] or "").startswith("https://") else ""
            out.append(video(clean_text(v["title"], 200), clean_text(v["description"], 500), thumb, v["src"]))
    return out[:20]


def allowed_hosts(videos):
    return sorted({urlparse(v["src"]).hostname for v in videos} |
                  {urlparse(v["thumbnail"]).hostname for v in videos if v["thumbnail"]})


# ---------- open (TV "Open" button: scrape the video from OPEN_URL for one IMDb title) ----------
#
# Safety, on top of the rules above:
#   * Every request made here goes through safe_get(): https only, the hostname must resolve to
#     PUBLIC internet addresses only (never your router, Mac, or anything on your Wi-Fi), and
#     the connection is pinned to the address we checked, so a DNS trick can't swap it later.
#     Redirects are followed by hand (max 3) and each hop is checked again.
#   * The video itself is never downloaded. For HLS (.m3u8) we read only the small text
#     playlists (size-capped, in memory) to learn which hosts the TV will need, and to check
#     every part is https on a normal public hostname. Anything odd -> the video is refused.
#   * If the page needs JavaScript, it runs only inside safe_browser.py (sandboxed Chromium,
#     throwaway profile, popups closed, downloads refused, video bytes never fetched).

import http.client
import ipaddress
import socket
import ssl

IMDB_ID_RE = re.compile(r"^tt\d{7,10}$")
HOSTNAME_RE = re.compile(r"^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$")
LOCAL_SUFFIXES = (".local", ".localhost", ".lan", ".home", ".internal", ".intranet", ".corp", ".arpa")
PLAYLIST_MAX = 2_000_000
MAX_STREAM_HOSTS = 12


def safe_host(host):
    """A normal public hostname (letters in the last part, so no raw IP addresses), or None."""
    h = (host or "").lower().rstrip(".")
    if not HOSTNAME_RE.match(h) or h == "localhost" or h.endswith(LOCAL_SUFFIXES):
        return None
    return h


def public_ip(host):
    infos = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    addrs = [i[4][0].split("%")[0] for i in infos]
    if not addrs or not all(ipaddress.ip_address(a).is_global for a in addrs):
        raise ValueError(f"refusing {host}: not a public internet address")
    return addrs[0]


class _PinnedHTTPS(http.client.HTTPSConnection):
    """HTTPS to `host`, but the TCP connection goes to the already-checked IP address.
    The certificate is still verified against the real hostname."""

    def __init__(self, host, ip):
        super().__init__(host, 443, timeout=15, context=ssl.create_default_context())
        self._ip = ip

    def connect(self):
        sock = socket.create_connection((self._ip, 443), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def safe_get(url, max_bytes, accept, same_site=None):
    """GET a small text resource safely. Returns (final_url, text, hosts_visited)."""
    hosts = set()
    for _ in range(4):
        p = urlparse(url)
        host = safe_host(p.hostname)
        if p.scheme != "https" or not host or p.port not in (None, 443) or p.username or p.password:
            raise ValueError(f"refusing URL {url[:200]}")
        if same_site and site_of(host) != same_site:
            raise ValueError(f"blocked redirect to another site: {url[:200]}")
        hosts.add(host)
        conn = _PinnedHTTPS(host, public_ip(host))
        try:
            conn.request("GET", (p.path or "/") + (f"?{p.query}" if p.query else ""),
                         headers={"User-Agent": UA, "Accept": accept, "Accept-Encoding": "identity"})
            r = conn.getresponse()
            if r.status in (301, 302, 303, 307, 308) and r.getheader("Location"):
                url = urljoin(url, r.getheader("Location"))
                continue
            if r.status != 200:
                raise ValueError(f"HTTP {r.status} from {host}")
            ctype = (r.getheader("Content-Type") or "").lower()
            data = r.read(max_bytes + 1)
        finally:
            conn.close()
        if len(data) > max_bytes:
            raise ValueError("response too large")
        return url, data.decode("utf-8", "replace"), hosts, ctype
    raise ValueError("too many redirects")


def _playlist(url):
    final, text, hosts, ctype = safe_get(url, PLAYLIST_MAX, "application/vnd.apple.mpegurl, */*")
    if "html" in ctype or not text.lstrip("﻿ \r\n").startswith("#EXTM3U"):
        raise ValueError("not an HLS playlist")
    return final, text, hosts


def _attrs(line):
    return dict(re.findall(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)', line.split(":", 1)[-1]))


def _playlist_urls(text, base):
    """Every URL a playlist points at (segments, sub-playlists, keys, init/map files)."""
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(urljoin(base, line))
        for u in re.findall(r'URI="([^"]+)"', line):
            out.append(urljoin(base, u))
    return out


def _hosts_of(urls):
    hosts = set()
    for u in urls:
        p = urlparse(u)
        if p.scheme in ("data", "skd"):
            continue
        h = safe_host(p.hostname)
        if p.scheme != "https" or not h:
            raise ValueError(f"stream uses a non-https or private address: {u[:200]}")
        hosts.add(h)
    return hosts


def inspect_stream(src):
    """Check a video link without downloading video. Returns (hosts the TV needs, quality)."""
    p = urlparse(src)
    if p.path.lower().endswith(".mp4"):
        return _hosts_of([src]), ""
    final, text, hosts = _playlist(src)
    quality = ""
    if "#EXT-X-STREAM-INF" in text:  # master playlist: list of qualities
        lines = text.splitlines()
        variants = []
        for i, line in enumerate(lines):
            if line.startswith("#EXT-X-STREAM-INF"):
                a = _attrs(line)
                nxt = next((l.strip() for l in lines[i + 1:] if l.strip() and not l.startswith("#")), None)
                if nxt:
                    res = re.match(r"(\d+)x(\d+)", a.get("RESOLUTION", ""))
                    variants.append((int(res.group(2)) if res else 0, int(a.get("BANDWIDTH", "0") or 0),
                                     urljoin(final, nxt), a.get("AUDIO", "").strip('"')))
        if not variants:
            raise ValueError("playlist lists no streams")
        hosts |= _hosts_of(_playlist_urls(text, final))
        height, _, best, audio_group = max(variants)  # the TV locks to this (highest quality)
        quality = f"{height}p" if height else ""
        vfinal, vtext, vhosts = _playlist(best)
        hosts |= vhosts | _hosts_of(_playlist_urls(vtext, vfinal))
        for line in text.splitlines():  # separate audio track for that quality, if any
            if line.startswith("#EXT-X-MEDIA") and "TYPE=AUDIO" in line:
                a = _attrs(line)
                if a.get("GROUP-ID", "").strip('"') == audio_group and "URI" in a:
                    afinal, atext, ahosts = _playlist(urljoin(final, a["URI"].strip('"')))
                    hosts |= ahosts | _hosts_of(_playlist_urls(atext, afinal))
                    break
    else:
        hosts |= _hosts_of(_playlist_urls(text, final))
    if len(hosts) > MAX_STREAM_HOSTS:
        raise ValueError("stream spread over too many hosts")
    return hosts, quality


def _candidates_from_html(html, base):
    found = []
    for strategy in STRATEGIES:
        for v in strategy(html):
            v["src"] = urljoin(base, v["src"])
            if is_video(v["src"]):
                found.append(v)
    return found


def _candidates_from_browser(url):
    from safe_browser import SafeBrowser
    with SafeBrowser(url, allow_other_site_frames=True) as b:
        page = b.page
        page.goto(url, wait_until="domcontentloaded", timeout=30000)
        for i in range(80):  # up to ~20s for the player to request its video
            if b.videos:
                break
            if i == 24:  # nothing after ~6s: press play in the middle of the player once
                try:
                    page.mouse.click(640, 400)
                except Exception:
                    pass
            page.wait_for_timeout(250)
        info = b.page_info()
        html = page.content()
        urls = b.videos + [u for u in b.dom_videos() if u not in b.videos]
    found = [video(info["title"], info["description"], info["thumbnail"], u) for u in urls]
    return found + _candidates_from_html(html, url)


def open_title(imdb_id, season=None, episode=None):
    """Scrape OPEN_URL (or OPEN_URL_TV for an episode) for one IMDb title.
    Returns {"videos": [...], "allowedHosts": [...]}."""
    if not IMDB_ID_RE.match(imdb_id or ""):
        raise ValueError("bad IMDb id")
    if season is not None or episode is not None:
        season, episode = int(season), int(episode)
        if not (0 <= season <= 999 and 0 <= episode <= 9999):
            raise ValueError("bad season/episode")
        url = OPEN_URL_TV.format(imdb_id=imdb_id, season=season, episode=episode)
    else:
        url = OPEN_URL.format(imdb_id=imdb_id)
    site = site_of(urlparse(url).hostname)
    candidates = []
    try:  # 1) read the page as plain text (nothing from the site runs)
        final, html, _, ctype = safe_get(url, MAX_BYTES, "text/html", same_site=site)
        if "text/html" in ctype:
            candidates = _candidates_from_html(html, final)
    except Exception as e:
        print(f"open {imdb_id}: plain fetch failed: {e}")
    if not candidates:  # 2) the page needs JavaScript: use the sandboxed browser
        try:
            candidates = _candidates_from_browser(url)
        except Exception as e:
            print(f"open {imdb_id}: sandboxed browser failed: {str(e).splitlines()[0][:200]}")
    candidates.sort(key=lambda v: not urlparse(v["src"]).path.lower().endswith(".m3u8"))  # HLS first
    seen = set()
    for v in candidates[:8]:
        if v["src"] in seen:
            continue
        seen.add(v["src"])
        try:
            hosts, quality = inspect_stream(v["src"])
        except Exception as e:
            print(f"open {imdb_id}: rejected {v['src'][:120]}: {e}")
            continue
        thumb = v["thumbnail"] if safe_host(urlparse(v["thumbnail"] or "").hostname) and \
            (v["thumbnail"] or "").startswith("https://") else ""
        if thumb:
            hosts.add(urlparse(thumb).hostname.lower())
        item = video(clean_text(v["title"], 200), clean_text(v["description"], 500), thumb, v["src"])
        item["quality"] = quality
        return {"imdbId": imdb_id, "season": season, "episode": episode,
                "videos": [item], "allowedHosts": sorted(hosts)}
    return {"imdbId": imdb_id, "videos": [], "allowedHosts": [], "error": "no playable video found"}


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
    if len(sys.argv) > 2 and sys.argv[1] == "--open":
        print(json.dumps(open_title(*sys.argv[2:5]), indent=2, ensure_ascii=False))
        return
    if len(sys.argv) > 2 and sys.argv[1] == "--search":
        print(json.dumps(search(" ".join(sys.argv[2:])), indent=2, ensure_ascii=False))
        return
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/videos.json"
    videos, how = scrape()
    if not videos:
        sys.exit("no videos found; leaving existing file untouched")
    hosts = allowed_hosts(videos)
    data = {"source": SOURCE, "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "allowedHosts": hosts, "videos": videos}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"wrote {len(videos)} videos to {out} (found via {how}); first: {videos[0]['title']}")


if __name__ == "__main__":
    main()
