# Video TV (Fire TV)

Fire TV app that shows videos scraped from a website you choose, with search: press **▲** on
the remote, type a word, and it shows the matching video.

## Install (once)

On the Fire TV, open **Downloader** and enter:

```
github.com/tylerhartmanbiz/weather-tv/releases/latest/download/weather-tv.apk
```

## Set up a new website (record once, reuse forever)

```
./helper.sh setup     # first time only (installs an isolated browser into .venv/)
./helper.sh record    # records your search, writes scrapper.py for you
./helper.sh serve     # keep running: the TV sends its searches here
```

1. **Record:** `./helper.sh record` asks for the website and an example word (e.g. `tornado`).
   A locked-down browser opens: search for that word the normal way and open the video you
   want. When the terminal says *video detected*, press Enter there. Your clicks and typing are
   saved into the `RECIPE` section of `scrapper.py` (the example word becomes `{query}`), and
   it immediately re-runs the search by itself to check it works.
2. **Test any word:** `./helper.sh search hurricane`
3. **Use it on the TV:** `./helper.sh serve` (same Wi-Fi as the TV). It publishes this
   computer's home-network address to `docs/config.json` so the app can find it. On the TV,
   press ▲, type, press Enter.

Optional default video list (what plays when the app opens): set `SOURCE` in `scrapper.py`,
then `./helper.sh scrape`.

Changing websites = edit `SOURCE` in `scrapper.py` and run `./helper.sh record` again.
Nothing else, and no APK reinstall.

## Remote

▲ search · ◀ ▶ previous/next video · OK pause/play · Back closes search / exits · Menu ☰ reload

## How updates work (no reinstall)

The APK is a thin shell around `docs/index.html` on GitHub Pages. Push a change and the TV
shows it on next launch (or Menu ☰). Only changes under `app/` need a new APK (built by the
*Build APK* GitHub Action).

## Safety

Every website is treated as untrusted.

- **Your computer:** searches run in `safe_browser.py`, a Chromium with its OS sandbox ON, a
  brand-new empty profile each time (none of your logins/cookies), downloads refused, popups
  closed instantly, pages from other websites blocked, permissions (camera/mic/location)
  denied, and video bytes never downloaded (only the link is kept). The plain default-list
  scraper (`./helper.sh scrape`) runs in a macOS sandbox that can only write `docs/videos.json`.
- **The TV never opens the website.** It only streams the video link. The app connects over
  https to just: this app page, the pinned + integrity-checked player library, and the video
  hosts the scraper approved. Everything else is refused. Popups/new windows are refused,
  dialogs suppressed, navigation to other sites or apps blocked, downloads ignored, no file
  access, only the INTERNET permission. Searches are forwarded only to a private home-network
  address (192.168.x / 10.x / 172.16–31.x).
- **Limits:** works with sites whose videos are direct `.m3u8`/`.mp4` streams. Sites that need
  a login, or only embed YouTube-style players, won't play on the TV. Anyone on your Wi-Fi
  could also send searches to the server while it's running.
