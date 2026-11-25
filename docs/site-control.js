// How the TV remote drives the video website (the full-screen site you use after ▶ Open).
//
// This file is LIVE: edit it, push, and the TV uses the new version on its next launch (or
// press Menu ☰). No reinstall. The app (APK) only does the basic actions it is asked for, inside
// its sandbox: move the cursor, tap, put the "finger" down / lift it, scroll, show a text bar,
// close. Everything about WHEN and HOW is decided here.
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
    // After each of your clicks (short OK), also click here, this long after. null = off.
    // x / y are fractions of the screen: 0, 0 = top left, 1, 1 = bottom right.
    followUpClick: { afterMs: 500, x: 0.03, y: 0.97, onlyFirst: false },
  };

  // ---------- talking to the app ----------
  let chain = Promise.resolve();
  let state = {};               // {open, w, h, x, y, dragging, density} from the app
  // Commands run one after another, in order (so a "down" never overtakes a "move").
  let holding = false;          // finger down? (known right away, not only after the app answers)
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
  let followUps = 0;

  // Your click, then (if set) the extra click near the bottom left.
  function userClick() {
    cmd('tap');
    const f = S.followUpClick;
    if (!f || (f.onlyFirst && followUps > 0)) return;
    followUps++;
    setTimeout(() => {
      if (!state.open || !state.w) return;
      cmd('tap', { x: Math.round(state.w * f.x), y: Math.round(state.h * f.y) });
    }, f.afterMs);
  }

  function onShown() {
    holding = false;
    autoDone = false;
    okStartedDrag = false;
    followUps = 0;
    cmd('hello');
    if (S.hint) cmd('hint', { text: S.hint });
  }

  function onVisible() {         // the website's page just appeared
    if ((!S.autoClick && !S.autoHold) || autoDone) return;
    autoDone = true;
    cmd('center');
    if (S.autoClick) cmd('tap');
    if (S.autoHold) setTimeout(() => cmd('down'), S.autoHoldAfterMs);
  }

  function onKey(k) {
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
          userClick();
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

  window.SiteControl = {
    settings: S,
    onEvent(ev) {
      if (ev.type === 'shown') onShown();
      else if (ev.type === 'visible') onVisible();
      else if (ev.type === 'key') onKey(ev);
      // also: 'loaded' (page finished), 'checking' / 'rejected' (a video link is being checked)
    },
  };
})();
