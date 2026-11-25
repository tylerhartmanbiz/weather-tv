package app.weathertv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small, careful network helpers used on the TV.
 *
 *  * https only, normal public hostnames only (no raw IP addresses, no home-network names),
 *    and every hostname must resolve to public internet addresses (never your router,
 *    computer, or anything else on your Wi-Fi). Redirects are followed by hand, max 3, and
 *    each hop is checked again.
 *  * Everything is read as text into memory with a size cap. Nothing is ever saved.
 *  * Video is never downloaded: for HLS (.m3u8) only the small text playlists are read, to
 *    learn which hosts the player will need and to check every part is safe.
 */
final class Net {
    private Net() { }

    private static final Pattern HOST_RE =
            Pattern.compile("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final String[] LOCAL_SUFFIXES =
            {".local", ".localhost", ".lan", ".home", ".internal", ".intranet", ".corp", ".arpa", ".home.arpa"};
    static final int PLAYLIST_MAX = 2_000_000;
    static final int MAX_STREAM_HOSTS = 12;
    private static final Map<String, Boolean> PUBLIC_CACHE = new ConcurrentHashMap<String, Boolean>();

    static boolean isPublicHostname(String h) {
        if (h == null || h.length() > 253 || "localhost".equals(h) || !HOST_RE.matcher(h).matches()) return false;
        for (String s : LOCAL_SUFFIXES) if (h.endsWith(s)) return false;
        return true;
    }

    static String siteOf(String host) {
        String[] p = (host == null ? "" : host.toLowerCase(Locale.ROOT)).split("\\.");
        return p.length < 2 ? p[p.length - 1] : p[p.length - 2] + "." + p[p.length - 1];
    }

    static boolean isPublicAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int x = b[0] & 255, y = b[1] & 255;
            if (x == 0 || x == 10 || x == 127 || x >= 224) return false;
            if (x == 100 && (y & 0xC0) == 64) return false;          // 100.64.0.0/10 carrier NAT
            if (x == 169 && y == 254) return false;
            if (x == 172 && (y & 0xF0) == 16) return false;
            if (x == 192 && y == 168) return false;
            if (x == 192 && y == 0 && (b[2] & 255) == 0) return false;
            if (x == 198 && (y == 18 || y == 19)) return false;
            return true;
        }
        if ((b[0] & 0xFE) == 0xFC) return false;                      // fc00::/7 unique local
        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) return false; // fe80::/10
        return true;
    }

    /** True if every address the hostname resolves to is on the public internet. Cached. */
    static boolean resolvesPublic(String host) {
        Boolean hit = PUBLIC_CACHE.get(host);
        if (hit != null) return hit;
        boolean ok;
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            ok = all.length > 0;
            for (InetAddress a : all) if (!isPublicAddress(a)) ok = false;
        } catch (Exception e) {
            ok = false;
        }
        if (PUBLIC_CACHE.size() > 2000) PUBLIC_CACHE.clear();
        PUBLIC_CACHE.put(host, ok);
        return ok;
    }

    /** Throws unless url is https on a public hostname (port 443, no user:password). */
    static String checkUrl(String url) throws IOException {
        URL u = new URL(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(u.getProtocol()) || !isPublicHostname(host)
                || (u.getPort() != -1 && u.getPort() != 443) || u.getUserInfo() != null) {
            throw new IOException("refused url");
        }
        return host;
    }

    static final class Resp {
        int status;
        String url, type, text;
        final Set<String> hosts = new HashSet<String>();
    }

    /** GET (or POST json) a small text resource safely. */
    static Resp fetch(String url, String postJson, String accept, int maxBytes, String sameSite) throws IOException {
        return fetch(url, postJson, accept, maxBytes, sameSite, null);
    }

    /** Same, sending extra request headers (e.g. the video site's Referer). */
    static Resp fetch(String url, String postJson, String accept, int maxBytes, String sameSite,
                      Map<String, String> headers) throws IOException {
        Resp r = new Resp();
        for (int hop = 0; hop < 4; hop++) {
            String host = checkUrl(url);
            if (sameSite != null && !sameSite.equals(siteOf(host))) throw new IOException("blocked redirect to another site");
            if (!resolvesPublic(host)) throw new IOException("not a public internet address");
            r.hosts.add(host);
            HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", accept);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if (postJson != null) {
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream o = c.getOutputStream();
                    o.write(postJson.getBytes("UTF-8"));
                    o.close();
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without location");
                    url = new URL(new URL(url), loc).toString();
                    postJson = null;
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        buf.write(b, 0, n);
                        if (buf.size() > maxBytes) { in.close(); throw new IOException("response too large"); }
                    }
                    in.close();
                }
                r.status = code;
                r.url = url;
                r.type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                r.text = buf.toString("UTF-8");
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /**
     * Open a stream request for the app's player (GET, video bytes flow straight through to
     * the player, nothing is saved). Every hop must be https, on one of `hosts`, and resolve
     * to public addresses. Redirects are followed by hand (max 3). Caller reads + disconnects.
     */
    static HttpsURLConnection openStream(String url, Map<String, String> headers, Set<String> hosts) throws IOException {
        for (int hop = 0; hop < 4; hop++) {
            String host = checkUrl(url);
            if (!hosts.contains(host)) throw new IOException("host not approved");
            if (!resolvesPublic(host)) throw new IOException("not a public internet address");
            HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    // ---------- HLS playlists (text only; video is never downloaded) ----------

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern RES = Pattern.compile("^(\\d+)x(\\d+)");

    static final class Stream {
        final Set<String> hosts = new HashSet<String>();
        String quality = "";
    }

    private static Resp playlist(String url, Map<String, String> headers) throws IOException {
        Resp r = fetch(url, null, "application/vnd.apple.mpegurl, */*", PLAYLIST_MAX, null, headers);
        String t = r.text.replace("﻿", "").trim();
        if (r.status != 200 || r.type.contains("html") || !t.startsWith("#EXTM3U")) throw new IOException("not an HLS playlist");
        return r;
    }

    private static String attr(String line, String key) {
        Matcher m = ATTR.matcher(line.substring(line.indexOf(':') + 1));
        while (m.find()) if (m.group(1).equals(key)) return m.group(2).replace("\"", "");
        return "";
    }

    private static List<String> playlistUrls(String text, String base) throws IOException {
        List<String> out = new ArrayList<String>();
        URL b = new URL(base);
        for (String line : text.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.startsWith("#")) out.add(new URL(b, line).toString());
            Matcher m = URI_ATTR.matcher(line);
            while (m.find()) {
                String u = m.group(1);
                if (u.startsWith("data:") || u.startsWith("skd:")) continue;
                out.add(new URL(b, u).toString());
            }
        }
        return out;
    }

    private static void addHosts(Set<String> into, List<String> urls) throws IOException {
        for (String u : urls) into.add(checkUrl(u));
    }

    /**
     * Check a video link without downloading video: which hosts the TV needs + quality.
     * headers: what the video site's own player would send (browser identity, Referer).
     */
    static Stream inspect(String src, Map<String, String> headers) throws IOException {
        Stream s = new Stream();
        String path = new URL(src).getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".mp4")) {
            s.hosts.add(checkUrl(src));
            if (!resolvesPublic(new URL(src).getHost())) throw new IOException("not public");
            return s;
        }
        Resp m = playlist(src, headers);
        s.hosts.addAll(m.hosts);
        if (m.text.contains("#EXT-X-STREAM-INF")) {          // master playlist: list of qualities
            String[] lines = m.text.split("\r?\n");
            int bestH = -1; long bestBw = -1; String best = null, audio = "";
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                String next = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String l = lines[j].trim();
                    if (!l.isEmpty() && !l.startsWith("#")) { next = l; break; }
                }
                if (next == null) continue;
                Matcher r = RES.matcher(attr(lines[i], "RESOLUTION"));
                int h = r.find() ? Integer.parseInt(r.group(2)) : 0;
                long bw = 0;
                try { bw = Long.parseLong(attr(lines[i], "BANDWIDTH")); } catch (Exception ignored) { }
                if (h > bestH || (h == bestH && bw > bestBw)) {
                    bestH = h; bestBw = bw; best = new URL(new URL(m.url), next).toString(); audio = attr(lines[i], "AUDIO");
                }
            }
            if (best == null) throw new IOException("playlist lists no streams");
            addHosts(s.hosts, playlistUrls(m.text, m.url));
            s.quality = bestH > 0 ? bestH + "p" : "";
            Resp v = playlist(best, headers);                            // the TV locks to this one
            s.hosts.addAll(v.hosts);
            addHosts(s.hosts, playlistUrls(v.text, v.url));
            for (String line : lines) {                         // its separate audio track, if any
                if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=AUDIO")
                        && attr(line, "GROUP-ID").equals(audio) && !attr(line, "URI").isEmpty()) {
                    Resp a = playlist(new URL(new URL(m.url), attr(line, "URI")).toString(), headers);
                    s.hosts.addAll(a.hosts);
                    addHosts(s.hosts, playlistUrls(a.text, a.url));
                    break;
                }
            }
        } else {
            addHosts(s.hosts, playlistUrls(m.text, m.url));
        }
        if (s.hosts.size() > MAX_STREAM_HOSTS) throw new IOException("stream spread over too many hosts");
        for (String h : s.hosts) if (!resolvesPublic(h)) throw new IOException("stream uses a private address");
        return s;
    }
}
