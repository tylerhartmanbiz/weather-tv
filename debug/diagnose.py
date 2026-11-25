"""Find out why the TV app doesn't play a video, by repeating what the TV does on this Mac.

    ./helper.sh diagnose tt0993846              a movie
    ./helper.sh diagnose tt0903747 1 2          a TV episode (season 1, episode 2)
    ./helper.sh watch tt0993846                 the same, in a browser window you can watch:
                                                a bar at the top shows each step, a red dot
                                                shows each click, and the video links found

It reads your real settings (docs/config-noclaude.json, falling back to docs/config.json),
opens the Open link in the same kind of locked-down sandbox the TV uses (sandboxed Chromium,
throwaway profile, popups/downloads refused, video bytes never downloaded), and walks the
same four steps the TV takes:

  1. FIND   does the page ask for a video link the TV would notice, in time?
  2. CHECK  does the TV's safety check (Net.inspect) accept that link?
  3. ALLOW  is the stream all https, on public addresses, on few enough hosts?
  4. PLAY   will the stream servers let the app's player (on github.io) read it?

Note: on the TV you now start the video yourself (the site is shown, you use it with the
remote). This tool still clicks by itself to find the link, so a site that detects automated
browsers may refuse it; then test on the TV / emulator instead.

When a step fails, it tries alternatives to find out why (other user-agent, Referer, a
second click, waiting longer, links without .m3u8/.mp4 in their name, players in iframes).

Everything goes to a report file (default debug/output/diagnose-report.txt, git-ignored), never to
the screen (watch mode also saves a screen recording in debug/output/videos/). The SUMMARY
at the top of the report has no web addresses in it: just which step failed and a short
CAUSE code. The DETAILS below it do contain addresses.

Options: --config FILE   use another settings file
         --out FILE      write the report somewhere else
         --ua desktop    pretend to be desktop Chrome instead of the Fire TV
         --watch         show the browser window (what ./helper.sh watch does)
"""
import argparse
import html
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

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import settings  # noqa: E402  (reads the settings exactly like the TV)
APP_ORIGIN = "https://tylerhartmanbiz.github.io"
APP_PAGE = APP_ORIGIN + "/weather-tv/"
# What the TV's hidden browser (Android WebView on a Fire TV) and its Java code look like.
# The TV's website view now presents as plain Chrome (no "; wv" / "Version/4.0").
TV_UA = ("Mozilla/5.0 (Linux; Android 11; AFTKA Build/PS7633) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/130.0.6723.102 Mobile Safari/537.36")
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
PLAY_JS = """() => { try { document.querySelectorAll('video').forEach(v => {
  v.muted = true; const p = v.play(); if (p && p.catch) p.catch(() => {}); }); } catch (x) {} }"""
