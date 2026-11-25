package app.weathertv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;

/**
 * Opens the video website in a hidden, locked-down WebView on the TV, just long enough to
 * learn the address of its video stream, then wipes and destroys it.
 *
 * Treats the site as hostile:
 *  * Runs in Android's sandboxed WebView renderer process. No JavaScript bridge into the
 *    app, no file or content access, no camera/mic/location (all permission requests are
 *    denied, and the app only has the INTERNET permission anyway).
 *  * Downloads are ignored. Popups / new windows are refused. alert/confirm/prompt are
 *    suppressed. The page can't navigate away from the site or open other apps (intent:,
 *    market:, etc.); if it somehow ends up on another site anyway, it is stopped and sent
 *    back. Service workers are blocked.
 *  * Every request must be https to a public hostname that resolves to public internet
 *    addresses: nothing on your home network can be reached. Video players embedded from
 *    another site may load (they stay inside this same sandbox).
 *  * Video bytes are never downloaded: video/playlist requests are noted and cancelled, and
 *    media segments, images and fonts are cancelled too.
 *  * Afterwards its cookies, storage and cache are cleared and it is destroyed.
 *  * If the page crashes its renderer, only this hidden WebView is thrown away.
 */
final class Scraper {
    private Scraper() { }

    static final String TAG = "VideoTV";  // see these with: ./emulator.sh logs

    static void log(String what, String url) {
        Log.i(TAG, what + (url == null ? "" : ": " + (url.length() > 160 ? url.substring(0, 160) + "..." : url)));
    }

    private static final String[] VIDEO_EXT = {".m3u8", ".mp4"};
    private static final String[] BLOCK_EXT = {".ts", ".m4s", ".m4a", ".m4v", ".aac", ".cmfv", ".cmfa", ".mp3",
            ".webm", ".mkv", ".mov", ".avi", ".flv", ".vtt", ".srt", ".apk", ".exe", ".zip", ".dmg", ".bin",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf"};

    private static final String SCAN_JS = "(function(){var o=[];try{"
            + "document.querySelectorAll('video,source').forEach(function(e){var s=e.currentSrc||e.src;if(s)o.push(s)});"
            + "var h=document.documentElement.outerHTML.split('\\\\/').join('/');"
            + "var re=/https:\\/\\/[^\"'\\s<>\\\\]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>\\\\]*)?/gi,m;"
            + "while((m=re.exec(h))&&o.length<50)o.push(m[0]);"
            + "}catch(x){}return o})()";
    /** Center (CSS px) of the biggest visible player-like element, plus the page width; or null. */
    private static final String PLAYER_JS = "(function(){try{var b=null,a=0,W=innerWidth,H=innerHeight;"
            + "document.querySelectorAll('video,iframe,embed,object,canvas').forEach(function(e){"
            + "var r=e.getBoundingClientRect();if(r.width<200||r.height<120)return;"
            + "var s=Math.max(0,Math.min(r.right,W)-Math.max(r.left,0))*Math.max(0,Math.min(r.bottom,H)-Math.max(r.top,0));"
            + "if(s>a){a=s;b=r}});"
            + "if(!b)return null;return[(Math.max(b.left,0)+Math.min(b.right,W))/2,(Math.max(b.top,0)+Math.min(b.bottom,H))/2,W]"
            + "}catch(x){return null}})()";
    private static final String PLAY_JS = "(function(){try{document.querySelectorAll('video').forEach(function(v){"
            + "v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){})})}catch(x){}})()";

    static boolean endsWithAny(String path, String[] ext) {
        for (String e : ext) if (path.endsWith(e)) return true;
        return false;
    }

    static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
    }

