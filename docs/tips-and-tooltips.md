# Tips, tours and tooltips

Every piece of help text a person sees in HelioFITS Studio, where it lives, and how to add more.
All three are data files under `resources/data/`: edit the JSON, run `ant jar`, and the next
launch shows it. Each has a check that fails on a broken entry.

| What | File | Check |
|---|---|---|
| Tip of the Day | `resources/data/tips.json` | `TipsCheck` |
| Guided tours (Help > Tours) | `resources/data/tours/<id>.json`, listed in `Tour.IDS` | `TourCheck` |
| Tooltips for controls the code leaves bare | `resources/data/tooltips.json` | `TooltipsCheck` |
| Tooltips set in code | `setToolTipText(...)` beside the control | none |
| Help search words | `ControlIndex.CONTROLS` | `ControlSearchCheck` |

## Tip of the Day

A window of its own (`TipOfTheDay`), apart from the Welcome window, so each has its own startup
switch. At startup it opens at most once a day: after the Welcome window closes, or a moment after
launch when that window is switched off. It never opens over a running tour or the first-launch
tour offer. Help > Tip of the Day opens it at any time.

What a person has seen is kept in their settings (`~/HFStudio`, so per account and per computer):

| Key | Holds |
|---|---|
| `tips.seen` | the ids already shown, comma separated |
| `tips.atStartup` | `false` when "Show a tip at startup" is off |
| `tips.lastShown` | the last date a tip opened at startup |
| `tips.snoozeUntil` | no startup tip before this date (Snooze: a day, a week, a month) |

New-user tips come first, then the rest, each in file order. A tip is not shown again until every
tip has been; then the round starts over.

To add a tip, append an object to `tips`:

```json
{
  "id": "short-stable-id",
  "title": "A few words",
  "text": "One or two sentences. Name a menu item as Menu > Item, exactly as the menu reads.",
  "action": "catalogId",
  "target": "componentName",
  "reveal": "catalogId",
  "tour": "tour-id",
  "newUser": true
}
```

- `id`, `title`, `text` are required. Never reuse or rename an id: it is how the tip is
  remembered as seen.
- `action`: the ActionCatalog id (`docs/actions.md`) whose menu path the text spells out.
  TipsCheck fails when the text and the menu disagree.
- `target`: a component to point at with Show Me, spotlit as a tour step is. Any name a tour can
  target works (below). `reveal` is a catalog id run first to bring it on screen, such as
  `showImageLayers`, `showProjection` or `showPlayback`.
- `tour`: one of `Tour.IDS`; the tip then has Take the Tour.
- `newUser`: shown before every other tip.

## Tours

One file per tour in `resources/data/tours/`, and its id in `Tour.IDS` (the Help > Tours order).
Each step has a `target`, a `title` and a `body`, and optionally an `action` (a catalog id run
first). `"sample": true` loads the bundled AIA 171 image when the canvas is empty. Keep a tour to
about ten steps.

A target is a name the code gives a component: a toolbar id (`ToolbarOrder.DEFAULT_ORDER`), a
catalogued menu item's id (MenuBar's `catalogItem`), or a literal `setName("...")` under `src/`.
TourCheck reads those from the source. To make a new control targetable, give it a literal
`setName` and list it in `ControlIndex.CONTROLS` (ControlSearchCheck requires both). A menu item
is never on screen during a tour, so its card is centred with no cut-out.

The tours: Getting started, Loading data, Making a movie, Views and projections, RHEF and
filters, Sharing your work.

## Tooltips

`Tooltips.install()` runs at startup and fills every control added to any window from then on.
A tooltip the code sets always wins; `tooltips.json` only fills controls that have none. A
control is looked up by its key:

- its component name, when it has one (`imageLayersNew`, `projectionHPC`);
- otherwise `Section/label`: the title of the sidebar section it sits in, or the class of the
  panel that built it, or the title of its dialog; then the text on the control, or on the label
  just before it, without a trailing colon (`Grid/Radial grid`, `MoviePanel/Loop`);
- then `*/label`, for the same button anywhere (`*/Close`).

A named control with no entry gets the description of the catalogued command with that id.

To find what is missing, open the windows you care about, then choose Help > Report Controls
Without Tooltips: the log lists each bare control with its key, ready to paste into
`tooltips.json`. `(no key)` means the control has neither a name nor any text near it; give it a
`setToolTipText` in code. For a run with no hand on the mouse, launch with
`-Djhv.probeTooltips=true` (and `-Djhv.probeExit=true` to quit after the report), with a
throwaway home as in CLAUDE.md "Running the dev build safely".

On 2026-10-20 the audit found 70 bare controls in the main window and 32 more in an image
layer's options; after this change, with every palette open and the demo scene's AIA 171 layer
loaded, it found none (run under xvfb on Linux). Not yet audited: the dialogs (New Layer, the
archive dialogs, Preferences and the rest) and the options of other layer types (timelines, the
viewpoint and grid layers' less common panels, point clouds).
