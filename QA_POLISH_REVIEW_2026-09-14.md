# HelioFITS Studio: product QA pass, 2026-09-14

Method: the running build (pid 2042, HFStudio.jar from this tree) was inspected
through macOS accessibility screenshots of the main window with the "August 30th
CONNECT" session loaded, plus the current log and a code sweep. Items marked
[Observed] were seen on screen; [Code] were read in source; [Verify] are
suspicions that need a second look. Line numbers are as of HEAD cedc2c5b2.

## The three sentences

1. The app is feature-complete for a preview and the sidebars are beautifully
   dense, but the first screen a new user sees is the densest one (Record,
   Output, Preset, codec) and it speaks in codec strings before the user has
   loaded anything.
2. Two layout bugs are visible without doing anything: a half-row sliver in the
   Camera panel and a help dialog that opens 1665 px wide.
3. Nothing in the interface has an accessible name, so VoiceOver reads every
   icon button as "button".

## Tier 1: visible defects (fix first)

| # | Defect | Where | Fix |
|---|---|---|---|
| 1 | [Observed] Camera panel: the object list under Free / Follow / Overview is squeezed to about 8 px, showing half of the word "Mercury" and nothing else. Same family as commit 868f048ae ("A layer list will not be compressed to a sliver"), different list. | `layers/selector/SpaceObjectContainerPanel.java:98` sets a preferred height of N rows, but the parent layout compresses it | set a minimum size as well as the preferred one, or fold the list behind the Follow radio |
| 2 | [Observed] The "i" button beside Seen-from opens "Camera Options Information" as a modal dialog 1665 x 234 px, a single strip across the whole screen with Close at the far right. Cause: the last line of `explanation` is one unwrapped 120-character line and `TextDialog.showDialog` packs to the longest line. | `ViewpointLayerOptionsPanel.java:37-51`, `gui/dialog/TextDialog.java:55` | wrap the HTMLPane at a fixed width before `pack()` (a `<div style="width:460px">` around the text is one line); consider a non-modal popover, since this is help text |
| 3 | [Observed] Track CME table header reads "Speed k..." (truncated). | `event/info/CactusTrackPanel.java:52` | header "Speed", unit in a tooltip or a second header row; or size the column to the header |
| 4 | [Observed] Timelines: layer names ("PUNCH CAM v0l", "LASCO C3", "LASCO C2") are painted on top of their own coverage bars in ~8 px type; the two rows of tick labels collide ("11:30:18 / 2025-08-31" over "2025-08-31"); the range-selector thumb overlaps the date axis. | `timelines/` draw code | labels left of the bars in a gutter; one label row with date on the major ticks only; move the range thumb off the label row |
| 5 | [Observed] A text label is clipped by the top edge of the image viewport (partially visible above the top strip). [Verify] which label. | viewport grid labels | inset labels by their own height |
| 6 | [Observed] Filters palette: "Whole movie" is Off, yet Pass/Notch, From/To, Direction, Radial bins, Angular bins, Spectrum..., the frame summary and Apply all stay fully drawn and enabled. | Filters palette | grey or collapse the block while Off |
| 7 | [Observed, then checked in code] The LASCO C3 row has no funnel while the Filters palette shows C3 with RHEF selected. Not a state bug: the funnel marks only a whole-movie (Fourier) filter that is computing through the layer, never the per-frame RHEF (`CellRenderer.java:207-217`). But nothing on screen says so, and the per-frame filter has no indicator at all. | `layers/selector/CellRenderer.java:217` | tooltip on the funnel; a second glyph (or the same funnel, hollow) for a per-frame filter |
| 8 | [Code] Zero accessible names anywhere (`grep setAccessibleName src` = 0). Nine sidebar-header and toolbar buttons expose no AX title at all; tooltips exist (222 of them) but are not what VoiceOver announces. | `gui/Buttons.flat(...)` helper and toolbar | in the helper: `b.getAccessibleContext().setAccessibleName(tooltip)`; one line covers most of the app |
| 9 | [Code] Startup logs `WARNING DisplayController.missingRenderRequestHandler - No render request handler installed` on every launch. Harmless today, but a WARNING that fires every run is either a bug or not a warning. | `display/DisplayController` | install the handler before the first render request, or demote to debug |

## Tier 2: reads as unfinished