    /** Block service workers app-wide (neither WebView needs them). Call once. */
    static void blockServiceWorkers() {
        if (Build.VERSION.SDK_INT < 24) return;
        ServiceWorkerController c = ServiceWorkerController.getInstance();
        c.getServiceWorkerWebSettings().setBlockNetworkLoads(true);
        c.getServiceWorkerWebSettings().setAllowFileAccess(false);
        c.getServiceWorkerWebSettings().setAllowContentAccess(false);
        c.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) { return blocked(); }
        });
    }

    /**
     * Load url (must be on `site`) hidden inside root, wait for its video stream, and return
     * every candidate video address found (HLS first). Call from a background thread.
     */
    static List<String> scrape(final Activity act, final FrameLayout root, final String url, final String site)
            throws InterruptedException {
        final Set<String> found = Collections.synchronizedSet(new LinkedHashSet<String>());
        final CountDownLatch loaded = new CountDownLatch(1);
        final AtomicBoolean crashed = new AtomicBoolean(false);
        final WebView[] holder = new WebView[1];

        act.runOnUiThread(new Runnable() { public void run() {
            WebView w = build(act, root, holder, site, url, found, loaded, crashed);
            holder[0] = w;
            // Hidden: underneath the app's own (opaque) WebView, never focused.
            root.addView(w, 0, new FrameLayout.LayoutParams(1280, 720));
            log("sandbox opened", url);
            w.loadUrl(url);
        }});

        // Let the page (and its player) load, then press play like a person would: once
        // always (the first click often only dismisses an overlay or ad), and a second time
        // if no new video link showed up. Popups those clicks trigger are refused.
        loaded.await(6, TimeUnit.SECONDS);
        Thread.sleep(1000);
        int before = found.size();
        click(act, holder, 1);
        if (!waitForVideo(found, before, crashed, 3000)) {
            click(act, holder, 2);
            // Something already loaded with the page? Then don't wait long for anything newer.
            waitForVideo(found, before, crashed, before > 0 ? 4000 : 15000);
        }
        Thread.sleep(1500); // let the player finish asking for its playlists

        // Also look for video links written in the page itself.
        final CountDownLatch scanned = new CountDownLatch(1);
        act.runOnUiThread(new Runnable() { public void run() {
            WebView w = holder[0];
            if (w == null) { scanned.countDown(); return; }
            w.evaluateJavascript(SCAN_JS, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    try {
                        JSONArray a = new JSONArray(v);
                        for (int i = 0; i < a.length() && i < 50; i++) {
                            String s = a.optString(i, "");
                            String path = Uri.parse(s).getPath();
                            if (s.startsWith("https://") && path != null
                                    && endsWithAny(path.toLowerCase(Locale.ROOT), VIDEO_EXT)) found.add(s);
                        }
                    } catch (Exception ignored) { }
                    scanned.countDown();
                }
            });
        }});
        scanned.await(4, TimeUnit.SECONDS);

        final CountDownLatch gone = new CountDownLatch(1);
        act.runOnUiThread(new Runnable() { public void run() {
            destroy(root, holder[0], true);
            holder[0] = null;
            gone.countDown();
        }});
        gone.await(5, TimeUnit.SECONDS);
        log("sandbox wiped and destroyed", null);

        // Try first what the page asked for after the clicks (the real video; something that
        // loaded with the page is more likely a preview or an ad), HLS before .mp4.
        List<String> all, out = new ArrayList<String>();
        synchronized (found) { all = new ArrayList<String>(found); }
        List<String> afterClick = all.subList(Math.min(before, all.size()), all.size());
        for (List<String> group : Arrays.asList(afterClick, all)) {
            for (String s : group) if (s.toLowerCase(Locale.ROOT).contains(".m3u8") && !out.contains(s)) out.add(s);
            for (String s : group) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Wait until a new video link is found (true), the time is up, or the sandbox crashed. */
    private static boolean waitForVideo(Set<String> found, int before, AtomicBoolean crashed, long ms)
            throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end && !crashed.get()) {
            if (found.size() > before) return true;
            Thread.sleep(200);
        }
        return found.size() > before;
    }

    /** Tap the middle of the page's player (or of the page), then ask its videos to play. */
    private static void click(final Activity act, final WebView[] holder, final int n) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        act.runOnUiThread(new Runnable() { public void run() {
            final WebView w = holder[0];
            if (w == null) { done.countDown(); return; }
            w.evaluateJavascript(PLAYER_JS, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    try {
                        if (holder[0] != w) return;
                        float x = w.getWidth() / 2f, y = w.getHeight() / 2f;
                        try {
                            JSONArray p = new JSONArray(v);
                            double scale = w.getWidth() / Math.max(1.0, p.getDouble(2));
                            x = (float) Math.max(1, Math.min(w.getWidth() - 1, p.getDouble(0) * scale));
                            y = (float) Math.max(1, Math.min(w.getHeight() - 1, p.getDouble(1) * scale));
                            log("click " + n + " on the player", null);
                        } catch (Exception noPlayer) {
                            log("click " + n + " in the middle (no player found on the page)", null);
                        }
                        long t = SystemClock.uptimeMillis();
                        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                        MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0);
                        w.dispatchTouchEvent(down);
                        w.dispatchTouchEvent(up);
                        down.recycle();
                        up.recycle();
                        w.evaluateJavascript(PLAY_JS, null);
                    } finally {
                        done.countDown();
                    }
                }
            });
        }});
        done.await(3, TimeUnit.SECONDS);
    }

    private static void destroy(FrameLayout root, WebView w, boolean alive) {
        if (w == null) return;
        try {
            if (alive) {
                w.stopLoading();
                w.loadUrl("about:blank");
            }
            root.removeView(w);
            if (alive) {
                w.clearHistory();
                w.clearFormData();
                w.clearCache(true);
            }
            w.destroy();
        } catch (Exception ignored) { }
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView build(Activity act, final FrameLayout root, final WebView[] holder, final String site,
                                 final String startUrl, final Set<String> found, final CountDownLatch loaded,
                                 final AtomicBoolean crashed) {
        final AtomicInteger sentBack = new AtomicInteger(0);
        final WebView w = new WebView(act);
        w.setFocusable(false);
        w.setFocusableInTouchMode(false);
        w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);          // the site's player needs it; it runs sandboxed
        s.setDomStorageEnabled(true);          // wiped afterwards
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);     // window.open goes to onCreateWindow, which refuses
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setBlockNetworkImage(true);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);

        w.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String u, String ua, String cd, String mime, long len) { log("BLOCKED download", u); }
        });

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(u.getScheme()) || !Net.isPublicHostname(host)) {   // intent:, market:, ...
                    log("BLOCKED navigation", u.toString());
                    return true;
                }
                if (r.isForMainFrame() && !site.equals(Net.siteOf(host))) {             // stay on the site
                    log("BLOCKED redirect to another site", u.toString());
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("data".equals(scheme) || "blob".equals(scheme)) return null;
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(scheme) || !Net.isPublicHostname(host) || !Net.resolvesPublic(host)) {
                    log("BLOCKED (not https, a home-network address, or the site doesn't exist)", u.toString());
                    return blocked();
                }
                String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
                if (endsWithAny(path, VIDEO_EXT)) {         // the prize: note it, don't download it
                    if (found.size() < 50 && found.add(u.toString())) log("video link found (not downloaded)", u.toString());
                    return blocked();
                }
                if (endsWithAny(path, BLOCK_EXT)) return blocked();
                if (r.isForMainFrame() && !site.equals(Net.siteOf(host))) {
                    log("BLOCKED page from another site", u.toString());
                    return blocked();
                }
                return null;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // The page crashed its sandbox: throw this WebView away (it must not be used
                // again), keep the app running.
                log("sandbox crashed; discarded", null);
                if (holder[0] == v) holder[0] = null;
                destroy(root, v, false);
                crashed.set(true);
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap icon) {
                // Last line of defence: if the page ended up on another site anyway, stop it
                // and send it back to the video page (a few times at most).
                if (u == null || u.startsWith("about:")) return;
                String host = Uri.parse(u).getHost();
                if (host != null && site.equals(Net.siteOf(host))) return;
                v.stopLoading();
                if (sentBack.incrementAndGet() > 3) {
                    log("LEFT the site again; giving up on this page", u);
                    v.loadUrl("about:blank");
                } else {
                    log("LEFT the site; sent back", u);
                    if (v.canGoBack()) v.goBack(); else v.loadUrl(startUrl);
                }
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (u != null && !u.startsWith("about:")) loaded.countDown();
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message m) {
                log("BLOCKED popup / new window", null);
                return false;
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) { log("DENIED permission request", r.getOrigin().toString()); r.deny(); }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) { log("BLOCKED alert dialog", null); r.cancel(); return true; }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) { r.cancel(); return true; }

            @Override
            public boolean onJsBeforeUnload(WebView v, String url, String msg, JsResult r) { r.cancel(); return true; }
        });
        return w;
    }
}
