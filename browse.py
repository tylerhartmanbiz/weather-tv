#!/usr/bin/env python3
"""Build docs/browse.json: the rows on the app's home screen (Trending, Christmas, genres).

Runs every day on GitHub (.github/workflows/browse.yml) and commits docs/browse.json, which the
TV reads live: no app update needed. Python standard library only.

Where the titles come from (all free, public, no keys):
  Trending   Wikipedia's most-viewed articles of the last 2 days that are films / TV series
             (Wikimedia pageviews API -> Wikidata, for the IMDb ID)
  Christmas  the CHRISTMAS list below, each looked up by title + year in IMDb's public search
  genres     Wikidata: the films / TV series of that genre with the most Wikipedia languages
             (a decent stand-in for "popular"), films from 2000 on
Every title is then looked up in IMDb's public suggestion search (the same one the TV app uses
for searching) for its name, year, type, stars and poster. Anything not found there is left out.

Edit ROWS / CHRISTMAS below and push: the workflow runs again and the TV shows the new rows.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

UA = "weather-tv-browse/1.0 (home screen rows; github.com/tylerhartmanbiz/weather-tv)"
PER_ROW = 40          # titles in browse.json (what the home screen shows first)
FULL_ROW = 300        # titles in docs/browse/<row>.json (the grid keeps loading these as you scroll)

# Wikidata genre IDs. Films and TV series use different genre items.
ROWS = [
    {"id": "trending", "name": "Trending", "trending": True},
    {"id": "christmas", "name": "Christmas", "christmas": True},
    {"id": "comedy", "name": "Comedy",
     "film": ["Q157443"], "tv": ["Q9335576", "Q170238", "Q859369"]},
    {"id": "adultswim", "name": "Adult Swim", "adultswim": True},      # shows first aired on Adult Swim
    {"id": "standup", "name": "Stand-Up", "standup": True},            # stand-up comedy specials
    {"id": "action", "name": "Action & Adventure",
     "film": ["Q188473", "Q319221"], "tv": ["Q343782", "Q56064758", "Q141622854"]},
    {"id": "crime", "name": "Crime",
     "film": ["Q959790"], "tv": ["Q9335577", "Q2321734"]},
    {"id": "thriller", "name": "Thrillers & Mystery",
     "film": ["Q2484376"], "tv": ["Q67175872", "Q101898470"]},
    {"id": "horror", "name": "Horror",
     "film": ["Q200092"], "tv": ["Q20220309"]},
    {"id": "scifi", "name": "Sci-Fi & Fantasy",
     "film": ["Q471839"], "tv": ["Q140472311", "Q98526245"]},
    {"id": "romance", "name": "Romance",
     "film": ["Q1054574"], "tv": ["Q84270297", "Q133308333"]},
    {"id": "family", "name": "Animation & Family",
     "film": ["Q202866", "Q1361932"], "tv": ["Q581714", "Q1273568", "Q17113138"]},
]

# Christmas row: (title, year). Each is checked against IMDb; misses are dropped.
CHRISTMAS = [
    ("Elf", 2003), ("Home Alone", 1990), ("Home Alone 2: Lost in New York", 1992),
    ("The Polar Express", 2004), ("How the Grinch Stole Christmas", 2000), ("The Grinch", 2018),
    ("Love Actually", 2003), ("The Holiday", 2006), ("National Lampoon's Christmas Vacation", 1989),
    ("A Christmas Story", 1983), ("The Santa Clause", 1994), ("Klaus", 2019),
    ("The Nightmare Before Christmas", 1993), ("Die Hard", 1988), ("It's a Wonderful Life", 1946),
    ("Arthur Christmas", 2011), ("The Muppet Christmas Carol", 1992), ("Jingle All the Way", 1996),
    ("Red One", 2024), ("Violent Night", 2022), ("The Christmas Chronicles", 2018),
    ("Four Christmases", 2008), ("Last Christmas", 2019), ("Bad Santa", 2003),
    ("Scrooged", 1988), ("A Christmas Carol", 2009), ("Miracle on 34th Street", 1947),
    ("The Family Stone", 2005), ("Noelle", 2019), ("Gremlins", 1984),
    ("Home Alone 3", 1997), ("The Santa Clause 2", 2002), ("Christmas with the Kranks", 2004),
    ("Deck the Halls", 2006), ("Fred Claus", 2007), ("Santa Claus: The Movie", 1985),
    ("The Night Before", 2015), ("Office Christmas Party", 2016), ("A Bad Moms Christmas", 2017),
    ("Jingle Jangle: A Christmas Journey", 2020), ("Spirited", 2022), ("Single All the Way", 2021),
    ("The Christmas Chronicles 2", 2020), ("A Boy Called Christmas", 2021), ("Happiest Season", 2020),
    ("While You Were Sleeping", 1995), ("White Christmas", 1954), ("Holiday Inn", 1942),
    ("Rise of the Guardians", 2012), ("Krampus", 2015), ("Black Christmas", 1974),
    ("The Grinch Who Stole Christmas", 1966), ("Frosty the Snowman", 1969), ("A Charlie Brown Christmas", 1965),
    ("Rudolph the Red-Nosed Reindeer", 1964), ("Love the Coopers", 2015), ("Christmas Vacation 2: Cousin Eddie's Island Adventure", 2003),
    ("The Holiday Calendar", 2018), ("A Christmas Prince", 2017), ("The Princess Switch", 2018),
]

FILM_TYPES = ["Q11424"]                                   # film
TV_TYPES = ["Q5398426", "Q1259759", "Q581714"]           # television series, miniseries, animated series
OK_KINDS = {"movie", "tvSeries", "tvMiniSeries", "tvMovie", "tvSpecial", "video"}
TV_KINDS = {"tvSeries", "tvMiniSeries"}


def get(url, data=None, accept="application/json", tries=5):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept": accept})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                raise
            wait = 3 * (i + 1)
            if getattr(e, "code", None) == 429:          # asked to slow down: wait as long as it says
                try:
                    wait = max(wait, min(120, int(e.headers.get("Retry-After", "65"))))
                except ValueError:
                    wait = 65
            print(f"  retry in {wait}s: {url[:80]} ({e})", file=sys.stderr)
            time.sleep(wait)


def sparql(q):
    d = get("https://query.wikidata.org/sparql", data=urllib.parse.urlencode({"query": q}).encode(),
            accept="application/sparql-results+json")
    return d["results"]["bindings"]


def wd_genres(kind, types, since, min_links):
    """One query for every row: {genre QID: [IMDb IDs, most Wikipedia languages first]}."""
    genres = sorted({g for r in ROWS for g in r.get(kind, [])})
    g = " ".join("wd:" + x for x in genres)
    t = " ".join("wd:" + x for x in types)
    # first release from `since` on (re-releases of older films don't count)
    year = (f"FILTER EXISTS {{ ?s wdt:P577 ?d . FILTER(YEAR(?d) >= {since}) }} "
            f"FILTER NOT EXISTS {{ ?s wdt:P577 ?d0 . FILTER(YEAR(?d0) < {since}) }}") if since else ""
    q = f"""SELECT ?g ?imdb ?k WHERE {{
      VALUES ?g {{ {g} }} VALUES ?t {{ {t} }}
      ?s wdt:P136 ?g ; wdt:P31 ?t ; wikibase:sitelinks ?k .
      FILTER(?k >= {min_links})
      ?s wdt:P345 ?imdb . FILTER(STRSTARTS(?imdb, "tt"))
      {year}
    }}"""
    best = {}
    for b in sparql(q):
        gid = b["g"]["value"].rsplit("/", 1)[1]
        best.setdefault(gid, {})
        imdb_id, k = b["imdb"]["value"], int(b["k"]["value"])
        best[gid][imdb_id] = max(k, best[gid].get(imdb_id, 0))
    return best


def wd_simple(where, min_links=0):
    """IMDb IDs matching a Wikidata pattern (?s), most Wikipedia languages first."""
    q = f"""SELECT ?imdb (MAX(?k) AS ?links) WHERE {{
      {where}
      ?s wdt:P345 ?imdb ; wikibase:sitelinks ?k . FILTER(STRSTARTS(?imdb, "tt")) FILTER(?k >= {min_links})
    }} GROUP BY ?imdb ORDER BY DESC(?links) LIMIT 2000"""
    return [b["imdb"]["value"] for b in sparql(q)]


def adult_swim():
    """Shows first aired on Adult Swim (original broadcaster)."""
    return wd_simple("?s wdt:P449 wd:Q379052 .")


def standup():
    """Stand-up comedy specials (not people, not TV series such as sketch shows)."""
    return wd_simple("""VALUES ?g { wd:Q140299056 wd:Q145806 } ?s wdt:P136 ?g .
      FILTER NOT EXISTS { ?s wdt:P31 wd:Q5 } FILTER NOT EXISTS { ?s wdt:P31 wd:Q5398426 }""")


def ranked(by_genre, genres):
    """IMDb IDs of these genres, most-linked first."""
    score = {}
    for g in genres:
        for i, k in by_genre.get(g, {}).items():
            score[i] = max(k, score.get(i, 0))
    return sorted(score, key=score.get, reverse=True)


def trending(days=2, top=500):
    """IMDb IDs of films / TV series among Wikipedia's most-read articles, most read first."""
    views = {}
    now = datetime.now(timezone.utc)
    got = 0
    for back in range(1, 6):
        if got >= days:
            break
        d = now - timedelta(days=back)
        url = ("https://wikimedia.org/api/rest_v1/metrics/pageviews/top/en.wikipedia/all-access/"
               f"{d:%Y/%m/%d}")
        try:
            arts = get(url)["items"][0]["articles"]
        except Exception as e:  # noqa: BLE001  (that day isn't published yet)
            print(f"  no pageviews for {d:%Y-%m-%d} ({e})", file=sys.stderr)
            continue
        got += 1
        for a in arts[:top]:
            t = a["article"]
            if ":" in t or t == "Main_Page":
                continue
            views[t] = views.get(t, 0) + a["views"]
    titles = sorted(views, key=views.get, reverse=True)
    # Wikipedia article -> Wikidata item (50 per request)
    qid_views = {}
    for i in range(0, len(titles), 50):
        chunk = titles[i:i + 50]
        d = get("https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({
            "action": "query", "prop": "pageprops", "ppprop": "wikibase_item", "redirects": 1,
            "format": "json", "titles": "|".join(t.replace("_", " ") for t in chunk)}))
        for p in d.get("query", {}).get("pages", {}).values():
            qid = p.get("pageprops", {}).get("wikibase_item")
            if qid:
                key = p["title"].replace(" ", "_")
                qid_views[qid] = qid_views.get(qid, 0) + views.get(key, 0) or 1
        time.sleep(1.5)
    # which of them are films / TV series with an IMDb ID
    out = {}
    qids = list(qid_views)
    t = " ".join("wd:" + x for x in FILM_TYPES + TV_TYPES)
    for i in range(0, len(qids), 300):
        vals = " ".join("wd:" + q for q in qids[i:i + 300])
        for b in sparql(f"""SELECT ?s ?imdb WHERE {{ VALUES ?s {{ {vals} }} VALUES ?t {{ {t} }}
                             ?s wdt:P31 ?t ; wdt:P345 ?imdb . FILTER(STRSTARTS(?imdb, "tt")) }}"""):
            q = b["s"]["value"].rsplit("/", 1)[1]
            out[b["imdb"]["value"]] = qid_views.get(q, 0)
    return sorted(out, key=out.get, reverse=True)


