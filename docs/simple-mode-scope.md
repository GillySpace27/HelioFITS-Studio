# Simple and Advanced modes: scope

Status: scope only, no code. Written 2026-11-19 against `origin/dev` at 57269998f. Every piece below
waits for Gilly's yes. "Read" below means the source was read for this note; nothing was run or
launched.

## The ask

A simplified mode beside the current (advanced) one. Presentation only: every feature stays
reachable in both modes; simple mode moves rarely used controls behind an "advanced" tab, fold or
menu. Plus a preferences preset that hides the least-used palettes by default, with a clear cue that
more palettes exist.

The invariant every option below is held to: **nothing is removed in either mode, only placed.** A
control hidden in simple mode is one click, one menu or one Cmd+K away, and the cue says so.

## What the code already has (read)

Most of the machinery exists. Simple mode is mainly a new arrangement over it, plus one missing
state (a palette that is registered but deliberately not shown).

| Piece | Where | Why it matters here |
|---|---|---|
| Layout preset | `app/Settings.java`: `LAYOUT_PRESET` (`/settings/layout-defaults.properties`), `isLayoutKey` | Gilly's arrangement ships as *defaults* for `ui.toolbar.*`, `ui.palette.*`, `ui.section.*`, sidebar widths and collapse flags. The user's own file always wins. A simple-mode arrangement is a second file of the same shape. `LayoutPresetCheck` already pins the first. |
| Toolbar More divider | `gui/component/ToolbarOrder.java` (`MORE_DIVIDER` `>>>`, `barIds`, `moreIds`, `SEED_ONCE`) | Already a "simple vs advanced" split for tools: before the divider is on the bar, after it is in More. A simple bar is just a shorter prefix. `ToolbarOrderCheck` pins the rules. |
| Tools menu as inventory | `gui/component/MenuBar.java`, `buildToolsMenu` | Lists every tool whether or not it is on the bar. The pattern a "Panels" inventory should copy. |
| Palette homes | `gui/component/Palette.java`: `setHome`, `dockInto`, `setSidebarShown`, keys `ui.palette.<Title>`, `.sidebar`, `.shown`, `.size` | A palette lives in the left bar, the right bar or a window. Whether it shows is a separate key (`.shown`). |
| Forced showing on restore | `Palette.dockInto` (comment: "Always shown ... honouring it would dock a palette into a sidebar as nothing at all, with no control anywhere that brings it back") | **The gap.** Today a docked palette cannot start hidden: `.shown=false` is deliberately ignored at launch because nothing would bring it back. A hide preset needs exactly that state, and the cue is what makes it safe. |
| Left sidebar panes | `gui/MainFrame.java` lines 233 to 237 (`LeftSidebar.register`: Playback and Recording, Image Layers, Overlays, Camera, 3D Scene); plugins `timelines/Timelines.java:55`, `plugins/swek/SWEKPlugin.java:53` | Seven left sections, all palettes, so all can be hidden by the same mechanism. |
| Toolbar palettes | `gui/component/ToolBar.java` (Projection, HDR, Filters, Grid, Camera, Compare, Pizza, Annotation, Identify); `event/info/CactusTrackPanel.java` (Track CME), `CometTrackPanel.java` (Track comet) | Eleven more. The preset docks seven of them in the right bar (`ui.rightSidebarOrder`). |
| Preset over parts | `gui/component/PanelVisibility.java` (`MASTER` switch over `HEADERS`, `TITLE`, `RULE`, `SPACING`, ...), UI in `gui/dialog/ThemeDialog.java` ("Easier to find panels") | Gilly's existing shape for "one switch that sets several independent options, each still changeable". Simple mode should look like this. |
| Nested "rare corner" fold | `layers/selector/ImageLayerGeometryPanel.java`: Alignment as a `CollapsiblePane` inside Geometry ("a rarely-touched corner of Geometry") | The in-panel precedent for an "Advanced" fold. |
| Search reaches everything | `gui/search/ControlIndex.java`, `CommandPalette.java` (Cmd+K), `resources/data/controls.json`, `ControlSearchCheck` | Each listed control has a `reveal` catalog action. If revealing also un-hides, every hidden control stays one search away. |
| Positional panel keys | `gui/component/PanelKeys.java` (Option+N left, Shift+Option+N right; numbers drawn by `PanelVisibility.numberPrefix`) | Hiding a section renumbers the ones below it. Positional by Gilly's choice (2026-11-07), so this is expected, but worth stating in the UI. |
| Lock | `gui/component/PanelLock.java`, preset `ui.panelsLocked=true` | Locked panels hide their move buttons. Whether hide or unhide counts as a move is a choice (open question 7). |
| Preferences export | `app/GlobalPreferences.java` (allow-list, plan, confirm, apply) | New `ui.mode*` and hidden-panel keys must be added to its allow-list or they will not travel. |
| Tours | `gui/search/Tour.java`, `resources/data/tours/*.json` (`rearranging-the-interface.json` among them) | A tour step that targets a hidden panel must un-hide it through its reveal action, or the spotlight points at nothing. |