# --watch: an overlay drawn on top of the page (it ignores the mouse, so clicks still reach the
# page underneath). Lives in a shadow root, so the page's HTML (what the TV scans) doesn't change.
OVERLAY_JS = r"""(a) => {
  let host = document.getElementById('__tv_diagnose');
  if (!host) {
    host = document.createElement('div'); host.id = '__tv_diagnose';
    host.style.cssText = 'position:fixed;inset:0;z-index:2147483647;pointer-events:none';
    (document.body || document.documentElement).appendChild(host);
    host.attachShadow({mode: 'open'}).innerHTML = `<style>
      :host { all: initial; }
      * { box-sizing: border-box; font-family: -apple-system, Helvetica, sans-serif; text-align: left; }
      .bar { position: fixed; left: 0; right: 0; top: 0; padding: 8px 14px; background: rgba(0,0,0,.85);
             color: #fff; font-size: 16px; line-height: 1.35; border-bottom: 3px solid #ffd54a; }
      .bar.ok { border-color: #3ddc84; } .bar.bad { border-color: #ff4d4d; }
      .step { font-weight: 700; font-size: 19px; } .step span { color: #ffd54a; font-weight: 400; }
      .n { color: #ffd54a; font-weight: 700; } .n.yes { color: #3ddc84; } .feed { color: #bbb; font-size: 13px; }
      .links div { color: #7cfc9a; font: 12px ui-monospace, Menlo, monospace; white-space: nowrap;
                   overflow: hidden; text-overflow: ellipsis; }
      .cross { position: fixed; left: 50%; top: 50%; width: 44px; height: 44px; margin: -22px 0 0 -22px;
               border: 2px dashed #ffd54a; border-radius: 50%; }
      .cross::after { content: ''; position: absolute; left: 50%; top: 50%; width: 6px; height: 6px;
                      margin: -3px 0 0 -3px; background: #ffd54a; border-radius: 50%; }
      .dot { position: fixed; left: 50%; top: 50%; width: 40px; height: 40px; margin: -20px 0 0 -20px;
             border-radius: 50%; background: rgba(255,50,50,.55); border: 3px solid #ff3030;
             animation: pop 1.6s ease-out forwards; }
      .tag { position: fixed; left: 50%; top: 50%; margin: 30px 0 0 -40px; width: 80px; text-align: center;
             color: #fff; background: #ff3030; border-radius: 4px; font-weight: 700; font-size: 14px;
             animation: fade 2.5s forwards; }
      @keyframes pop { to { transform: scale(3); opacity: 0; } }
      @keyframes fade { 70% { opacity: 1; } to { opacity: 0; } }
    </style><div class="bar"><div class="step"></div><div class="info"></div><div class="feed"></div>
    <div class="links"></div></div><div class="cross"></div>`;
  }
  const r = host.shadowRoot, bar = r.querySelector('.bar');
  bar.className = 'bar ' + (a.mood || '');
  r.querySelector('.step').textContent = a.step + ' ';
  const t = document.createElement('span'); t.textContent = a.time; r.querySelector('.step').appendChild(t);
  r.querySelector('.info').innerHTML = '';
  const n = document.createElement('span'); n.className = a.links.length ? 'n yes' : 'n';
  n.textContent = a.links.length ? `${a.links.length} video link(s) found` : 'no video link yet';
  r.querySelector('.info').append(n, ` · ${a.popups} popup(s) blocked · ${a.blocked} other-site page(s) blocked`);
  r.querySelector('.feed').textContent = a.feed.join('  ·  ');
  const l = r.querySelector('.links'); l.innerHTML = '';
  a.links.slice(-4).forEach(u => { const e = document.createElement('div'); e.textContent = '▶ ' + u; l.appendChild(e); });
  if (a.click) {
    r.querySelectorAll('.dot,.tag').forEach(e => e.remove());
    const dot = document.createElement('div'); dot.className = 'dot';
    const tag = document.createElement('div'); tag.className = 'tag'; tag.textContent = 'CLICK ' + a.click;
    r.append(dot, tag);
  }
}"""
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
    if who == "tv-check":      # the TV's safety check: browser identity + the site's Referer/Origin
        return {"User-Agent": TV_UA, "Accept": "application/vnd.apple.mpegurl, */*",
                "Accept-Encoding": "identity", "Referer": site_origin + "/", "Origin": site_origin}
    if who == "java":          # what the TV's check used to send (no Referer, Java identity)
        return {"User-Agent": JAVA_UA, "Accept": "application/vnd.apple.mpegurl, */*",
                "Accept-Encoding": "identity"}
    if who == "browser":       # a normal browser, no Referer
        return {"User-Agent": TV_UA, "Accept": "*/*", "Accept-Encoding": "identity"}
    if who == "site":          # the video site's own player
        return {"User-Agent": TV_UA, "Accept": "*/*", "Accept-Encoding": "identity",
                "Referer": page_url, "Origin": site_origin}
    if who == "app":           # the TV app fetches the stream itself for its player, as the site would
        return {"User-Agent": TV_UA, "Accept": "*/*", "Accept-Encoding": "identity",
                "Referer": site_origin + "/", "Origin": site_origin}
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

class _Stop(Exception):
    pass


