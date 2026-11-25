"""Find out why the TV app doesn't play a video, by repeating what the TV does on this Mac.

    ./helper.sh diagnose tt0993846              a movie
    ./helper.sh diagnose tt0903747 1 2          a TV episode (season 1, episode 2)

It reads your real settings (docs/config-noclaude.json, falling back to docs/config.json),
opens the Open link in the same kind of locked-down sandbox the TV uses (sandboxed Chromium,
throwaway profile, popups/downloads refused, video bytes never downloaded), and walks the
same four steps the TV takes:

  1. FIND   does the page ask for a video link the TV would notice, in time?
  2. CHECK  does the TV's safety check (Net.inspect) accept that link?
  3. ALLOW  is the stream all https, on public addresses, on few enough hosts?
  4. PLAY   will the stream servers let the app's player (on github.io) read it?

When a step fails, it tries alternatives to find out why (other user-agent, Referer, a
second click, waiting longer, links without .m3u8/.mp4 in their name, players in iframes).

Everything goes to a report file (default debug/diagnose-report.txt, git-ignored), never to
the screen. The SUMMARY at the top of the report has no web addresses in it: just which step
failed and a short CAUSE code. The DETAILS below it do contain addresses.

Options: --config FILE   use another settings file
         --out FILE      write the report somewhere else
         --ua desktop    pretend to be desktop Chrome instead of the Fire TV
"""
import argparse
import ipaddress
import json
import re
import socket
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
APP_ORIGIN = "https://tylerhartmanbiz.github.io"
APP_PAGE = APP_ORIGIN + "/weather-tv/"
# What the TV's hidden browser (Android WebView on a Fire TV) and its Java code look like.
TV_UA = ("Mozilla/5.0 (Linux; Android 11; AFTKA Build/PS7633; wv) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.102 Mobile Safari/537.36")
JAVA_UA = "Dalvik/2.1.0 (Linux; U; Android 11; AFTKA Build/PS7633)"
DESKTOP_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
VIDEO_EXT = (".m3u8", ".mp4")
BLOCK_EXT = (".ts", ".m4s", ".m4a", ".m4v", ".aac", ".cmfv", ".cmfa", ".mp3", ".webm", ".mkv", ".mov",
             ".avi", ".flv", ".vtt", ".srt", ".apk", ".exe", ".zip", ".dmg", ".bin", ".png", ".jpg",
             ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf")
MAX_STREAM_HOSTS = 12
# Same scan the TV runs on the page's HTML (Scraper.SCAN_JS); here it's also run in iframes.
SCAN_JS = r"""() => { const o = [];
  try {
    document.querySelectorAll('video,source').forEach(e => { const s = e.currentSrc || e.src; if (s) o.push(s); });
    const h = document.documentElement.outerHTML.split('\\/').join('/');
    const re = /https:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/gi; let m;
    while ((m = re.exec(h)) && o.length < 50) o.push(m[0]);
  } catch (x) {}
  return o.filter(s => s.startsWith('https://') && /\.(m3u8|mp4)$/i.test(new URL(s).pathname)); }"""
# Same as Scraper.PLAYER_JS: center of the biggest visible player-like element.
PLAYER_JS = """() => { let b = null, a = 0; const W = innerWidth, H = innerHeight;
  document.querySelectorAll('video,iframe,embed,object,canvas').forEach(e => {
    const r = e.getBoundingClientRect(); if (r.width < 200 || r.height < 120) return;
    const s = Math.max(0, Math.min(r.right, W) - Math.max(r.left, 0)) * Math.max(0, Math.min(r.bottom, H) - Math.max(r.top, 0));
    if (s > a) { a = s; b = r; } });
  return b ? [(Math.max(b.left, 0) + Math.min(b.right, W)) / 2, (Math.max(b.top, 0) + Math.min(b.bottom, H)) / 2] : null; }"""
PLAY_JS = """() => { try { document.querySelectorAll('video').forEach(v => {
  v.muted = true; const p = v.play(); if (p && p.catch) p.catch(() => {}); }); } catch (x) {} }"""
CHALLENGE = re.compile(r"just a moment|cf-challenge|challenge-platform|captcha|are you a robot|"
                       r"verify you are human|access denied|attention required", re.I)


def site_of(host):
    return ".".join((host or "").lower().split(".")[-2:])


# ---------------------------------------------------------------- careful http (text only)

class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_OPENER = build_opener(_NoRedirect)
_PUBLIC = {}


def is_public(host):
    if host not in _PUBLIC:
        try:
            ips = {i[4][0] for i in socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)}
            _PUBLIC[host] = bool(ips) and all(ipaddress.ip_address(ip.split("%")[0]).is_global for ip in ips)
        except Exception:
            _PUBLIC[host] = False
    return _PUBLIC[host]


