package app.weathertv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
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
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;

/**
 * Shows the video website on the TV in a locked-down WebView that YOU use with the remote
 * (an on-screen cursor: arrows move, OK clicks, Back cancels). Nothing is clicked for you.
 * As soon as the site asks for its video stream, the link is checked; the first one that's
 * safe closes the website (wiped and destroyed) and plays in the app's own player.
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
 *  * Video bytes are never downloaded by the website: video/playlist requests are noted and
 *    cancelled, and media segments are cancelled too.
 *  * Afterwards its cookies, storage and cache are cleared and it is destroyed.
 *  * If the page crashes its renderer, only this WebView is thrown away.
 */
final class Scraper {
    private Scraper() { }

    static final String TAG = "VideoTV";  // see these with: ./emulator.sh logs

    /** The browser identity the website sees; the safety check and player use it too. */
    static volatile String browserUa = "";

    /** "Chrome 91"-style name of this device's web engine, and its major version (0 if unknown). */
    static int webViewMajor() {
        try {
            if (Build.VERSION.SDK_INT < 26) return 0;
            PackageInfo p = WebView.getCurrentWebViewPackage();
            return p == null ? 0 : Integer.parseInt(p.versionName.split("\\.")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    static void log(String what, String url) {
        Log.i(TAG, what + (url == null ? "" : ": " + (url.length() > 160 ? url.substring(0, 160) + "..." : url)));
    }

    private static final String[] VIDEO_EXT = {".m3u8", ".mp4"};
    private static final String[] BLOCK_EXT = {".ts", ".m4s", ".m4a", ".m4v", ".aac", ".cmfv", ".cmfa", ".mp3",
            ".webm", ".mkv", ".mov", ".avi", ".flv", ".vtt", ".srt", ".apk", ".exe", ".zip", ".dmg", ".bin"};

    private static final String SCAN_JS = "(function(){var o=[];try{"
            + "document.querySelectorAll('video,source').forEach(function(e){var s=e.currentSrc||e.src;if(s)o.push(s)});"
            + "var h=document.documentElement.outerHTML.split('\\\\/').join('/');"
            + "var re=/https:\\/\\/[^\"'\\s<>\\\\]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>\\\\]*)?/gi,m;"
            + "while((m=re.exec(h))&&o.length<50)o.push(m[0]);"
            + "}catch(x){}return o})()";

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

    /** Decides whether a found video link is the one to play (runs on a background thread). */
    interface Checker { boolean accept(String src); }

    /** The website currently on screen, if any. Only touched on the UI thread (except `ended`). */
    private static final class Session {
        WebView web;
        CursorView cursor;
        TextView hint;
        volatile String ended;   // null while open; then "cancelled" or "crashed"
    }

    private static Session current;

    /**
     * Show url (must be on `site`) full screen for you to use with the remote, and return once
     * `checker` accepted a video link the site asked for ("ok"), or you pressed Back
     * ("cancelled"), the site crashed ("crashed"), or 15 minutes passed ("timeout").
     * Call from a background thread.
     */
    static String browse(final Activity act, final FrameLayout root, final String url, final String site,
                         Checker checker) throws InterruptedException {
        final Set<String> found = Collections.synchronizedSet(new LinkedHashSet<String>());
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        final Session s = new Session();
        final CountDownLatch shown = new CountDownLatch(1);

        act.runOnUiThread(new Runnable() { public void run() {
            s.web = build(act, root, s, site, url, found, queue);
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            root.addView(s.web, full);
            s.cursor = new CursorView(act);
            root.addView(s.cursor, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            s.hint = new TextView(act);
            s.hint.setTextColor(Color.WHITE);
            s.hint.setTextSize(17);
            s.hint.setBackgroundColor(0xCC000000);
            s.hint.setPadding(30, 14, 30, 14);
            FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            bottom.bottomMargin = 30;
            root.addView(s.hint, bottom);
            int engine = webViewMajor();
            hint(s, "Start the video on the website.   Move: \u25C0 \u25B2 \u25BC \u25B6   Click: OK   Cancel: Back"
                    + (engine > 0 && engine < 110 ? "\nThis device's web engine is old (Chrome " + engine
                    + "), so some websites won't work. Update the device's software." : ""));
            log("web engine: Chrome " + engine, null);
            current = s;
            log("website shown", url);
            s.web.loadUrl(url);
            shown.countDown();
        }});
        shown.await(5, TimeUnit.SECONDS);

        String result = "timeout";
        long end = SystemClock.uptimeMillis() + 15 * 60 * 1000L;
        int tick = 0;
        while (SystemClock.uptimeMillis() < end) {
            if (s.ended != null) { result = s.ended; break; }
            String src = queue.poll(1, TimeUnit.SECONDS);
            if (src == null) {
                if (++tick % 2 == 0) scanPage(act, s, found, queue);   // links written in the page
                continue;
            }
            setHint(act, s, "Video found, checking it\u2026");
            if (checker.accept(src)) { result = "ok"; break; }
            setHint(act, s, "That video link can't be played safely. Keep trying, or Back to cancel.");
        }
        log("website closed (" + result + ")", null);

        final CountDownLatch gone = new CountDownLatch(1);
        act.runOnUiThread(new Runnable() { public void run() {
            if (current == s) current = null;
            root.removeView(s.cursor);
            root.removeView(s.hint);
            destroy(root, s.web, !"crashed".equals(s.ended));
            s.web = null;
            gone.countDown();
        }});
        gone.await(5, TimeUnit.SECONDS);
        log("website wiped and destroyed", null);
        return result;
    }

    /** Remote keys while the website is on screen (UI thread). True = handled here. */
    static boolean handleKey(KeyEvent e) {
        Session s = current;
        if (s == null || s.web == null || s.ended != null) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        float step = 14 + Math.min(e.getRepeatCount() * 8, 60);   // faster while held
        CursorView c = s.cursor;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  if (down) move(s, -step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(s, step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP:    if (down) move(s, 0, -step); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  if (down) move(s, 0, step); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && e.getRepeatCount() == 0) tap(s.web, c.x, c.y);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) { s.ended = "cancelled"; hint(s, "Closing\u2026"); }
                return true;
            default:
                return true;   // nothing else reaches the app while the website is shown
        }
    }

    /** Move the cursor; pushing past an edge scrolls the page instead. */
    private static void move(Session s, float dx, float dy) {
        CursorView c = s.cursor;
        float w = c.getWidth(), h = c.getHeight(), m = 8;
        float nx = c.x + dx, ny = c.y + dy;
        int sx = 0, sy = 0;
        if (nx < m) { sx = (int) (nx - m); nx = m; }
        if (nx > w - m) { sx = (int) (nx - (w - m)); nx = w - m; }
        if (ny < m) { sy = (int) (ny - m); ny = m; }
        if (ny > h - m) { sy = (int) (ny - (h - m)); ny = h - m; }
        c.moveTo(nx, ny);
        if (sx != 0 || sy != 0) {
            float d = s.web.getResources().getDisplayMetrics().density;
            s.web.evaluateJavascript("window.scrollBy(" + Math.round(sx * 3 / d) + "," + Math.round(sy * 3 / d) + ")", null);
        }
    }

    /** A real tap at (x, y) on the website, exactly like touching the screen there. */
    private static void tap(WebView w, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        w.dispatchTouchEvent(down);
        w.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static void hint(Session s, String text) {
        if (s.hint != null) s.hint.setText(text);
    }

    private static void setHint(Activity act, final Session s, final String text) {
        act.runOnUiThread(new Runnable() { public void run() { hint(s, text); } });
    }

    /** Also look for video links written in the page itself (async). */
    private static void scanPage(Activity act, final Session s, final Set<String> found,
                                 final LinkedBlockingQueue<String> queue) {
        act.runOnUiThread(new Runnable() { public void run() {
            if (s.web == null || s.ended != null) return;
            s.web.evaluateJavascript(SCAN_JS, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    try {
                        JSONArray a = new JSONArray(v);
                        for (int i = 0; i < a.length() && i < 50; i++) {
                            String u = a.optString(i, "");
                            String path = Uri.parse(u).getPath();
                            if (u.startsWith("https://") && path != null && found.size() < 50
                                    && endsWithAny(path.toLowerCase(Locale.ROOT), VIDEO_EXT) && found.add(u)) {
                                log("video link found in the page", u);
                                queue.offer(u);
                            }
                        }
                    } catch (Exception ignored) { }
                }
            });
        }});
    }

    /** The on-screen pointer. */
    private static final class CursorView extends View {
        float x = -1, y = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(Activity a) {
            super(a);
            fill.setColor(0xEEFFFFFF);
            ring.setColor(0xFF000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4);
            setFocusable(false);
            setClickable(false);
        }

        void moveTo(float nx, float ny) { x = nx; y = ny; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            if (x < 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
            float r = 14 * getResources().getDisplayMetrics().density / 2f + 6;
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r, ring);
        }
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
    private static WebView build(Activity act, final FrameLayout root, final Session session, final String site,
                                 final String startUrl, final Set<String> found,
                                 final LinkedBlockingQueue<String> queue) {
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
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        // Present as plain Chrome (what this engine is), not as an app's embedded browser ("; wv"),
        // which many sites refuse. You're the one using it.
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/\\d+(\\.\\d+)* ", ""));
        browserUa = s.getUserAgentString();
        // Players embedded from another domain often need their own cookies. All cookies are
        // wiped when the website closes.
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

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
                    if (found.size() < 50 && found.add(u.toString())) {
                        log("video link found (not downloaded)", u.toString());
                        queue.offer(u.toString());
                    }
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
                log("website crashed; discarded", null);
                session.ended = "crashed";
                root.removeView(v);
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

        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message m) {
                // Some sites only play after their popup "opened". Give it an empty, invisible
                // window that can't run scripts or load anything, and throw it away shortly.
                log("BLOCKED popup / new window (it gets an empty window that loads nothing)", null);
                try {
                    final WebView dummy = new WebView(v.getContext());
                    dummy.getSettings().setJavaScriptEnabled(false);
                    dummy.getSettings().setAllowFileAccess(false);
                    dummy.getSettings().setAllowContentAccess(false);
                    dummy.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView d, WebResourceRequest r) { return true; }

                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView d, WebResourceRequest r) { return blocked(); }
                    });
                    ((WebView.WebViewTransport) m.obj).setWebView(dummy);
                    m.sendToTarget();
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        public void run() { dummy.destroy(); }
                    }, 3000);
                    return true;
                } catch (Exception e) {
                    return false;
                }
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