- [Observed] Toolbar label casing is mixed: "Zoom-Fit", "Reset View", "Reset Axis" (Title Case, one hyphenated) against "Left bar", "Right bar" (sentence case). `gui/component/ToolBar.java:100-124`. macOS toolbars are Title Case: "Zoom to Fit", "Left Sidebar", "Right Sidebar".
- [Observed] Every section header carries a padlock (eleven identical padlocks on one screen). Show it only when the lock state differs from default, or on hover.
- [Observed] Playback and Recording is the first section a new user sees and it opens with Record chips ("One loop", "Screenshot", "Unlimited", "Frame") in ~9 px type, "Output 1:1 long side 2048 2048 x 2048", "Preset Custom", and a collapsed row titled "H.265 HDR (HLG), 4:4:4, 10-bit" that looks like a heading. Fold everything below Play into a "Recording" disclosure, closed by default.
- [Observed] Two paragraphs of documentation live permanently in the Camera panel in small grey type. Keep the conditional flat-projection line (it is a real state message); move the rest to the info popover.
- [Observed] Track CME: "6 CACTus event(s) — double-click one to track." uses the "(s)" plural hack and an em dash (`CactusTrackPanel.java:242`, and ten other UI strings carry em dashes). Buttons "Track (Warp)" and "Track (Crop)" put the mode in parentheses; a user cannot tell what either does without the tooltip.
- [Observed] Status bar: "FPS: 0" is a developer readout, permanently visible. Right side "CR: 2301.69  D☉: 1.000au  (θ,ρ):( -12.59°, 2.60R☉)" mixes spacing conventions and reads as code; "1.000 au", "θ = -12.6°, ρ = 2.60 R☉".
- [Observed] HDR panel copy: "display max", "top 25%", "interface white", "No headroom in use". Precise, but "interface white" is an internal term; say "SDR white".
- [Observed] Filters frame summary "111 frames, cadence 2880 s (largest gap 6480 s) output 222 MB": humanize the durations (48 min, largest gap 1.8 h).
- [Observed] Timelines pane takes over half the vertical space while showing no band data, with no empty-state text. One line: "No timelines loaded. Add one under Timeline Layers."
- [Observed] Session header: five icon-only buttons (new, open, save, save as, refresh) and two arrow buttons, none labelled; an off-screen text field is parked at y = -28 (the rename field). Harmless, but it is in the AX tree.
- [Observed] The bottom section header "Space Weather Event Knowledgebase" is cut off by the sidebar's bottom edge on a 1028 px tall window; nothing indicates the sidebar scrolls.

## Tier 3: what premium would look like

- One type scale. The sidebars currently use at least four sizes (section title, control, chip, footnote). Pick three and retire the ~8 px chips.
- Disclosure discipline: default-collapse everything a first-time user does not need (Record, Output, Preset, Camera motion, Observer sky), and remember the state per session, which the code already does for sections.
- Names, not modes: "Track (Warp)" / "Track (Crop)" become "Hold front in place" / "Follow front"; "Helioradial Unrolled" gets a one-line caption under the radio group the first time it is chosen.
- Error and empty states written for the user, not the log (see the code-sweep section below).
- A first-run card: three sentences and a "Load a PUNCH movie" button. Today the first launch of a fork with six headline features shows a JHelioviewer-shaped empty window.

## Code sweep (subagent findings)

Filled in below from the three parallel sweeps: UI copy register, menu and
dialog consistency, error paths and discoverability.

### A. Copy register (Sonnet sweep, verified by grep)

Overall: tooltips and dialog bodies are full sentences and carefully written;
no misspellings found. The embarrassment is concentrated in fork leftovers and
a few raw-exception concatenations.