class Resp:
    def __init__(self):
        self.status, self.headers, self.text, self.url, self.error = 0, {}, "", "", ""
        self.hosts = []


def http(url, headers, method="GET", max_bytes=2_000_000):
    """Like the TV's Net.fetch: https + public addresses only, redirects checked hop by hop."""
    r = Resp()
    for _ in range(4):
        p = urlparse(url)
        if p.scheme != "https":
            r.error = "not https"
            return r
        if not is_public(p.hostname or ""):
            r.error = "address is not on the public internet (or doesn't exist)"
            return r
        r.hosts.append(p.hostname.lower())
        try:
            res = _OPENER.open(Request(url, method=method, headers=headers), timeout=15)
        except HTTPError as e:
            res = e
        except (URLError, OSError, ValueError) as e:
            r.error = f"could not connect ({type(e).__name__}: {e})"
            return r
        status = res.status if hasattr(res, "status") else res.code
        hdrs = {k.lower(): v for k, v in res.headers.items()}
        if status in (301, 302, 303, 307, 308) and hdrs.get("location"):
            url = urljoin(url, hdrs["location"])
            continue
        body = b""
        if method != "HEAD":
            try:
                body = res.read(max_bytes + 1)
            except Exception:
                pass
        r.status, r.headers, r.url = status, hdrs, url
        r.text = body[:max_bytes].decode("utf-8", "replace")
        return r
    r.error = "too many redirects"
    return r


def headers_for(who, page_url):
    """The request headers each party sends."""
    site_origin = "https://" + urlparse(page_url).hostname
    if who == "tv-check":      # the TV's Java safety check (Net.fetch): no Referer at all
        return {"User-Agent": JAVA_UA, "Accept": "application/vnd.apple.mpegurl, */*",
                "Accept-Encoding": "identity"}
    if who == "browser":       # a normal browser, no Referer
        return {"User-Agent": TV_UA, "Accept": "*/*", "Accept-Encoding": "identity"}
    if who == "site":          # the video site's own player
        return {"User-Agent": TV_UA, "Accept": "*/*", "Accept-Encoding": "identity",
                "Referer": page_url, "Origin": site_origin}
    if who == "app":           # hls.js in the TV app (page on github.io)
        return {"User-Agent": TV_UA, "Accept": "*/*", "Accept-Encoding": "identity",
                "Referer": APP_ORIGIN + "/", "Origin": APP_ORIGIN}
    raise ValueError(who)


# ---------------------------------------------------------------- HLS (same rules as Net.inspect)

ATTR = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')
URI_ATTR = re.compile(r'URI="([^"]+)"')


def attr(line, key):
    for k, v in ATTR.findall(line.split(":", 1)[-1]):
        if k == key:
            return v.strip('"')
    return ""


def playlist_urls(text, base):
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if not line.startswith("#"):
            out.append(urljoin(base, line))
        for u in URI_ATTR.findall(line):
            if not u.startswith(("data:", "skd:")):
                out.append(urljoin(base, u))
    return out


class Reject(Exception):
    pass


