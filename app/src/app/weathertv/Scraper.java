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

        // Exactly like a person: wait for the page to load, click the middle of the screen,
        // wait 5 s, click the middle again, wait 10 s. Look for the video after every step and
        // stop as soon as it's found. Popups / other sites the clicks try to open are refused.
        // (The page's network requests are watched the whole time, see shouldInterceptRequest.)
        loaded.await(10, TimeUnit.SECONDS);
        if (!lookFor(act, holder, found, "after loading")) {
            click(act, holder, 1);
            if (!lookFor(act, holder, found, "after click 1")
                    && !(waitForVideo(found, crashed, 5000) | lookFor(act, holder, found, "5 s after click 1"))) {
                click(act, holder, 2);
                if (!lookFor(act, holder, found, "after click 2")
                        && !(waitForVideo(found, crashed, 10000) | lookFor(act, holder, found, "10 s after click 2"))) {
                    log("NO VIDEO FOUND after loading, 2 clicks and 15 s", null);
                }
            }
        }
        if (!found.isEmpty()) Thread.sleep(1500); // let the player finish asking for its playlists

        final CountDownLatch gone = new CountDownLatch(1);
        act.runOnUiThread(new Runnable() { public void run() {
            destroy(root, holder[0], true);
            holder[0] = null;
            gone.countDown();
        }});
        gone.await(5, TimeUnit.SECONDS);
        log("sandbox wiped and destroyed", null);

        List<String> out = new ArrayList<String>();
        synchronized (found) {
            for (String s : found) if (s.toLowerCase(Locale.ROOT).contains(".m3u8")) out.add(s);
            for (String s : found) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Wait until a video link is found (true), the time is up, or the sandbox crashed. */
    private static boolean waitForVideo(Set<String> found, AtomicBoolean crashed, long ms) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end && !crashed.get() && found.isEmpty()) Thread.sleep(200);
        return !found.isEmpty();
    }

    /** Search for the video now: links the page already asked for, plus links in its HTML. */
    private static boolean lookFor(final Activity act, final WebView[] holder, final Set<String> found, String when)
            throws InterruptedException {
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
                            if (s.startsWith("https://") && path != null && found.size() < 50
                                    && endsWithAny(path.toLowerCase(Locale.ROOT), VIDEO_EXT)
                                    && found.add(s)) log("video link found in the page", s);
                        }
                    } catch (Exception ignored) { }
                    scanned.countDown();
                }
            });
        }});
        scanned.await(3, TimeUnit.SECONDS);
        boolean got = !found.isEmpty();
        log("looked for the video " + when + ": " + (got ? found.size() + " link(s)" : "none yet"), null);
        return got;
    }

    /** Tap the middle of the screen, then ask the page's videos to play. */
    private static void click(final Activity act, final WebView[] holder, final int n) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        act.runOnUiThread(new Runnable() { public void run() {
            WebView w = holder[0];
            if (w != null) {
                log("click " + n + " in the middle of the screen", null);
                long t = SystemClock.uptimeMillis();
                float x = w.getWidth() / 2f, y = w.getHeight() / 2f;
                MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0);
                w.dispatchTouchEvent(down);
                w.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
                w.evaluateJavascript(PLAY_JS, null);
            }
            done.countDown();
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
