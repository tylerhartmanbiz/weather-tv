# Video TV (Fire TV)

Fire TV app: search movies and TV shows, pick one, pick a season/episode, press **▶ Open**,
and it plays the video from your video website. **Everything runs on the TV.** No computer
needed.

## Install (once)

On the Fire TV, open **Downloader** and enter:

```
github.com/tylerhartmanbiz/weather-tv/releases/latest/download/weather-tv.apk
```

(If you installed an older version that needed your computer, install this once more. After
that, updates arrive by themselves; see below.)

## Set the video website

Put your settings in `docs/config-noclaude.json`, using the same format as the example
`docs/config.json`, and push. The app reads `config-noclaude.json` first and only falls back to
`config.json` if it's missing. (Claude is told never to open `config-noclaude.json`.) Note that
everything in `docs/` is public on GitHub Pages, because that's where the TV downloads it from.

```json
{
  "openUrl":   "https://test.com/{imdb_id}",
  "openUrlTv": "https://test.com/{imdb_id}_{season}_{episode}"
}
```

`openUrl` is for movies, `openUrlTv` for TV episodes. Comments (`//`) and trailing commas are
allowed. If the file has a mistake the TV shows an error (it never quietly uses the example).

`{imdb_id}` becomes e.g. `tt0993846`. TV episodes become e.g. `tt0903747_1_2`.

## Using it

1. The home screen (opens on launch, or press **▲**): **Continue watching** (your ★ shows, with the
   episode that's up next), **★ Favorites**, and **Browse**: Trending, Christmas, Comedy, Action,
   Crime, Thrillers, Horror, Sci-Fi, Romance, Animation & Family. Move over a genre to see it,
   ▼ to its posters. Or type a title: IMDb results appear as you type.
   The genre rows are rebuilt daily by `browse.py` (GitHub Action *Home screen rows*) from
   Wikipedia's most-read pages (Trending), Wikidata (genres) and IMDb's public search (posters).
   Edit the rows at the top of `browse.py`.
   A title's page: description, cast with photos (◀ ▶ to scroll), director, genres, runtime,
   each episode's description / air date / length, and **More like this** (OK opens it, Back
   returns). TV details come from TVmaze; movie details from Wikidata + Wikipedia (the app may
   reach those hosts because `docs/videos.json` lists them).
   TV shows remember the episodes you opened: ✓ Watched in the episode picker, and the show
   opens on the next episode (▶ Up next).
2. Pick one: poster, rating, description, cast. TV shows get **Season / Episode** pickers
   (◀ ▶ to change, with episode names).
3. **▶ Open**: the TV shows the video website (in a sandbox, see Safety). Use it like a
   browser: **◀ ▲ ▼ ▶** move the round cursor (hold to go faster; push past the top or bottom
   edge to scroll), **OK** clicks, **Back** cancels. To drag (e.g. a slider puzzle): hold **OK**
   ~1 s (or press Play/Pause) to grab, the cursor turns blue, arrows drag, **OK** lets go.
   As soon as the page appears, the TV clicks the middle once for you and then starts *holding*
   there, so the arrows drag right away; press **OK** to let go. As soon as you
   start the video there, the website closes and the app loads 30 seconds of the
   highest-quality stream with a **% counter** before it starts. It streams; nothing is saved.

On the website: ⏪ ⏩ rewind / fast-forward (held = faster); in button mode ◀ ▶ on the timeline do the
same; Menu ☰ removes whatever lies on top of the video (press again to put it back).

Remote: ▲ search · ▼▲ move · ◀ ▶ change season/episode (or skip 10 s while watching) ·
OK open / pause · Back goes back a screen · Menu ☰ reload.

Show details and episode lists come from TVmaze (free public API, looked up by IMDb ID).
IMDb's details service refuses apps, so movies show the poster, year and stars from IMDb
search.

## How updates work (no reinstall)

The APK is a thin shell. Everything you'd want to change is in `docs/` on GitHub Pages and is
**live**: push it and the TV uses it on its next launch, or press **Menu ☰**. No reinstall.

| File | What it controls |
|---|---|
| `docs/index.html` | the app's screens: search, details, player |
| `docs/site-control.js` | how the remote drives the video website: auto-click, hold mode, drag, cursor speed, button / cursor mode (OK ×3 switches; opens in button mode), help bar (settings at the top of the file) |
| `docs/site-nav.js` | runs inside the video website (every page and embedded frame): removes overlays, and in button mode moves between the site's own buttons |
| `docs/config-noclaude.json` | the video website's address |

Only changes under `app/` need a new APK (built by the *Build APK* GitHub Action): the
sandbox's safety rules, and the basic actions `site-control.js` can ask for (move the cursor,
tap, finger down/up, scroll, text bar, close, press a key on the page). Hold **Back** for 3 seconds to close the website
even if `site-control.js` is broken.

## Safety

The video website is treated as hostile.

- **It never runs in the app's own page.** When you press Open, the TV shows the site in a
  separate browser (Android's sandboxed WebView renderer) that has no access to the
  app, your files, camera, mic or location (all permission requests are denied, and the app
  only has the INTERNET permission).
- In that browser: downloads are ignored, popups and new windows get an empty window that
  loads nothing,
  alert/confirm/prompt are suppressed, the page can't leave the site or open other apps or
  app stores (if it ends up on another site anyway, it is stopped and sent back), service
  workers are blocked, and only https to public internet addresses is
  allowed. Nothing on your home network (router, computers, phones) can be reached. Video
  players embedded from another site may load, inside the same sandbox.
- The website never downloads the video: video and playlist requests are noted and
  cancelled, and media pieces are cancelled too. Then its cookies, storage and cache are
  wiped and it is destroyed. If the page crashes the browser, the app just rebuilds it.
- The found stream is checked before playback: only its small text playlists are read, in
  memory, to list every host it uses. Every one must be https on a normal public hostname
  that resolves to a public address.
- The video site sees a normal Chrome browser. Its cookies (including those of players
  embedded from other sites) are wiped when it closes. The app fetches the checked stream
  itself, the way the video site's player would, and hands it to its player.
- The app you see may only connect to: this page, the pinned + integrity-checked player
  library, the IMDb/TVmaze image hosts, IMDb/TVmaze data (read as text), and exactly the
  stream hosts that check approved. Everything else is refused. No http at all.
- **Limits:** works with sites whose player uses a direct `.m3u8`/`.mp4` stream. Sites that
  need a login, or DRM-protected streams, won't play. The sandbox is strong, but no sandbox
  is perfect: keep the Fire TV's software updated (Settings → My Fire TV → About → Check for
  updates) so the built-in browser engine has the latest security fixes.

## Optional computer tools

Not needed for the TV. `./helper.sh open tt0993846 [season episode]` tests a site's Open link
from a Mac (sandboxed Chromium). `./helper.sh diagnose tt0993846 [season episode]` finds out
why a title won't play on the TV and writes a report to `debug/output/` (git-ignored).
`./helper.sh watch tt0993846 [season episode]` does the same in a browser window you can watch
(each step, each click, and every video link found), and saves a screen recording there too.
`./emulator.sh -N` tests fix method N on the virtual TV for a website that works in Chrome
but not in the app (see `testing websites/README.md`; `./emulator.sh methods` lists them).
`server.py` and `imdb.py` are leftovers from the old
computer-based version and can be deleted.
