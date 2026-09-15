# JHV left-panel reorganization — design

**Date:** 2026-07-08
**Branch base:** `punch-integration`
**Ships as:** two-PR stack (structural reorg, then the actual-stats readout)

## Goal

The entire left panel currently lives under a single "Image Layers" collapsible
(`MoviePanel`), an undifferentiated stack of playback, recording, time/cadence,
the add-layer control, the layer table, and the per-layer adjustment tools.
Break it into four nested, collapsible sub-sections with clear responsibilities,
and add a per-layer readout of a downloaded layer's actual statistics.

## Current state

`MainFrame` builds `leftPane` (a `SideContentPane`) with one collapsible,
`"Image Layers"`, whose managed component is `MoviePanel`. `MoviePanel` stacks,
top to bottom:

- time slider
- playback buttons (prev / play / next / record / ⚙ advanced)
- speed + advance-mode (shown under ⚙ advanced)
- recording options (shown under ⚙ advanced)
- master start/end time (`TimeSelectorPanel`)
- Time step / frame count (`CadencePanel`)
- New Layer ▾ split button + sync button
- `LayersPanel` — the layer table plus a single `optionsPanelWrapper`

`LayersPanel.setOptionsPanel(layer)` rebuilds the selected layer's options into
`optionsPanelWrapper` on every selection change, via
`LayerOptions.getOptionsPanel(layer)`. For an `ImageLayer` this returns
`ImageLayerOptions`, which bundles: difference, opacity, blend, sharpen, levels,
colormap (LUT), channels, filter, a ⚙ "more adjustments" toggle (slit, inner
mask, ΔCROTA, ΔCRVAL1, ΔCRVAL2), and download / metadata / refresh buttons.

`CollapsiblePane` is a plain toggle-button + one managed component, so it nests
without modification.

## Target structure

```
▾ Image Layers
    ▸ Transport control   playback buttons · speed/advance · recording · master time range
    ▸ Layers              New Layer ▾ + time step (request cadence) · sync ·
                          layer table · selected-layer readout + download/meta/refresh
    ▸ Layer options       difference · opacity · blend · sharpen · levels ·
                          colormap · channels · filter   (image layers)
                          — or the selected layer type's own options panel
    ▸ Geometry / crop     slit · inner mask · ΔCROTA · ΔCRVAL   (image layers only)
```

The outer "Image Layers" pane's managed component becomes a container of four
nested `CollapsiblePane`s. Section names: **Transport control**, **Layers**,
**Layer options**, **Geometry / crop**.

## Two cadences

The design separates two concepts that were previously conflated:

- **Request cadence** ("time step") — the cadence the *next* layer downloads at.
  An input. Lives in the **Layers** section, beside New Layer ▾, since it is an
  acquisition knob used at the point of adding a layer. (This is the
  `CadencePanel` added previously; it moves here.)
- **Actual cadence** — what a *downloaded* layer actually is. An output; can
  differ from the request (archive gaps, "get all", native cadence). Reported in
  the **readout** for the selected layer.

The **master time range** stays in **Transport control** — not as an acquisition
param but as the playback timeline the slider scrubs and the default range new
layers inherit.

## Section responsibilities

### Transport control
Playback buttons, speed + advance-mode, recording options, and the master
`TimeSelectorPanel`. Purely about controlling / recording the loaded movie and
defining its timeline.

### Layers
- New Layer ▾ split button with the **request-cadence** control (`CadencePanel`)
  adjacent, so it reads as "parameters for the next layer."
- sync button (unchanged: applies master range + request cadence to existing
  layers on demand).
- the layer table (`LayersTable`, unchanged).
- **selected-layer readout** (PR2): actual start–stop, actual cadence, N frames.
- per-layer **download / metadata / refresh** buttons, relocated here from
  `ImageLayerOptions` — they are layer management, not rendering.

