# Testing websites: "works in Chrome, not in the TV app"

Each method changes **one** thing about how the app shows the video website (or about the
virtual TV), so we can find what breaks it. Run one with:

```
./emulator.sh -3        # method 3
./emulator.sh methods   # the list
```

It builds the app from this folder (only when the code changed), sets up the virtual TV for
that method (restarting it if the method needs different emulator options), clears the app's
data, and opens the app with that method on. Then: search a title, press **Open**, and try to
start the video on the website. Ctrl+C in the terminal when done.

- What the app did is saved to `testing websites/logs/method-N.txt` (git-ignored). The video
  site's domain is replaced by `<site>` in that file, so it can be shared.
- To see the website's own errors and network requests, open `chrome://inspect` in Chrome on
  the Mac while a method runs and click **inspect** under the emulator.
- A normal launch (the Fire TV, `./emulator.sh`, `./emulator.sh local`) uses no method: the
  app behaves exactly as before. The methods only switch on through `./emulator.sh -N`.

## Start here

1. **-0** baseline, so we know what "broken" looks like in the log.
2. **-1** bare WebView. If this one works, the cause is one of the app's safety rules
   (then try 2, 3, 4, 7, 10, 11, 13, 14 to find which). If even this fails, the cause is the
   emulator or the website's view of it (try 5, 6, 26, 27, 28, 8, 9, 21).
3. **-34** everything at once. If it works, halve the list until we know which part matters.

## The methods

| # | What it changes | Why it might be the problem | Result |
|---|---|---|---|
| 0 | Nothing (today's app) | Baseline for comparison | |
| 1 | Bare WebView: no sandbox rules at all (no blocking, popups as normal, real identity, cache, service workers, dialogs, DRM) | Tells whether the app's rules or the device is the cause | |
| 2 | Safe Browsing off | Google Safe Browsing often flags video sites; the app's browser then refuses the page or its player, while your Chrome may not | |
| 3 | Follow the site to other domains | Sites move between mirror domains (`.to`, `.cc`, ...) or redirect to a player domain; the app blocks and "sends back" any page on another domain | |
| 4 | Same-site popups / new tabs open in place | A Play button that opens a new tab (`target=_blank`/`window.open`) gets an empty window in the app, so nothing happens | |
| 5 | Emulator: encrypted DNS (dns.google) | Chrome uses Secure DNS; your internet provider's DNS may block or misdirect the site, and the virtual TV uses that DNS | |
| 6 | Emulator: DNS servers 1.1.1.1 + 8.8.8.8 | Same idea, plain DNS without your provider | |
| 7 | Real WebView browser identity | The app claims to be Chrome but the engine still says "Android WebView" in other places; that mismatch looks like a bot to Cloudflare-type checks | |
| 8 | Desktop Chrome mode (desktop identity + desktop-width page) | Your Chrome is desktop; the site may serve TVs/phones a different, broken page | |
| 9 | Phone Chrome identity | The TV's identity has no "Mobile"; some sites have no page for "Android TV" | |
| 10 | Let the website load its own video | The app answers the video request with "blocked", and some players then show an error or give up before asking for the real link | |
| 11 | Find hidden video links (no `.m3u8` in the name; playlists read by the page's scripts) | The diagnose run found the page never requested a `.m3u8`/`.mp4`; the stream may have a disguised name | |
| 12 | Fetch the stream with the website's own headers + cookies | The page works but the app's player can't load the stream (stream server checks Referer/cookies) | |
| 13 | Loosen the network rules (no DNS checks, http, mixed content) | A VPN/proxy on the Mac (fake 198.18.x addresses) or http parts make the app refuse the site | |
| 14 | Allow service workers | Some sites and players need one to load | |
| 15 | Normal browser cache | The app turns the cache off; some challenge pages loop without it | |
| 16 | Answer OK to pop-up dialogs | "Are you 18?" / "Continue?" dialogs are silently cancelled in the app | |
| 17 | Allow protected media (DRM) | Players that check for Widevine stop when it's refused | |
| 18 | Mouse clicks + hover instead of touch | Some players show their Play button only on hover, or ignore touch | |
| 19 | Give the page focus | The app's browser never has focus, so the page thinks it's in the background | |
| 20 | Hide the help text after 6 s | The help bar covers the bottom middle of the screen (a Play or "Accept cookies" button) | |
| 21 | Stealth: hide app-browser signs (`window.chrome`, brands, plugins) | Bot checks spot an app's built-in browser | |
| 22 | Arrive from Google (Referer) | Some sites only work when you arrive from a search | |
| 23 | Open the homepage first, then the video page | Some sites set a cookie or pass a check on the homepage first | |
| 24 | Built-in ad blocker | Your Chrome may have an ad blocker; ads/overlays can cover or hijack the player | |
| 25 | Draw the page without the GPU | Rendering problems in the app's browser | |
| 26 | Emulator: GPU on (host) | The virtual TV runs with its GPU off; the log shows "tile memory limits exceeded, some content may not draw" | |
| 27 | Emulator: GPU SwiftShader | Same, with another graphics mode | |
| 28 | Emulator: 4 GB memory | Heavy sites crash or don't draw with 2 GB | |
| 29 | Emulator: factory reset of the virtual TV | Something stuck in the virtual TV itself | |
| 30 | All app fixes together (2, 3, 4, 10–17, 19–21, 24) | Several causes at once | |
| 31 | 30 + desktop mode + mouse | | |
| 32 | 30 + real WebView identity | | |
| 33 | Bare WebView + encrypted DNS + GPU + 4 GB | | |
| 34 | Everything: 30 + encrypted DNS + GPU + 4 GB | | |

Note: methods 1, 3, 10, 13 and 30–34 turn safety rules off. They're for finding the cause on
the virtual TV only. Once we know which one works, the real fix will be made as narrow and
safe as possible.
