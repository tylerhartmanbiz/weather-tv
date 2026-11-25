"""A locked-down Chromium for visiting untrusted sites (used by record.py and scrapper.search).

Protections:
  * Chromium's own OS sandbox is ON (Playwright turns it off by default; we turn it back on).
  * Brand-new throwaway profile every time: none of your cookies, logins or history.
  * Downloads are refused. Popups / new tabs are closed the instant they open.
  * The main page may only go to the starting site; frames/pages from other sites are
    blocked (ads, redirects to other websites, etc.).
  * Video bytes are never downloaded: the video URL is noted and the request is cancelled.
  * Permissions (camera, mic, location, notifications) are all denied.
"""
import re
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright

PLAYLIST_EXT = (".m3u8", ".mp4", ".webm", ".mov")
SEGMENT_EXT = (".ts", ".m4s", ".m4a", ".m4v", ".aac", ".cmfv", ".cmfa", ".mp3", ".vtt")


def site_of(host):
    return ".".join((host or "").lower().split(".")[-2:])


def is_video_url(url):
    p = urlparse(url)
    return p.scheme == "https" and p.path.lower().endswith(PLAYLIST_EXT)


class SafeBrowser:
    def __init__(self, start_url, headless=True, block_images=True, on_video=None, on_block=None):
        self.site = site_of(urlparse(start_url).hostname)
        self.headless = headless
        self.block_images = block_images
        self.on_video = on_video or (lambda url: None)
        self.on_block = on_block or (lambda what, url: None)
        self.videos = []  # video URLs seen, in order

    def __enter__(self):
        self._pw = sync_playwright().start()
        self.browser = self._pw.chromium.launch(
            headless=self.headless, chromium_sandbox=True,
            args=["--disable-extensions", "--disable-background-networking",
                  "--autoplay-policy=no-user-gesture-required"])
        self.context = self.browser.new_context(
            accept_downloads=False, permissions=[], java_script_enabled=True,
            service_workers="block", viewport={"width": 1280, "height": 800})
        self.context.route("**/*", self._route)
        self.context.on("page", self._on_page)
        self.page = self.context.new_page()
        self._main = self.page
        self.page.on("download", lambda d: d.cancel())
        return self

    def __exit__(self, *exc):
        try:
            self.context.close()
            self.browser.close()
        finally:
            self._pw.stop()

    # --- protections ---
    def _on_page(self, page):
        if page is not getattr(self, "_main", None) and getattr(self, "_main", None) is not None:
            self.on_block("popup", page.url)
            page.close()

    def _route(self, route):
        req = route.request
        url = req.url
        p = urlparse(url)
        path = p.path.lower()
        if p.scheme not in ("https", "http", "data", "blob"):
            return route.abort()
        if is_video_url(url) or req.resource_type == "media":
            if url not in self.videos and p.scheme == "https":
                self.videos.append(url)
                self.on_video(url)
            return route.abort()  # never download the video itself
        if path.endswith(SEGMENT_EXT):
            return route.abort()
        if req.resource_type == "document" and site_of(p.hostname) != self.site:
            self.on_block("other-site page", url)
            return route.abort()
        if self.block_images and req.resource_type in ("image", "font"):
            return route.abort()
        return route.continue_()

    # --- helpers ---
    def page_info(self):
        """Title/description/thumbnail of the current page (from meta tags)."""
        return self.page.evaluate("""() => {
          const m = n => (document.querySelector(`meta[property="${n}"],meta[name="${n}"]`) || {}).content || '';
          return {title: m('og:title') || document.title || '', description: m('og:description') || m('description'),
                  thumbnail: m('og:image')};
        }""")

    def dom_videos(self):
        urls = self.page.evaluate("""() => [...document.querySelectorAll('video, video source')]
            .map(v => v.currentSrc || v.src || '').filter(u => u.startsWith('https:'))""")
        return [u for u in urls if re.match(r"https://", u)]