IMDB_CACHE = {}


def imdb(id_):
    """Title info from IMDb's public suggestion search, or None."""
    if id_ in IMDB_CACHE:
        return IMDB_CACHE[id_]
    info = None
    try:
        d = get(f"https://v3.sg.media-imdb.com/suggestion/x/{id_}.json")
        for x in d.get("d", []):
            if x.get("id") == id_:
                info = entry(x)
                break
    except Exception as e:  # noqa: BLE001
        print(f"  IMDb lookup failed for {id_} ({e})", file=sys.stderr)
    time.sleep(0.15)
    IMDB_CACHE[id_] = info
    return info


TYPE_NAMES = {"feature": "Movie", "TV series": "TV Series", "TV mini-series": "TV Mini Series",
              "TV movie": "TV Movie", "TV special": "TV Special", "video": "Special"}


def entry(x):
    if x.get("qid") not in OK_KINDS:
        return None
    img = (x.get("i") or {}).get("imageUrl") or ""
    if not img.startswith("https://m.media-amazon.com/"):
        return None                                   # no poster: looks broken on a TV row
    e = {"id": x["id"], "title": str(x.get("l", ""))[:200], "year": str(x.get("yr") or x.get("y") or "")[:20],
         "type": TYPE_NAMES.get(x.get("q"), str(x.get("q") or "")[:30]), "isTv": x.get("qid") in TV_KINDS,
         "poster": img[:500]}
    if x.get("s"):
        e["stars"] = str(x["s"])[:200]
    return e