def get_playlist(url, headers):
    r = http(url, headers)
    if r.error:
        raise Reject(r.error)
    t = r.text.lstrip("﻿").strip()
    ctype = r.headers.get("content-type", "").lower()
    if r.status != 200:
        raise Reject(f"server answered HTTP {r.status}")
    if "html" in ctype:
        raise Reject("server sent a web page instead of a playlist")
    if not t.startswith("#EXTM3U"):
        raise Reject("not an HLS playlist (doesn't start with #EXTM3U)")
    return r


def check_urls(urls, hosts):
    for u in urls:
        p = urlparse(u)
        if p.scheme != "https":
            raise Reject("the stream uses a non-https (http) part")
        if p.port not in (None, 443):
            raise Reject("the stream uses an unusual port")
        hosts.add(p.hostname.lower())


def tv_inspect(src, headers):
    """Repeat Net.inspect. Returns details; raises Reject with the reason the TV would reject."""
    d = {"quality": "", "hosts": set(), "variant": None, "segment": None, "audio": None, "drm": False}
    if urlparse(src).path.lower().endswith(".mp4"):
        check_urls([src], d["hosts"])
        if not is_public(urlparse(src).hostname):
            raise Reject("address is not on the public internet")
        d["segment"] = src
        return d
    m = get_playlist(src, headers)
    d["hosts"].update(m.hosts)
    text = m.text
    if "#EXT-X-STREAM-INF" in text:
        lines = text.splitlines()
        best_h, best_bw, best, audio = -1, -1, None, ""
        for i, line in enumerate(lines):
            if not line.startswith("#EXT-X-STREAM-INF"):
                continue
            nxt = next((l.strip() for l in lines[i + 1:] if l.strip() and not l.startswith("#")), None)
            if not nxt:
                continue
            res = re.match(r"(\d+)x(\d+)", attr(line, "RESOLUTION"))
            h = int(res.group(2)) if res else 0
            try:
                bw = int(attr(line, "BANDWIDTH"))
            except ValueError:
                bw = 0
            if h > best_h or (h == best_h and bw > best_bw):
                best_h, best_bw, best, audio = h, bw, urljoin(m.url, nxt), attr(line, "AUDIO")
        if not best:
            raise Reject("playlist lists no streams")
        check_urls(playlist_urls(text, m.url), d["hosts"])
        d["quality"] = f"{best_h}p" if best_h > 0 else ""
        d["variant"] = best
        v = get_playlist(best, headers)
        d["hosts"].update(v.hosts)
        check_urls(playlist_urls(v.text, v.url), d["hosts"])
        media = v
        for line in lines:
            if line.startswith("#EXT-X-MEDIA") and "TYPE=AUDIO" in line and attr(line, "GROUP-ID") == audio \
                    and attr(line, "URI"):
                d["audio"] = urljoin(m.url, attr(line, "URI"))
                a = get_playlist(d["audio"], headers)
                d["hosts"].update(a.hosts)
                check_urls(playlist_urls(a.text, a.url), d["hosts"])
                break
    else:
        media = m
    segs = [u for u in playlist_urls(media.text, media.url)]
    d["segment"] = segs[-1] if segs else None
    for line in media.text.splitlines():
        if line.startswith("#EXT-X-KEY") and ("SAMPLE-AES" in line or "skd:" in line or "widevine" in line.lower()
                                               or "playready" in line.lower()):
            d["drm"] = True
        elif line.startswith("#EXT-X-MAP"):
            u = attr(line, "URI")
            if u:
                d["segment"] = urljoin(media.url, u)
    if len(d["hosts"]) > MAX_STREAM_HOSTS:
        raise Reject(f"stream spread over too many hosts ({len(d['hosts'])}, the TV allows {MAX_STREAM_HOSTS})")
    for h in d["hosts"]:
        if not is_public(h):
            raise Reject("stream uses a private / non-public address")
    return d


def cors_ok(resp):
    acao = resp.headers.get("access-control-allow-origin", "")
    return acao.strip() in ("*", APP_ORIGIN)


