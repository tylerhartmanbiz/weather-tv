#!/usr/bin/env python3
"""Record how to search a website for a video, and turn it into scraper code.

    ./helper.sh record

1. A locked-down browser opens (see safe_browser.py).
2. You do a normal search on the site: click the search box, type the example word,
   press Enter, click the result you want.
3. When a video is detected, the terminal tells you. Press Enter in the terminal to finish.
4. Your clicks/typing are written into the RECIPE section of scrapper.py, with your example
   word replaced by {query}, so the TV can repeat the same search with any word.
"""
import pprint
import re
import select
import sys
import time
from pathlib import Path
from urllib.parse import quote, quote_plus, urlparse

from safe_browser import SafeBrowser

SCRAPER = Path(__file__).with_name("scrapper.py")
START, END = "# >>> RECIPE (written by record.py) >>>", "# <<< END RECIPE <<<"

# Runs inside the page: reports clicks, typing and Enter presses with a reusable CSS selector.
RECORDER_JS = r"""
(() => {
  if (window !== window.top || window.__recInstalled) return;
  window.__recInstalled = true;
  const uniq = s => { try { return document.querySelectorAll(s).length === 1; } catch (e) { return false; } };
  const q = v => '"' + String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  function selector(el) {
    const tag = el.tagName.toLowerCase();
    if (el.id && !/\d{3,}|[:]/.test(el.id) && uniq('#' + CSS.escape(el.id))) return '#' + CSS.escape(el.id);
    const isLink = tag === 'a';
    for (const a of ['data-testid', 'data-test', 'data-qa', 'name', 'aria-label', 'placeholder', 'title', 'type']) {
      const v = el.getAttribute(a);
      if (!v || v.length > 40 || (isLink && (a === 'aria-label' || a === 'title'))) continue;
      const s = `${tag}[${a}=${q(v)}]`;
      if (uniq(s)) return s;
    }
    // Positional path (generalizes to "the first result" on a results page).
    const parts = [];
    let n = el;
    while (n && n.nodeType === 1 && n !== document.body && parts.length < 7) {
      if (n !== el && n.id && !/\d{3,}|[:]/.test(n.id)) { parts.unshift('#' + CSS.escape(n.id)); break; }
      let i = 1, s = n;
      while ((s = s.previousElementSibling)) if (s.tagName === n.tagName) i++;
      parts.unshift(`${n.tagName.toLowerCase()}:nth-of-type(${i})`);
      n = n.parentElement;
    }
    return parts.join(' > ');
  }
  const target = e => e.target.closest('a,button,input,select,textarea,[role=button],[role=link],[role=searchbox],[onclick],summary') || e.target;
  document.addEventListener('click', e => {
    const el = target(e);
    window.__rec({type: 'click', selector: selector(el), text: (el.innerText || el.value || '').trim().slice(0, 60)});
  }, true);
  document.addEventListener('input', e => {
    const el = e.target;
    if (el.matches && el.matches('input,textarea,[contenteditable]'))
      window.__rec({type: 'fill', selector: selector(el), value: el.value ?? el.innerText});
  }, true);
  document.addEventListener('keydown', e => {
    if (e.key === 'Enter') window.__rec({type: 'press', key: 'Enter', selector: selector(e.target)});
  }, true);
})();
"""


def ask(prompt, default=""):
    v = input(f"{prompt}" + (f" [{default}]" if default else "") + ": ").strip()
    return v or default


def stdin_ready():
    return bool(select.select([sys.stdin], [], [], 0)[0])


def templatize(text, query):
    """Replace the example query (in any common encoding) with {query}."""
    for enc, form in (("plus", quote_plus(query)), ("percent", quote(query)), ("raw", query)):
        if form and re.search(re.escape(form), text, re.I):
            return re.sub(re.escape(form), "{query}", text, flags=re.I), enc
    return text, None


