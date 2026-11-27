// How the TV remote drives the video website (the full-screen site you use after ▶ Open).
//
// This file is LIVE: edit it, push, and the TV uses the new version on its next launch (or
// press Menu ☰). No reinstall. The app (APK) only does the basic actions it is asked for, inside
// its sandbox: move the cursor, tap, put the "finger" down / lift it, scroll, show a text bar,
// close. Everything about WHEN and HOW is decided here.
//
// Two modes (press OK 3 times quickly to switch). The website opens in button mode.
//   cursor mode   a round cursor: arrows move it, OK clicks, hold OK to drag
//   button mode   no cursor: arrows jump between the site's own buttons (play, timeline, captions,
//                 settings...), like a TV browser. On a timeline / volume slider, ◀ ▶ change it
//                 and ▲ ▼ leave it (OK on a slider: ◀ ▶ go past it). OK presses the outlined button.
//                 ⏪ ⏩ jump to the previous / next button in page order.
//                 How it finds the buttons: site-nav.js, which the app puts into the website. That
//                 file also removes overlays (popups, ad layers over the video) in both modes.
//
// Hold Back for 3 seconds to close the website even if this file is broken.
(function () {
  'use strict';

  // ---------- settings: change these freely ----------
  const S = {
    autoClick: false,         // click the middle of the screen as soon as the page shows
    autoHold: false,          // put the finger down in the middle as soon as the page shows (hold mode)
    autoHoldAfterMs: 150,     // ...this long after the page shows (after the click, if autoClick)
    holdToGrabMs: 800,        // hold OK this long to grab (start dragging)
    moveStep: repeat => 14 + Math.min(repeat * 8, 60),   // pixels per arrow press (faster when held)
    dragStep: repeat => 8 + Math.min(repeat * 4, 40),    // same, while holding (finer)
    hint: '',                 // text for a bar at the bottom while the site is shown ('' = no bar)
    // Hide the round cursor after this long without a button press (null = always show).
    // The next arrow / OK press only brings it back, in the same place.
    hideCursorAfterMs: 3000,
    multiPressMs: 400,        // OK presses this close together count as a triple press (switch mode)
    modeHintMs: 3500,         // how long the "button mode" / "cursor mode" note shows
    startMode: 'buttons',     // 'buttons' or 'cursor': the mode the website opens in
  };

  // ---------- talking to the app ----------
  let chain = Promise.resolve();
  let state = {};               // {open, w, h, x, y, dragging, density} from the app
  let holding = false;          // finger down? (known right away, not only after the app answers)
  let swallow = null;          // a key whose press only revealed the cursor
  // Commands run one after another, in order (so a "down" never overtakes a "move").
  function cmd(op, params) {
    if (op === 'down') holding = true;
    else if (op === 'up' || op === 'close') holding = false;
    const q = Object.entries(params || {}).map(([k, v]) => '&' + k + '=' + encodeURIComponent(v)).join('');
    chain = chain.then(() => fetch('api/site?op=' + op + q, { cache: 'no-store' })
      .then(r => r.json()).then(s => { state = s; return s; }).catch(() => state));
    return chain;
  }

  // ---------- behaviour ----------
  let autoDone = false;
  let okStartedDrag = false;   // this OK press began the drag (its release keeps holding)
  let mode = 'cursor';         // 'cursor' | 'buttons'

  // ---------- OK pressed 3 times quickly = switch mode ----------
  // Each press waits that long before it clicks, so a triple press never clicks anything.
  let okCount = 0, lastOkAt = 0, pendingOk = null, pendingFns = [], hintTimer = null;
  function countOk() {           // on each new OK press; true = it was the 3rd (mode switched)
    const now = Date.now();
    okCount = now - lastOkAt < S.multiPressMs ? okCount + 1 : 1;
    lastOkAt = now;
    if (okCount < 3) return false;
    okCount = 0;
    clearTimeout(pendingOk); pendingOk = null; pendingFns = [];
    if (mode === 'cursor' && !(state.nav && state.navReady)) {
      if (state.nav) {           // app can do it, the page script just hadn't arrived: send it now
        sendNav().then(() => cmd('hello')).then(s => {
          note(s && s.navReady ? 'Button mode is ready from the next website you open'
            : 'Button mode couldn\'t start (no connection?) · try again');
        });
      } else {                   // the app on the TV predates button mode (it can't reach into the website)
        note('Button mode needs the app update from Nov 26 (one install, then it updates live)');
      }
      return true;
    }
    setMode(mode === 'cursor' ? 'buttons' : 'cursor');
    return true;
  }
  function okAction(fn) {        // on OK release: do it once it's clear this isn't a triple press
    pendingFns.push(fn);
    clearTimeout(pendingOk);
    pendingOk = setTimeout(() => { pendingOk = null; const f = pendingFns; pendingFns = []; f.forEach(g => g()); },
      Math.max(0, S.multiPressMs - (Date.now() - lastOkAt)));
  }
  function note(text) {
    clearTimeout(hintTimer);
    cmd('hint', { text });
    hintTimer = setTimeout(() => cmd('hint', { text: S.hint }), S.modeHintMs);
  }
  function setMode(m) {
    mode = m;
    clearTimeout(hideTimer);
    if (holding) cmd('up');
    if (m === 'buttons') {
      if (!hiddenAt && state.w) hiddenAt = { x: state.x, y: state.y };
      if (state.w) cmd('cursor', { x: state.w + 500, y: state.h + 500 });   // cursor off screen
      cmd('focus');
      note('Button mode: ◀ ▲ ▼ ▶ move between buttons · OK press · OK ×3 = cursor');
    } else {
      if (!reveal()) cmd('center');
      idle();
      note('Cursor mode · OK ×3 = button mode');
    }
  }

  // ---------- hiding the cursor when the remote isn't used ----------
  let hideTimer = null, hiddenAt = null;   // hiddenAt = where the cursor was when hidden
  function idle() {
    clearTimeout(hideTimer);
    if (S.hideCursorAfterMs == null || mode !== 'cursor') return;
    hideTimer = setTimeout(() => {
      if (hiddenAt || !state.open || !state.w) return;
      hiddenAt = { x: state.x, y: state.y };
      cmd('cursor', { x: state.w + 500, y: state.h + 500 });   // parked off screen
    }, S.hideCursorAfterMs);
  }
  function reveal() {          // true if the cursor was hidden (this press only shows it)
    if (!hiddenAt) return false;
    if (state.w && (hiddenAt.x > state.w || hiddenAt.y > state.h)) cmd('center');   // was never on screen
    else cmd('cursor', hiddenAt);
    hiddenAt = null;
    return true;
  }

  function onShown() {
    holding = false;
    hiddenAt = null;
    mode = 'cursor';
    okCount = 0; clearTimeout(pendingOk); pendingOk = null; pendingFns = []; clearTimeout(hintTimer);
    idle();
    autoDone = false;
    okStartedDrag = false;
    cmd('hello').then(s => {
      // Button mode needs the app version that can press keys on the website (s.nav), and the
      // page script (site-nav.js). If the script never arrived (e.g. no connection at launch),
      // send it again now; it applies to the next website opened.
      if (s && s.nav && !s.navReady) sendNav();
      if (S.startMode === 'buttons' && s && s.nav && s.navReady) setMode('buttons');
    });
    if (S.hint) cmd('hint', { text: S.hint });
  }

  function onVisible() {         // the website's page just appeared
    if ((!S.autoClick && !S.autoHold) || autoDone) return;
    autoDone = true;
    cmd('center');
    if (S.autoClick) cmd('tap');
    if (S.autoHold) setTimeout(() => cmd('down'), S.autoHoldAfterMs);
  }

  function onButtonsKey(k) {     // button mode: real key presses on the page
    switch (k.key) {
      case 'left': case 'right': case 'up': case 'down':
        if (k.down) cmd('key', { code: k.key });   // held = repeats (e.g. keeps seeking)
        break;
      case 'ok':
        if (!k.down) okAction(() => cmd('key', { code: 'enter' }));
        break;
      case 'playpause':
        if (k.down && k.repeat === 0) cmd('key', { code: 'space' });
        break;
      case 'rewind': case 'forward':                 // jump through the buttons in page order
        if (k.down) cmd('key', { code: 'tab', shift: k.key === 'rewind' ? 1 : 0 });
        break;
      case 'back':
        if (!k.down) cmd('close');
        break;
    }
  }

  function onKey(k) {
    if (k.key === 'ok' && k.down && k.repeat === 0 && countOk()) { swallow = 'ok'; return; }
    if (swallow === 'ok' && k.key === 'ok') { if (!k.down) swallow = null; return; }
    if (mode === 'buttons') { onButtonsKey(k); return; }
    idle();
    if (k.key !== 'back' && k.down && k.repeat === 0 && reveal()) { swallow = k.key; return; }
    if (swallow === k.key) { if (!k.down) swallow = null; return; }   // the rest of that press
    if (k.key === 'back') reveal();
    const step = holding ? S.dragStep(k.repeat) : S.moveStep(k.repeat);
    switch (k.key) {
      case 'left':  if (k.down) cmd('move', { dx: -step, dy: 0 }); break;
      case 'right': if (k.down) cmd('move', { dx: step, dy: 0 }); break;
      case 'up':    if (k.down) cmd('move', { dx: 0, dy: -step }); break;
      case 'down':  if (k.down) cmd('move', { dx: 0, dy: step }); break;
      case 'ok':
        // Short press = click. Hold = grab (then arrows drag). Press again = let go.
        if (k.down) {
          if (k.repeat === 0) okStartedDrag = false;
          else if (!holding && !okStartedDrag && k.held >= S.holdToGrabMs) { okStartedDrag = true; cmd('down'); }
        } else if (okStartedDrag) {
          okStartedDrag = false;            // keep holding after the long press ends
        } else if (holding) {
          cmd('up');
        } else {
          okAction(() => cmd('tap'));
        }
        break;
      case 'playpause':                     // also grabs / lets go
        if (k.down && k.repeat === 0) cmd(holding ? 'up' : 'down');
        break;
      case 'back':                          // while holding: let go; otherwise close the website
        if (!k.down) cmd(holding ? 'up' : 'close');
        break;
    }
  }

  // The page script for the website (overlay remover + button mode), sent to the app once.
  function sendNav() {
    return fetch('site-nav.js?t=' + Date.now(), { cache: 'no-store' }).then(r => r.ok ? r.text() : null)
      .then(src => src ? cmd('navscript', { src }) : null).catch(() => null);
  }
  sendNav();

  window.SiteControl = {
    settings: S,
    onEvent(ev) {
      if (ev.type === 'shown') onShown();
      else if (ev.type === 'visible') onVisible();
      else if (ev.type === 'key') onKey(ev);
      // also: 'loaded' (page finished), 'checking' / 'rejected' (a video link is being checked)
    },
    get mode() { return mode; },
  };
})();