| # | Finding | Where | Rewrite |
|---|---|---|---|
| A1 | Watermark on the canvas draws `programName + version + '.' + revision`; if `version.properties` fails to load the defaults are `2.-1.-1` and `-1`, so a broken build stamps "HelioFITS Studio 2.-1.-1.-1" over the science image. | `layers/TimestampLayer.java:219`, `app/AppInfo.java:22-23` | fall back to "(dev build)" |
| A2 | HDR Canvas tooltip says "...the next time JHelioviewer starts." | `gui/component/MenuBar.java:237` | "HelioFITS Studio" |
| A3 | Help > Open Change Log links to the `thomson-warp` dev branch; Help > Open Website goes to jhelioviewer.org unlabelled as upstream. | `MenuBar.java:372-373` | tagged release URL; "JHelioviewer Website (upstream)" |
| A4 | Help > "Report Clipped Controls" is an internal QA probe shipped in the Help menu. | `MenuBar.java:379-381` | debug menu or strip from release |
| A5 | "Could not open a new window: " + e.getMessage() and "Could not read the cache: " + e.getMessage() render "...: null" when the exception has no message; the null guard in `Message.format` runs too late. | `app/Session.java:338`, `gui/dialog/CacheDialog.java:227` | "See the log for details." and guard null |
| A6 | Crash dialog prints "Message: null" above the trace when there is no message. | `app/JHVUncaughtExceptionHandler.java:76-88` | friendlier header, guard null |
| A7 | Title "Error getting the data" copy-pasted on six unrelated failures. | `ImageLayerLoader.java:160`, `GaiaClient.java:294`, `LoadSunJSON.java:64`, `PointCloudLoader.java:220`, `LoadState.java:35`, `LoadRequest.java:76` | name what failed |
| A8 | "Socket timeout" / "Socket timeout while requesting JPIP URL." and a dialog titled just "Warning". | `layers/ImageLayerLoader.java:221-238` | "Connection timed out while loading the layer." |
| A9 | JOptionPane titled "Error", body "End date is before start date". | `LayersSectionPanel.java:149` | title "Invalid Date Range", full sentence |
| A10 | "Set java.io.tmpdir to an ASCII path." shown to users. | `io/Directories.java:179` | plain-language instruction |
| A11 | Startup failure: generic title plus raw Throwable message. | `HFStudio.java:185` | name the subsystem |
| A12 | "Details…" with ellipsis in one panel, "Details" without in the other, both open a modal. | `CactusTrackPanel.java:132`, `SWEKEventInformationDialog.java:278` | ellipsis on both |
| A13 | Dialog titles drift across "PUNCH refresh", "Cache deleted", "SOAR error", "PUNCH error". | `ImageLayerManagePanel.java:159` and siblings | one casing rule |

### B. Menus and dialogs (Sonnet sweep; items B1, B2, B4 re-verified by grep)

| # | Finding | Where | Fix |
|---|---|---|---|
| B1 | Play/Pause Movie is bound to Cmd+P, the macOS Print shortcut. | `gui/Actions.java:412` | Space when the canvas has focus, or Cmd+Shift+P style chord; keep menu discoverability |
| B2 | Five palette-opening actions with the correct ellipsised labels ("Projection…", "Fourier Filter…", "HDR Settings…", "Grid Settings…", "Camera Settings…") are declared and never used, while the Tools menu items that actually open those dialogs carry bare labels. | `Actions.java:108-161`, `MenuBar.java:123-134` | wire the existing actions, delete the duplicates |
| B3 | Presentation mode is two commands with two names: View > "Presentation Mode" (Cmd+Shift+P) and toolbar/Tools "Present" (raw ItemListener, no shortcut). | `Actions.java:164-167`, `ToolBar.java:541-550` | one Action, one name |
| B4 | "Track CME" appears twice in the same Tools menu: the toolbar-mirrored toggle and a hard-coded "Track CME..." two lines below, with a comment claiming the palette has no toolbar button (it does). | `MenuBar.java:62-68` | delete the second entry and the stale comment |
| B5 | File menu says "State" for Load / Save / Save As / Revert and "Session" for Start New / Set Default / Clear Default / Open Recent, for the same .jhv file. | `Actions.java:198-651` | pick "Session" everywhere (matches the header in the sidebar) |
| B6 | Same command, two names: View "Zoom to Fit" vs toolbar "Zoom-Fit"; View "Reset Camera" vs toolbar "Reset View". | `Actions.java:718,353`, `ToolBar.java:118,124` | one label per Action |
| B7 | Only the JP2 layer gets Cmd+N; PUNCH, SOAR, Synoptic, ASPIICS, Point Cloud have no shortcut; Save State has Cmd+S but Load State has none; there is no Undo although Clear Annotations and Start New Session are destructive. | `Actions.java:215-296` | Cmd+Shift+N for the fork's headline PUNCH layer; Cmd+Z for Clear Annotations |
| B8 | Menu label "New FITS Layer — PUNCH (SDAC)…" opens a dialog titled "New PUNCH Layer"; same for SOAR. | `Actions.java:248,226`, `PunchDialog.java:103`, `SoarDialog.java:106` | "New PUNCH Layer…" in the menu too |
| B9 | Two ad hoc JOptionPane calls have no title and show macOS's default "Message"; one is titled "Error"; one is titled "Fourier filter" in lower case among Title Case siblings. | `JHVUncaughtExceptionHandler.java:85`, `LogDialog.java:41`, `LayersSectionPanel.java:149`, `SequencePanel.java:368` | title every dialog |
| B10 | "Revert to Saved" confirmation logic exists twice. | `Actions.java:585-587`, `MainFrame.java:621` | one path |
| B11 | Vocabulary: "Left bar" / "Right bar" on the toolbar, "Left sidebar" / "Right sidebar" in the Presentation submenu, "Timelines pane" in a tooltip, "Palette" / "Section" / "Panel" in class names. | `ToolBar.java:100-101`, `MenuBar.java:402-404` | sidebar, palette, section: three words, each meaning one thing |

