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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
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
import android.webkit.WebResourceError;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Host of the page the website view is on now (it can change with test method 3). */
    static volatile String lastMainHost = null;

    /** Request headers the website sent for each video link it asked for (test method 12). */
    static final Map<String, Map<String, String>> seenHeaders = new ConcurrentHashMap<String, Map<String, String>>();

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
        c.getServiceWorkerWebSettings().setAllowFileAccess(false);
        c.getServiceWorkerWebSettings().setAllowContentAccess(false);
        if (Methods.on(Methods.SERVICE_WORKERS | Methods.BARE)) {     // test methods 1, 14
            c.getServiceWorkerWebSettings().setBlockNetworkLoads(false);
            c.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) {
                    Session s = current;
                    String path = r.getUrl().getPath() == null ? "" : r.getUrl().getPath().toLowerCase(Locale.ROOT);
                    if (s != null && endsWithAny(path, VIDEO_EXT)) note(s, r.getUrl().toString(), r.getRequestHeaders(), "service worker");
                    return null;
                }
            });
            return;
        }
        c.getServiceWorkerWebSettings().setBlockNetworkLoads(true);
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
        Set<String> found;
        LinkedBlockingQueue<String> queue;
        volatile String pageSite;  // site of the page shown now
        boolean dragging;          // holding the "finger" down (drag mode)
        long dragDownTime;
        boolean okStartedDrag;     // this OK press began the drag (its release keeps holding)
        boolean autoStarted;       // the automatic first click + hold has been done
    }

    /** A video link the website asked for: remember it (and its headers) for the checker. */
    static void note(Session s, String url, Map<String, String> headers, String how) {
        if (s.found.size() < 50 && s.found.add(url)) {
            log("video link found (" + how + ")", url);
            if (headers != null) seenHeaders.put(url, new HashMap<String, String>(headers));
            s.queue.offer(url);
        }
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
        s.found = found;
        s.queue = queue;
        s.pageSite = site;
        seenHeaders.clear();
        lastMainHost = Uri.parse(url).getHost();
        final CountDownLatch shown = new CountDownLatch(1);

        act.runOnUiThread(new Runnable() { public void run() {
            s.web = build(act, root, s, site, url, found, queue);
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            root.addView(s.web, full);
            if (Methods.on(Methods.FOCUS)) s.web.requestFocus();
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
            hint(s, MAIN_HINT
                    + (engine > 0 && engine < 110 ? "\nThis device's web engine is old (Chrome " + engine
                    + "), so some websites won't work. Update the device's software." : ""));
            log("web engine: Chrome " + engine, null);
            current = s;
            log("website shown", url);
            final Map<String, String> extra = new HashMap<String, String>();
            if (Methods.on(Methods.GOOGLE_REFERER)) extra.put("Referer", "https://www.google.com/");
            if (Methods.on(Methods.HOME_FIRST)) {                 // test method 23
                String home = "https://" + Uri.parse(url).getHost() + "/";
                log("opening the homepage first", home);
                s.web.loadUrl(home, extra);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { public void run() {
                    if (s.web == null || s.ended != null) return;
                    log("now the video page", url);
                    s.web.loadUrl(url, extra);
                }}, 8000);
            } else {
                s.web.loadUrl(url, extra);
            }
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
            if (s.ended != null) { result = s.ended; break; }
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

    static final String MAIN_HINT = "Start the video on the website.   Move: \u25C0 \u25B2 \u25BC \u25B6   Click: OK"
            + "   Drag: hold OK   Cancel: Back";
    private static final String DRAG_HINT = "HOLDING \u2014 move with \u25C0 \u25B2 \u25BC \u25B6, then press OK to let go";
    private static final long HOLD_MS = 800;   // hold OK this long to grab (start dragging)

    /** Remote keys while the website is on screen (UI thread). True = handled here. */
    static boolean handleKey(KeyEvent e) {
        Session s = current;
        if (s == null || s.web == null || s.ended != null) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Faster while an arrow is held; while dragging, a bit finer for exact placement.
        float step = s.dragging ? 8 + Math.min(e.getRepeatCount() * 4, 40) : 14 + Math.min(e.getRepeatCount() * 8, 60);
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
                // Short press = click. Hold ~1 s = grab (then arrows drag). Press again = let go.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        s.okStartedDrag = false;
                    } else if (!s.dragging && !s.okStartedDrag && e.getEventTime() - e.getDownTime() >= HOLD_MS) {
                        startDrag(s);
                        s.okStartedDrag = true;
                    }
                } else if (s.okStartedDrag) {
                    s.okStartedDrag = false;           // keep holding after the long press ends
                } else if (s.dragging) {
                    endDrag(s);
                } else if (Methods.on(Methods.MOUSE)) {
                    mouseClick(s.web, c.x, c.y);
                } else {
                    tap(s.web, c.x, c.y);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:     // also grabs / lets go
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (down && e.getRepeatCount() == 0) { if (s.dragging) endDrag(s); else startDrag(s); }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) {
                    if (s.dragging) endDrag(s);        // Back while holding = let go, not close
                    else { s.ended = "cancelled"; hint(s, "Closing\u2026"); }
                }
                return true;
            default:
                return true;   // nothing else reaches the app while the website is shown
        }
    }

    /**
     * Right after the video page loads: one click in the middle of the screen (so you don't
     * have to), then the "finger" goes down there and stays down, so the arrows drag right
     * away (e.g. a slider puzzle). OK lets go; after that everything works as usual.
     */
    private static void autoStart(final Session s) {
        if (s.web == null || s.ended != null || current != s || s.dragging) return;
        final CursorView c = s.cursor;
        c.moveTo(c.getWidth() / 2f, c.getHeight() / 2f);
        log("automatic first click in the middle", null);
        if (Methods.on(Methods.MOUSE)) mouseClick(s.web, c.x, c.y); else tap(s.web, c.x, c.y);
        c.postDelayed(new Runnable() { public void run() {
            if (s.web == null || s.ended != null || current != s || s.dragging) return;
            startDrag(s);
        }}, 400);
    }

    /** Put the "finger" down at the cursor and keep it there (drag mode). */
    private static void startDrag(Session s) {
        CursorView c = s.cursor;
        s.dragging = true;
        s.dragDownTime = SystemClock.uptimeMillis();
        press(s, MotionEvent.ACTION_DOWN, c.x, c.y);
        c.setHolding(true);
        hint(s, DRAG_HINT);
        log("drag: holding", null);
    }

    /** Lift the "finger" where the cursor is now. */
    private static void endDrag(Session s) {
        CursorView c = s.cursor;
        press(s, MotionEvent.ACTION_UP, c.x, c.y);
        s.dragging = false;
        c.setHolding(false);
        hint(s, MAIN_HINT);
        log("drag: let go", null);
    }

    /** One step of the held gesture (touch, or mouse with the button down for test method 18). */
    private static void press(Session s, int action, float x, float y) {
        if (Methods.on(Methods.MOUSE)) {
            mouse(s.web, action, x, y, action == MotionEvent.ACTION_UP ? 0 : MotionEvent.BUTTON_PRIMARY, s.dragDownTime);
            return;
        }
        MotionEvent ev = MotionEvent.obtain(s.dragDownTime, SystemClock.uptimeMillis(), action, x, y, 0);
        s.web.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Move the cursor; pushing past an edge scrolls the page instead. While dragging, the held
     *  "finger" slides along in small steps (no scrolling), like a real drag. */
    private static void move(Session s, float dx, float dy) {
        CursorView c = s.cursor;
        float w = c.getWidth(), h = c.getHeight(), m = 8;
        float nx = c.x + dx, ny = c.y + dy;
        if (s.dragging) {
            nx = Math.max(m, Math.min(w - m, nx));
            ny = Math.max(m, Math.min(h - m, ny));
            float x0 = c.x, y0 = c.y;
            int n = Math.max(1, (int) Math.ceil(Math.hypot(nx - x0, ny - y0) / 6));
            for (int i = 1; i <= n; i++) press(s, MotionEvent.ACTION_MOVE, x0 + (nx - x0) * i / n, y0 + (ny - y0) * i / n);
            c.moveTo(nx, ny);
            return;
        }
        int sx = 0, sy = 0;
        if (nx < m) { sx = (int) (nx - m); nx = m; }
        if (nx > w - m) { sx = (int) (nx - (w - m)); nx = w - m; }
        if (ny < m) { sy = (int) (ny - m); ny = m; }
        if (ny > h - m) { sy = (int) (ny - (h - m)); ny = h - m; }
        c.moveTo(nx, ny);
        if (Methods.on(Methods.MOUSE)) mouse(s.web, MotionEvent.ACTION_HOVER_MOVE, nx, ny, 0, SystemClock.uptimeMillis());
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

    /** A mouse event (test method 18): hover, press, release, like a real mouse pointer. */
    private static void mouse(WebView w, int action, float x, float y, int buttons, long downTime) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = buttons != 0 ? 1f : 0f;
        pc.size = 1f;
        MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc}, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (action == MotionEvent.ACTION_HOVER_MOVE) w.dispatchGenericMotionEvent(ev); else w.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private static void mouseClick(WebView w, float x, float y) {
        long t = SystemClock.uptimeMillis();
        mouse(w, MotionEvent.ACTION_HOVER_MOVE, x, y, 0, t);
        mouse(w, MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, t);
        mouse(w, MotionEvent.ACTION_UP, x, y, 0, t);
    }

    private static void hint(final Session s, String text) {
        if (s.hint == null) return;
        s.hint.setText(text);
        s.hint.setVisibility(View.VISIBLE);
        if (Methods.on(Methods.HIDE_HINT)) {                   // test method 20
            final TextView h = s.hint;
            final String shownText = text;
            h.postDelayed(new Runnable() { public void run() {
                if (shownText.equals(h.getText().toString())) h.setVisibility(View.GONE);
            }}, 6000);
        }
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

        /** Blue while holding (dragging), white otherwise. */
        void setHolding(boolean on) { fill.setColor(on ? 0xEE3D7CFF : 0xEEFFFFFF); ring.setStrokeWidth(on ? 6 : 4); invalidate(); }

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

    /** Test method 11: report fetch/XHR answers that are HLS playlists, whatever their name. */
    private static final String SNIFF_JS = "(function(){if(window.__vtv)return;window.__vtv=1;"
            + "function rep(u){try{u=new URL(u,location.href).href;if(u.indexOf('https:')!==0)return;"
            + "(new Image()).src='/__videotv_found__?u='+encodeURIComponent(u)}catch(e){}}"
            + "function hls(ct){return ct&&/mpegurl/i.test(ct)}"
            + "var F=window.fetch;if(F)window.fetch=function(){var p=F.apply(this,arguments);try{p.then(function(r){try{"
            + "var ct=r.headers.get('content-type')||'',n=+(r.headers.get('content-length')||0);"
            + "if(hls(ct))rep(r.url);else if(/text|json|octet/i.test(ct)&&n<2000000)r.clone().text().then(function(t){"
            + "if(t.slice(0,7)==='#EXTM3U')rep(r.url)})}catch(e){}})}catch(e){}return p};"
            + "var O=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(){var x=this;try{"
            + "x.addEventListener('load',function(){try{var ct=x.getResponseHeader('content-type')||'';"
            + "if(hls(ct))rep(x.responseURL);else if((x.responseType===''||x.responseType==='text')&&"
            + "String(x.responseText).slice(0,7)==='#EXTM3U')rep(x.responseURL)}catch(e){}})}catch(e){}"
            + "return O.apply(this,arguments)}})()";

    /** Test method 21: look like normal Chrome to scripts that check for an app's browser. */
    private static final String STEALTH_JS = "(function(){try{"
            + "if(!window.chrome)window.chrome={runtime:{},app:{isInstalled:false},csi:function(){return{}},"
            + "loadTimes:function(){return{}}};"
            + "Object.defineProperty(Navigator.prototype,'webdriver',{get:function(){return false},configurable:true});"
            + "var d=navigator.userAgentData;if(d&&d.brands){var v=(d.brands.filter(function(b){return/Chrom/.test(b.brand)})[0]||{version:'130'}).version;"
            + "var b=[{brand:'Google Chrome',version:v},{brand:'Chromium',version:v},{brand:'Not_A Brand',version:'24'}];"
            + "var f={brands:b,mobile:d.mobile,platform:d.platform,toJSON:function(){return{brands:b,mobile:d.mobile,platform:d.platform}},"
            + "getHighEntropyValues:function(h){return d.getHighEntropyValues(h).then(function(r){r.brands=b;"
            + "r.fullVersionList=b;return r})}};"
            + "Object.defineProperty(Navigator.prototype,'userAgentData',{get:function(){return f},configurable:true})}"
            + "if(navigator.plugins&&navigator.plugins.length===0)Object.defineProperty(Navigator.prototype,'plugins',"
            + "{get:function(){return[1,2,3]},configurable:true});"
            + "}catch(e){}})()";

    private static final String[] NOT_A_PLAYLIST = {".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
            ".ico", ".woff", ".woff2", ".ttf", ".html", ".php"};

    /** Test method 11: a request that might be a playlist even without .m3u8 in its name. */
    private static boolean maybePlaylist(String full, String path) {
        if (endsWithAny(path, NOT_A_PLAYLIST)) return false;
        return full.contains("m3u8") || path.contains("/hls/") || path.contains("playlist") || path.contains("manifest")
                || path.endsWith("/master") || path.endsWith("/index")
                || ((path.endsWith(".txt") || path.endsWith(".json"))
                    && (path.contains("video") || path.contains("stream") || path.contains("source")));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView build(final Activity act, final FrameLayout root, final Session session, final String site,
                                 final String startUrl, final Set<String> found,
                                 final LinkedBlockingQueue<String> queue) {
        final AtomicInteger sentBack = new AtomicInteger(0);
        final boolean bare = Methods.on(Methods.BARE), loose = Methods.loose(), follow = Methods.follow();
        final boolean letMedia = bare || Methods.on(Methods.LET_MEDIA), sniff = Methods.on(Methods.SNIFF);
        final boolean adblock = Methods.on(Methods.ADBLOCK), testing = Methods.testing();
        final WebView w = new WebView(act);
        boolean focus = Methods.on(Methods.FOCUS);
        w.setFocusable(focus);
        w.setFocusableInTouchMode(focus);
        w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (Methods.on(Methods.SOFTWARE)) w.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);          // the site's player needs it; it runs sandboxed
        s.setDomStorageEnabled(true);          // wiped afterwards
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(loose ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(!bare);    // window.open goes to onCreateWindow, which refuses
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setCacheMode(bare || Methods.on(Methods.CACHE) ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(!bare && !Methods.on(Methods.NO_SAFE_BROWSING));
        int major = webViewMajor() > 0 ? webViewMajor() : 130;
        if (Methods.on(Methods.DESKTOP)) {                       // test method 8
            s.setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + major + ".0.0.0 Safari/537.36");
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
        } else if (Methods.on(Methods.PHONE)) {                  // test method 9
            s.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + major + ".0.0.0 Mobile Safari/537.36");
        } else if (!bare && !Methods.on(Methods.STOCK_UA)) {
            // Present as plain Chrome (what this engine is), not as an app's embedded browser ("; wv"),
            // which many sites refuse. You're the one using it.
            s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/\\d+(\\.\\d+)* ", ""));
        }
        browserUa = s.getUserAgentString();
        if (testing) log("browser identity: " + browserUa, null);
        // Players embedded from another domain often need their own cookies. All cookies are
        // wiped when the website closes.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String u, String ua, String cd, String mime, long len) { log("BLOCKED download", u); }
        });

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                boolean web = "https".equals(scheme) || (loose && "http".equals(scheme));
                if (!web || (!loose && !Net.isPublicHostname(host))) {   // intent:, market:, ...
                    log("BLOCKED navigation", u.toString());
                    return true;
                }
                if (adblock && Methods.isAd(host)) { log("ad blocked (navigation)", u.toString()); return true; }
                if (r.isForMainFrame() && !site.equals(Net.siteOf(host))) {             // stay on the site
                    if (follow) { log("following the site to another domain", u.toString()); return false; }
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
                String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
                if (sniff && "/__videotv_found__".equals(path)) {       // reported by SNIFF_JS
                    String f = u.getQueryParameter("u");
                    if (f != null && f.startsWith("https://")) note(session, f, null, "hidden playlist, seen by the page hook");
                    return blocked();
                }
                if (loose) {
                    if (!"https".equals(scheme) && !"http".equals(scheme)) return blocked();
                } else if (!"https".equals(scheme) || !Net.isPublicHostname(host) || !Net.resolvesPublic(host)) {
                    log("BLOCKED (not https, a home-network address, or the site doesn't exist)", u.toString());
                    return blocked();
                }
                if (adblock && Methods.isAd(host)) return blocked();
                String full = u.toString().toLowerCase(Locale.ROOT);
                boolean video = endsWithAny(path, VIDEO_EXT);
                if (video || (sniff && maybePlaylist(full, path))) {   // the prize: note it
                    if ("https".equals(scheme)) note(session, u.toString(), r.getRequestHeaders(), video ? "not downloaded" : "maybe a hidden playlist");
                    return video && !letMedia ? blocked() : null;
                }
                if (endsWithAny(path, BLOCK_EXT) && !letMedia) return blocked();
                if (r.isForMainFrame() && !follow && !site.equals(Net.siteOf(host))) {
                    log("BLOCKED page from another site", u.toString());
                    return blocked();
                }
                return null;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // The page crashed its sandbox: throw this WebView away (it must not be used
                // again), keep the app running.
                log("website crashed; discarded" + (Build.VERSION.SDK_INT >= 26 && !d.didCrash() ? " (ran out of memory)" : ""), null);
                session.ended = "crashed";
                root.removeView(v);
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap icon) {
                if (u != null && !u.startsWith("about:")) {
                    if (Methods.on(Methods.STEALTH)) v.evaluateJavascript(STEALTH_JS, null);
                    if (sniff) v.evaluateJavascript(SNIFF_JS, null);
                }
                if (testing) log("page started", u);
                if (u == null || u.startsWith("about:")) return;
                String host = Uri.parse(u).getHost();
                if (host != null) { lastMainHost = host; session.pageSite = Net.siteOf(host); }
                if (follow) return;
                // Last line of defence: if the page ended up on another site anyway, stop it
                // and send it back to the video page (a few times at most).
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
                if (sniff && u != null && !u.startsWith("about:")) v.evaluateJavascript(SNIFF_JS, null);
                if (testing) log("page finished loading", u);
                // Once the video page has loaded: click the middle for you, then hold (see autoStart).
                if (u != null && !u.startsWith("about:") && !session.autoStarted
                        && (!Methods.on(Methods.HOME_FIRST) || u.equals(startUrl))) {
                    session.autoStarted = true;
                    v.postDelayed(new Runnable() { public void run() { autoStart(session); } }, 1500);
                }
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
                if (testing && r.isForMainFrame()) log("PAGE ERROR: HTTP " + resp.getStatusCode() + " " + resp.getReasonPhrase(), r.getUrl().toString());
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (testing && r.isForMainFrame()) log("PAGE ERROR: " + e.getErrorCode() + " " + e.getDescription(), r.getUrl().toString());
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(final WebView v, boolean dialog, boolean gesture, Message m) {
                if (bare) return false;
                final boolean inPlace = Methods.on(Methods.POPUPS);
                // Some sites only play after their popup "opened". Give it an empty, invisible
                // window that can't run scripts or load anything, and throw it away shortly.
                // (Test method 4: if the popup is on the same site, open it in place instead.)
                log(inPlace ? "popup / new window: opening it here if it's on the same site"
                        : "BLOCKED popup / new window (it gets an empty window that loads nothing)", null);
                try {
                    final WebView dummy = new WebView(v.getContext());
                    final Handler ui = new Handler(Looper.getMainLooper());
                    final boolean[] taken = {false};
                    dummy.getSettings().setJavaScriptEnabled(false);
                    dummy.getSettings().setAllowFileAccess(false);
                    dummy.getSettings().setAllowContentAccess(false);
                    dummy.setWebViewClient(new WebViewClient() {
                        void take(final String url) {
                            if (!inPlace) return;
                            ui.post(new Runnable() { public void run() {
                                if (taken[0] || session.web != v || session.ended != null) return;
                                taken[0] = true;
                                Uri u = Uri.parse(url);
                                String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                                String hs = Net.siteOf(h);
                                if ("https".equals(u.getScheme()) && (hs.equals(site) || hs.equals(session.pageSite))
                                        && !(adblock && Methods.isAd(h))) {
                                    log("popup is on the same site: opened here", url);
                                    v.loadUrl(url);
                                } else {
                                    log("BLOCKED popup to another site", url);
                                }
                            }});
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView d, WebResourceRequest r) {
                            take(r.getUrl().toString());
                            return true;
                        }

                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView d, WebResourceRequest r) {
                            if (r.isForMainFrame()) take(r.getUrl().toString());
                            return blocked();
                        }
                    });
                    ((WebView.WebViewTransport) m.obj).setWebView(dummy);
                    m.sendToTarget();
                    ui.postDelayed(new Runnable() {
                        public void run() { dummy.destroy(); }
                    }, 3000);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                if (Methods.on(Methods.DRM | Methods.BARE)) {           // test methods 1, 17
                    for (String res : r.getResources()) {
                        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                            log("ALLOWED protected media (DRM) request", r.getOrigin().toString());
                            r.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                            return;
                        }
                    }
                }
                log("DENIED permission request", r.getOrigin().toString());
                r.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage c) {
                if (testing && c.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String msg = c.message() == null ? "" : c.message();
                    log("page console error: " + (msg.length() > 200 ? msg.substring(0, 200) + "..." : msg), null);
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                if (bare) return false;
                if (Methods.on(Methods.DIALOGS)) { log("alert dialog answered OK", null); r.confirm(); return true; }
                log("BLOCKED alert dialog", null); r.cancel(); return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                if (bare) return false;
                if (Methods.on(Methods.DIALOGS)) { log("confirm dialog answered OK", null); r.confirm(); return true; }
                r.cancel(); return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) {
                if (bare) return false;
                if (Methods.on(Methods.DIALOGS)) { r.confirm(def == null ? "" : def); return true; }
                r.cancel(); return true;
            }

            @Override
            public boolean onJsBeforeUnload(WebView v, String url, String msg, JsResult r) {
                if (bare || Methods.on(Methods.DIALOGS)) { r.confirm(); return true; }
                r.cancel(); return true;
            }
        });
        return w;
    }
}
