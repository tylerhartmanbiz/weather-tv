package app.weathertv;

import java.util.Locale;

/**
 * Test methods for "the website works in Chrome but not in the app" (see testing websites/).
 * Picked with ./emulator.sh -N, which starts the app with the extra "method" = N. A normal
 * launch (the Fire TV) has no extra: method -1, no flags, the app behaves exactly as before.
 * Methods marked "emulator" only change the virtual TV (emulator.sh does that part).
 */
final class Methods {
    private Methods() { }

    static final long BARE = 1L,              // no sandbox rules at all (only notes video links)
            NO_SAFE_BROWSING = 1L << 1,
            FOLLOW_SITE = 1L << 2,            // main page may move to another domain (mirror/redirect)
            POPUPS = 1L << 3,                 // popups / new tabs on the same site open in place
            STOCK_UA = 1L << 4,               // keep the WebView's own browser identity
            DESKTOP = 1L << 5,                // desktop Chrome identity + desktop-width page
            PHONE = 1L << 6,                  // phone Chrome identity
            LET_MEDIA = 1L << 7,              // don't block the website's own video requests
            SNIFF = 1L << 8,                  // also find video links without .m3u8/.mp4 in the name
            REAL_HEADERS = 1L << 9,           // fetch the stream with the website's own headers + cookies
            LOOSE_NET = 1L << 10,             // no DNS/home-network checks, http + mixed content allowed
            SERVICE_WORKERS = 1L << 11,
            CACHE = 1L << 12,
            DIALOGS = 1L << 13,               // alert/confirm/prompt answered "OK"
            DRM = 1L << 14,                   // grant protected-media (Widevine) requests
            MOUSE = 1L << 15,                 // clicks + hover as a mouse, not a finger
            FOCUS = 1L << 16,                 // the page gets focus like a normal browser tab
            HIDE_HINT = 1L << 17,             // help text disappears after 6 s
            STEALTH = 1L << 18,               // hide signs of an app's built-in browser
            GOOGLE_REFERER = 1L << 19,
            HOME_FIRST = 1L << 20,            // open the site's homepage first, then the video page
            ADBLOCK = 1L << 21,
            SOFTWARE = 1L << 22;              // draw the page without the GPU

    private static final long ALL_APP = NO_SAFE_BROWSING | FOLLOW_SITE | POPUPS | LET_MEDIA | SNIFF | REAL_HEADERS
            | LOOSE_NET | SERVICE_WORKERS | CACHE | DIALOGS | DRM | FOCUS | HIDE_HINT | STEALTH | ADBLOCK;

    /** Index = method number. Keep in sync with emulator.sh and testing websites/README.md. */
    private static final Object[][] TABLE = {
            /* 0 */ {0L, "baseline: the app exactly as it is today"},
            /* 1 */ {BARE, "bare WebView: no sandbox rules at all"},
            /* 2 */ {NO_SAFE_BROWSING, "Safe Browsing off"},
            /* 3 */ {FOLLOW_SITE, "follow the site to other domains"},
            /* 4 */ {POPUPS, "same-site popups / new tabs open in place"},
            /* 5 */ {0L, "emulator: encrypted DNS (dns.google), like Chrome's Secure DNS"},
            /* 6 */ {0L, "emulator: public DNS servers 1.1.1.1 + 8.8.8.8"},
            /* 7 */ {STOCK_UA, "real WebView browser identity"},
            /* 8 */ {DESKTOP, "desktop Chrome mode"},
            /* 9 */ {PHONE, "phone Chrome identity"},
            /* 10 */ {LET_MEDIA, "let the website load its own video"},
            /* 11 */ {SNIFF, "find hidden video links"},
            /* 12 */ {REAL_HEADERS, "fetch the stream with the website's own headers + cookies"},
            /* 13 */ {LOOSE_NET, "loosen the network rules (VPN/proxy, http, odd names)"},
            /* 14 */ {SERVICE_WORKERS, "allow service workers"},
            /* 15 */ {CACHE, "normal browser cache"},
            /* 16 */ {DIALOGS, "answer OK to pop-up dialogs"},
            /* 17 */ {DRM, "allow protected media (DRM)"},
            /* 18 */ {MOUSE, "mouse clicks + hover instead of touch"},
            /* 19 */ {FOCUS, "give the page focus"},
            /* 20 */ {HIDE_HINT, "hide the help text after 6 s"},
            /* 21 */ {STEALTH, "stealth: hide app-browser signs"},
            /* 22 */ {GOOGLE_REFERER, "arrive from Google"},
            /* 23 */ {HOME_FIRST, "open the homepage first, then the video page"},
            /* 24 */ {ADBLOCK, "built-in ad blocker"},
            /* 25 */ {SOFTWARE, "draw the page without the GPU"},
            /* 26 */ {0L, "emulator: GPU on (host)"},
            /* 27 */ {0L, "emulator: GPU SwiftShader"},
            /* 28 */ {0L, "emulator: 4 GB memory"},
            /* 29 */ {0L, "emulator: factory reset of the virtual TV"},
            /* 30 */ {ALL_APP, "all app fixes together"},
            /* 31 */ {ALL_APP | DESKTOP | MOUSE, "all app fixes + desktop mode + mouse"},
            /* 32 */ {ALL_APP | STOCK_UA, "all app fixes + real WebView identity"},
            /* 33 */ {BARE, "bare WebView + encrypted DNS + GPU + 4 GB"},
            /* 34 */ {ALL_APP, "everything: all app fixes + encrypted DNS + GPU + 4 GB"},
    };

    static volatile int method = -1;
    private static volatile long flags = 0;

    static void set(int m) {
        if (m < 0 || m >= TABLE.length) { method = -1; flags = 0; return; }
        method = m;
        flags = (Long) TABLE[m][0];
        Scraper.log("TEST METHOD " + m + ": " + TABLE[m][1], null);
    }

    static boolean testing() { return method >= 0; }

    static boolean on(long f) { return (flags & f) != 0; }

    /** Bare also means the network/site rules are off. */
    static boolean loose() { return on(LOOSE_NET | BARE); }

    static boolean follow() { return on(FOLLOW_SITE | BARE); }

    private static final String[] ADS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "googletagmanager.com", "popads.net", "popcash.net", "propellerads.com",
            "propellerclick.com", "adsterra.com", "adsterratech.com", "exoclick.com", "exosrv.com",
            "juicyads.com", "trafficjunky.net", "adcash.com", "hilltopads.net", "clickadu.com", "onclickads.net",
            "onclkds.com", "admaven.com", "ad-maven.com", "a-ads.com", "mgid.com", "taboola.com", "outbrain.com",
            "revcontent.com", "zedo.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "criteo.com",
            "amazon-adsystem.com", "yieldmo.com", "bidswitch.net", "popunder.net", "richpush.co",
            "pushnami.com", "pushwhy.com", "monetag.com", "galaksion.com", "clickaine.com", "realsrv.com",
            "bebi.com", "acint.net", "dtscout.com", "histats.com", "yandex.ru", "mc.yandex.ru",
    };

    static boolean isAd(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String a : ADS) if (host.equals(a) || host.endsWith("." + a)) return true;
        return false;
    }
}