# ---------------------------------------------------------------- the sandboxed visit (like Scraper.scrape)

def visit(url, ua):
    site = site_of(urlparse(url).hostname)
    ev = {"tv_found": [], "media_no_ext": [], "blocked_main": [], "blocked_http": 0, "popups": 0,
          "popups_after_click": 0, "main_status": None, "main_error": "", "title": "", "challenge": False,
          "frames": [], "dom_main": [], "dom_frames": [], "playlist_no_ext": [], "late": [],
          "extra_click": [], "clicks": [], "before_clicks": 0, "error": ""}
    t0 = time.time()
    responses = []
    clicked = [False]

    def now():
        return round(time.time() - t0, 1)

    def route(r):
        req = r.request
        p = urlparse(req.url)
        path = p.path.lower()
        if p.scheme in ("data", "blob"):
            return r.continue_()
        if p.scheme != "https":
            ev["blocked_http"] += 1
            return r.abort()
        if path.endswith(VIDEO_EXT):                      # what the TV notices
            if req.url not in [u for _, u in ev["tv_found"]]:
                ev["tv_found"].append((now(), req.url))
            return r.abort()
        if req.resource_type == "media":                  # a video the TV would NOT notice
            ev["media_no_ext"].append((now(), req.url))
            return r.abort()
        if path.endswith(BLOCK_EXT):
            return r.abort()
        try:
            main = req.frame.parent_frame is None
        except Exception:
            main = False
        if req.resource_type == "document" and main and site_of(p.hostname) != site:
            ev["blocked_main"].append((now(), req.url))
            return r.abort()
        return r.continue_()

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, chromium_sandbox=True,
                                     args=["--disable-extensions", "--disable-background-networking",
                                           "--autoplay-policy=no-user-gesture-required"])
        ctx = browser.new_context(accept_downloads=False, permissions=[], java_script_enabled=True,
                                  service_workers="block", viewport={"width": 1280, "height": 720},
                                  user_agent=ua)
        ctx.route("**/*", route)
        page = ctx.new_page()

        def on_page(p):
            if p is not page:
                ev["popups"] += 1
                if clicked[0]:
                    ev["popups_after_click"] += 1
                p.close()
        ctx.on("page", on_page)
        ctx.on("response", lambda resp: responses.append((now(), resp)))
        page.on("download", lambda d: d.cancel())

        def wait(seconds, until=lambda: False):
            end = time.time() + seconds
            while time.time() < end and not until():
                page.wait_for_timeout(250)

        def click():
            clicked[0] = True
            try:
                pos = page.evaluate(PLAYER_JS)
            except Exception:
                pos = None
            ev["clicks"].append("on the player" if pos else "in the middle (no player found)")
            x, y = (pos or (640, 360))
            try:
                page.mouse.click(max(1, min(1279, x)), max(1, min(719, y)))
                page.evaluate(PLAY_JS)
            except Exception:
                pass

        try:
            try:
                first = page.goto(url, wait_until="commit", timeout=20000)
                ev["main_status"] = first.status if first else None
            except Exception as e:
                ev["main_error"] = str(e).splitlines()[0][:200]
            # The TV: let the page load (max 6 s) + 1 s, click the player once, wait 3 s for a
            # new video link; if none, click again and wait up to 15 s; then 1.5 s more.
            try:
                page.wait_for_load_state("load", timeout=6000)
            except Exception:
                pass
            wait(1)
            before = ev["before_clicks"] = len(ev["tv_found"])
            click()
            wait(3, lambda: len(ev["tv_found"]) > before)
            if len(ev["tv_found"]) == before:
                click()
                wait(4 if before else 15, lambda: len(ev["tv_found"]) > before)
            wait(1.5)
            tv_window = now()
            for f in page.frames:
                try:
                    links = f.evaluate(SCAN_JS)
                except Exception:
                    links = []
                if f == page.main_frame:
                    ev["dom_main"] = links
                else:
                    ev["frames"].append(f.url)
                    ev["dom_frames"] += links
            try:
                ev["title"] = page.title()
                ev["challenge"] = bool(CHALLENGE.search(ev["title"] + " " + page.content()[:200000]))
            except Exception:
                pass
            # Beyond what the TV does: if nothing new yet, click a third time, then wait longer.
            if len(ev["tv_found"]) == before:
                click()
                wait(10, lambda: len(ev["tv_found"]) > before)
                ev["extra_click"] = ev["tv_found"][before:]
                wait(10, lambda: len(ev["tv_found"]) > before)
            ev["late"] = [(t, u) for t, u in ev["tv_found"] if t > tv_window]
            # Video playlists at addresses without .m3u8 (the TV can't recognise those).
            seen = {u for _, u in ev["tv_found"]}
            for t, resp in responses:
                try:
                    if resp.url in seen or resp.request.resource_type not in ("xhr", "fetch", "other"):
                        continue
                    ctype = (resp.headers.get("content-type") or "").lower()
                    if "mpegurl" in ctype or ctype.startswith(("video/", "audio/")):
                        ev["playlist_no_ext"].append((t, resp.url, ctype))
                        continue
                    if int(resp.headers.get("content-length") or 0) > 2_000_000:
                        continue
                    if resp.status == 200 and resp.body()[:400].lstrip(b"\xef\xbb\xbf").strip().startswith(b"#EXTM3U"):
                        ev["playlist_no_ext"].append((t, resp.url, ctype or "?"))
                except Exception:
                    pass
            ev["tv_window"] = tv_window
        except Exception as e:
            ev["error"] = f"{type(e).__name__}: {str(e).splitlines()[0][:200]}"
        finally:
            ctx.close()
            browser.close()
    return ev