### C. Error paths and discoverability (Opus sweep; C1, C4, C5, C15, C16 re-verified by grep)

| # | Sev | Finding | Where | Fix |
|---|---|---|---|---|
| C1 | blocker | SPICE failure at startup: `Message.fatalErr` runs before `Message.setHandler` (installed at `MainFrame.java:168`), so the console handler prints to stderr and `System.exit(-1)` follows. Dock icon bounces, no window, no dialog. | `HFStudio.java:97-101`, `app/Message.java:46-49` | install the GUI handler before `loadSpice()`, or show a JOptionPane on this path |
| C2 | blocker | Offline startup: all three dataset servers fail silently (`LoadSources.java:46-52` logs only); New JP2 Image Layer shows three empty server nodes and an enabled Add button. The only retry is File > Reload Datasets Listings. | `io/DataSources.java:101`, `gui/DataSourcesTree.java:68-76` | "Could not reach <server>" node with inline Retry |
| C3 | major | New PUNCH Layer offline: two stacked modal errors ("Error listing the PUNCH archive") over the modal dialog, then "Checking archive coverage..." and "Searching..." stay forever. | `PunchDialog.java:245,307-321`, `PunchClient.java:65-69` | failure callback writes the reason into the labels; no modal while the dialog is open |
| C4 | major | Crash dialog asks the user to email `swhv@oma.be` (upstream ROB) while `bugURL` points at the fork. | `app/AppInfo.java:21`, `JHVUncaughtExceptionHandler.java:76-78` | fork maintainer address; lead with "Copy report" |
| C5 | major | Every non-network error suggests filing a Helioviewer server bug ("If this is a JPIP connection failure..."), including a corrupt local FITS and a full disk. | `gui/MessageHandler.java:90-91` | show the JPIP link only for JPIP causes |
| C6 | major | LASCO background unavailable: frames render unsubtracted, marked `provisional` for cache policy only; nothing reaches the UI. Movie alternates good and blob frames with no explanation. | `io/LascoBackground.java:125-127`, `FITSImage.java:431-434` | layer-row badge "background not applied for N frames" with Retry |
| C7 | major | Download cache never evicts (`ponytail:` note at `NetFileCache.java:57-59`); a full disk surfaces as "Error getting the data: Empty list of views". | `NetFileCache.java:66`, `ImageLayerLoader.java:184-188`, `ManyView.java:44` | free-space check before a batch; map disk causes to a named dialog |
| C8 | major | Legacy-home migration runs before `Log.init()`, so the one record of what was carried over never reaches the log file; no UI notice; a partial copy is never retried (`Files.copy` without REPLACE_EXISTING, guard on target existing). | `HFStudio.java:46,74`, `io/Directories.java:220-247` | migrate into a temp dir and rename; one-time notice with counts |
| C9 | major | Help > Open User Manual opens the upstream manual, which documents none of the fork's features. | `AppInfo.java:20`, `MenuBar.java:370` | point at the preview guide |
| C10 | major | PUNCH download confirmation is sized in files ("typically a few MB each"), not bytes or minutes; no free-space check; no cancel once Add is pressed. SOAR quotes GiB, so the pattern exists. | `PunchDialog.java:41,135-148`, `ImageLayerLoader.java:180-192` | sum archive byte sizes; cancel control on the layer row |
| C11 | major | Mid-movie download failure: the gap-skip logic is good, but the outcome is a modal 600 x 400 dialog over the playing movie, repeated on the next stall. | `movie/Player.java:169,213` | status-bar message and a timeline marker |
| C12 | major | A local FITS that fails to parse is reported under "Error getting the data" with the JPIP bug link and no filename; unsupported WCS is only logged and the layer loads mis-positioned. | `ImageLayerLoader.java:160`, `FITSImage.java:71,366-415`, `FitsMetaData.java:324,363` | "Could not open <filename>"; WCS warning on the layer row |
| C13 | major | Unwritable home or cache directory throws IllegalStateException before any window and lands in the crash dialog as a stack trace. | `io/Directories.java:128-148` | catch and name the path |
| C14 | major | Frames that failed to download are reported only in a status-bar tooltip; "retry from the layer" is true only for PUNCH layers, and the refresh loads new frames as a separate layer rather than filling the holes. | `ActivityStatusPanel.java:121-138`, `ImageLayerManagePanel.java:142`, `PunchClient.java:144-150` | persistent "N frames missing, Retry" on the row that refills the same layer |
| C15 | minor | Every message dialog is a 600 x 400 scroll pane, even for one sentence. | `gui/MessageHandler.java:77`, `LogDialog.java:50`, `JHVUncaughtExceptionHandler.java:94` | size to content |
| C16 | minor | Success is announced with the warning icon ("Loaded N new frames", "Cache deleted", "Default session"); there is no `Message.info`. | `ImageLayerManagePanel.java:159,213,240`, `Actions.java:636` | add info, use it |
| C17 | minor | Lambda and Disk sliders go disabled in Orthographic / HPC with no hint that Helioradial enables them. | `ToolBar.java:1155-1158` | caption "available in Helioradial projections" |
| C18 | minor | No first-run, welcome, or empty-state text anywhere in the tree. | `MainContentPanel`, layer list | one empty-state panel with three links |
| C19 | good | "0 found — archive may not cover this period; see umbra.nascom.nasa.gov/punch" is the model error string: what, why, where to look. Nothing else does this. | `PunchDialog.java:331-333` | template for C2, C3, C7, C12 |

