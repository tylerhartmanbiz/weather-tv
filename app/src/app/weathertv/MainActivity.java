package app.weathertv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;

/**
 * Thin, locked-down shell that does everything on the TV (no computer needed).
 *
 * The whole UI is docs/index.html on GitHub Pages, and the video website's address is in
 * docs/config.json, so changes pushed to the repo show up on the next launch (or press
 * Menu to reload) with no reinstall.
 *
 * The page asks this shell for two things (same-origin URLs it intercepts):
 *   api/fetch?url=...[&post=json]   read JSON from an approved data host (IMDb), as text
 *   api/open?url=...                show the video site (sandboxed, Scraper) for you to
 *                                   start the video; return its checked video stream link
 *
 * Safety: the app's own WebView only talks https to this app page, the pinned player
 * library, IMDb/TVmaze image hosts, and the video hosts a checked stream needs. Everything else is
 * refused. It never loads the video website itself. Navigation to any other page/app is
 * blocked, popups refused, dialogs suppressed, downloads ignored, no file access. The app
 * has only the INTERNET permission, and no cleartext (http) traffic at all.
 */
public class MainActivity extends Activity {
    private static final String APP_URL = "https://tylerhartmanbiz.github.io/weather-tv/";
    private static final String APP_HOST = "tylerhartmanbiz.github.io";
    private static final String API_PREFIX = "/weather-tv/api/";
    private static final Set<String> BASE_HOSTS = new HashSet<String>(Arrays.asList(
            APP_HOST,               // the app page, config.json, videos.json
            "cdn.jsdelivr.net",     // pinned hls.js player (integrity-checked)
            "static.tvmaze.com",    // TV show posters / cast photos
            "m.media-amazon.com")); // IMDb posters / cast photos
    private static final List<String> DEFAULT_DATA_HOSTS = Arrays.asList(
            "v3.sg.media-imdb.com", "caching.graphql.imdb.com", "api.tvmaze.com");

    private final Set<String> allowedHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> dataHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> openSites = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Hosts of the checked stream: the app fetches these itself, as the video site would. */
    private final Set<String> streamHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile Map<String, String> streamHeaders = new HashMap<String, String>();
    private volatile String configError = null;
    private final Object scrapeLock = new Object();

