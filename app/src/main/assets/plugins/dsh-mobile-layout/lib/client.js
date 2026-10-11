window.__ModuleLoader__.load({ id: "dsh-mobile-layout", factory: (require) => {
var module = { exports: {} };
var exports = module.exports;

// dsh-mobile-layout client (v5, 2026-10-11): always-mobile drawer overlay for
// the DSH WebUI — a pure visual layer that never owns official sidebar state.
//
// History:
//   v1/v2 drove the official toggle button (click to collapse on boot) and
//   mirrored `data-sidebar-collapsed`; a lost synthetic click (slow boot,
//   React event system not hydrated, many plugins) left the official sidebar
//   expanded under a 0-width pinned column: user got neither sidebar nor FAB.
//   v3 removed that dependency — the plugin owns `drawerOpen`, CSS maps it to
//   hidden/fixed-drawer, and a 250ms reconciler keeps marks alive.
//   v4 (this file) fixes what the 0.1.2 official locale rename broke and
//   hardens every anchor:
//     - Official toggle labels changed: 'toggle.open' is now 打开侧边栏 (was
//       打开侧栏). The label table carries BOTH generations plus English, and
//       a structural fallback resolves the toggle even when the locale is
//       unknown (collapsed rail: the column's first button IS the toggle).
//     - Columns are pinned by JS-applied marker classes (dshml-col-1/2/3)
//       instead of relying on official CSS-module class names surviving a
//       build. `[class*=sidebarCol]` stays as the secondary locator.
//     - Opening the drawer now asks the official side to render the full
//       sidebar when it is still collapsed (best effort, retried once), so a
//       narrow boot never shows a rail-only drawer — that was the "hamburger
//       opens an empty drawer" report.
//     - Composer capsule tightening kept (data-composer-card anchor).
//   v5 (this file) adds what four community mobile plugins shipped first and
//   this one lacked — but only the parts that are pure additions, because v4
//   is verified working and a presentation plugin must not regress a good
//   layout to chase a nicer one:
//     - CANARY (idea from dsh-mobile-xc's "canary"): v1..v4 each broke on an
//       official-DOM drift (locale rename 0.1.2, class renames, sidebar-right
//       going absolute in 0.2.0) and every break was found by a human noticing
//       a broken screen. The canary notices for us: once the frame has been
//       marked successfully at least once, a sustained loss of every column
//       anchor means the official DOM moved out from under us — so we
//       SELF-UNINSTALL and fall back to the official layout rather than keep
//       applying CSS that no longer matches. Fail-safe over fail-broken.
//     - Composer touch/font floor (idea from dsh-webui-mobile + dsh-mobile-theme):
//       >=16px input text (below that, mobile WebView auto-zooms on focus and
//       the page never returns to scale) and >=44px hit targets in the
//       composer row and drawer (WCAG 2.5.5). Scoped to OUR surfaces only —
//       never a blanket rule over official buttons, which would reflow rows
//       we do not own.

var name = "mobile-layout";
var inject = [];

// Both locale generations of the official toggle + English, so an upgraded
// (or downgraded) host keeps resolving the button.
var TOGGLE_LABELS = [
  "\u6253\u5f00\u4fa7\u8fb9\u680f", "\u6536\u8d77\u4fa7\u8fb9\u680f", // 打开/收起侧边栏 (0.1.2+)
  "\u6253\u5f00\u4fa7\u680f", "\u6536\u8d77\u4fa7\u680f",             // 打开/收起侧栏 (older)
  "Open sidebar", "Collapse sidebar",
];

var CSS = [
  /* viewport / safe-area shell defaults (no-op on desktop) */
  "@supports (height: 100dvh) { html, body, #root { height: 100dvh; } }",
  "body { box-sizing: border-box; padding-top: env(safe-area-inset-top, 0px); padding-right: env(safe-area-inset-right, 0px); padding-bottom: env(safe-area-inset-bottom, 0px); padding-left: env(safe-area-inset-left, 0px); overscroll-behavior-y: none; }",
  /* Lock the official frame to a single full-width chat track */
  ".dshml-frame { grid-template-columns: 0px minmax(0, 1fr) var(--dshml-rightbar, 0px) !important; }",
  ".dshml-frame [data-dsh-stats] { width: auto !important; display: flex !important; padding: 0 !important; min-height: 0 !important; margin: 0 !important; border-top: 0 !important; }",
  /* Aqua draws its divider on the stats block itself, so it spans only the two readings.
     Draw it on the composer dock row instead: that row is exactly the input box's width and
     already holds all three readings (the two statistics plus the context meter). */
  ".dshml-frame div:has(> [data-slot=\"conversation.composer.dock\"]) { border-top: 1px solid #132d532e; }",
  "body[data-ds-dark-theme] .dshml-frame div:has(> [data-slot=\"conversation.composer.dock\"]) { border-top-color: #94b4dc3d; }",
  /* Pin every column to its explicit track: the JS marker classes are the
     primary anchors (build-proof), the official class-name match is a
     secondary safety net. A hidden first grid item must never be able to
     auto-shift the others (details wearing the center track was the
     "details page" leak). */
  ".dshml-frame > .dshml-col-1, .dshml-frame > [class*='sidebarCol'] { grid-column: 1 !important; }",
  ".dshml-frame > .dshml-col-2, .dshml-frame > [class*='centerCol'] { grid-column: 2 !important; }",
  ".dshml-frame > .dshml-col-3, .dshml-frame > [class*='detailsCol'] { grid-column: 3 !important; }",
  /* Hide official drag handles (sidebar/details) — nothing to resize */
  ".dshml-frame [data-side='sidebar'], .dshml-frame [data-side='details'] { display: none !important; }",
  /* Zero-width column must not keep a themed card shell. Themes that dress the sidebar column as a glass card (dsh-client-ui-aqua: margin 12px, padding 10px 12px 14px, 1px border, rounded translucent background) keep more than their margin once this layout pins the track to 0px: a grid item's default min-width:auto refuses to shrink below its own padding + border, so the box still measured ~25.4px (24px padding + 2px border) inside a 0px track — a solid dark strip at the left edge. Clearing margin alone is not enough, and the strip is easy to miss in DevTools because the visible part is padding. Scoped to the closed state so the open drawer (.dshml-open, a separate fixed 280px element with its own width rule) is untouched. */
  ".dshml-frame:not(.dshml-open) > .dshml-col-1, .dshml-frame:not(.dshml-open) > [class*='sidebarCol'] { margin: 0 !important; padding: 0 !important; border: 0 !important; background: none !important; box-shadow: none !important; border-radius: 0 !important; min-width: 0 !important; width: 0 !important; }",
  /* Sidebar column: hidden while closed (FAB opens it).
     visibility (not display:none) so the column keeps its grid slot — a
     vanished first grid item would shift center/details into wrong tracks. */
  ".dshml-frame > .dshml-sidebar { visibility: hidden !important; }",
  /* Sidebar column: fixed 280px drawer while open.
     z-index 14 (was 40): the drawer only has to outrank the in-flow chat
     surface (composerSeat 7/9, width handles 8, sticky code headers 6). At 40
     it also outranked the floating-layer band and swallowed popovers that
     belong to its own footer entries — most visibly the Cordis panel
     (z-index 30), whose plugin cards carry the dynamic-plugin approval
     buttons, so the approval action became unreachable. */
  ".dshml-frame.dshml-open > .dshml-sidebar { visibility: visible !important; display: block !important; position: fixed; top: env(safe-area-inset-top, 0px); bottom: env(safe-area-inset-bottom, 0px); left: env(safe-area-inset-left, 0px); z-index: 14; width: 280px !important; border-right: none; box-shadow: var(--dsw-shadow-lv3); animation: dshml-drawer-in 220ms var(--ds-ease-in-out); }",
  "@keyframes dshml-drawer-in { from { transform: translateX(-16px); opacity: 0.4; } to { transform: translateX(0); opacity: 1; } }",
  "@media (prefers-reduced-motion: reduce) { .dshml-frame.dshml-open > .dshml-sidebar { animation: none; } }",
  /* Floating menu FAB */
  ".dshml-fab { position: fixed; left: calc(12px + env(safe-area-inset-left, 0px)); bottom: calc(env(safe-area-inset-bottom, 0px) + 188px); z-index: 30; display: grid; place-items: center; width: 44px; height: 44px; padding: 0; border: 1px solid var(--dsw-alias-border-l2, rgba(0,0,0,.1)); border-radius: 999px; color: var(--dsw-alias-label-primary, #0f1115); background: var(--dsw-alias-button-floating-fill, #fff); box-shadow: var(--dsw-shadow-lv2); cursor: pointer; -webkit-tap-highlight-color: transparent; }",
  ".dshml-fab:active { background: var(--dsw-alias-button-floating-hover, #eef0f3); }",
  ".dshml-fab[hidden] { display: none; }",
  /* Tap-to-close scrim */
  /* Tap-to-close scrim. z-index 13 (was 35): still above the whole chat
     surface so tapping anywhere closes the drawer, but below the floating
     band so an open popover is never dimmed or click-blocked. */
  ".dshml-scrim { position: fixed; inset: 0; z-index: 13; background: rgba(0, 0, 0, 0.36); touch-action: none; animation: dshml-scrim-in 160ms ease-out; }",
  "@keyframes dshml-scrim-in { from { opacity: 0; } to { opacity: 1; } }",
  "@media (prefers-reduced-motion: reduce) { .dshml-scrim { animation: none; } }",
  ".dshml-scrim[hidden] { display: none; }",
  /* Composer capsule tightening (official anchor: data-composer-card; the
     control row is tagged by JS as dshml-composer-row) */
  "[data-composer-card] { border-radius: 18px; gap: 8px !important; padding-top: 8px !important; }",
  "[data-composer-card] > .dshml-composer-row { gap: 8px !important; padding: 2px 6px 5px !important; }",
  "[data-composer-card] > .dshml-composer-row > div { gap: 8px !important; }",
    /* ── v5: composer touch + font floor (mobile WebView) ───────────────
       A 16px floor on the input text is not cosmetic: mobile WebView zooms the
       whole page when a control with font-size < 16px receives focus, and the
       zoom does not always unwind — the layout ends up permanently scaled.
       Scoped to the composer's editable surface so official styling elsewhere
       is untouched. */
    "[data-composer-card] textarea, [data-composer-card] [contenteditable='true'], [data-composer-card] input { font-size: max(16px, 1em) !important; }",
    /* Hit targets: WCAG 2.5.5 asks for 44px. Applied to the composer row and
       the drawer only — a blanket rule over every official button would
       reflow rows whose heights we do not control. */
    "[data-composer-card] > .dshml-composer-row button { min-width: 44px !important; min-height: 44px !important; }",
    ".dshml-fab, .dshml-frame.dshml-open > .dshml-sidebar button { min-height: 44px; }",
    /* The composer is the bottom-most element; on notched devices the home
       indicator sits on top of it unless it carries the inset itself. */
    "[data-composer-card] { margin-bottom: env(safe-area-inset-bottom, 0px) !important; }",
    /* Prevent the drawer's own scroll from chaining into the chat behind it. */
    ".dshml-frame.dshml-open > .dshml-sidebar { overscroll-behavior: contain; -webkit-overflow-scrolling: touch; }",
  ].join("\n");

/** Consecutive unhealthy reconciles (250ms each) before the canary trips. 40
 * passes is ~10s: long enough that a remount or a slow React commit never
 * trips it, short enough that a user is not left staring at a broken layout. */
var CANARY_MISS_LIMIT = 40;

var FAB_SVG = '<svg viewBox="0 0 20 20" width="20" height="20" aria-hidden="true"><path d="M3 5.5h14M3 10h14M3 14.5h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" fill="none"/></svg>';

function installStyles() {
  var tagId = "dsh-mobile-layout/styles";
  if (document.querySelector('style[data-plugin-css="dsh-mobile-layout/styles"]') !== null) return;
  var tag = document.createElement("style");
  tag.dataset.plugin = "dsh-mobile-layout";
  tag.dataset.pluginCss = tagId;
  tag.textContent = CSS;
  document.head.appendChild(tag);
}

function findFrame() {
  var overlay = document.querySelector("[data-shell-overlay]");
  return overlay === null ? null : overlay.parentElement;
}

/** Climb from a descendant to the direct frame child that wraps it. */
function directChildOf(frame, node) {
  var el = node;
  while (el !== null && el.parentElement !== frame) el = el.parentElement;
  return el === frame ? null : el;
}

/** Official toggle by accessible name, both locale generations + English. */
function findToggleByLabel(frame) {
  for (var i = 0; i < TOGGLE_LABELS.length; i++) {
    var btn = frame.querySelector('button[aria-label="' + TOGGLE_LABELS[i] + '"]');
    if (btn !== null) return btn;
  }
  return null;
}

/** The frame's flow columns in official render order: sidebar / center /
 * details. The overlay layer and the drag handles are skipped by their own
 * data attributes, so nothing about official class names is assumed. */
function flowColumns(frame) {
  var flow = [];
  for (var i = 0; i < frame.children.length; i++) {
    var child = frame.children[i];
    if (child.hasAttribute("data-shell-overlay")) continue;
    if (child.hasAttribute("data-side")) continue;
    flow.push(child);
  }
  return flow;
}

/** The sidebar column: class-name match first (official build keeps the
 * original suffix), then the ancestor chain of the labeled toggle, then the
 * frame's first flow column (render order is contract). */
function findSidebarColumn(frame) {
  var byClass = frame.querySelector('[class*="sidebarCol"]');
  if (byClass !== null) {
    var col = directChildOf(frame, byClass);
    if (col !== null) return col;
  }
  var toggle = findToggleByLabel(frame);
  if (toggle !== null) {
    var viaToggle = directChildOf(frame, toggle);
    if (viaToggle !== null) return viaToggle;
  }
  var flow = flowColumns(frame);
  return flow.length > 0 ? flow[0] : null;
}

/** The toggle button that EXPANDS a collapsed sidebar: the labeled button
 * when the locale is known, otherwise the collapsed rail's first button
 * (brand button only renders while wide, so the first one IS the toggle). */
function findExpandButton(frame) {
  var labeled = findToggleByLabel(frame);
  if (labeled !== null) return labeled;
  var col = findSidebarColumn(frame);
  if (col === null) return null;
  return col.querySelector("button");
}

/** Tag the three flow columns with build-proof marker classes (dshml-col-N),
 * in official order: sidebar / center / details. Overlay layer and drag
 * handles are skipped by their own data attributes. */
/** How many of the three flow columns currently carry a layout marker.
 * The canary's only input: if this drops to 0 while the frame is still there,
 * the official DOM no longer matches what our CSS is written against. */
function markedColumnCount(frame) {
  var count = 0;
  for (var i = 0; i < frame.children.length; i++) {
    var child = frame.children[i];
    if (child.classList.contains("dshml-col-1") ||
        child.classList.contains("dshml-col-2") ||
        child.classList.contains("dshml-col-3")) count++;
  }
  return count;
}

function markColumns(frame) {
  var flow = flowColumns(frame);
  if (flow.length < 3) return null;
  // Every column resolves by its own locator first (official class-name suffix
  // when present), with render order as the fallback: sidebar / center /
  // details is the frame contract, and an extra unrelated flow child inserted
  // by a future release must not shift these marks.
  var byClass = function (suffix) {
    var hit = frame.querySelector('[class*="' + suffix + '"]');
    return hit === null ? null : directChildOf(frame, hit);
  };
  var sidebar = findSidebarColumn(frame) ?? flow[0];
  var rest = flow.filter(function (el) { return el !== sidebar; });
  var center = byClass("centerCol") ?? rest[0] ?? null;
  // details sits AFTER center in the frame contract: take the next flow child
  // rather than the first leftover (an unrelated inserted element must not be
  // mistaken for it).
  var afterCenter = center === null ? [] : flow.slice(flow.indexOf(center) + 1);
  var details = byClass("detailsCol") ?? afterCenter[0] ?? null;
  if (sidebar !== null) sidebar.classList.add("dshml-col-1", "dshml-sidebar");
  if (center !== null) center.classList.add("dshml-col-2");
  if (details !== null) details.classList.add("dshml-col-3");
  markComposerRow();
  return { sidebar: sidebar, center: center, details: details };
}

/** Tag the composer's control row (last direct child holding buttons) so CSS
 * can tighten its rhythm; harmless no-op before the composer mounts. */
function markComposerRow() {
  var card = document.querySelector("[data-composer-card]");
  if (card === null) return;
  var row = null;
  for (var i = card.children.length - 1; i >= 0; i--) {
    var child = card.children[i];
    if (child.querySelector("button") !== null) { row = child; break; }
  }
  if (row !== null) row.classList.add("dshml-composer-row");
}

function installLayout(ctx) {
  // The shell mounts the frame after plugin activation: poll until it exists.
  var timer = setInterval(function () {
    var frame = findFrame();
    if (frame === null) return;
    clearInterval(timer);
    setup(ctx, frame);
  }, 300);
  return function () { clearInterval(timer); };
}

function setup(ctx, initialFrame) {
  var fab = document.createElement("button");
  fab.type = "button";
  fab.className = "dshml-fab";
  fab.dataset.role = "dshml-fab";
  fab.setAttribute("aria-label", "打开侧边栏");
  fab.innerHTML = FAB_SVG;

  var scrim = document.createElement("div");
  scrim.className = "dshml-scrim";
  scrim.dataset.role = "dshml-scrim";
  scrim.hidden = true;

  // The plugin owns the drawer state; the official sidebar state machine is
  // only nudged to render content (never used as the open/closed source).
  var drawerOpen = false;
  var markedFrame = null;

  // ── canary ────────────────────────────────────────────────────────────
  // v1..v4 each died on an official-DOM drift and every one was caught by a
  // human looking at a broken screen. This makes the plugin notice instead.
  //
  // Counting only starts AFTER one fully healthy pass: on a cold boot the frame
  // legitimately exists before its columns are populated, and a canary that
  // fires during normal startup would be worse than no canary at all.
  var canaryHealthy = false;
  var canaryMisses = 0;
  var canaryTripped = false;

  /** Give up and hand the UI back to the official layout.
   * Fail-safe rather than fail-broken: CSS written against a DOM that no
   * longer exists is not "partially working", it is actively harmful. */
  function tripCanary(reason) {
    if (canaryTripped) return;
    canaryTripped = true;
    console.warn(
      "[dsh-mobile-layout] canary tripped \u2014 disabling the overlay and falling " +
      "back to the official layout.\nreason: " + reason + "\n" +
      "Most likely the official WebUI DOM changed (an upgrade). This plugin never " +
      "touched official state, so removing it restores the stock UI."
    );
    clearInterval(poll);
    var style = document.querySelector('style[data-plugin-css="dsh-mobile-layout/styles"]');
    if (style !== null) style.remove();
    fab.remove();
    scrim.remove();
    clearFrameMarks(markedFrame);
    markedFrame = null;
  }

  function currentFrame() {
    var f = findFrame();
    return f === null ? initialFrame : f;
  }

  function clearFrameMarks(frame) {
    if (frame === null) return;
    frame.classList.remove("dshml-frame", "dshml-open");
    var marked = frame.querySelectorAll(".dshml-col-1, .dshml-col-2, .dshml-col-3, .dshml-sidebar");
    for (var i = 0; i < marked.length; i++) {
      marked[i].classList.remove("dshml-col-1", "dshml-col-2", "dshml-col-3", "dshml-sidebar");
    }
  }

  /** Ask the official side to render the full sidebar when it is still the
   * collapsed rail. Best effort, retried once after React commits: a lost
   * click means an icon-only drawer, never a broken layout. */
  function requestOfficialExpand() {
    var frame = currentFrame();
    if (frame === null || !frame.hasAttribute("data-sidebar-collapsed")) return;
    var btn = findExpandButton(frame);
    if (btn !== null) btn.click();
    setTimeout(function () {
      var f = currentFrame();
      if (f === null || !f.hasAttribute("data-sidebar-collapsed")) return;
      var retry = findExpandButton(f);
      if (retry !== null) retry.click();
    }, 400);
  }

  // The reconciler: resolve everything live so a remounted frame or a slow
  // boot can never strand stale UI state.
/** 0.2.0: the right panel is `position: absolute` inside the third grid item, so it
 * contributes nothing to that track's size and `auto` resolves to 0. Publish the panel's
 * own width (--dsh-sidebar-width, set inline by ui-sidebar-right) as a frame variable the
 * grid rule reads; that reserves the track and pushes the conversation aside instead of
 * letting the panel hang over it. Zero while the panel is closed/unmounted. */
  function syncRightbarTrack(frame) {
    var panel = frame.querySelector("[data-sidebar-right-panel]");
    // Fullscreen publishes 100vw as its width; a 100vw track would collapse the
    // centre column, and the overlay covers the frame anyway.
    var fullscreen = panel !== null && panel.getAttribute("data-sidebar-right-panel") === "fullscreen";
    var width = panel === null || fullscreen ? "" : (panel.style.getPropertyValue("--dsh-sidebar-width") || "").trim();
    var open = panel !== null && !fullscreen && panel.hasAttribute("data-sidebar-right-open");
    var value = open && width !== "" ? width : "0px";
    if (frame.style.getPropertyValue("--dshml-rightbar") !== value) frame.style.setProperty("--dshml-rightbar", value);
  }
  function sync() {
    var frame = currentFrame();
    if (frame === null) return;
    if (markedFrame !== null && markedFrame !== frame) clearFrameMarks(markedFrame);
    markedFrame = frame;
    frame.classList.add("dshml-frame");
    frame.classList.toggle("dshml-open", drawerOpen);
    scrim.hidden = !drawerOpen;
    fab.hidden = drawerOpen;
    // Always (re)mark the columns: closed → CSS hides the sidebar; open →
    // fixed drawer. Marker classes are idempotent.
    markColumns(frame);
    syncRightbarTrack(frame);

    // canary: a healthy pass arms it, a sustained total loss trips it
    if (!canaryTripped) {
      if (markedColumnCount(frame) >= 3) {
        canaryHealthy = true;
        canaryMisses = 0;
      } else if (canaryHealthy) {
        canaryMisses++;
        if (canaryMisses >= CANARY_MISS_LIMIT) {
          tripCanary("all three column anchors lost for " +
            Math.round(CANARY_MISS_LIMIT * 250 / 1000) + "s (last healthy pass had 3)");
        }
      }
    }
  }

  function openDrawer() {
    drawerOpen = true;
    sync();
    requestOfficialExpand();
  }
  function closeDrawer() {
    drawerOpen = false;
    sync();
  }

  fab.addEventListener("click", openDrawer);
  scrim.addEventListener("click", closeDrawer);

  document.body.appendChild(fab);
  document.body.appendChild(scrim);
  sync();

  // Backstop reconciler: keeps frame marks and drawer state coherent across
  // remounts; cheap no-op when everything already matches.
  var poll = setInterval(sync, 250);

  ctx.effect(function () {
    return function () {
      clearInterval(poll);
      fab.remove();
      scrim.remove();
      clearFrameMarks(markedFrame);
    };
  }, "dsh-mobile-layout: dom");

  console.log("[dsh-mobile-layout] client mounted (v5) \u00b7 canary armed at " +
    CANARY_MISS_LIMIT + " misses");
}

function apply(ctx) {
  ctx.effect(installStyles, "dsh-mobile-layout: styles");
  ctx.effect(function () { return installLayout(ctx); }, "dsh-mobile-layout: layout");
}

exports.name = name;
exports.inject = inject;
exports.apply = apply;
return module.exports; } });