def christmas():
    out = []
    for title, year in CHRISTMAS:
        q = urllib.parse.quote(title.lower()[:100])
        try:
            d = get(f"https://v3.sg.media-imdb.com/suggestion/x/{q}.json")
        except Exception as e:  # noqa: BLE001
            print(f"  search failed: {title} ({e})", file=sys.stderr)
            continue
        hit = next((x for x in d.get("d", []) if x.get("y") == year and x.get("qid") in OK_KINDS
                    and str(x.get("l", "")).lower() == title.lower()), None) \
            or next((x for x in d.get("d", []) if x.get("y") == year and x.get("qid") == "movie"), None)
        e = entry(hit) if hit else None
        if e:
            out.append(e)
        else:
            print(f"  Christmas: not found on IMDb: {title} ({year})", file=sys.stderr)
        time.sleep(0.15)
    return out


def resolve(ids, limit=PER_ROW, skip=()):
    out, seen = [], set(skip)
    for i in ids:
        if i in seen:
            continue
        seen.add(i)
        e = imdb(i)
        if e:
            out.append(e)
        if len(out) >= limit:
            break
    return out


def interleave3(a, b):
    """3 from a, then 2 from b, and so on (the rest of either at the end)."""
    out, i, j = [], 0, 0
    while i < len(a) or j < len(b):
        out += a[i:i + 3]; i += 3
        out += b[j:j + 2]; j += 2
    return out