    private FrameLayout root;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        // Test methods (testing websites/README.md): only ./emulator.sh -N passes this extra.
        Methods.set(getIntent() == null ? -1 : getIntent().getIntExtra("method", -1));
        if (Methods.testing()) {
            WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect on the Mac
            Scraper.log("web engine: Chrome " + Scraper.webViewMajor(), null);
        }
        Scraper.blockServiceWorkers();
        createWebView();
        reload();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true); // routes window.open to onCreateWindow, which blocks it
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE); // always fetch the latest app page

        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String u, String ua, String cd, String mime, long len) { /* ignore */ }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true; // never navigate to another page, site, or app (intent://, market://...)
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String path = u.getPath() == null ? "" : u.getPath();
                if (APP_HOST.equals(u.getHost()) && path.startsWith(API_PREFIX) && "GET".equals(r.getMethod())) {
                    return api(path.substring(API_PREFIX.length()), u);
                }
                if ("https".equals(u.getScheme()) && streamHosts.contains(u.getHost()) && "GET".equals(r.getMethod())) {
                    return stream(u.toString(), r.getRequestHeaders());
                }
                if ("https".equals(u.getScheme()) && allowedHosts.contains(u.getHost())) return null;
                if (!"tylerhartmanbiz.github.io".equals(u.getHost())) Scraper.log("app: blocked", u.toString());
                return Scraper.blocked();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // A web page crashed the (shared) WebView renderer: rebuild instead of crashing.
                root.removeAllViews();
                v.destroy();
                createWebView();
                reload();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message m) { return false; }

            @Override
            public void onPermissionRequest(PermissionRequest r) { r.deny(); }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsBeforeUnload(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }
        });
    }

    /** Re-read config.json + videos.json, then (re)load the app page. */
    private void reload() {
        new Thread(new Runnable() { public void run() {
            Set<String> hosts = new HashSet<String>(BASE_HOSTS);
            Set<String> data = new HashSet<String>(DEFAULT_DATA_HOSTS);
            Set<String> sites = new HashSet<String>();
            String error = null;
            try {
                JSONObject cfg = config();
                if (cfg.optString("openUrl", "").isEmpty() && cfg.optString("openUrlTv", "").isEmpty()) {
                    throw new Exception("it has no \"openUrl\"");
                }
                for (String k : new String[]{"openUrl", "openUrlTv"}) {
                    String t = cfg.optString(k, "");
                    if (t.isEmpty()) continue;
                    String host;
                    try {
                        host = Net.checkUrl(t.replace("{imdb_id}", "tt0000000")
                                .replace("{season}", "1").replace("{episode}", "1"));
                    } catch (Exception e) {
                        throw new Exception("its " + k + " isn't an https address the TV accepts");
                    }
                    sites.add(Net.siteOf(host));
                }
                JSONArray d = cfg.optJSONArray("dataHosts");
                for (int i = 0; d != null && i < d.length() && i < 20; i++) {
                    String h = d.optString(i, "").toLowerCase(Locale.ROOT);
                    if (Net.isPublicHostname(h)) data.add(h);
                }
            } catch (Exception e) {
                error = "Your settings (config-noclaude.json) can't be used: " + e.getMessage();
                Scraper.log(error, null);
            }
            try {
                JSONArray arr = new JSONObject(appFile("videos.json")).optJSONArray("allowedHosts");
                for (int i = 0; arr != null && i < arr.length() && i < 20; i++) {
                    String h = arr.optString(i, "").toLowerCase(Locale.ROOT);
                    if (Net.isPublicHostname(h)) hosts.add(h);
                }
            } catch (Exception ignored) { }
            allowedHosts.clear(); allowedHosts.addAll(hosts);
            dataHosts.clear(); dataHosts.addAll(data);
            openSites.clear(); openSites.addAll(sites);
            configError = error;
            runOnUiThread(new Runnable() { public void run() {
                web.loadUrl(APP_URL + "?t=" + System.currentTimeMillis());
                web.requestFocus();
            }});
        }}).start();
    }

    /**
     * Your settings: docs/config-noclaude.json. docs/config.json (the example) is used only if
     * that file doesn't exist; a mistake in it is reported, never silently replaced by the example.
     */
    private static JSONObject config() throws Exception {
        Net.Resp r = Net.fetch(APP_URL + "config-noclaude.json?t=" + System.currentTimeMillis(), null,
                "application/json", 500_000, null);
        if (r.status == 404) return parseSettings(appFile("config.json"));
        if (r.status != 200) throw new Exception("it couldn't be downloaded (HTTP " + r.status + ")");
        try {
            return parseSettings(r.text);
        } catch (Exception e) {
            throw new Exception("it has a mistake in it, so it isn't valid JSON");
        }
    }

    /** Lenient JSON: allows // and /* *\/ comments, trailing commas, curly quotes, missing { }. */
    static JSONObject parseSettings(String text) throws Exception {
        text = text.replace('\u201C', '"').replace('\u201D', '"').replace("\uFEFF", "");
        StringBuilder out = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i), nx = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (str) {
                out.append(ch);
                if (ch == '\\' && nx != 0) { out.append(nx); i++; } else if (ch == '"') str = false;
            } else if (ch == '"') {
                str = true;
                out.append(ch);
            } else if (ch == '/' && nx == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (ch == '/' && nx == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else {
                out.append(ch);
            }
        }
        String t = out.toString().trim().replaceAll(",\\s*([}\\]])", "$1").replaceAll(",\\s*$", "");
        if (!t.startsWith("{")) t = "{" + t + "}";
        return new JSONObject(t);
    }

    private static String appFile(String name) throws Exception {
        Net.Resp r = Net.fetch(APP_URL + name + "?t=" + System.currentTimeMillis(), null, "application/json", 500_000, null);
        if (r.status != 200) throw new Exception("HTTP " + r.status);
        return r.text;
    }

    // ---------- api/ for the page ----------

    private WebResourceResponse api(String name, Uri u) {
        int code = 200;
        String type = "application/json";
        String body;
        try {
            if ("fetch".equals(name)) {
                String url = u.getQueryParameter("url");
                String post = u.getQueryParameter("post");
                String host = Net.checkUrl(url == null ? "" : url);
                if (!dataHosts.contains(host)) throw new Exception("host not allowed");
                Net.Resp r = Net.fetch(url, post, "application/json", 4_000_000, null);
                code = r.status;
                body = r.text;
            } else if ("open".equals(name)) {
                body = open(u.getQueryParameter("url")).toString();
            } else {
                code = 404;
                body = "{\"error\":\"not found\"}";
            }
        } catch (Exception e) {
            code = 502;
            body = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-store");
        try {
            return new WebResourceResponse(type, "utf-8", code < 100 || code > 599 ? 502 : code,
                    code == 200 ? "OK" : "Error", h, new ByteArrayInputStream(body.getBytes("UTF-8")));
        } catch (Exception e) {
            return Scraper.blocked();
        }
    }

    /**
     * Show the video site (sandboxed) for you to use with the remote. The first video link it
     * asks for that passes the safety check closes it and is returned to the page to play.
     */
    private JSONObject open(String url) throws Exception {
        if (configError != null) throw new Exception(configError);
        final String host = Net.checkUrl(url == null ? "" : url);
        String site = Net.siteOf(host);
        if (!openSites.contains(site)) {
            throw new Exception("That website isn't the one in your settings. Press Menu \u2630 to reload them.");
        }
        final JSONObject out = new JSONObject();
        final int[] tried = {0};
        String result;
        synchronized (scrapeLock) { // one website at a time
            result = Scraper.browse(this, root, url, site, new Scraper.Checker() {
                public boolean accept(String src) {
                    if (++tried[0] > (Methods.on(Methods.SNIFF) ? 40 : 20)) return false;
                    try {
                        // Ask for the stream the way the video site's own player does.
                        Map<String, String> h = new HashMap<String, String>();
                        if (!Scraper.browserUa.isEmpty()) h.put("User-Agent", Scraper.browserUa);
                        String page = Methods.follow() && Scraper.lastMainHost != null ? Scraper.lastMainHost : host;
                        h.put("Referer", "https://" + page + "/");
                        h.put("Origin", "https://" + page);
                        Map<String, String> seen = Scraper.seenHeaders.get(src);
                        if (Methods.on(Methods.REAL_HEADERS) && seen != null) {   // test method 12
                            for (Map.Entry<String, String> e : seen.entrySet()) {
                                String k = e.getKey().toLowerCase(Locale.ROOT);
                                if (k.equals("referer")) h.put("Referer", e.getValue());
                                else if (k.equals("origin")) h.put("Origin", e.getValue());
                                else if (k.equals("user-agent")) h.put("User-Agent", e.getValue());
                            }
                            if (!seen.keySet().toString().toLowerCase(Locale.ROOT).contains("origin")) h.remove("Origin");
                            String ck = android.webkit.CookieManager.getInstance().getCookie(src);
                            if (ck != null && !ck.isEmpty()) h.put("Cookie", ck);
                            Scraper.log("using the website's own headers for the stream: " + h.keySet(), null);
                        }
                        Net.Stream s = Net.inspect(src, h);
                        streamHeaders = h;
                        streamHosts.clear();
                        streamHosts.addAll(s.hosts); // the player may now stream from exactly these hosts
                        Scraper.log("PLAYING " + (s.quality.isEmpty() ? "" : s.quality + " ") + "from hosts " + s.hosts, src);
                        JSONObject v = new JSONObject();
                        v.put("src", src);
                        v.put("quality", s.quality);
                        out.put("videos", new JSONArray().put(v));
                        out.put("allowedHosts", new JSONArray(s.hosts));
                        return true;
                    } catch (Exception e) {
                        Scraper.log("rejected (" + e.getMessage() + ")", src); // unsafe or not a real stream
                        return false;
                    }
                }
            });
        }
        if (out.has("videos")) return out;
        out.put("videos", new JSONArray());
        out.put("error", "cancelled".equals(result) ? "Cancelled."
                : "crashed".equals(result) ? "The website crashed. Try again."
                : tried[0] > 0 ? "The website's video couldn't be played safely."
                : "No video was started on the website.");
        return out;
    }

    /**
     * The player's request for the checked stream: fetched here with the video site's headers
     * (Referer etc.), so stream servers that only serve the site itself still work, and handed
     * to the player as it arrives (nothing is saved). Only the checked stream hosts, https,
     * public addresses; redirects checked hop by hop.
     */
    private WebResourceResponse stream(String url, Map<String, String> req) {
        try {
            Map<String, String> h = new HashMap<String, String>(streamHeaders);
            for (Map.Entry<String, String> e : req.entrySet()) {
                if ("range".equalsIgnoreCase(e.getKey())) h.put("Range", e.getValue());
            }
            HttpsURLConnection c = Net.openStream(url, h, streamHosts);
            int code = c.getResponseCode();
            String type = c.getContentType() == null ? "application/octet-stream" : c.getContentType().split(";")[0].trim();
            Map<String, String> out = new HashMap<String, String>();
            out.put("Access-Control-Allow-Origin", "*");
            out.put("Cache-Control", "no-store");
            for (String k : new String[]{"Content-Range", "Content-Length", "Accept-Ranges"}) {
                String v = c.getHeaderField(k);
                if (v != null) out.put(k, v);
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new ByteArrayInputStream(new byte[0]);
            if (code < 200 || code > 599 || (code >= 300 && code < 400)) code = 502;
            if (code >= 400) Scraper.log("stream server answered HTTP " + code, url);
            String msg = c.getResponseMessage();
            return new WebResourceResponse(type, null, code, msg == null || msg.isEmpty() ? "OK" : msg, out, in);
        } catch (Exception e) {
            Scraper.log("stream request refused (" + e.getMessage() + ")", url);
            return Scraper.blocked();
        }
    }

    // ---------- remote ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // While the video website is on screen, the remote drives its cursor (Scraper).
        if (Scraper.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) { reload(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
        // Back steps back through the app's screens; on the first screen it exits.
        web.evaluateJavascript("(function(){try{return window.onTvBack&&window.onTvBack()?'1':'0'}catch(e){return '0'}})()",
                new ValueCallback<String>() {
                    public void onReceiveValue(String r) { if (!"\"1\"".equals(r)) finish(); }
                });
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
