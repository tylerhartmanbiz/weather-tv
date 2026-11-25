package app.weathertv;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Thin, locked-down shell. The whole UI is docs/index.html on GitHub Pages, so changes
 * pushed to the repo show up on the next launch (or press Menu to reload) with no reinstall.
 *
 * Safety: the WebView can only talk over https to our own page, the pinned player library,
 * and the video/image hosts that scrapper.py lists in videos.json ("allowedHosts"), so
 * changing the scraped website never needs a new APK. Every other request is refused.
 * Navigation to any other page/app is blocked, popups/new windows are refused (and closed
 * if one ever appears), JS alert/confirm/prompt dialogs are suppressed, downloads are
 * ignored, and it has no access to the TV's files. Videos are streamed, never saved.
 */
public class MainActivity extends Activity {
    private static final String APP_URL = "https://tylerhartmanbiz.github.io/weather-tv/";
    private static final Set<String> BASE_HOSTS = new HashSet<String>(Arrays.asList(
            "tylerhartmanbiz.github.io", // the app page + videos.json
            "cdn.jsdelivr.net"));        // pinned hls.js player (integrity-checked)
    private static final String HOST_RE = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$";
    // Search server on your computer (from docs/config.json). Only private LAN addresses accepted.
    private static final String LAN_RE =
            "^http://(10\\.\\d+|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.\\d+\\.\\d+:\\d{2,5}$";
    private static final String API_PREFIX = "/weather-tv/api/";
    private volatile String searchServer = null;
    private final Set<String> allowedHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

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
            public void onDownloadStart(String u, String ua, String cd, String mime, long len) {
                // Ignore all downloads.
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true; // never navigate to another page, site, or app (intent://, market://...)
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("tylerhartmanbiz.github.io".equals(u.getHost()) && u.getPath() != null
                        && u.getPath().startsWith(API_PREFIX) && "GET".equals(r.getMethod())) {
                    return proxyToSearchServer(u);
                }
                if ("https".equals(u.getScheme()) && allowedHosts.contains(u.getHost())) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null,
                        new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message m) {
                return false; // refuse every popup / new window
            }

            @Override
            public void onCloseWindow(WebView w) {
                if (w != web) w.destroy();
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsBeforeUnload(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }
        });

        reload();
    }

    /** Refresh the host allowlist from videos.json, then (re)load the app page. */
    private void reload() {
        new Thread(new Runnable() { public void run() {
            Set<String> hosts = fetchAllowedHosts();
            String server = fetchSearchServer();
            searchServer = server;
            allowedHosts.clear();
            allowedHosts.addAll(BASE_HOSTS);
            allowedHosts.addAll(hosts);
            runOnUiThread(new Runnable() { public void run() {
                web.loadUrl(APP_URL + "?t=" + System.currentTimeMillis());
                web.requestFocus();
            }});
        }}).start();
    }

    private static String get(String url, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 2_000_000) buf.write(b, 0, n);
            in.close();
            return buf.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> hostsFrom(String json) {
        Set<String> out = new HashSet<String>();
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("allowedHosts");
            for (int i = 0; arr != null && i < arr.length() && i < 20; i++) {
                String h = arr.optString(i, "").toLowerCase();
                if (h.matches(HOST_RE)) out.add(h);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Set<String> fetchAllowedHosts() {
        String s = get(APP_URL + "videos.json?t=" + System.currentTimeMillis(), 8000);
        return s == null ? new HashSet<String>() : hostsFrom(s);
    }

    private static String fetchSearchServer() {
        try {
            String s = get(APP_URL + "config.json?t=" + System.currentTimeMillis(), 8000);
            String addr = s == null ? "" : new JSONObject(s).optString("searchServer", "");
            return addr.matches(LAN_RE) ? addr : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Forward /api/... to the search server on your computer; allow the hosts it returns. */
    private WebResourceResponse proxyToSearchServer(Uri u) {
        String server = searchServer;
        String body = null;
        if (server != null) {
            String q = u.getEncodedQuery();
            body = get(server + "/api/" + u.getPath().substring(API_PREFIX.length()) + (q == null ? "" : "?" + q), 90000);
        }
        if (body == null) body = "{\"videos\":[],\"error\":\"search server unreachable\"}";
        else allowedHosts.addAll(hostsFrom(body));
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-store");
        try {
            return new WebResourceResponse("application/json", "utf-8", 200, "OK", h,
                    new ByteArrayInputStream(body.getBytes("UTF-8")));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) { reload(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
        // Back closes the search box if it's open, otherwise exits the app.
        web.evaluateJavascript("(function(){try{return window.onTvBack&&window.onTvBack()?'1':'0'}catch(e){return '0'}})()",
                new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String r) { if (!"\"1\"".equals(r)) finish(); }
                });
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