Discoverability verdict from the sweep: Filters, Track CME, Present and the
Tools / More menus are well exposed (default toolbar buttons, real tooltips, a
Tools menu that lists every tool whether or not it is on the bar). The padlock
that explains itself instead of refusing is the best interaction in the app.
Gaps: "Helioradial Unrolled" has no tooltip; the Υ split button in Filters is a
bare Greek letter defined only in its own tooltip; the lambda readout shows
three decimals with no marked "unwarped" tick; undocking a palette is never
advertised; the single-screen presentation behaviour is explained only in code.

## Suggested order

1. **One sitting, all one-liners:** C1 (handler before SPICE), C4 (email), C9 (manual URL), A2 and A3 (branding), item 2 (wrap the help dialog), item 3 (column header), item 8 (accessible names in the button helper), B1 (Cmd+P), B4 (duplicate Track CME), C16 (add info).
2. **Half a day:** the error-surface pass. C2, C3, C5, C7, C12, C13 share one shape: a named title, one sentence, and the fork's own tracker. C6 and C14 are the same feature: per-layer badge plus Retry into the same layer.
3. **Half a day:** the layout pass. Items 1, 4, 5, 6 and the Tier 2 list (casing, padlocks, Record disclosure, status bar).
4. **A design decision, not a cleanup:** default-collapse the first sidebar section and add the empty state (C18). This is the single largest change in how a stranger reads the product.

## Fixed 2026-09-14 evening (uncommitted, `ant jar` builds clean)

Done, 26 files: item 1 (object list minimum height), item 2 (help dialog wraps
at 520 px), item 3 (column header), item 8 (accessible names on every toolbar
button), item 9 (startup warning demoted), the toolbar casing, the lambda and
disk slider hint, A2, A3, A5, A6-adjacent (crash dialog now points at the
fork's tracker only), A7-A9, A10, A11, A12, B4 (the second entry is now "Find
CMEs to Track...", which is what it does), B5 (File menu says Session
throughout), B6 (View says Reset View / Reset View Axis; toolbar says Zoom to
Fit), B8, C1 (a fatal error before the GUI handler exists now shows a dialog),
C2 (failed server shows a reason node), C3 (PUNCH dialog reports archive
failures inline, no modal), C5 (JPIP bug link only for JPIP causes), C8 (the
migration note reaches the log file), C9 (manual link goes to the releases
page, where the guide ships), C12 (local file failures name the file), C15
(short messages are small dialogs), C16 (`Message.info`, used for success),
every em dash in user-facing prose.

Not done, needs a design decision or more than the window allowed: items 4, 5,
6, 7 (timeline labels, clipped viewport label, Filters Off greying, funnel
tooltip); Tier 2 padlocks, Record disclosure, status bar, HDR copy, empty
states; A4 (Report Clipped Controls is your own tool, left in place); A13; B1
(which key replaces Cmd+P is your call); B2, B3, B7, B9-B11; C4's SAMP
`author.mail` still says swhv@oma.be (no fork address to use); C6, C7, C10,
C11, C13, C14, C17 done as a permanent hint only, C18.
