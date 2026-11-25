# Weather TV (Fire TV)

Plays the first video from weather.com on Fire TV, with ◀ ▶ to browse the rest.

## Install (once)

On the Fire TV, open the **Downloader** app and enter:

```
github.com/tylerhartmanbiz/weather-tv/releases/latest/download/weather-tv.apk
```

## How updates work (no reinstall)

The APK is a thin shell that opens `docs/index.html` from GitHub Pages
(https://tylerhartmanbiz.github.io/weather-tv/). Edit `docs/index.html`, push, and the TV
shows the change on next launch (or press **Menu ☰** on the remote to reload). Pages can take
~1–10 minutes to update.

Video info lives in `docs/videos.json`. To refresh it run `./run_scraper.sh --push` (sandboxed,
see below). weather.com blocks GitHub's cloud servers, so the cloud **Scrape videos** action is manual-only.
You only need a new APK if something under `app/` changes (the Build APK action builds it).

## Safety

weather.com is treated as untrusted:

- **The scraper is minimal.** `scrapper.py` uses
  only the Python standard library, reads the page as text in memory, never executes or saves
  anything from the site, caps the size, and keeps only `https` `.m3u8` links on allowlisted hosts.
- **It runs in a sandbox.** `./run_scraper.sh` is a macOS sandbox where it can write only
  `docs/videos.json` and cannot launch other programs.
- **The TV never opens weather.com.** It streams the video (not downloaded to storage). The
  WebView only connects to 4 https hosts (the app page, the pinned + integrity-checked player
  library, the video host, the image host); everything else is refused. Navigation, popups and
  downloads are blocked, there is no file access, and the only permission is INTERNET.