One collision noticed while reading, not tested: the left pane `LeftSidebar.register("Camera", ...)`
(`MainFrame.java:236`) and the toolbar palette `new Palette("Camera", ...)` (`ToolBar.java:677`)
share the title "Camera", and every palette key and `Palette.named` is keyed by title. Any per-palette
hide list keyed by title inherits that ambiguity (open question 9).

## Design options

### 1. What "mode" is

**A. Mode as two saved layouts (recommended core).** A mode is a named set of layout keys (exactly
the keys `Settings.isLayoutKey` accepts, plus the new hidden flags). Switching saves the current
layout under the mode being left and applies the one being entered. Shipped defaults: Advanced is
today's `layout-defaults.properties`; Simple is a new `layout-simple.properties` of the same shape.

- Pro: reuses the preset filter, the preset check and the existing restore paths; nothing in the
  panels needs to know a mode exists; arranging things in simple mode is remembered for simple mode.
- Pro (extensible): N named layouts fall out for free (Simple, Advanced, Presenting, a user's own
  "Teaching" layout), saved and shared like themes.
- Con: switching rewrites many `ui.*` keys at once. `Settings.setProperties` already batches them
  into one write, and the switch rebuilds the toolbar and sidebars, which they already do on edit.

**B. Mode as a runtime filter.** One key (`ui.mode=simple|advanced`) and each surface asks a small
`InterfaceMode.shows(id)` before it adds a control.

- Pro: one key, instant, no layout copying.
- Con: touches every surface (toolbar, both sidebars, menus, Settings tabs); a user who rearranges in
  simple mode has nowhere to keep that arrangement; upstream-derived files (`ToolBar.java`,
  `MenuBar.java`) take hunks in many places.

**C. Both (recommended overall).** A for arrangement (which palettes, where, which tools on the bar).
B only for the few things a layout cannot express: in-panel Advanced folds and Settings dialog tabs.
The B flag is then read in a handful of places, not everywhere.

### 2. The hidden palette state

Needed by the preset whether or not modes ship. Options:

- **2a. Honour `.shown=false` at launch** when a cue exists (change `dockInto`). Smallest diff, but
  it gives an old key a new meaning: settings written before the toolbar button stopped releasing
  docked palettes say `shown=false` for palettes Gilly expects to see (the comment on `shownKey`).