def interleave(a, b):
    out = []
    for i in range(max(len(a), len(b))):
        if i < len(a):
            out.append(a[i])
        if i < len(b):
            out.append(b[i])
    return out


def main(path):
    full_dir = os.path.join(os.path.dirname(path), "browse")
    os.makedirs(full_dir, exist_ok=True)
    old = {}
    try:                                   # a row that fails today keeps yesterday's titles
        with open(path, encoding="utf-8") as f:
            old = {r["id"]: r["items"] for r in json.load(f).get("rows", [])}
    except Exception:  # noqa: BLE001
        pass
    for r in ROWS:                         # yesterday's full lists: their titles needn't be looked up again
        try:
            with open(os.path.join(full_dir, r["id"] + ".json"), encoding="utf-8") as f:
                items = json.load(f).get("items", [])
            old[r["id"]] = items if len(items) > len(old.get(r["id"], [])) else old.get(r["id"], [])
            for e in items:
                if e.get("id") and e.get("poster"):
                    IMDB_CACHE.setdefault(e["id"], e)
        except Exception:  # noqa: BLE001
            pass
    rows = []
    films_by, shows_by = {}, {}
    try:
        print("Wikidata: films…", file=sys.stderr)
        films_by = wd_genres("film", FILM_TYPES, 2000, 6)
        print("Wikidata: TV series…", file=sys.stderr)
        shows_by = wd_genres("tv", TV_TYPES, None, 4)
    except Exception as e:  # noqa: BLE001
        print(f"  Wikidata failed: {e}", file=sys.stderr)
    for r in ROWS:
        print(f"{r['name']}…", file=sys.stderr)
        try:
            if r.get("trending"):
                items = resolve(trending(), FULL_ROW)
            elif r.get("christmas"):
                items = christmas()
            elif r.get("adultswim"):
                items = resolve(adult_swim(), FULL_ROW)
            elif r.get("standup"):
                items = resolve(standup(), FULL_ROW)
            else:                          # 3 films : 2 shows, each most popular first
                films = resolve(ranked(films_by, r["film"]), FULL_ROW * 3 // 5)
                shows = resolve(ranked(shows_by, r["tv"]), FULL_ROW * 2 // 5, {f["id"] for f in films})
                items = interleave3(films, shows)[:FULL_ROW]
        except Exception as e:  # noqa: BLE001  (one broken row never stops the others)
            print(f"  row failed: {e}", file=sys.stderr)
            items = []
        if not items and old.get(r["id"]):
            items = old[r["id"]]
            print("  kept yesterday's titles", file=sys.stderr)
        print(f"  {len(items)} titles", file=sys.stderr)
        if items:
            row = {"id": r["id"], "name": r["name"], "items": items[:PER_ROW], "total": len(items)}
            if len(items) > PER_ROW:
                row["more"] = f"browse/{r['id']}.json"
                with open(os.path.join(full_dir, r["id"] + ".json"), "w", encoding="utf-8") as f:
                    json.dump({"id": r["id"], "items": items}, f, ensure_ascii=False, separators=(",", ":"))
            rows.append(row)
    if len(rows) < 3:
        sys.exit("too few rows built; keeping the old browse.json")
    data = {"updated": datetime.now(timezone.utc).strftime("%Y-%m-%d"), "rows": rows}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {path}: {len(rows)} rows", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/browse.json")
