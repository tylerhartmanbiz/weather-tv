// Runs inside the video website (every page and embedded frame) to:
//  * remove overlays (always): popups, dimmed backdrops and see-through ad "click catchers"
//    lying over the page or the video
//  * button mode: the remote's arrows jump between the site's own buttons.
//
// This file is LIVE (like site-control.js): the app downloads it when it starts and puts it into
// every page and every embedded frame (players are often inside another site's frame) of the
// website, before the site's own scripts run. It only reacts to the key presses the app sends
// in button mode; in cursor mode no keys reach the website at all.
//
//   ◀ ▲ ▼ ▶   go to the nearest button / link / slider in that direction (into and out of frames)
//   on a slider (timeline, volume): ◀ ▶ change it, ▲ ▼ leave it; at its end ◀ ▶ go past it;
//                                   OK = ◀ ▶ go past it instead (OK again: change it again)
//   in a text box: ◀ ▶ move in the text
//   Enter     press the outlined thing
//
// It runs inside the (untrusted) website, so it has no access to the app and nothing it does
// leaves the website view.
(function () {
  'use strict';
  if (window.__vtvNav) return;
  window.__vtvNav = 1;
  const TOP = window === window.top;
  const MSG = '__vtvNav';

  // ---------- no fullscreen ----------
  // The website already fills the TV. If a player goes "fullscreen" anyway (often on a double
  // click), Android hands the video to a fullscreen view the app doesn't show: black picture,
  // sound still playing. So fullscreen requests do nothing, and the page says it can't.
  (function noFullscreen() {
    const ok = function () { return Promise.resolve(); };
    const P = window.Element && Element.prototype;
    try {
      for (const k of ['requestFullscreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen', 'mozRequestFullScreen', 'msRequestFullscreen']) {
        if (P && k in P) Object.defineProperty(P, k, { value: ok, configurable: false, writable: false });
      }
      const V = window.HTMLVideoElement && HTMLVideoElement.prototype;
      for (const k of ['webkitEnterFullscreen', 'webkitEnterFullScreen']) {
        if (V && k in V) Object.defineProperty(V, k, { value: function () { }, configurable: false, writable: false });
      }
      for (const k of ['fullscreenEnabled', 'webkitFullscreenEnabled']) {
        if (k in Document.prototype || k in document) Object.defineProperty(document, k, { get: () => false, configurable: false });
      }
    } catch (x) { }
    const leave = () => {
      try {
        if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => { });
        else if (document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen();
      } catch (x) { }
    };
    document.addEventListener('fullscreenchange', leave, true);
    document.addEventListener('webkitfullscreenchange', leave, true);
    setInterval(leave, 1000);
  })();
  const DIRS = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };

  const SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,iframe,video[controls],' +
    '[tabindex]:not([tabindex="-1"]),[contenteditable=""],[contenteditable=true],' +
    '[role=button],[role=link],[role=slider],[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],' +
    '[role=option],[role=tab],[role=checkbox],[role=radio],[role=switch],[role=scrollbar],[onclick]';

  // ---------- finding the things you can press ----------
  function parentOf(e) { return e.parentNode instanceof ShadowRoot ? e.parentNode.host : e.parentElement; }
  function deepActive() {
    let e = document.activeElement;
    while (e && e.shadowRoot && e.shadowRoot.activeElement) e = e.shadowRoot.activeElement;
    return e === document.body || e === document.documentElement ? null : e;
  }
  function all(root, out) {
    const els = root.querySelectorAll('*');
    for (let i = 0; i < els.length && out.length < 6000; i++) {
      out.push(els[i]);
      if (els[i].shadowRoot) all(els[i].shadowRoot, out);
    }
    return out;
  }
  function shown(e, r) {
    // Timelines can be very thin (2-3 px) until focused; other things must be at least 4 px.
    if (isSlider(e) ? r.width < 2 && r.height < 2 || r.width < 1 || r.height < 1 : r.width < 4 || r.height < 4) return false;
    if (r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) return false;
    const st = getComputedStyle(e);
    if (st.visibility === 'hidden' || st.display === 'none') return false;
    if (e.disabled) return false;
    // A control hidden on top of the video (auto-hiding player bars) still counts: it shows
    // again when focused. Anything else that's fully see-through doesn't.
    return true;
  }
  function candidates() {
    const list = [], seen = new Set();
    for (const e of all(document, [])) {
      if (e.id === '__vtvRing') continue;
      let ok = e.matches(SEL);
      if (!ok) {
        // Things made clickable by a script: the outermost element with a hand cursor.
        const tag = e.tagName;
        if (tag !== 'DIV' && tag !== 'SPAN' && tag !== 'LI' && tag !== 'IMG' && tag !== 'svg' && tag !== 'I') continue;
        if (getComputedStyle(e).cursor !== 'pointer') continue;
        const p = parentOf(e);
        if (p && p.nodeType === 1 && getComputedStyle(p).cursor === 'pointer') continue;
        ok = true;
      }
      const r = e.getBoundingClientRect();
      if (!shown(e, r)) continue;
      if (e.tagName === 'IFRAME' && (r.width < 40 || r.height < 30)) continue;
      list.push({ e, r });
      seen.add(e);
    }
    // Prefer the innermost: drop anything that contains another candidate (e.g. a whole player
    // box with a tabindex, or a link around a button). Frames and sliders stay.
    const parents = new Set();
    for (const c of list) for (let p = parentOf(c.e); p && p.nodeType === 1; p = parentOf(p)) {
      if (parents.has(p)) break;
      parents.add(p);
    }
    return list.filter(c => !parents.has(c.e) || c.e.tagName === 'IFRAME' || isSlider(c.e));
  }

  function huge(e) {
    const r = e.getBoundingClientRect();
    return e.tagName === 'VIDEO' || r.width * r.height > innerWidth * innerHeight * 0.3;
  }
  function isSlider(e) {
    if (!e) return false;
    const role = (e.getAttribute && e.getAttribute('role')) || '';
    return role === 'slider' || role === 'scrollbar' || (e.tagName === 'INPUT' && e.type === 'range') ||
      /^MEDIA-.*-RANGE$/.test(e.tagName);
  }
  function isText(e) {
    if (!e) return false;
    if (e.isContentEditable) return true;
    if (e.tagName === 'TEXTAREA') return true;
    return e.tagName === 'INPUT' && /^(text|search|email|url|tel|password|number|)$/.test(e.type || '');
  }

  // ---------- picking the nearest one in a direction ----------
  function score(from, r, dir) {
    const fcx = (from.left + from.right) / 2, fcy = (from.top + from.bottom) / 2;
    const cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
    let major, minor, overlap;
    if (dir === 'right' || dir === 'left') {
      if (dir === 'right' ? cx <= fcx + 1 || r.right <= from.right : cx >= fcx - 1 || r.left >= from.left) return Infinity;
      major = dir === 'right' ? Math.max(0, r.left - from.right) : Math.max(0, from.left - r.right);
      overlap = Math.min(r.bottom, from.bottom) - Math.max(r.top, from.top);
      minor = overlap > 0 ? 0 : Math.abs(cy - fcy);
    } else {
      if (dir === 'down' ? cy <= fcy + 1 || r.bottom <= from.bottom : cy >= fcy - 1 || r.top >= from.top) return Infinity;
      major = dir === 'down' ? Math.max(0, r.top - from.bottom) : Math.max(0, from.top - r.bottom);
      overlap = Math.min(r.right, from.right) - Math.max(r.left, from.left);
      minor = overlap > 0 ? 0 : Math.abs(cx - fcx);
    }
    return major + minor * 3;
  }
  function best(from, dir, skip) {
    let top = null, s = Infinity;
    for (const c of candidates()) {
      if (c.e === skip || c.e === current) continue;
      const v = score(from, c.r, dir);
      if (v < s) { s = v; top = c; }
    }
    return top;
  }
  function nearestToMiddle() {   // nothing chosen yet: the button nearest the middle of the video
    let x = innerWidth / 2, y = innerHeight / 2, big = 0;
    for (const v of document.querySelectorAll('video,iframe')) {
      const r = v.getBoundingClientRect(), a = r.width * r.height;
      if (a > big && a > innerWidth * innerHeight * 0.05) { big = a; x = r.left + r.width / 2; y = r.top + r.height / 2; }
    }
    let top = null, s = Infinity;
    for (const c of candidates()) {
      const dx = Math.max(c.r.left - x, 0, x - c.r.right), dy = Math.max(c.r.top - y, 0, y - c.r.bottom);
      const v = Math.hypot(dx, dy) + (c.e.tagName === 'IFRAME' ? 0 : 1);   // a player frame first
      if (v < s) { s = v; top = c; }
    }
    return top;
  }

  // ---------- moving ----------
  let current = null, lastRect = null, passSlider = null;
  function go(target, dir, from) {
    const e = target.e;
    if (e.tagName === 'IFRAME' && e.contentWindow) {
      // Into an embedded frame: it picks its own button, starting from where we are.
      const r = target.r, b = e.getBoundingClientRect();
      const ox = b.left + e.clientLeft, oy = b.top + e.clientTop;
      current = e;
      focusEl(e);
      ring();
      e.contentWindow.postMessage({ [MSG]: 'enter', dir, from: from ?
        { left: from.left - ox, right: from.right - ox, top: from.top - oy, bottom: from.bottom - oy } : null }, '*');
      return;
    }
    current = e;
    passSlider = null;
    lastRect = target.r;
    focusEl(e);
    wake(e);
    ring();
  }
  function rectOf(e) {   // where it is (or was, if the site just hid it, e.g. a play button)
    const r = e.getBoundingClientRect();
    if (r.width || r.height) { lastRect = r; return r; }
    return lastRect || r;
  }
  function atEnd(e, dir) {   // a slider already at its start (◀) or end (▶)
    let v, min, max;
    if (e.tagName === 'INPUT') { v = +e.value; min = e.min === '' ? 0 : +e.min; max = e.max === '' ? 100 : +e.max; }
    else { v = +e.getAttribute('aria-valuenow'); min = +(e.getAttribute('aria-valuemin') || 0); max = +(e.getAttribute('aria-valuemax') || 100); }
    if (!isFinite(v) || !isFinite(min) || !isFinite(max) || max <= min) return false;
    return dir === 'right' ? v >= max - (max - min) * 0.005 : v <= min + (max - min) * 0.005;
  }
  function focusEl(e) {
    if (!e.matches(SEL) || e.getAttribute('tabindex') === '-1' || (!e.hasAttribute('tabindex') && !/^(A|BUTTON|INPUT|SELECT|TEXTAREA|IFRAME|SUMMARY)$/.test(e.tagName)))
      if (!e.hasAttribute('tabindex')) e.setAttribute('tabindex', '-1');
    try { e.focus({ preventScroll: false, focusVisible: true }); } catch (x) { try { e.focus(); } catch (y) { } }
    try { e.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (x) { }
  }
  // Players hide their buttons when the mouse is still: tell them the "mouse" moved there.
  function wake(e) {
    try {
      const r = e.getBoundingClientRect();
      const o = { bubbles: true, composed: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
      e.dispatchEvent(new PointerEvent('pointermove', o));
      e.dispatchEvent(new MouseEvent('mousemove', o));
      e.dispatchEvent(new MouseEvent('mouseover', o));
    } catch (x) { }
  }
  function move(dir, from) {
    if (current && !current.isConnected) current = null;
    const cur = current || deepActive();
    if (!from && !cur) {
      const first = nearestToMiddle();
      if (first) go(first, dir, null);
      else if (!TOP) leave(dir, { left: innerWidth / 2, right: innerWidth / 2, top: innerHeight / 2, bottom: innerHeight / 2 });
      return;
    }
    if (!from) from = rectOf(cur);
    const t = best(from, dir, null);
    if (t) go(t, dir, from);
    else leave(dir, from);
  }
  function leave(dir, from) {     // nothing more this way here: let the page around us continue
    if (!TOP) { window.parent.postMessage({ [MSG]: 'exit', dir, from }, '*'); return; }
    // At the top page: scroll to show more.
    if (dir === 'down' || dir === 'up') window.scrollBy(0, (dir === 'down' ? 1 : -1) * innerHeight * 0.6);
  }

  window.addEventListener('message', ev => {
    const d = ev.data;
    if (!d || typeof d !== 'object' || !d[MSG] || !DIRS['Arrow' + (d.dir || '').replace(/^./, c => c.toUpperCase())]) return;
    const n = v => (typeof v === 'number' && isFinite(v) ? v : 0);
    const f = d.from && typeof d.from === 'object'
      ? { left: n(d.from.left), right: n(d.from.right), top: n(d.from.top), bottom: n(d.from.bottom) } : null;
    if (d[MSG] === 'enter' && ev.source === window.parent) {
      current = null;
      const t = f ? best(f, d.dir, null) : null;
      const pick = t || nearestToMiddle();
      if (pick) go(pick, d.dir, f);
      else window.parent.postMessage({ [MSG]: 'exit', dir: d.dir, from: f, empty: 1 }, '*');
    } else if (d[MSG] === 'exit') {
      // A frame of ours ran out of buttons that way: carry on from it, in our coordinates.
      const fr = [...document.querySelectorAll('iframe')].find(x => x.contentWindow === ev.source);
      if (!fr || !f) return;
      const b = fr.getBoundingClientRect(), ox = b.left + fr.clientLeft, oy = b.top + fr.clientTop;
      const from = d.empty ? b : { left: f.left + ox, right: f.right + ox, top: f.top + oy, bottom: f.bottom + oy };
      current = fr;
      const t = best(from, d.dir, fr);
      if (t) go(t, d.dir, from);
      else leave(d.dir, from);
    }
  });

  // ---------- keys ----------
  window.addEventListener('keydown', ev => {
    if (!ev.isTrusted) return;
    const dir = DIRS[ev.key];
    const el = deepActive();
    if (dir) {
      if (dir === 'left' || dir === 'right') {
        if (isText(el) || (isSlider(el) && el !== passSlider && !atEnd(el, dir))) { wake(el); return; }  // the site changes it
      }
      if (el && el.tagName === 'SELECT') return;
      ev.preventDefault();
      ev.stopImmediatePropagation();
      move(dir, null);
    } else if (ev.key === 'Enter') {
      const t = current && current.isConnected && current.tagName !== 'IFRAME' ? current : el;
      if (!t || isText(t) || t.tagName === 'SELECT') return;
      ev.preventDefault();
      ev.stopImmediatePropagation();
      if (isSlider(t)) { passSlider = passSlider === t ? null : t; ring(); return; }   // ◀ ▶: change it / go past it
      press(t);
    }
  }, true);

  function press(e) {
    wake(e);
    const r = e.getBoundingClientRect();
    const o = { bubbles: true, cancelable: true, composed: true, view: window, button: 0,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    try {
      e.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ pointerId: 1, isPrimary: true, pointerType: 'mouse' }, o)));
      e.dispatchEvent(new MouseEvent('mousedown', o));
      e.dispatchEvent(new PointerEvent('pointerup', Object.assign({ pointerId: 1, isPrimary: true, pointerType: 'mouse' }, o)));
      e.dispatchEvent(new MouseEvent('mouseup', o));
    } catch (x) { }
    if (typeof e.click === 'function') e.click(); else e.dispatchEvent(new MouseEvent('click', o));
    setTimeout(ring, 50);
  }

  // ---------- the blue outline around the chosen button ----------
  let box = null;
  function ring() {
    const e = current;
    const host = document.fullscreenElement || document.documentElement;
    if (!e || !e.isConnected || e.tagName === 'IFRAME' || !document.hasFocus()) { if (box) box.style.display = 'none'; return; }
    if (!box || !box.isConnected || box.parentNode !== host) {
      if (box) box.remove();
      box = document.createElement('div');
      box.id = '__vtvRing';
      box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:4px solid #3d7cff;' +
        'border-radius:6px;box-shadow:0 0 0 3px rgba(255,255,255,.8),0 0 14px 4px rgba(61,124,255,.7);' +
        'box-sizing:border-box;margin:0;padding:0;transition:all .08s';
      host.appendChild(box);
    }
    const r = e.getBoundingClientRect();
    box.style.display = 'block';
    box.style.borderStyle = e === passSlider || (passSlider && passSlider === deepActive()) ? 'dashed' : 'solid';
    box.style.left = (r.left - 5) + 'px';
    box.style.top = (r.top - 5) + 'px';
    box.style.width = (r.width + 10) + 'px';
    box.style.height = (r.height + 10) + 'px';
  }
  setInterval(ring, 250);
  window.addEventListener('blur', () => { if (box) box.style.display = 'none'; });
  window.addEventListener('focus', ring);
  // ---------- overlays: removed, always (both modes, every page and frame) ----------
  // Sites lay things over the page or the video: tinted "screens" with a QR code or a countdown
  // X, popups, dimmed backdrops, see-through "click catchers" that open ads. Anything lying on
  // top of the video or on top of the page is hidden as soon as it shows, and kept hidden.
  // Kept: the player itself (anything that holds the video or a player-sized frame / canvas),
  // the player's own controls, and normal page layout (something that isn't on top of anything).
  function isOurs(e) { return e.id === '__vtvRing'; }
  function area(r) { return Math.max(0, r.width) * Math.max(0, r.height); }
  function coverage(r, m) {   // how much of rect m is covered by rect r (0..1)
    const w = Math.min(r.right, m.right) - Math.max(r.left, m.left), h = Math.min(r.bottom, m.bottom) - Math.max(r.top, m.top);
    return w > 0 && h > 0 ? (w * h) / Math.max(1, area(m)) : 0;
  }
  function screenRect() { return { left: 0, top: 0, right: innerWidth, bottom: innerHeight, width: innerWidth, height: innerHeight }; }
  // The video player: a video, or a frame / canvas / plugin big enough to be one. (A QR code
  // drawn on a canvas, or a small ad frame, isn't.)
  function isPlayer(m) {
    if (!m || m.nodeType !== 1 || !m.matches('video,iframe,embed,object,canvas')) return false;
    const a = area(m.getBoundingClientRect()), sa = innerWidth * innerHeight;
    return m.tagName === 'VIDEO' ? a > sa * 0.03 : a > sa * 0.25;
  }
  function holdsPlayer(e) {
    if (isPlayer(e)) return true;
    for (const m of e.querySelectorAll('video,iframe,embed,object,canvas')) if (isPlayer(m)) return true;
    return holdsMovie(e);
  }
  // The movie itself: a video longer than 5 minutes (ads and QR clips are short). Whatever holds
  // it is never hidden, at any size (players often start at 0 x 0 and grow once it loads).
  const isMovie = v => v.tagName === 'VIDEO' && isFinite(v.duration) && v.duration > 300;
  function holdsMovie(e) {
    if (isMovie(e)) return true;
    for (const v of e.querySelectorAll('video')) if (isMovie(v)) return true;
    return false;
  }
  function playerBox(m) {     // the player's own box: the video's ancestor that's about its size
    let box = m;
    const v = area(m.getBoundingClientRect());
    for (let p = parentOf(m); p && p.nodeType === 1 && p !== document.body && p !== document.documentElement; p = parentOf(p)) {
      if (area(p.getBoundingClientRect()) > v * 2.2 + 20000) break;
      box = p;
    }
    return box;
  }
  function contains(a, b) {   // a contains b (through shadow roots too)
    for (let x = b; x; x = parentOf(x)) if (x === a) return true;
    return false;
  }
  // A see-through tint (e.g. 50% white or black) rather than buttons or a picture.
  const tintSince = new WeakMap();
  function lastingTint(e, st) {   // tinted for over 0.6 s (not just a player's fade-in / fade-out)
    if (!tint(e, st)) { tintSince.delete(e); return false; }
    const now = Date.now();
    if (!tintSince.has(e)) { tintSince.set(e, now); soonAgain(); return false; }
    return now - tintSince.get(e) > 600;
  }
  function tint(e, st) {
    if (st.backgroundImage && st.backgroundImage !== 'none') return false;   // a poster / picture
    if (e.querySelector('video')) return false;
    // A poster (a picture filling most of it) isn't a tint; a small picture (a QR code) is fine.
    const ea = area(e.getBoundingClientRect());
    for (const i of e.querySelectorAll('img,picture')) if (area(i.getBoundingClientRect()) > ea * 0.5) return false;
    const m = /rgba?\(([^)]+)\)/.exec(st.backgroundColor || '');
    const alpha = m ? (m[1].split(',')[3] === undefined ? 1 : parseFloat(m[1].split(',')[3])) : 0;
    const op = parseFloat(st.opacity || '1'), seen = alpha * (isFinite(op) ? op : 1);
    return seen > 0.15 && seen < 0.97;    // part see-through, like a 50% white screen
  }

  const hidden = new Set(), before = new Map();
  function hide(e, why) {
    if (holdsMovie(e)) return;                   // never the movie (or what holds it)
    if (!before.has(e)) before.set(e, ['display', 'visibility', 'pointer-events'].map(k =>
      [k, e.style.getPropertyValue(k), e.style.getPropertyPriority(k)]));
    e.style.setProperty('display', 'none', 'important');
    e.style.setProperty('visibility', 'hidden', 'important');
    e.style.setProperty('pointer-events', 'none', 'important');
    if (hidden.has(e)) return;
    hidden.add(e);
    try { console.log('[video tv] overlay removed (' + why + ')', e.tagName, String(e.className || '').slice(0, 60)); } catch (x) { }
    for (const r of [document.documentElement, document.body]) {   // popups often lock scrolling
      if (r && getComputedStyle(r).overflow === 'hidden') r.style.setProperty('overflow', 'auto', 'important');
    }
  }
  function unhide(e, why) {      // put it back exactly as the site had it
    hidden.delete(e);
    for (const [k, v, pr] of before.get(e) || []) { if (v) e.style.setProperty(k, v, pr); else e.style.removeProperty(k); }
    before.delete(e);
    try { console.log('[video tv] overlay put back (' + why + ')', e.tagName); } catch (x) { }
  }
  // The movie loaded or started inside something hidden: that was the player, show it again.
  for (const t of ['loadedmetadata', 'durationchange', 'playing']) document.addEventListener(t, ev => {
    if (!isMovie(ev.target)) return;
    for (const e of [...hidden]) if (contains(e, ev.target)) unhide(e, 'the movie is in it');
  }, true);

  // The biggest thing around `hit` that still doesn't hold the player = the whole overlay.
  function overlayRoot(hit, stopAt) {
    let o = hit;
    for (let p = parentOf(o); p && p.nodeType === 1 && p !== document.body && p !== document.documentElement; p = parentOf(p)) {
      if (p === stopAt || holdsPlayer(p) || contains(p, stopAt)) break;
      o = p;
    }
    return o;
  }
  // Is e on top of something else (not just the page's own layout)? Looks at what's underneath.
  function onTopOfSomething(e, r) {
    const pts = [[0.5, 0.5], [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75]];
    for (const [fx, fy] of pts) {
      const x = r.left + r.width * fx, y = r.top + r.height * fy;
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
      for (const u of document.elementsFromPoint(x, y)) {
        if (u === e || contains(e, u) || contains(u, e) || isOurs(u)) continue;
        if (u === document.body || u === document.documentElement) continue;
        const ur = u.getBoundingClientRect();
        if (area(ur) > 2500) return true;       // a real thing lies under it
      }
    }
    return false;
  }

  function sweep() {
    if (!document.body) return;
    for (const e of [...hidden]) {
      if (!e.isConnected) { hidden.delete(e); before.delete(e); continue; }
      if (holdsMovie(e)) { unhide(e, 'it holds the movie'); continue; }
      if (getComputedStyle(e).display !== 'none') hide(e, 'came back');
    }
    if (document.fullscreenElement) return;
    const sa = innerWidth * innerHeight, scr = screenRect();
    const players = [...document.querySelectorAll('video,iframe,embed,object,canvas')].filter(isPlayer)
      .map(m => ({ m, r: m.getBoundingClientRect(), box: playerBox(m) }));

    // 1) Whatever is on top of the video: check a few spots on it.
    for (const p of players) {
      if (p.m.getClientRects().length === 0) continue;          // hidden by now (e.g. an ad frame just removed)
      const r = p.r;
      for (const [fx, fy] of [[0.5, 0.5], [0.3, 0.3], [0.7, 0.3], [0.3, 0.7], [0.7, 0.7]]) {
        const x = r.left + r.width * fx, y = r.top + r.height * fy;
        if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
        const stack = document.elementsFromPoint(x, y);
        for (const u of stack) {
          if (u === p.m || contains(u, p.m)) break;              // reached the player: nothing more on top
          if (isOurs(u) || hidden.has(u)) continue;
          const o = overlayRoot(u, p.m);
          const ost = getComputedStyle(o), or = o.getBoundingClientRect();
          if (contains(p.box, o)) {
            // Inside the player's own box: its controls / poster stay, but a tinted screen
            // over (nearly) the whole video goes.
            if (coverage(or, r) > 0.8 && !o.querySelector('video') && lastingTint(o, ost)) hide(o, 'tinted screen over the video');
            break;
          }
          if (coverage(or, r) > 0.3 || area(or) > sa * 0.2) hide(o, 'on top of the video');
          break;
        }
      }
    }

    // 2) Big layers over the page (popups, backdrops, tinted screens), video or not.
    const els = document.body.getElementsByTagName('*');
    for (let i = 0; i < els.length && i < 8000; i++) {
      const e = els[i];
      if (hidden.has(e) || isOurs(e)) continue;
      const st = getComputedStyle(e);
      if (st.display === 'none' || st.visibility === 'hidden') continue;
      if (st.position !== 'fixed' && st.position !== 'absolute') continue;
      const r = e.getBoundingClientRect();
      if (coverage(r, scr) < 0.5) continue;
      if (holdsPlayer(e)) continue;                               // the player (or the whole page) itself
      if (players.some(p => contains(p.box, e))) continue;        // the player's own layers (rule 1 handles tints)
      // Fixed to the screen: on top of anything, or tinted. Otherwise (absolute) only when it's
      // tinted or forced to the very front (players build their own full-size layers like this).
      const front = parseInt(st.zIndex, 10) >= 1000;
      if (st.position === 'fixed' ? onTopOfSomething(e, r) || lastingTint(e, st)
          : lastingTint(e, st) || (front && onTopOfSomething(e, r))) hide(e, 'covers the screen');
    }
  }
  // Menu ☰ on the remote (site-control.js presses Escape on the page): remove whatever lies on
  // top of the player, no questions asked (never the movie / player itself). In every frame.
  const zapped = new Set();
  function zap() {
    if (!document.body) return;
    const isP = m => m && m.nodeType === 1 && (isPlayer(m) || isMovie(m));
    let n = 0;
    for (let i = 1; i <= 4; i++) for (let j = 1; j <= 4; j++) {
      const stack = document.elementsFromPoint(innerWidth * i / 5, innerHeight * j / 5);
      // The player at this spot = the bottom-most video / player-sized frame. Everything lying on
      // top of it goes, even another big frame (ads come as full-screen frames too).
      let k = -1;
      for (let x = stack.length - 1; x >= 0; x--) if (isP(stack[x])) { k = x; break; }
      if (k < 0) continue;
      const player = stack[k];
      for (let x = 0; x < k; x++) {
        const u = stack[x];
        if (isOurs(u) || hidden.has(u) || contains(u, player) || area(u.getBoundingClientRect()) < 1500) continue;
        let o = u;                                                  // the whole layer, up to the player
        for (let p = parentOf(o); p && p.nodeType === 1 && p !== document.body && p !== document.documentElement; p = parentOf(p)) {
          if (contains(p, player) || holdsMovie(p)) break;
          o = p;
        }
        if (holdsMovie(o) || hidden.has(o)) continue;
        hide(o, 'Menu: removed by hand'); zapped.add(o); n++;
      }
    }
    for (const f of document.querySelectorAll('iframe')) { try { f.contentWindow.postMessage({ [MSG]: 'zap' }, '*'); } catch (x) { } }
    return n;
  }
  function unzap() { for (const e of zapped) if (hidden.has(e)) unhide(e, 'Menu: put back'); zapped.clear(); }
  function menuKey() {             // remove what's on top; if there's nothing left, put it all back
    if (zap() || !zapped.size) return;
    unzap();
    for (const f of document.querySelectorAll('iframe')) { try { f.contentWindow.postMessage({ [MSG]: 'unzap' }, '*'); } catch (x) { } }
  }
  window.addEventListener('message', ev => {
    if (!ev.data || typeof ev.data !== 'object' || ev.source !== window.parent) return;
    if (ev.data[MSG] === 'zap') zap(); else if (ev.data[MSG] === 'unzap') unzap();
  });
  window.addEventListener('keydown', ev => {
    if (!ev.isTrusted || ev.key !== 'Escape') return;
    ev.preventDefault(); ev.stopImmediatePropagation();
    if (!TOP) { window.top.postMessage({ [MSG]: 'zaptop' }, '*'); return; }
    menuKey();
  }, true);
  if (TOP) window.addEventListener('message', ev => { if (ev.data && typeof ev.data === 'object' && ev.data[MSG] === 'zaptop') menuKey(); });

  function soonAgain() { setTimeout(soon, 700); }
  let sweepQueued = false;
  function soon() { if (!sweepQueued) { sweepQueued = true; setTimeout(() => { sweepQueued = false; try { sweep(); } catch (x) { } }, 150); } }
  new MutationObserver(soon).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
  setInterval(soon, 1000);
  document.addEventListener('DOMContentLoaded', soon);

  document.addEventListener('focusin', () => {
    const a = deepActive();
    // The site moved focus itself (e.g. to the next menu item): follow it, unless it's something
    // huge like the whole video or player box (from there ▲ ▼ ◀ ▶ would lead nowhere useful).
    if (a && a !== current && a.tagName !== 'IFRAME' && a.id !== '__vtvRing' && !huge(a)) current = a;
    ring();
  }, true);
})();