- **2b. A new key `ui.palette.<Title>.hidden` (recommended).** Absent means not hidden. Append-only
  per the data-contract rule; old files are unaffected. Hidden means: not added to its sidebar (or
  not opened as a window), its toolbar toggle and its Panels menu item still present, and any reveal
  (toolbar, View menu, Cmd+K, a tour step, a layer row's "settings" button) clears the flag.
- 2c. Reuse "window and closed" (`.sidebar=window`, `ui.palette.<Title>=false`). No new key, but
  un-hiding would open a floating window rather than the sidebar section the user expects.

### 3. The cue that more palettes exist

Several, because each serves a different moment. Recommended: **3a + 3b + 3d** (3e comes free).

- **3a. A footer row in each sidebar: "+ 4 more panels".** Muted text with the add glyph at the
  bottom of the section stack, only when that sidebar has hidden panels. Click opens a small list with
  a checkbox per hidden panel (and "Show all"). Sits where the eye goes after the last section, says a
  number, and costs one line. Built from the same pieces as the header row in `RightSidebar`
  (`Buttons.flat`, themed via `UIGlobals.themed`).
- **3b. View > Panels submenu.** Every panel, checked when showing, grouped Left, Right, Windows:
  the inventory, as the Tools menu is for tools. Never filtered by mode.
- 3c. A "+" button beside Collapse all and Expand all in each sidebar's header row
  (`RightSidebar` constructor; the left bar has the same pair). Compact but less self-explaining
  than 3a; good as a second door.
- **3d. Say it once.** The first launch in simple mode shows a one-line notice (the existing
  `CompletionNotifications` or a Tip of the Day entry in `resources/data/tips.json`): "Some panels are
  tucked away. View > Panels or + more panels brings them back."
- 3e. Cmd+K. Already finds palette controls (`ControlIndex`); with 2b, choosing one un-hides its
  panel. No new UI.
- 3f. A count badge on the toolbar More button when hidden panels exist. Overloads More, which is
  about tools; listed for completeness, not recommended.

### 4. "Advanced" inside a panel

Following the Alignment precedent: in simple mode, a panel's rare controls go into a nested
`CollapsiblePane` titled "More options" (or "Advanced"), folded by default, with its own
`ui.section.*` key so its fold is remembered. In advanced mode the same controls sit inline as today.
This is the only part that needs the B flag, and it should go panel by panel, each listing which
controls move (open question 5). Candidates seen while reading, not yet reviewed with Gilly: the
Alignment fold already exists; the Background section of a layer (`layer_background`,
`ImageLayerRenderingPanel.java:129`).

### 5. Settings dialog

`SettingsDialog.createContentPanel` adds nine tabs (General, Appearance, Layers, Plugins, Network,
Keybindings, Identify, FITS Library, Storage). Options: (a) leave it alone, since a dialog is already
out of the way; (b) in simple mode, fold Plugins, Network, Keybindings, FITS Library and Storage into
one "Advanced" tab with a sub-list; (c) reorder only. Recommended: (a) for the first cut. It is the
least-seen surface and the cost of moving familiar tabs is high.

### 6. The toolbar in simple mode

Already expressible: the simple preset's `ui.toolbar.order` puts fewer tools before `>>>`. Nothing
new to build. A starting proposal for Gilly to edit: undo, redo, zoom fit, pan, rotate, sidebars,
present, then More.

### 7. Switching modes

Where the switch lives (both/and, all cheap once it is a catalogued command in `ActionCatalog`):
View > Interface Mode (Simple, Advanced) as radio items; a row in the Appearance tab; a Cmd+K command
`interfaceModeSimple` / `interfaceModeAdvanced` (ids are API once shipped). Optionally a choice on
first run (open question 2).

## The "least-used palettes" preset

There is no usage data in the code: a search for usage counters (`useCount`, `ui.usage`, `lastUsed`,
"usage count") found none. So "least used" is Gilly's call, not a measurement. A starting list built
only from what the code and its comments say about each panel:

| Shown by default (proposal) | Hidden by default (proposal) |
|---|---|
| Playback and Recording, Image Layers, Overlays, Camera (left); Projection, HDR, Grid (right) | 3D Scene, Timeline Layers, Space Weather Event Knowledgebase (left); Filters, Annotation, Track CME, Track comet, Compare, Pizza, Identify (right or window) |

Two ways to ship it, both/and:

- **Fixed list** in the new preset file (`ui.palette.<Title>.hidden=true` lines). Simple, reviewable,
  testable by `LayoutPresetCheck`.
- **Optional local tally** (later): count opens per panel in `ui.palette.<Title>.opens`, never sent
  anywhere, and offer "Hide panels I have not opened in 30 days" as a button. Only if Gilly wants it.

## Data contracts and checks

- New keys only, never renamed: `ui.mode`, `ui.palette.<Title>.hidden`, the per-mode saved layouts
  (file or key prefix: open question 4). Add each to `Settings.isLayoutKey` (so a preset may set it)
  and to the `GlobalPreferences` allow-list (so it travels).
- Checks, each shown failing first: `LayoutPresetCheck` extended to the simple preset (every key is a
  layout key, every palette it names exists); a new `PaletteHiddenCheck` (hidden survives restore;
  every reveal path clears it; a hidden palette is still in View > Panels); `ControlSearchCheck` and
  `TourCheck` unchanged but run with the simple preset loaded, so no tour targets a control it cannot
  reveal.

## Suggested order

1. Hidden state (2b) plus cues 3a and 3b. Useful on its own in today's single mode, and it is what
   makes any hide preset safe.
2. Simple preset file and the mode switch (1A, section 7). Pure arrangement.
3. In-panel Advanced folds (section 4), one panel per pull request.
4. Optional: local tally, Settings dialog folding, user-named layouts.

## Open questions

1. Names: "Simple" and "Advanced", or "Essentials" and "Full", or something else? Same for the
   in-panel fold: "More options" or "Advanced"?
2. Which mode does a fresh install start in, and does an existing user stay in Advanced on upgrade
   (recommended: yes, nothing moves under them)? Ask on first run, or not?
3. Approve, edit or replace the shown and hidden palette lists above (no usage data exists to decide it).
4. Per-mode layouts: kept as key prefixes in `user.properties`, or as named layout files beside
   `user.properties` (the shape user-defined layouts would need)?
5. For section 4: which controls in which panels count as rare? Needs a panel-by-panel pass with you.
6. Rearranging in simple mode: saved to simple mode only (recommended with 1A), or shared with
   advanced?
7. With panels locked (`ui.panelsLocked=true`, the shipped default), may the user still hide and
   un-hide panels from the footer and View > Panels? (Recommended: yes; hiding is not moving.)
8. Should hiding be offered on each section header too (a small hide button beside pop-out), or only
   from the cues and View > Panels?
9. The two palettes titled "Camera" (left pane and toolbar palette) share every `ui.palette.Camera*`
   key. Is that intended (one panel reached two ways), or should the hide list key on something
   other than the title?
10. Settings dialog: leave as is (recommended), or fold the less-used tabs into one Advanced tab in
    simple mode?
11. Does Presentation mode interact: should presenting use its own saved layout (a third mode under 1A)?
12. Optional local open-count tally for a "hide what I do not use" button: wanted, or not?
