# Notes for Claude

## Updates must be live whenever possible (the owner's standing rule)
The owner does not want to reinstall the APK. Put every change that *can* be live in `docs/`
(served by GitHub Pages and read by the TV at launch / Menu ☰):
`docs/index.html` (all screens), `docs/site-control.js` (remote on the video website),
`docs/site-nav.js` (script put into the website), `docs/browse.json` (home rows, built by
`browse.py` + `.github/workflows/browse.yml`), `docs/config-noclaude.json`.
`docs/videos.json` `allowedHosts` is a live list of extra hosts the app's page may reach
(currently Wikidata / Wikipedia / Wikimedia images for movie details and cast photos).
The app also takes live settings from `site-control.js` (`op=settings`: timeoutMin, sniff).
Only touch `app/` when something truly cannot be done from `docs/`, say so up front, and
prefer adding a small generic capability to the app so later changes can be live again.

## Workflow
- Push finished changes straight to `main` (no PR, don't ask). `docs/` is live as soon as Pages deploys.
- Never open `docs/config-noclaude.json`.