def build_recipe(events, query, start_url, video):
    events = [e for e in events if e["t"] <= video["t"]]
    # Shortcut: if the site put the search word in the URL, just go straight to that URL.
    search_nav = next((i for i, e in enumerate(events)
                       if e["type"] == "nav" and templatize(e["url"], query)[1]), None)
    steps = []
    if search_nav is not None:
        url, enc = templatize(events[search_nav]["url"], query)
        steps.append({"action": "goto", "url": url, "encode": enc})
        events = events[search_nav + 1:]
    else:
        steps.append({"action": "goto", "url": start_url})
    for e in events:
        if e["type"] == "nav":
            continue
        if e["type"] == "fill":
            value, enc = templatize(e["value"], query)
            if steps and steps[-1]["action"] == "fill" and steps[-1]["selector"] == e["selector"]:
                steps.pop()
            steps.append({"action": "fill", "selector": e["selector"], "value": value})
        elif e["type"] == "press":
            steps.append({"action": "press", "selector": e["selector"], "key": e["key"]})
        elif e["type"] == "click":
            if steps and steps[-1] == {"action": "click", "selector": e["selector"]}:
                continue  # double-click noise
            steps.append({"action": "click", "selector": e["selector"]})
    # A click on a box right before typing into it is redundant (fill focuses it).
    steps = [s for i, s in enumerate(steps) if not (
        s["action"] == "click" and i + 1 < len(steps) and steps[i + 1].get("selector") == s["selector"]
        and steps[i + 1]["action"] == "fill")]
    return {"start": start_url, "example_query": query, "steps": steps,
            "video_hosts": [urlparse(video["url"]).hostname]}


def write_recipe(recipe):
    src = SCRAPER.read_text()
    block = f"{START}\nRECIPE = {pprint.pformat(recipe, width=100, sort_dicts=False)}\n{END}"
    new = re.sub(re.escape(START) + r".*?" + re.escape(END), lambda m: block, src, flags=re.S)
    SCRAPER.write_text(new)


def main():
    sys.path.insert(0, str(SCRAPER.parent))
    import scrapper
    start = ask("Website to record on", scrapper.SOURCE)
    query = ask("The example word you will search for (e.g. tornado)")
    if not query:
        sys.exit("need an example search word")
    events, videos = [], []
    t0 = time.time()

    def note(e):
        e["t"] = time.time() - t0
        events.append(e)

    def on_video(url):
        videos.append({"url": url, "t": time.time() - t0})
        print(f"\n  🎬 video detected #{len(videos)}: {url[:110]}\n     (press Enter here if that's the right one, or keep going)")

    def on_block(what, url):
        print(f"  🛡  blocked {what}: {url[:100]}")

    with SafeBrowser(start, headless=False, block_images=False, on_video=on_video, on_block=on_block) as b:
        b.context.expose_binding("__rec", lambda source, e: note(dict(e)) if source["frame"] == b.page.main_frame else None)
        b.context.add_init_script(RECORDER_JS)
        b.page.on("framenavigated", lambda f: f == b.page.main_frame and note({"type": "nav", "url": f.url}))
        b.page.goto(start, wait_until="domcontentloaded")
        print(f"\nRecording. In the browser: search for '{query}' and open the video you want.")
        print("Videos are detected but not downloaded, so they won't play in this window.")
        print("Press Enter here when done.\n")
        while not stdin_ready():
            if b.page.is_closed():
                break
            b.page.wait_for_timeout(250)
        if stdin_ready():
            sys.stdin.readline()

    if not videos:
        sys.exit("No video was detected, nothing saved. Try again and make sure you open a video page.")
    if len(videos) == 1:
        chosen = videos[0]
    else:
        for i, v in enumerate(videos, 1):
            print(f"  {i}. {v['url'][:120]}")
        chosen = videos[int(ask("Which one is the right video", str(len(videos)))) - 1]

    recipe = build_recipe(events, query, start, chosen)
    write_recipe(recipe)
    print("\nSaved to the RECIPE section of scrapper.py:\n")
    pprint.pprint(recipe, width=100, sort_dicts=False)

    print(f"\nTesting it: searching for '{query}' with no help from you...")
    found = scrapper.search(query)
    if found:
        print(f"  ✅ works: {found[0]['title']!r}\n     {found[0]['src'][:110]}")
    else:
        print("  ⚠️  replay found no video. Try recording again, or tweak the steps in scrapper.py.")


if __name__ == "__main__":
    main()