# ---------------------------------------------------------------- the report

CAUSES = {
    "CONFIG_MISSING": "There is no openUrl / openUrlTv in your settings file for this kind of title.",
    "CONFIG_BAD_URL": "The Open link in your settings isn't a valid https address the TV accepts "
                      "(https only, normal hostname, no port, no user:password).",
    "PAGE_UNREACHABLE": "The video page didn't load at all (no answer, bad address, or the site is down).",
    "PAGE_HTTP_ERROR": "The video page answered with an error code (see DETAILS, step 1).",
    "PAGE_BOT_CHECK": "The site showed a bot check / captcha / 'access denied' page instead of the "
                      "player. The TV can't get past those.",
    "REDIRECT_OTHER_SITE": "The page tried to move to a different website, which the TV blocks. The "
                           "page that holds the player may live on another domain.",
    "VIDEO_NEEDS_MORE_CLICKS": "The video only started after a third click (the first ones probably "
                               "hit ads / overlays). The TV clicks at most twice.",
    "VIDEO_TOO_SLOW": "The page did ask for the video, but only after the TV had already given up "
                      "(about 27 s).",
    "VIDEO_URL_NO_EXTENSION": "The player loads its video from an address that doesn't end in "
                              ".m3u8 or .mp4, so the TV never notices it.",
    "VIDEO_ONLY_IN_IFRAME_HTML": "A video link is written inside an embedded player (iframe), but "
                                 "the TV only reads the main page.",
    "NO_VIDEO_FOUND": "The page never asked for any video. Maybe the title isn't on the site, the "
                      "Open link format is wrong, or the player needs something the sandbox blocks.",
    "CHECK_NEEDS_REFERER": "The stream server refuses the TV's safety check because it doesn't say "
                           "which page it came from (no Referer). With the site's Referer it works.",
    "CHECK_NEEDS_BROWSER_UA": "The stream server refuses the TV's safety check because of its "
                              "Java (Dalvik) user-agent. With a browser user-agent it works.",
    "CHECK_REJECTED": "The TV's safety check rejects the stream, even with Referer/browser "
                      "user-agent (reason in DETAILS, step 2).",
    "STREAM_UNSAFE": "The stream breaks one of the TV's safety rules (http part, private address, or "
                     "too many hosts). The reason is in DETAILS, step 3.",
    "DRM": "The stream is DRM-protected. The TV can't play those.",
    "PLAY_REFERER_REFUSED": "The stream server refuses the TV app's player because it comes from "
                            "github.io instead of the video site (Referer/Origin check).",
    "PLAY_NO_CORS": "The stream server doesn't allow other websites to read the stream (no CORS "
                    "header), so the app's player on github.io is blocked by the browser.",
    "ALL_PASS": "Every step works from this Mac. The problem is likely specific to the TV / "
                "emulator. Run ./emulator.sh logs debug/tv-log.txt while pressing Open on the "
                "emulator.",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("imdb_id")
    ap.add_argument("season", nargs="?")
    ap.add_argument("episode", nargs="?")
    ap.add_argument("--config")
    ap.add_argument("--out", default=str(ROOT / "debug" / "diagnose-report.txt"))
    ap.add_argument("--ua", choices=["tv", "desktop"], default="tv")
    a = ap.parse_args()

    summary, details, cause = [], [], [None]

    def step(name, ok, note=""):
        summary.append(f"  [{'ok' if ok else 'FAIL' if ok is False else ' -- '}] {name}" + (f": {note}" if note else ""))

    def fail(code):
        if not cause[0]:
            cause[0] = code

    def detail(*lines):
        details.extend(lines)

    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    # ---- 0. settings
    if a.config:
        cfg_path = Path(a.config)
    else:
        cfg_path = ROOT / "docs" / "config-noclaude.json"
        if not cfg_path.exists():
            cfg_path = ROOT / "docs" / "config.json"
    is_tv = bool(a.season and a.episode)
    try:
        cfg = json.loads(cfg_path.read_text())
    except Exception as e:
        cfg = {}
        detail(f"Could not read {cfg_path.name}: {e}")
    tmpl = cfg.get("openUrlTv" if is_tv else "openUrl", "")
    url = tmpl.replace("{imdb_id}", a.imdb_id).replace("{season}", a.season or "").replace("{episode}", a.episode or "")
    p = urlparse(url)
    if not tmpl:
        step("0. settings", False, f"{cfg_path.name} has no {'openUrlTv' if is_tv else 'openUrl'}")
        fail("CONFIG_MISSING")
    elif p.scheme != "https" or not p.hostname or p.port not in (None, 443) or p.username:
        step("0. settings", False, "Open link isn't an address the TV accepts")
        fail("CONFIG_BAD_URL")
    else:
        step("0. settings", True, f"using {cfg_path.name}, {'TV episode' if is_tv else 'movie'} link")
    detail("== 0. settings", f"file: {cfg_path}", f"Open link: {url or '(none)'}", "")

    if not cause[0]:
        ua = TV_UA if a.ua == "tv" else DESKTOP_UA
        ev = visit(url, ua)
        tv_found = [(t, u) for t, u in ev["tv_found"] if t <= ev.get("tv_window", 0)]

        # ---- 1. page
        detail("== 1. page (sandboxed browser, " + ("Fire TV" if a.ua == "tv" else "desktop") + " user-agent)",
               f"first answer: HTTP {ev['main_status']}" + (f"  error: {ev['main_error']}" if ev["main_error"] else ""),
               f"title: {ev['title']!r}",
               f"bot-check / captcha words on page: {ev['challenge']}",
               f"popups refused: {ev['popups']} ({ev['popups_after_click']} after clicking)",
               "clicks: " + (", ".join(ev["clicks"]) or "none"),
               f"video links found before the first click: {ev['before_clicks']}",
               f"plain-http requests blocked: {ev['blocked_http']}",
               f"embedded frames ({len(ev['frames'])}):", *[f"    {f}" for f in ev["frames"]],
               "main page tried to go to another site (blocked, like the TV: it stays on the video site):",
               *[f"    {t:5}s {u}" for t, u in ev["blocked_main"]] or ["    none"],
               *([f"script error: {ev['error']}"] if ev["error"] else []), "")
        if ev["main_error"] and ev["main_status"] is None:
            step("1. page loads", False, "no answer from the site")
            fail("PAGE_UNREACHABLE")
        elif ev["main_status"] and ev["main_status"] >= 400 and not ev["tv_found"]:
            step("1. page loads", False, f"HTTP {ev['main_status']}")
            fail("PAGE_BOT_CHECK" if ev["challenge"] else "PAGE_HTTP_ERROR")
        elif ev["challenge"] and not ev["tv_found"]:
            step("1. page loads", False, "looks like a bot check / captcha page")
            fail("PAGE_BOT_CHECK")
        else:
            step("1. page loads", True, f"HTTP {ev['main_status']}, {len(ev['frames'])} embedded frame(s), "
                                        f"{ev['popups']} popup(s) refused")

        # ---- 2. find
        tv_links = [u for _, u in tv_found]
        tv_links += [u for u in ev["dom_main"] if u not in [x for _, x in tv_found]]
        detail("== 2. find: video links the TV would notice (within its ~27 s)",
               *[f"    {t:5}s {u}" for t, u in tv_found] or ["    none from network"],
               "  in the main page's HTML:", *[f"    {u}" for u in ev["dom_main"]] or ["    none"],
               "  -- links the TV would MISS --",
               "  found only after the TV's time / after a second click:",
               *[f"    {t:5}s {u}" for t, u in ev["late"]] or ["    none"],
               "  video playlists at addresses without .m3u8/.mp4:",
               *[f"    {t:5}s [{c}] {u}" for t, u, c in ev["playlist_no_ext"]] or ["    none"],
               "  video requests without .m3u8/.mp4:",
               *[f"    {t:5}s {u}" for t, u in ev["media_no_ext"]] or ["    none"],
               "  links written in embedded frames' HTML:", *[f"    {u}" for u in ev["dom_frames"]] or ["    none"],
               "")
        if not cause[0]:
            if tv_links:
                step("2. TV finds a video link", True, f"{len(tv_links)} link(s)")
            else:
                if ev["extra_click"]:
                    code = "VIDEO_NEEDS_MORE_CLICKS"
                elif ev["late"]:
                    code = "VIDEO_TOO_SLOW"
                elif ev["playlist_no_ext"] or ev["media_no_ext"]:
                    code = "VIDEO_URL_NO_EXTENSION"
                elif ev["dom_frames"]:
                    code = "VIDEO_ONLY_IN_IFRAME_HTML"
                elif ev["blocked_main"]:
                    code = "REDIRECT_OTHER_SITE"
                else:
                    code = "NO_VIDEO_FOUND"
                step("2. TV finds a video link", False, code)
                fail(code)

        # ---- 3. check + 4. play, for each candidate the TV would try (max 8, HLS first)
        after = [u for _, u in tv_found[ev["before_clicks"]:]]
        cands = []
        for group in (after, tv_links):
            cands += [u for u in group if ".m3u8" in u.lower() and u not in cands]
            cands += [u for u in group if u not in cands]
        cands = cands[:8]
        if not cands and cause[0] in ("VIDEO_NEEDS_MORE_CLICKS", "VIDEO_TOO_SLOW"):
            cands = [u for _, u in ev["late"]][:8]   # still check them, to know what comes next
        accepted = None
        check_notes = []
        for src in cands:
            detail(f"== 3. check: {src}")
            try:
                d = tv_inspect(src, headers_for("tv-check", url))
                detail(f"    TV safety check: ACCEPTED  quality={d['quality'] or '?'}  hosts={sorted(d['hosts'])}"
                       f"  drm={d['drm']}")
                accepted = (src, d)
                break
            except Reject as e:
                reason = str(e)
                detail(f"    TV safety check: REJECTED ({reason})")
                retry = {}
                for who in ("browser", "site"):
                    try:
                        tv_inspect(src, headers_for(who, url))
                        retry[who] = "accepted"
                    except Reject as e2:
                        retry[who] = f"rejected ({e2})"
                    detail(f"    same check with {'browser user-agent' if who == 'browser' else 'site Referer + Origin'}: {retry[who]}")
                check_notes.append((reason, retry))
        detail("")

        if not cause[0] and not accepted:
            unsafe = ("http", "port", "too many hosts", "private")
            if any(r["browser"] == "accepted" for _, r in check_notes):
                code, note = "CHECK_NEEDS_BROWSER_UA", ""
            elif any(r["site"] == "accepted" for _, r in check_notes):
                code, note = "CHECK_NEEDS_REFERER", ""
            elif any(any(w in reason for w in unsafe) for reason, _ in check_notes):
                code, note = "STREAM_UNSAFE", ""
            else:
                code = "CHECK_REJECTED"
                note = "; ".join(sorted({reason for reason, _ in check_notes}))
            # Strip anything address-like from the summary note.
            note = re.sub(r"\S+\.\S+", "…", note)
            step("3. TV safety check accepts it", False, code + (f" ({note})" if note else ""))
            fail(code)
        elif accepted:
            step("3. TV safety check accepts it", True,
                 f"{accepted[1]['quality'] or 'unknown quality'}, {len(accepted[1]['hosts'])} host(s)")

        if accepted and not cause[0]:
            src, d = accepted
            if d["drm"]:
                step("4. app's player can read it", False, "DRM")
                fail("DRM")
            else:
                parts = [("playlist", src), ("best-quality playlist", d["variant"]), ("audio playlist", d["audio"]),
                         ("first video piece (headers only)", d["segment"])]
                is_mp4 = urlparse(src).path.lower().endswith(".mp4")
                bad_ref, bad_cors = [], []
                detail("== 4. play: what the app's player (on github.io) gets")
                for name, u in parts:
                    if not u:
                        continue
                    media = is_mp4 or "piece" in name
                    h = headers_for("app", url)
                    if is_mp4:
                        h.pop("Origin")        # a <video src> doesn't need CORS
                    r = http(u, h, method="HEAD" if media else "GET")
                    if media and r.status in (405, 501):   # server doesn't do HEAD
                        r = http(u, {**h, "Range": "bytes=0-0"}, max_bytes=1)
                    ok_status = 200 <= r.status < 300
                    ok_cors = is_mp4 or cors_ok(r)
                    detail(f"    {name}: HTTP {r.status or r.error}  "
                           f"Access-Control-Allow-Origin={r.headers.get('access-control-allow-origin', '(none)')!r}"
                           f"  {u}")
                    if not ok_status:
                        s = http(u, headers_for("site", url), method="HEAD" if media else "GET")
                        detail(f"      with the video site's Referer instead: HTTP {s.status or s.error}")
                        bad_ref.append(name)
                    elif not ok_cors:
                        bad_cors.append(name)
                detail("")
                if bad_ref:
                    step("4. app's player can read it", False, "PLAY_REFERER_REFUSED (" + ", ".join(bad_ref) + ")")
                    fail("PLAY_REFERER_REFUSED")
                elif bad_cors:
                    step("4. app's player can read it", False, "PLAY_NO_CORS (" + ", ".join(bad_cors) + ")")
                    fail("PLAY_NO_CORS")
                else:
                    step("4. app's player can read it", True)

    code = cause[0] or "ALL_PASS"
    report = [
        "WEATHER-TV VIDEO DIAGNOSIS",
        f"title: {a.imdb_id}" + (f" season {a.season} episode {a.episode}" if is_tv else ""),
        "",
        "SUMMARY (no web addresses in this part)",
        *summary,
        "",
        f"CAUSE: {code}",
        "  " + CAUSES[code],
        "",
        "=" * 78,
        "DETAILS (contains web addresses: keep private)",
        "",
        *details,
    ]
    out.write_text("\n".join(report) + "\n")
    print(f"Done. Report written to {out}")


if __name__ == "__main__":
    sys.exit(main())
