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
            allowedHosts.clear();
            allowedHosts.addAll(BASE_HOSTS);
            allowedHosts.addAll(hosts);
            runOnUiThread(new Runnable() { public void run() {
                web.loadUrl(APP_URL + "?t=" + System.currentTimeMillis());
                web.requestFocus();
            }});
        }}).start();
    }

    private static Set<String> fetchAllowedHosts() {
        Set<String> out = new HashSet<String>();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(APP_URL + "videos.json?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return out;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 2_000_000) buf.write(b, 0, n);
            in.close();
            JSONArray arr = new JSONObject(buf.toString("UTF-8")).optJSONArray("allowedHosts");
            for (int i = 0; arr != null && i < arr.length() && i < 20; i++) {
                String h = arr.optString(i, "").toLowerCase();
                if (h.matches(HOST_RE)) out.add(h);
            }
        } catch (Exception ignored) { }
        return out;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) { reload(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() { finish(); }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
