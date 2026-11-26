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
  // Sites lay things over the page or the video: see-through "click catchers" that open ads,
  // full-screen popups and dimmed backdrops. Anything like that is hidden as soon as it shows.
  // The video player itself is kept: anything containing a video / player frame, and the player's
  // own layers (inside the same player box as the video), stay.
  const MEDIA = 'video,iframe,embed,object,canvas';
  function hasMedia(e) { return e.matches(MEDIA) || !!e.querySelector(MEDIA); }
  function isOurs(e) { return e.id === '__vtvRing'; }
  function coverage(r, m) {   // how much of rect m is covered by rect r (0..1)
    const w = Math.min(r.right, m.right) - Math.max(r.left, m.left), h = Math.min(r.bottom, m.bottom) - Math.max(r.top, m.top);
    return w > 0 && h > 0 ? (w * h) / Math.max(1, m.width * m.height) : 0;
  }
  function playerBox(m) {     // the player's own box: the video's ancestor that's about its size
    let box = m;
    for (let p = parentOf(m); p && p.nodeType === 1 && p !== document.body && p !== document.documentElement; p = parentOf(p)) {
      const r = p.getBoundingClientRect(), v = m.getBoundingClientRect();
      if (r.width * r.height > v.width * v.height * 2.2 + 20000) break;
      box = p;
    }
    return box;
  }
  function insideComposed(e, box) {
    for (let x = e; x; x = parentOf(x)) if (x === box) return true;
    return false;
  }
  function hide(e, why) {
    if (e.__vtvHidden) return;
    e.__vtvHidden = 1;
    e.style.setProperty('display', 'none', 'important');
    e.style.setProperty('pointer-events', 'none', 'important');
    try { console.log('[video tv] overlay removed (' + why + ')', e.tagName, e.className || ''); } catch (x) { }
    for (const r of [document.documentElement, document.body]) {   // popups often lock scrolling
      if (r && getComputedStyle(r).overflow === 'hidden') r.style.setProperty('overflow', 'auto', 'important');
    }
  }
  function sweep() {
    if (document.fullscreenElement || !document.body) return;
    const vw = innerWidth, vh = innerHeight, screenArea = vw * vh;
    const medias = [...document.querySelectorAll('video,iframe')].map(m => ({ m, r: m.getBoundingClientRect() }))
      .filter(x => x.r.width * x.r.height > screenArea * 0.04);
    const boxes = medias.map(x => playerBox(x.m));
    const els = document.body.getElementsByTagName('*');
    for (let i = 0; i < els.length && i < 8000; i++) {
      const e = els[i];
      if (e.__vtvHidden || isOurs(e) || e.matches(MEDIA)) continue;
      const st = getComputedStyle(e);
      if (st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none') continue;
      if (st.position !== 'fixed' && st.position !== 'absolute' && st.position !== 'sticky') continue;
      const r = e.getBoundingClientRect();
      if (r.width < 40 || r.height < 40) continue;
      // 1) A layer over most of the screen (popup, backdrop, see-through click catcher).
      if (st.position === 'fixed' && coverage(r, { left: 0, top: 0, right: vw, bottom: vh, width: vw, height: vh }) > 0.5) {
        if (!hasMedia(e)) { hide(e, 'covers the screen'); continue; }
      }
      // 2) Something lying over the video that isn't part of the player.
      for (let j = 0; j < medias.length; j++) {
        const mr = medias[j].r;
        if (coverage(r, mr) < 0.5 || hasMedia(e)) continue;
        if (insideComposed(e, boxes[j])) continue;               // the player's own poster, controls...
        const z = parseInt(st.zIndex, 10);
        if (st.position === 'fixed' || z > 0 || e.tagName === 'A') { hide(e, 'covers the video'); break; }
      }
    }
  }
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