def visit(url, ua, watch=False, video_dir=None):
    site = site_of(urlparse(url).hostname)
    ev = {"tv_found": [], "media_no_ext": [], "blocked_main": [], "blocked_http": 0, "popups": 0,
          "popups_after_click": 0, "main_status": None, "main_error": "", "title": "", "challenge": False,
          "frames": [], "dom_main": [], "dom_frames": [], "playlist_no_ext": [], "late": [],
          "extra_click": [], "steps": [], "before_clicks": 0, "error": ""}
    t0 = time.time()
    responses = []
    clicked = [False]
    feed = []                 # --watch: recent events, shown on the overlay
    status = ["Opening the page…", ""]

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
                feed.append(f"{now()}s video link noticed (not downloaded)")
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
        if req.resource_type == "document" and main:
            if site_of(p.hostname) != site:
                ev["blocked_main"].append((now(), req.url))
                feed.append(f"{now()}s BLOCKED going to another site")
                return r.abort()
            # Follow server redirects one hop at a time, so a redirect to another site is caught
            # (Playwright would otherwise follow it unseen; the TV checks every hop).
            try:
                resp = r.fetch(max_redirects=0, timeout=20000)
            except Exception:
                return r.abort()
            loc = resp.headers.get("location")
            if 300 <= resp.status < 400 and loc:
                target = urljoin(req.url, loc)
                tp = urlparse(target)
                if tp.scheme != "https" or site_of(tp.hostname) != site:
                    ev["blocked_main"].append((now(), target))
                    feed.append(f"{now()}s BLOCKED redirect to another site")
                    return r.abort()
            return r.fulfill(response=resp)
        return r.continue_()

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=not watch, chromium_sandbox=True,
                                     args=["--disable-extensions", "--disable-background-networking",
                                           "--autoplay-policy=no-user-gesture-required"])
        rec = {"record_video_dir": str(video_dir), "record_video_size": {"width": 1280, "height": 720}} \
            if watch and video_dir else {}
        ctx = browser.new_context(accept_downloads=False, permissions=[], java_script_enabled=True,
                                  service_workers="block", viewport={"width": 1280, "height": 720},
                                  user_agent=ua, **rec)
        ctx.route("**/*", route)
        page = ctx.new_page()

        def on_page(p):
            if p is not page:
                ev["popups"] += 1
                feed.append(f"{now()}s BLOCKED popup / new tab")
                if clicked[0]:
                    ev["popups_after_click"] += 1
                p.close()
        ctx.on("page", on_page)
        ctx.on("response", lambda resp: responses.append((now(), resp)))
        page.on("download", lambda d: d.cancel())

        def overlay(click_n=0, mood=""):
            if not watch:
                return
            try:
                page.evaluate(OVERLAY_JS, {
                    "step": status[0], "time": f"{now()} s" + (f" · {status[1]}" if status[1] else ""),
                    "links": [u for _, u in ev["tv_found"]] + [u for u in ev["dom_main"]
                                                             if u not in [x for _, x in ev["tv_found"]]],
                    "popups": ev["popups"], "blocked": len(ev["blocked_main"]), "feed": feed[-3:],
                    "click": click_n, "mood": mood or ("ok" if found() else "")})
            except Exception:
                pass   # page was navigating; drawn again on the next tick

        def say(text):
            status[0], status[1] = text, ""
            overlay()

        def wait(seconds, until=lambda: False):
            end = time.time() + seconds
            last = 0
            while time.time() < end and not until():
                page.wait_for_timeout(250)
                if watch and time.time() - last > 0.5:
                    last = time.time()
                    status[1] = f"{max(0, end - time.time()):.0f} s left" if seconds >= 2 else ""
                    overlay()
            status[1] = ""
            return bool(until())

        def click(n):
            clicked[0] = True
            ev["steps"].append(f"{now():5}s click {n} in the middle of the screen")
            status[0] = f"Click {n} in the middle of the screen" + ("  (extra test, the TV doesn't do this)" if n > 2 else "")
            overlay(click_n=n)
            try:
                page.mouse.click(640, 360)
                page.evaluate(PLAY_JS)
            except Exception:
                pass

        def found():
            return bool(ev["tv_found"] or ev["dom_main"])

        def look(when):
            # Same as the TV's lookFor: links the page asked for + links in the main page's HTML.
            try:
                for u in page.main_frame.evaluate(SCAN_JS):
                    if u not in ev["dom_main"]:
                        ev["dom_main"].append(u)
            except Exception:
                pass
            n = len({u for _, u in ev["tv_found"]} | set(ev["dom_main"]))
            ev["steps"].append(f"{now():5}s looked for the video {when}: " + (f"{n} link(s)" if n else "none yet"))
            say(f"Looked for the video {when}: " + (f"FOUND {n} link(s)" if n else "none yet"))
            return found()

        try:
            opened_at = time.time()
            try:
                first = page.goto(url, wait_until="commit", timeout=20000)
                ev["main_status"] = first.status if first else None
            except Exception as e:
                ev["main_error"] = str(e).splitlines()[0][:200]
            # Wait for the page to load (at least 10 s), click the middle, wait 5 s, click the
            # middle again, wait 10 s. Look after every step; stop as soon as a video is found.
            # (The TV no longer clicks by itself: there YOU use the site with the remote.)
            say("Waiting for the page to load (max 10 s)…")
            try:
                page.wait_for_load_state("load", timeout=10000)
            except Exception:
                pass
            # Did the video site's page actually load? (A blocked redirect or an error leaves a
            # blank or error page, with nothing to click.)
            try:
                st = page.evaluate("() => ({t: ((document.body && document.body.innerText) || '').trim().length,"
                                   " h: document.documentElement.outerHTML.length})")
            except Exception:
                st = None
            final = urlparse(page.url)
            ev["final_on_site"] = final.scheme == "https" and site_of(final.hostname) == site
            ev["text_len"], ev["html_len"] = (st["t"], st["h"]) if st else (0, 0)
            if not ev["final_on_site"] or st is None:
                why = []
                if ev["blocked_main"]:
                    why.append("The site sent the browser to a DIFFERENT website, which is blocked (the TV "
                               "blocks it too). Where it wanted to go:")
                    why += [f"    {u}" for _, u in ev["blocked_main"]]
                    why.append("If that's just the site's new address, put that address in "
                               "config-noclaude.json instead.")
                if ev["main_error"]:
                    why.append(f"Browser error: {ev['main_error']}")
                if not why:
                    why.append(f"The browser ended up on: {page.url}")
                ev["steps"].append(f"{now():5}s PAGE DID NOT LOAD: nothing to click")
                if watch:
                    page.set_content(
                        "<body style='font:18px -apple-system,sans-serif;background:#1b1b1b;color:#eee;padding:30px'>"
                        "<h2 style='color:#ff6b6b'>The video site's page didn't load</h2><pre style='white-space:"
                        "pre-wrap;font:15px Menlo,monospace;color:#ffd54a'>" + html.escape("\n".join(why)) + "</pre>"
                        "<p>The tool stops here (the TV would fail here too). Press Enter in the terminal.</p></body>")
                    input("\nThe page didn't load: the browser window says why. Press Enter to finish the report... ")
                raise _Stop()
            # Give the page at least 10 s (from opening it) to load before the first click.
            left = 10 - (time.time() - opened_at)
            if left > 0:
                say("Letting the page load: first click after 10 s…")
                wait(left, found)
            if ev["text_len"] < 20 and not page.frames[1:]:
                feed.append(f"{now()}s the page looks EMPTY")
            if not look("after loading"):
                ev["before_clicks"] = 0
                click(1)
                if not look("after click 1") and not (say("Waiting 5 s after click 1…") or wait(5, found)
                                                       or look("5 s after click 1")):
                    click(2)
                    if not look("after click 2") and not (say("Waiting 10 s after click 2…") or wait(10, found)
                                                           or look("10 s after click 2")):
                        ev["steps"].append(f"{now():5}s NO VIDEO FOUND after 2 clicks")
                        say("NO VIDEO FOUND after 2 clicks")
                        overlay(mood="bad")
                        wait(3 if watch else 0)
            else:
                ev["before_clicks"] = len(ev["tv_found"]) + len(ev["dom_main"])
            if found():
                wait(1.5)
            tv_window = now()
            for f in page.frames:
                if f == page.main_frame:
                    continue
                ev["frames"].append(f.url)
                try:
                    ev["dom_frames"] += f.evaluate(SCAN_JS)
                except Exception:
                    pass
            try:
                ev["title"] = page.title()
                ev["challenge"] = bool(CHALLENGE.search(ev["title"] + " " + page.content()[:200000]))
            except Exception:
                pass
            # Beyond what the TV does: if nothing yet, click a third time, then wait longer.
            if not found():
                click(3)
                status[0] = "Extra test (not on the TV): waiting 20 s more after a 3rd click…"
                wait(10, lambda: ev["tv_found"])
                ev["extra_click"] = list(ev["tv_found"])
                wait(10, lambda: ev["tv_found"])
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
            if watch:
                n = len({u for _, u in ev["tv_found"]} | set(ev["dom_main"]))
                say(f"Done: {n} video link(s) found. Next the stream is checked; see the report."
                    if n else "Done: no video link found. See the report.")
                overlay(mood="ok" if n else "bad")
                input("\nWatching done. Look around the browser window if you like, then press Enter here to "
                      "close it and finish the report... ")
        except _Stop:
            ev["tv_window"] = now()
        except Exception as e:
            ev["error"] = f"{type(e).__name__}: {str(e).splitlines()[0][:200]}"
        finally:
            video = page.video if watch and video_dir else None
            ctx.close()
            if video:
                try:
                    ev["video_file"] = video.path()
                except Exception:
                    pass
            browser.close()
    return ev


