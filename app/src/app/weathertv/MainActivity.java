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

/**
 * Thin, locked-down shell that does everything on the TV (no computer needed).
 *
 * The whole UI is docs/index.html on GitHub Pages, and the video website's address is in
 * docs/config.json, so changes pushed to the repo show up on the next launch (or press
 * Menu to reload) with no reinstall.
 *
 * The page asks this shell for two things (same-origin URLs it intercepts):
 *   api/fetch?url=...[&post=json]   read JSON from an approved data host (IMDb), as text
 *   api/open?url=...                open the video site hidden + sandboxed (Scraper) and
 *                                   return its checked video stream link
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
    private final Object scrapeLock = new Object();

    private FrameLayout root;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
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
                if ("https".equals(u.getScheme()) && allowedHosts.contains(u.getHost())) return null;
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
            try {
                JSONObject cfg = new JSONObject(appFile("config.json"));
                for (String k : new String[]{"openUrl", "openUrlTv"}) {
                    String t = cfg.optString(k, "");
                    if (t.isEmpty()) continue;
                    String host = Net.checkUrl(t.replace("{imdb_id}", "tt0000000")
                            .replace("{season}", "1").replace("{episode}", "1"));
                    sites.add(Net.siteOf(host));
                }
                JSONArray d = cfg.optJSONArray("dataHosts");
                for (int i = 0; d != null && i < d.length() && i < 20; i++) {
                    String h = d.optString(i, "").toLowerCase(Locale.ROOT);
                    if (Net.isPublicHostname(h)) data.add(h);
                }
            } catch (Exception ignored) { }
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
            runOnUiThread(new Runnable() { public void run() {
                web.loadUrl(APP_URL + "?t=" + System.currentTimeMillis());
                web.requestFocus();
            }});
        }}).start();
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

    /** Open the video site hidden + sandboxed, then check the stream it uses. */
    private JSONObject open(String url) throws Exception {
        String host = Net.checkUrl(url == null ? "" : url);
        String site = Net.siteOf(host);
        if (!openSites.contains(site)) throw new Exception("site not in config.json");
        JSONObject out = new JSONObject();
        List<String> candidates;
        synchronized (scrapeLock) { // one hidden browser at a time
            candidates = Scraper.scrape(this, root, url, site);
        }
        int tried = 0;
        for (String src : candidates) {
            if (++tried > 8) break;
            try {
                Net.Stream s = Net.inspect(src);
                allowedHosts.addAll(s.hosts); // the player may now stream from exactly these hosts
                JSONObject v = new JSONObject();
                v.put("src", src);
                v.put("quality", s.quality);
                out.put("videos", new JSONArray().put(v));
                out.put("allowedHosts", new JSONArray(s.hosts));
                return out;
            } catch (Exception ignored) {
                // unsafe or not a real stream: try the next one
            }
        }
        out.put("videos", new JSONArray());
        out.put("error", candidates.isEmpty() ? "no video found on the page" : "no playable video found");
        return out;
    }

    // ---------- remote ----------

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