### Layer options
The selected layer's rendering controls. For an `ImageLayer`: difference,
opacity, blend, sharpen, levels, colormap, channels, filter. For any other
layer type, its existing options panel (grid, viewpoint, timestamp, connection).
Named "Layer options" rather than "Image adjustments" so it honestly covers all
layer types.

### Geometry / crop
The image-layer geometry group formerly behind ⚙ "more adjustments": slit,
inner mask, ΔCROTA, ΔCRVAL1, ΔCRVAL2. Populated only for image layers; empty /
collapsed for other layer types. The old in-panel ⚙ toggle is removed — the
collapsible header now serves that role.

## Retargeting on selection

Reuse the existing rebuild-on-selection mechanism rather than adding live
re-pointing. Today one selection event rebuilds one wrapper; after the reorg the
same event repopulates **three** wrappers:

1. **Layer options** wrapper ← rendering controls for the selected layer.
2. **Geometry / crop** wrapper ← geometry group (image layers only; else empty).
3. **Layers management** wrapper ← download / metadata / refresh buttons (PR1),
   plus the selected-layer stats readout (PR2).

`ImageLayerOptions` is split so its widget groups can be placed into separate
containers: a rendering group, a geometry group, and (PR2) a readout + button
group. A coordinator — the natural extension of `LayersPanel.setOptionsPanel` —
owns the three target wrappers and repopulates them together on selection. When
nothing is selected, all three are empty. Non-image layers populate only the
Layer options wrapper; Geometry and the image-specific readout fields stay empty.

No change to how filter panels are constructed (still per-rebuild), so no risk to
their internal state handling.

## Propagation to new layers

New layers inherit the master time range and request cadence. This is
**new-layers-only**: changing the master values does not retro-fetch existing
layers (the sync button remains the explicit way to do that). The existing
inheritance path already covers most of this — `PunchDialog` reads
`Player.getStartTime()/getEndTime()` and the active layer's request range;
`ObservationDialog` has its own time + cadence. The reorg keeps these dialogs
inheriting from the master (Transport range + Layers request cadence). No new
propagation machinery beyond ensuring the "New PUNCH Layer" and "New Layer"
dialogs read the same master values the Transport/Layers sections show.

## Actual-stats readout (PR2)

For the selected image layer, compute and display from what is actually loaded:

- **start – stop**: first and last frame times of the layer.
- **actual cadence**: median (or representative) spacing between loaded frames.
- **N frames**: count of loaded frames.

Sourced from the layer's view / frame metadata (the same data the layer table's
time column already reflects), not from the request parameters. Updates on
selection and as frames stream in for a still-downloading layer.

## PR decomposition

- **PR 1 — structural reorg (behavior-preserving).** Nested sub-collapsibles;
  move existing widgets into the four sections; split `ImageLayerOptions` into
  rendering + geometry groups; fan `setOptionsPanel` out to the section wrappers;
  relocate request cadence and download/meta/refresh into Layers; remove the old
  ⚙ "more adjustments" in-panel toggle. No new user-visible values — reviewable
  as "boxes moved."
- **PR 2 — actual-stats readout.** Add the selected-layer readout (start/stop,
  actual cadence, N frames) into the Layers section, computed from the loaded
  layer. Stacks on PR1.

## Non-goals

- No live re-pointing of filter panels (keep rebuild-on-selection).
- No retro-sync of existing layers on master change (sync button unchanged).
- No changes to the rendering pipeline, download logic, or the layer table model.
- No new cadence widget styles — reuse `CadencePanel`.

## Verification

JHV has no automated test suite; verification is a clean `ant compile` plus a
manual launch (`ant run`, JDK 25) confirming:

- all four sub-sections collapse/expand independently under "Image Layers";
- selecting each layer type populates the right sections and clears the others;
- adding a layer (New Layer and New PUNCH Layer) inherits master range + request
  cadence;
- playback, recording, sync, download, metadata, refresh all still work;
- (PR2) the readout matches a known layer's actual start/stop/cadence/frame count.
