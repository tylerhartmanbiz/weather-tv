package app.weathertv;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Thin, locked-down shell. The whole UI is docs/index.html on GitHub Pages, so changes
 * pushed to the repo show up on the next launch (or press Menu to reload) with no reinstall.
 *
 * Safety: the WebView can only talk to the hosts below over https. Every other request is
 * refused, navigation away from the app page is blocked, popups are blocked, downloads are
 * ignored, and it has no access to the TV's files. The video is streamed, never saved.
 */
public class MainActivity extends Activity {
    private static final String APP_URL = "https://tylerhartmanbiz.github.io/weather-tv/";
    private static final Set<String> ALLOWED_HOSTS = new HashSet<String>(Arrays.asList(
            "tylerhartmanbiz.github.io", // the app page + videos.json
            "cdn.jsdelivr.net",          // pinned hls.js player (integrity-checked)
            "v.w-x.co",                  // video streams
            "s.w-x.co"));                // thumbnails

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
                return true; // never navigate away from the app page
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && ALLOWED_HOSTS.contains(u.getHost())) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null,
                        new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message m) {
                return false;
            }
        });

        reload();
    }

    private void reload() {
        web.loadUrl(APP_URL + "?t=" + System.currentTimeMillis());
        web.requestFocus();
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