# ---------------------------------------------------------------- the report

CAUSES = {
    "CONFIG_BROKEN": "Your settings file isn't valid JSON (even allowing comments and trailing commas). "
                     "The TV shows an error for this too. Check for a missing comma or quote.",
    "CONFIG_MISSING": "There is no openUrl / openUrlTv in your settings file for this kind of title.",
    "CONFIG_BAD_URL": "The Open link in your settings isn't a valid https address the TV accepts "
                      "(https only, normal hostname, no port, no user:password).",
    "PAGE_UNREACHABLE": "The video page didn't load at all (no answer, bad address, or the site is down).",
    "PAGE_EMPTY": "The page loaded but is empty (no text, no embedded player). Some sites show nothing to "
                  "the Fire TV's browser: try again with --ua desktop to compare.",
    "PAGE_HTTP_ERROR": "The video page answered with an error code (see DETAILS, step 1).",
    "PAGE_BOT_CHECK": "The site showed a bot check / captcha / 'access denied' page to this automated "
                      "browser. On the TV you use the site yourself, so try it there "
                      "(./emulator.sh local, or the Fire TV).",
    "REDIRECT_OTHER_SITE": "The site sends the browser to a different website, which the TV blocks. "
                           "If that's the site's new address, use it in config-noclaude.json (the "
                           "address is in DETAILS, step 1, or shown in the watch window).",
    "VIDEO_NEEDS_MORE_CLICKS": "The video only started after a third click (the first ones probably "
                               "hit ads / overlays). The TV clicks twice.",
    "VIDEO_TOO_SLOW": "The page did ask for the video, but only after the TV had already given up "
                      "(10 s after its second click).",
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
                "emulator. Run ./emulator.sh logs debug/output/tv-log.txt while pressing Open on the "
                "emulator.",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("imdb_id")
    ap.add_argument("season", nargs="?")
    ap.add_argument("episode", nargs="?")
    ap.add_argument("--config")
    ap.add_argument("--out", default=str(ROOT / "debug" / "output" / "diagnose-report.txt"))
    ap.add_argument("--ua", choices=["tv", "desktop"], default="tv")
    ap.add_argument("--watch", action="store_true", help="show the browser window, with an overlay of each step")
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
        cfg = settings.load(cfg_path)
    except Exception as e:
        cfg = {}
        step("0. settings", False, str(e))
        fail("CONFIG_BROKEN")
        detail(f"Could not read {cfg_path.name}: {e}")
    tmpl = cfg.get("openUrlTv" if is_tv else "openUrl", "")
    url = tmpl.replace("{imdb_id}", a.imdb_id).replace("{season}", a.season or "").replace("{episode}", a.episode or "")
    p = urlparse(url)
    if cause[0]:
        pass
    elif not tmpl:
        step("0. settings", False, f"{cfg_path.name} has no {'openUrlTv' if is_tv else 'openUrl'}")
        fail("CONFIG_MISSING")
    elif p.scheme != "https" or not p.hostname or p.port not in (None, 443) or p.username:
        step("0. settings", False, "Open link isn't an address the TV accepts")
        fail("CONFIG_BAD_URL")
    else:
        step("0. settings", True, f"using {cfg_path.name}, {'TV episode' if is_tv else 'movie'} link")
    detail("== 0. settings", f"file: {cfg_path}", f"Open link: {url or '(none)'}", "")

    ev = {}
    if not cause[0]:
        ua = TV_UA if a.ua == "tv" else DESKTOP_UA
        if a.watch:
            print("Opening a browser window: watch the yellow bar at the top for each step.")
        ev = visit(url, ua, watch=a.watch, video_dir=out.parent / "videos")
        tv_found = [(t, u) for t, u in ev["tv_found"] if t <= ev.get("tv_window", 0)]

        # ---- 1. page
        detail("== 1. page (sandboxed browser, " + ("Fire TV" if a.ua == "tv" else "desktop") + " user-agent)",
               f"first answer: HTTP {ev['main_status']}" + (f"  error: {ev['main_error']}" if ev["main_error"] else ""),
               f"title: {ev['title']!r}",
               f"bot-check / captcha words on page: {ev['challenge']}",
               f"ended up on the video site: {ev.get('final_on_site')}   page text: {ev.get('text_len')} chars"
               f"   page HTML: {ev.get('html_len')} chars",
               f"popups refused: {ev['popups']} ({ev['popups_after_click']} after clicking)",
               "what happened, step by step:", *[f"    {x}" for x in ev["steps"]],
               f"plain-http requests blocked: {ev['blocked_http']}",
               f"embedded frames ({len(ev['frames'])}):", *[f"    {f}" for f in ev["frames"]],
               "main page tried to go to another site (blocked, like the TV: it stays on the video site):",
               *[f"    {t:5}s {u}" for t, u in ev["blocked_main"]] or ["    none"],
               *([f"script error: {ev['error']}"] if ev["error"] else []), "")
        if ev["blocked_main"] and not ev.get("final_on_site", True):
            step("1. page loads", False, "the site sent the browser to a different website (blocked)")
            fail("REDIRECT_OTHER_SITE")
        elif ev["main_error"] and ev["main_status"] is None:
            step("1. page loads", False, "no answer from the site")
            fail("PAGE_UNREACHABLE")
        elif not ev.get("final_on_site", True):
            step("1. page loads", False, "the browser didn't end up on the video site")
            fail("PAGE_UNREACHABLE")
        elif ev["main_status"] and ev["main_status"] >= 400 and not ev["tv_found"]:
            step("1. page loads", False, f"HTTP {ev['main_status']}")
            fail("PAGE_BOT_CHECK" if ev["challenge"] else "PAGE_HTTP_ERROR")
        elif ev["challenge"] and not ev["tv_found"]:
            step("1. page loads", False, "looks like a bot check / captcha page")
            fail("PAGE_BOT_CHECK")
        elif ev.get("text_len", 1) < 20 and not ev["frames"] and not ev["tv_found"] and not ev["dom_main"]:
            step("1. page loads", False, f"HTTP {ev['main_status']}, but the page is empty")
            fail("PAGE_EMPTY")
        else:
            step("1. page loads", True, f"HTTP {ev['main_status']}, {len(ev['frames'])} embedded frame(s), "
                                        f"{ev['popups']} popup(s) refused")

        # ---- 2. find
        tv_links = [u for _, u in tv_found]
        tv_links += [u for u in ev["dom_main"] if u not in [x for _, x in tv_found]]
        detail("== 2. find: video links the TV would notice (10 s to load, 2 clicks, 5 s + 10 s)",
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
                when = next((x.split("looked for the video ")[1].split(":")[0] for x in ev["steps"]
                             if "looked for the video" in x and "none yet" not in x), "")
                step("2. TV finds a video link", True, f"{len(tv_links)} link(s), found {when}")
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
        cands = sorted(dict.fromkeys(tv_links), key=lambda u: ".m3u8" not in u.lower())[:8]
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
                    ok_cors = True   # the app fetches the stream itself and adds the CORS header
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
    print("\n".join(["", "SUMMARY", *summary, "", f"CAUSE: {code}", "  " + CAUSES[code], ""]))  # no addresses
    print(f"Done. Full report: {out}")
    if ev.get("video_file"):
        print(f"Screen recording of what the browser did: {ev['video_file']}")


if __name__ == "__main__":
    sys.exit(main())
