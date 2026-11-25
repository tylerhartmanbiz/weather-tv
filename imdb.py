"""IMDb search + details for the TV (called by server.py; the TV never talks to IMDb itself).

Only two fixed IMDb addresses are ever contacted, and everything sent to the TV is rebuilt
here as plain text fields: no HTML, and image links only from IMDb's image host.
"""
import json
import re
import urllib.request
from urllib.parse import quote

SUGGEST = "https://v3.sg.media-imdb.com/suggestion/titles/x/{}.json"
GRAPHQL = "https://caching.graphql.imdb.com/"
IMAGE_HOST = "m.media-amazon.com"
IMDB_ID_RE = re.compile(r"^tt\d{7,10}$")
TYPES = {"feature": "Movie", "TV series": "TV Series", "TV mini-series": "TV Mini Series",
         "TV movie": "TV Movie", "TV special": "TV Special", "short": "Short", "video": "Video"}
SKIP = {"videoGame", "podcastSeries", "podcastEpisode", "musicVideo"}


def _text(s, limit):
    return re.sub(r"\s+", " ", re.sub(r"[<>\x00-\x1f]", "", str(s or ""))).strip()[:limit]


def _image(url):
    url = str(url or "")
    return url if url.startswith(f"https://{IMAGE_HOST}/") and len(url) < 500 else ""


def _get_json(req):
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read(3_000_000))


def search(q):
    q = re.sub(r"[\x00-\x1f]", "", q).strip().lower()[:100]
    if not q:
        return []
    data = _get_json(urllib.request.Request(SUGGEST.format(quote(q)), headers={"Accept": "application/json"}))
    out = []
    for x in data.get("d", []):
        if not IMDB_ID_RE.match(str(x.get("id", ""))) or x.get("qid") in SKIP:
            continue
        out.append({"id": x["id"], "title": _text(x.get("l"), 200), "year": _text(x.get("yr") or x.get("y"), 20),
                    "type": TYPES.get(x.get("q"), _text(x.get("q"), 30)),
                    "isTv": bool(re.search(r"TV (series|mini-series)", str(x.get("q", "")))),
                    "stars": _text(x.get("s"), 200), "poster": _image((x.get("i") or {}).get("imageUrl"))})
    return out[:10]


QUERY = """query($id: ID!) { title(id: $id) {
  titleText { text } titleType { text canHaveEpisodes }
  releaseYear { year endYear } runtime { seconds } certificate { rating }
  ratingsSummary { aggregateRating }
  plot { plotText { plainText } } primaryImage { url }
  genres { genres { text } }
  episodes { seasons { number } episodes(first: 0) { total } }
  credits(first: 15, filter: { categories: ["cast"] }) { edges { node {
    name { nameText { text } primaryImage { url } }
    ... on Cast { characters { name } }
  } } }
} }"""


def title(imdb_id):
    if not IMDB_ID_RE.match(imdb_id or ""):
        raise ValueError("bad IMDb id")
    body = json.dumps({"query": QUERY, "variables": {"id": imdb_id}}).encode()
    t = (_get_json(urllib.request.Request(GRAPHQL, data=body, headers={"Content-Type": "application/json"}))
         .get("data") or {}).get("title")
    if not t:
        raise ValueError("no data")
    y = t.get("releaseYear") or {}
    tv = bool((t.get("titleType") or {}).get("canHaveEpisodes"))
    year = str(y.get("year") or "")
    if y.get("endYear") and y.get("endYear") != y.get("year"):
        year += f"–{y['endYear']}"
    elif tv and year:
        year += "–"
    eps = t.get("episodes") or {}
    cast = []
    for e in ((t.get("credits") or {}).get("edges") or []):
        n = e.get("node") or {}
        name = _text(((n.get("name") or {}).get("nameText") or {}).get("text"), 100)
        if name:
            cast.append({"name": name,
                         "character": _text(" / ".join(c.get("name", "") for c in n.get("characters") or []), 100),
                         "photo": _image(((n.get("name") or {}).get("primaryImage") or {}).get("url"))})
    return {
        "id": imdb_id, "title": _text((t.get("titleText") or {}).get("text"), 200), "year": year,
        "type": _text((t.get("titleType") or {}).get("text"), 30), "isTv": tv,
        "seasons": len([s for s in eps.get("seasons") or [] if (s.get("number") or 0) > 0]) if tv else None,
        "seasonNumbers": sorted({int(s["number"]) for s in eps.get("seasons") or []
                                 if isinstance(s.get("number"), int) and 0 < s["number"] < 1000}) if tv else [],
        "episodes": ((eps.get("episodes") or {}).get("total")) if tv else None,
        "runtime": (t.get("runtime") or {}).get("seconds"),
        "certificate": _text((t.get("certificate") or {}).get("rating"), 10),
        "rating": (t.get("ratingsSummary") or {}).get("aggregateRating"),
        "plot": _text(((t.get("plot") or {}).get("plotText") or {}).get("plainText"), 1000),
        "poster": _image((t.get("primaryImage") or {}).get("url")),
        "genres": [_text(g.get("text"), 30) for g in ((t.get("genres") or {}).get("genres") or [])][:5],
        "cast": cast,
    }


# Responses for the TV. allowedHosts lets the TV app load the poster/cast images.
def search_result(q):
    return {"results": search(q), "allowedHosts": [IMAGE_HOST]}


def title_result(imdb_id):
    return {"title": title(imdb_id), "allowedHosts": [IMAGE_HOST]}


EPISODES_QUERY = """query($id: ID!, $season: String!) { title(id: $id) { episodes {
  episodes(first: 250, filter: { includeSeasons: [$season] }) { edges { node {
    titleText { text } series { episodeNumber { episodeNumber seasonNumber } }
  } } }
} } }"""


def episodes(imdb_id, season):
    """[{"episode": 1, "title": "Pilot"}, ...] for one season."""
    if not IMDB_ID_RE.match(imdb_id or "") or not str(season).isdigit():
        raise ValueError("bad id/season")
    body = json.dumps({"query": EPISODES_QUERY, "variables": {"id": imdb_id, "season": str(int(season))}}).encode()
    t = (_get_json(urllib.request.Request(GRAPHQL, data=body, headers={"Content-Type": "application/json"}))
         .get("data") or {}).get("title") or {}
    out = {}
    for e in ((((t.get("episodes") or {}).get("episodes") or {}).get("edges")) or []):
        n = e.get("node") or {}
        num = (((n.get("series") or {}).get("episodeNumber")) or {}).get("episodeNumber")
        if isinstance(num, int) and 0 <= num < 10000:
            out[num] = _text((n.get("titleText") or {}).get("text"), 120)
    return [{"episode": k, "title": v} for k, v in sorted(out.items())]


def episodes_result(key):
    imdb_id, season = key.split(":", 1)
    return {"episodes": episodes(imdb_id, season)}
