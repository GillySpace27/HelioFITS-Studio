# JHV Left-Panel Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolithic "Image Layers" left panel into four nested collapsible sub-sections (Transport control, Layers, Layer options, Geometry / crop) and add a per-layer readout of a downloaded layer's actual statistics.

**Architecture:** The outer "Image Layers" `CollapsiblePane` gains an inner container holding four nested `CollapsiblePane`s. `MoviePanel` is slimmed to the Transport section; a new `LayersSectionPanel` owns the New Layer control, request cadence, sync, the layer table, and a management area. A new `LayerOptionSections` controller repopulates three wrappers (Layer options, Geometry/crop, management) on selection, driven by the layer table's existing selection event. `ImageLayerOptions` is split into three separately-parented panels because one Swing component can only live in one wrapper.

**Tech Stack:** Java 25, Swing, JIDE components, Ant build. Design spec: `docs/superpowers/specs/2026-07-08-jhv-layer-panel-reorg-design.md`.

## Global Constraints

- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25; export PATH="$JAVA_HOME/bin:$PATH"; ant compile` must succeed with no new warnings.
- No automated test suite exists. Each task's verification is `ant compile` **plus** a manual `ant run` launch check of the described behavior. Kill any prior instance first: `pkill -f "jhv-panels/JHelioviewer.jar"`.
- The rebuilt `lib/natives-macos/libjhvmetalhost.dylib` is a build artifact — never stage it (`git checkout -- lib/natives-macos/libjhvmetalhost.dylib` before committing).
- Commit message trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- PR1 (Tasks 1–4) must be behavior-preserving: no new user-visible values, only relocation and nesting. PR2 (Task 5) adds the readout.
- Section names verbatim: **Transport control**, **Layers**, **Layer options**, **Geometry / crop**.
- Reuse `CadencePanel` for request cadence — do not create a new cadence widget.

---

## PR 1 — Structural reorganization (behavior-preserving)

### Task 1: Introduce the nested four-section container

Wrap the existing panel contents in an inner container of four nested collapsibles, keeping every widget where it functionally is for now (Transport gets all of today's MoviePanel; the other three panes start empty). This isolates the nesting change from the widget moves.

**Files:**
- Create: `src/org/helioviewer/jhv/gui/component/ImageLayersPane.java`
- Modify: `src/org/helioviewer/jhv/gui/MainFrame.java` (around line 121–124, 273–288)

**Interfaces:**
- Produces: `ImageLayersPane` — a `JComponent` with:
  - `ImageLayersPane()` constructor building four nested `CollapsiblePane`s.
  - `JPanel getLayerOptionsWrapper()`, `JPanel getGeometryWrapper()`, `JPanel getManageWrapper()` — the three empty `BorderLayout` wrappers later tasks fill.
- Consumes: `CollapsiblePane(String, JComponent, boolean)`, `MoviePanel.getInstance()`, `MainFrame.getLayersPanel()`.

- [ ] **Step 1: Create `ImageLayersPane`**

```java
package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.MainFrame;

// Inner container for the four nested sections shown under the "Image Layers" pane.
@SuppressWarnings("serial")
public final class ImageLayersPane extends JPanel {

    private final JPanel layerOptionsWrapper = new JPanel(new BorderLayout());
    private final JPanel geometryWrapper = new JPanel(new BorderLayout());
    private final JPanel manageWrapper = new JPanel(new BorderLayout());

    public ImageLayersPane(JComponent transport, JComponent layers) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        add(new CollapsiblePane("Transport control", transport, true));
        add(new CollapsiblePane("Layers", layers, true));
        add(new CollapsiblePane("Layer options", layerOptionsWrapper, true));
        add(new CollapsiblePane("Geometry / crop", geometryWrapper, false));
    }

    public JPanel getLayerOptionsWrapper() {
        return layerOptionsWrapper;
    }

    public JPanel getGeometryWrapper() {
        return geometryWrapper;
    }

    public JPanel getManageWrapper() {
        return manageWrapper;
    }
}
```

- [ ] **Step 2: Wire it into `MainFrame`**

In `MainFrame.prepare()` replace:

```java
        leftPane = new SideContentPane();
        leftPane.add("Image Layers", MoviePanel.getInstance(), true);
```

with (temporary intermediate — MoviePanel still holds everything; `layers` arg is an empty placeholder panel for now):

```java
        leftPane = new SideContentPane();
        imageLayersPane = new ImageLayersPane(MoviePanel.getInstance(), new JPanel());
        leftPane.add("Image Layers", imageLayersPane, true);
```

Add field near line 106: `private static ImageLayersPane imageLayersPane;` and import `ImageLayersPane`, `javax.swing.JPanel`.

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25; export PATH="$JAVA_HOME/bin:$PATH"; ant compile`
Expected: `BUILD SUCCESSFUL`, no new warnings.

- [ ] **Step 4: Launch check**

Run: `pkill -f "jhv-panels/JHelioviewer.jar"; ant run > /tmp/jhv-run.log 2>&1 &` then after ~8s confirm the window shows "Image Layers" containing a "Transport control" sub-header (expanded, holding the whole old panel) plus empty "Layers", "Layer options", "Geometry / crop" headers that collapse/expand independently.

- [ ] **Step 5: Commit**

```bash
git checkout -- lib/natives-macos/libjhvmetalhost.dylib
git add src/org/helioviewer/jhv/gui/component/ImageLayersPane.java src/org/helioviewer/jhv/gui/MainFrame.java
git commit -m "Nest Image Layers into four collapsible sections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Extract the Layers section from MoviePanel

Move the New Layer control, request cadence, sync button, and the layer table out of `MoviePanel` (which becomes Transport-only) into a new `LayersSectionPanel`. Rewire the `ObservationSelector` so the New Layer button reads the master range from `MoviePanel` and cadence from the Layers section.

**Files:**
- Create: `src/org/helioviewer/jhv/layers/selector/LayersSectionPanel.java`
- Modify: `src/org/helioviewer/jhv/gui/component/MoviePanel.java` (remove cadence/addLayer/sync/LayersPanel; keep slider, buttons, mode, record, timeSelector)
- Modify: `src/org/helioviewer/jhv/gui/MainFrame.java` (pass `LayersSectionPanel` as the `layers` arg)

**Interfaces:**
- Consumes: `MoviePanel.getInstance().getStartTime()`, `getEndTime()` (master range); `CadencePanel(TimeSelectorPanel)`; `MainFrame.getLayersPanel()`; `ImageSelectorPanel(Interfaces.ObservationSelector)`; `Interfaces.ObservationSelector`.
- Produces: `LayersSectionPanel` implementing `Interfaces.ObservationSelector` where `getStartTime()/getEndTime()` delegate to `MoviePanel.getInstance()` and `getCadence()` returns its own `CadencePanel`.

- [ ] **Step 1: Move the master `getStartTime/getEndTime` accessors' authority to MoviePanel**

`MoviePanel` already implements `getStartTime()/getEndTime()` via `timeSelectorPanel`. Keep those. Remove from `MoviePanel`: the `cadencePanel` field, `add(cadencePanel)`, the `addLayerButton`/`imageSelectorPanel`/`syncButton`/`addLayerPanel` block (lines ~197–222), the `add(MainFrame.getLayersPanel())` line (~224), the `load(String,int)` and `syncLayersSpan()` methods, and `getCadence()`. Leave the `Interfaces.ObservationSelector` implements clause only if still needed by `ExportMovie`/`Player`; if `getCadence`/`load` were the only ObservationSelector uses, drop the interface from MoviePanel. (Verify by compiling — the compiler names every remaining abstract-method obligation.)

- [ ] **Step 2: Create `LayersSectionPanel`**

```java
package org.helioviewer.jhv.layers.selector;

import java.awt.BorderLayout;
import java.awt.EventQueue;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.CadencePanel;
import org.helioviewer.jhv.gui.component.ImageSelectorPanel;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.layers.ImageLayers;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideSplitButton;

@SuppressWarnings("serial")
public final class LayersSectionPanel extends JPanel implements Interfaces.ObservationSelector {

    private final CadencePanel cadencePanel;
    private final ImageSelectorPanel imageSelectorPanel;
    private final JideSplitButton addLayerButton;

    public LayersSectionPanel(JPanel manageWrapper) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        // request cadence for the next layer, sourced against the master time range
        cadencePanel = new CadencePanel(MoviePanel.getInstance().getTimeSelectorPanel());
        imageSelectorPanel = new ImageSelectorPanel(this);

        addLayerButton = new JideSplitButton(Buttons.newLayer);
        addLayerButton.setAlwaysDropdown(true);
        addLayerButton.add(imageSelectorPanel);
        addLayerButton.getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                EventQueue.invokeLater(() -> imageSelectorPanel.getFocused().grabFocus());
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        JideButton syncButton = new JideButton(Buttons.syncLayers);
        syncButton.setToolTipText("Synchronize time intervals of all layers");
        syncButton.addActionListener(e -> syncLayersSpan());

        JPanel addLayerRow = new JPanel(new BorderLayout());
        addLayerRow.add(addLayerButton, BorderLayout.LINE_START);
        addLayerRow.add(cadencePanel, BorderLayout.CENTER);
        addLayerRow.add(syncButton, BorderLayout.LINE_END);

        add(addLayerRow);
        add(MainFrame.getLayersPanel());
        add(manageWrapper);
    }

    private void syncLayersSpan() {
        ImageLayers.syncLayersSpan(getStartTime(), getEndTime(), getCadence());
    }

    @Override
    public int getCadence() {
        return cadencePanel.getCadence();
    }

    @Override
    public void setTime(long start, long end) {
        MoviePanel.getInstance().setTime(start, end);
    }

    @Override
    public long getStartTime() {
        return MoviePanel.getInstance().getStartTime();
    }

    @Override
    public long getEndTime() {
        return MoviePanel.getInstance().getEndTime();
    }

    @Override
    public void load(String server, int sourceId) {
        addLayerButton.doClickOnMenu();
        imageSelectorPanel.load(null, server, sourceId, getStartTime(), getEndTime(), getCadence());
    }

    @Override
    public void setAvailabilityEnabled(boolean enable) {}
}
```

- [ ] **Step 3: Expose the master time selector on MoviePanel**

Add to `MoviePanel`: `public TimeSelectorPanel getTimeSelectorPanel() { return timeSelectorPanel; }` (needed by `CadencePanel`'s span math and by the Layers section).

- [ ] **Step 4: Wire into MainFrame**

`manageWrapper` belongs to the Layers section (it sits below the table), so build it in MainFrame and hand it to `LayersSectionPanel`; `ImageLayersPane` stays the 2-arg form from Task 1 (its `layerOptionsWrapper`/`geometryWrapper` remain internal until Task 3). Replace the Task 1 placeholder line:

```java
        JPanel manageWrapper = new JPanel(new java.awt.BorderLayout());
        LayersSectionPanel layersSection = new LayersSectionPanel(manageWrapper);
        imageLayersPane = new ImageLayersPane(MoviePanel.getInstance(), layersSection);
        leftPane.add("Image Layers", imageLayersPane, true);
```

Remove the unused `getManageWrapper()` from `ImageLayersPane` (the manage wrapper is now owned by MainFrame / the Layers section, not the pane).

- [ ] **Step 5: Compile & fix fallout**

Run `ant compile`. Resolve every reported error (removed methods referenced elsewhere — e.g. `MoviePanel.getInstance().load(...)`; if `ImageSelectorPanel` referenced `MoviePanel`, repoint to `LayersSectionPanel`). Expected end: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Launch check**

Confirm: "Transport control" now holds slider/playback/recording/master time only; "Layers" holds New Layer ▾ + Time step + sync + the layer table. Add a layer via New Layer ▾ — it loads with the master range and the Time step cadence. Sync still works.

- [ ] **Step 7: Commit**

```bash
git checkout -- lib/natives-macos/libjhvmetalhost.dylib
git add -A
git commit -m "Extract Layers section from MoviePanel; Transport holds playback + master time

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Split ImageLayerOptions and drive the three section wrappers

Split `ImageLayerOptions` into a rendering panel and a geometry panel, add a selection controller (`LayerOptionSections`) that fills the Layer options and Geometry wrappers, and route the table's selection through it. Non-image layers put their existing options panel into the Layer options wrapper only.

**Files:**
- Create: `src/org/helioviewer/jhv/layers/selector/ImageLayerRenderingPanel.java`
- Create: `src/org/helioviewer/jhv/layers/selector/ImageLayerGeometryPanel.java`
- Create: `src/org/helioviewer/jhv/layers/selector/LayerOptionSections.java`
- Modify: `src/org/helioviewer/jhv/layers/selector/LayersPanel.java` (drop internal `optionsPanelWrapper`; call the controller on selection; expose a `setSelectedLayer` for MainFrame width measurement)
- Modify: `src/org/helioviewer/jhv/gui/MainFrame.java` (measurement calls → controller)
- Delete: `src/org/helioviewer/jhv/layers/selector/ImageLayerOptions.java` (contents moved)

**Interfaces:**
- Produces:
  - `ImageLayerRenderingPanel(ImageLayer)` — difference, opacity, blend, sharpen, levels, colormap (LUT), channels, filter. Exposes `void refresh(Layer)` (moves the existing `ImageLayerOptions.refresh` body: `downloadButton` visibility no longer here — see Task 4; keep `lutPanel.setLUT(...)`).
  - `ImageLayerGeometryPanel(ImageLayer)` — slit, inner mask, ΔCROTA, ΔCRVAL1, ΔCRVAL2 (the widgets previously behind the ⚙ toggle). No internal toggle button.
  - `LayerOptionSections` — controller:
    - `LayerOptionSections(JPanel layerOptionsWrapper, JPanel geometryWrapper, JPanel manageWrapper)`
    - `void setSelectedLayer(@Nullable Layer layer)` — fills/clears the wrappers.
- Consumes: `LayerOptions.getOptionsPanel(Layer)` for non-image layers; `ComponentUtils.setEnabled`.

- [ ] **Step 1: Create `ImageLayerRenderingPanel`**

Move from `ImageLayerOptions` the rendering widgets and their `addToGridBag` layout: `differencePanel`, `opacityPanel`, `blendPanel`, `sharpenPanel`, `levelsPanel`, `lutPanel`, `channelMixerPanel`, `imageFilterPanel`. Keep the `addToGridBag(GridBagConstraints, FilterDetails)` helper verbatim. Keep `refresh(Layer)` but reduced to `lutPanel.setLUT(((ImageLayer) layer).getView().getDefaultLUT());`. Do **not** include the ⚙ adjustments toggle, the download/meta/refresh cluster, or the geometry widgets.

- [ ] **Step 2: Create `ImageLayerGeometryPanel`**

Move `slitPanel`, `innerMaskPanel`, `deltaCROTAPanel`, `deltaCRVAL1Panel`, `deltaCRVAL2Panel` and lay them out with the same `addToGridBag` helper, all rows visible (no toggle, no `setAdjustmentsVisibility`).

- [ ] **Step 3: Create `LayerOptionSections`**

```java
package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

// Fills the three section wrappers for the selected layer. Image layers get a
// split rendering/geometry pair (cached per layer); other layer types get their
// generic options panel in the Layer options wrapper only.
public final class LayerOptionSections implements Layers.Listener {

    private record ImagePanels(ImageLayerRenderingPanel rendering, ImageLayerGeometryPanel geometry) {}

    private final JPanel layerOptionsWrapper;
    private final JPanel geometryWrapper;
    private final JPanel manageWrapper;
    private final Map<ImageLayer, ImagePanels> cache = new IdentityHashMap<>();

    public LayerOptionSections(JPanel layerOptionsWrapper, JPanel geometryWrapper, JPanel manageWrapper) {
        this.layerOptionsWrapper = layerOptionsWrapper;
        this.geometryWrapper = geometryWrapper;
        this.manageWrapper = manageWrapper;
        Layers.addListener(this);
    }

    public void setSelectedLayer(@Nullable Layer layer) {
        layerOptionsWrapper.removeAll();
        geometryWrapper.removeAll();
        manageWrapper.removeAll();

        if (layer instanceof ImageLayer il) {
            ImagePanels p = cache.computeIfAbsent(il, k -> new ImagePanels(new ImageLayerRenderingPanel(il), new ImageLayerGeometryPanel(il)));
            ComponentUtils.setEnabled(p.rendering(), il.isEnabled());
            ComponentUtils.setEnabled(p.geometry(), il.isEnabled());
            layerOptionsWrapper.add(p.rendering());
            geometryWrapper.add(p.geometry());
            // manageWrapper filled in Task 4 (and readout in Task 5)
        } else if (layer != null) {
            Component generic = LayerOptions.getOptionsPanel(layer);
            if (generic != null) {
                ComponentUtils.setEnabled(generic, layer.isEnabled());
                layerOptionsWrapper.add(generic);
            }
        }
        revalidateAll();
    }

    private void revalidateAll() {
        layerOptionsWrapper.revalidate();
        layerOptionsWrapper.repaint();
        geometryWrapper.revalidate();
        geometryWrapper.repaint();
        manageWrapper.revalidate();
        manageWrapper.repaint();
    }

    @Override
    public void layerAdded(int index, Layer layer) {}

    @Override
    public void layerRemoved(int index, Layer layer) {
        if (layer instanceof ImageLayer il)
            cache.remove(il);
    }

    @Override
    public void layersCleared() {
        cache.clear();
    }

    @Override
    public void nameUpdated(Layer layer) {}

    @Override
    public void layerUpdated(Layer layer) {
        if (layer instanceof ImageLayer il && cache.get(il) instanceof ImagePanels p)
            p.rendering().refresh(layer);
    }

    @Override
    public void timeUpdated(Layer layer) {}
}
```

Confirm `Layers.Listener` method set matches `LayerOptions` (same interface). If the interface differs, mirror exactly the methods `LayerOptions` overrides.

- [ ] **Step 4: Rewire `LayersPanel`**

Remove the internal `optionsPanelWrapper` field, its `add(...)` (lines ~247–250), and `setOptionsPanel`/`refreshSelectedOptionsPanel`/`selectExistingRow`'s call into it. Give `LayersPanel` a `LayerOptionSections sections` reference (constructor arg) and replace `setOptionsPanel(selectedLayer())` calls with `sections.setSelectedLayer(selectedLayer())`. Add public `void setSelectedLayer(@Nullable Layer layer) { sections.setSelectedLayer(layer); }` for external callers (MainFrame). Update the `LayersPanel()` constructor signature to `LayersPanel(LayerOptionSections sections)`.

- [ ] **Step 5: Construct the controller in MainFrame and thread wrappers**

Move ownership of all three wrappers to MainFrame so the controller can hold them and the pane/section merely display them. Refactor `ImageLayersPane` to the signature `ImageLayersPane(JComponent transport, JComponent layers, JPanel layerOptionsWrapper, JPanel geometryWrapper)` — drop its internal `layerOptionsWrapper`/`geometryWrapper` fields and their getters; it now only assembles the four `CollapsiblePane`s from the passed components. In `MainFrame.prepare()`, build in this exact order (each object's dependency exists before it):

```java
        JPanel layerOptionsWrapper = new JPanel(new java.awt.BorderLayout());
        JPanel geometryWrapper = new JPanel(new java.awt.BorderLayout());
        JPanel manageWrapper = new JPanel(new java.awt.BorderLayout());
        LayerOptionSections sections = new LayerOptionSections(layerOptionsWrapper, geometryWrapper, manageWrapper);
        layersPanel = new LayersPanel(sections);                       // table needs the controller
        LayersSectionPanel layersSection = new LayersSectionPanel(manageWrapper); // ctor calls MainFrame.getLayersPanel()
        imageLayersPane = new ImageLayersPane(MoviePanel.getInstance(), layersSection, layerOptionsWrapper, geometryWrapper);
        leftPane.add("Image Layers", imageLayersPane, true);
```

Update MainFrame width-measurement calls: `layersPanel.setOptionsPanel(null)` → `layersPanel.setSelectedLayer(null)`; `layersPanel.setOptionsPanel(optionsLayer)` → `layersPanel.setSelectedLayer(optionsLayer)`.

- [ ] **Step 6: Delete `ImageLayerOptions.java`**

```bash
git rm src/org/helioviewer/jhv/layers/selector/ImageLayerOptions.java
```
The `LayerOptions` registry line `register(ImageLayer.class, ...)` is no longer used for image layers (the controller handles them directly) — but keep it harmless only if something else calls `getOptionsPanel(imageLayer)`. Since the controller no longer calls it for image layers, **remove** the `ImageLayer.class` registration to avoid referencing the deleted class.

- [ ] **Step 7: Compile & fix**

Run `ant compile`; resolve fallout until `BUILD SUCCESSFUL`.

- [ ] **Step 8: Launch check**

Select each layer type: an image layer fills "Layer options" (difference…filter) and "Geometry / crop" (slit…ΔCRVAL); a grid/viewpoint layer fills only "Layer options" with its generic panel and leaves Geometry empty. Deselect/remove clears all sections. Disabled layers gray out their controls.

- [ ] **Step 9: Commit**

```bash
git checkout -- lib/natives-macos/libjhvmetalhost.dylib
git add -A
git commit -m "Split image layer options into Layer options + Geometry/crop sections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Relocate download / metadata / refresh into the Layers management area

Move the per-layer download, metadata, and refresh buttons (formerly in `ImageLayerOptions`) into a management panel that the controller drops into `manageWrapper`.

**Files:**
- Create: `src/org/helioviewer/jhv/layers/selector/ImageLayerManagePanel.java`
- Modify: `src/org/helioviewer/jhv/layers/selector/LayerOptionSections.java` (cache + place the manage panel)

**Interfaces:**
- Produces: `ImageLayerManagePanel(ImageLayer)` — the download toggle + progress, metadata button, PUNCH refresh button (verbatim move of the button block and `DownloadProgress` inner class from the old `ImageLayerOptions`). Exposes `void refresh(Layer)` setting `downloadButton` visibility (`!imageLayer.isLocal()`).

- [ ] **Step 1: Create `ImageLayerManagePanel`** — move the `downloadButton`/`progressBar`/`downloadProgress`, the `metaButton` + `MetaDataDialog`, the `refreshButton` + spinner logic, `rightCluster`/`buttonPanel` layout, and the `DownloadProgress` inner class from the deleted `ImageLayerOptions`. Lay out as a single row (BorderLayout: downloadButton LINE_START, meta/refresh cluster LINE_END). Include the `refresh(Layer)` for download-button visibility.

- [ ] **Step 2: Extend the controller cache** — change `ImagePanels` to `record ImagePanels(ImageLayerRenderingPanel rendering, ImageLayerGeometryPanel geometry, ImageLayerManagePanel manage)`; in `setSelectedLayer`, `manageWrapper.add(p.manage())` for image layers; in `layerUpdated`, also call `p.manage().refresh(layer)`.

- [ ] **Step 3: Move download-visibility out of rendering.refresh** — ensure `ImageLayerRenderingPanel.refresh` no longer touches a download button (it was reduced in Task 3); the manage panel owns it now.

- [ ] **Step 4: Compile** — `ant compile` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Launch check** — select an image layer; download/metadata/refresh appear in the Layers section (below the table, in the management area), function identically (start/stop a download, open metadata, PUNCH refresh visible only for PUNCH layers). "Layer options" no longer shows those buttons.

- [ ] **Step 6: Commit**

```bash
git checkout -- lib/natives-macos/libjhvmetalhost.dylib
git add -A
git commit -m "Move download/metadata/refresh buttons into the Layers management area

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

**→ PR1 boundary.** Push `feat-panels` and open PR1 here (see Execution Handoff). PR2 stacks on it.

---

## PR 2 — Actual-stats readout

### Task 5: Selected-layer readout (start/stop · actual cadence · N frames)

Add a readout to the management panel showing the selected image layer's real statistics, updating on selection and as frames stream in.

**Files:**
- Modify: `src/org/helioviewer/jhv/layers/selector/ImageLayerManagePanel.java` (add a readout label; add `updateReadout()`)
- Modify: `src/org/helioviewer/jhv/layers/selector/LayerOptionSections.java` (call `manage.updateReadout()` from `layerUpdated`/`timeUpdated`)

**Interfaces:**
- Consumes: `ImageLayer.getView()` → `View.getFirstTime()`, `getLastTime()`, `getMaximumFrameNumber()`, `getFrameTime(int)`; `JHVTime.milli`; `TimeUtils.format(long)`.
- Produces: `ImageLayerManagePanel.updateReadout()`.

- [ ] **Step 1: Add the readout label** — in `ImageLayerManagePanel`, add `private final JLabel readout = new JLabel();` placed above the button row (BoxLayout PAGE_AXIS wrapping readout + button row). Add:

```java
    void updateReadout() {
        org.helioviewer.jhv.view.View view = layer.getView();
        int max = view.getMaximumFrameNumber();
        int frames = max + 1;
        long start = view.getFirstTime().milli;
        long end = view.getLastTime().milli;
        String cadence = frames > 1
                ? formatCadence(medianSpacingSec(view, max))
                : "—";
        readout.setText(String.format("<html>%s – %s<br>cadence %s · %d frame%s</html>",
                org.helioviewer.jhv.time.TimeUtils.format(start),
                org.helioviewer.jhv.time.TimeUtils.format(end),
                cadence, frames, frames == 1 ? "" : "s"));
    }

    private static long medianSpacingSec(org.helioviewer.jhv.view.View view, int max) {
        long[] gaps = new long[max];
        long prev = view.getFrameTime(0).milli;
        for (int i = 1; i <= max; i++) {
            long t = view.getFrameTime(i).milli;
            gaps[i - 1] = (t - prev) / 1000;
            prev = t;
        }
        java.util.Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    private static String formatCadence(long sec) {
        if (sec >= 86400) return (sec / 86400) + " d";
        if (sec >= 3600) return (sec / 3600) + " h";
        if (sec >= 60) return (sec / 60) + " min";
        return sec + " s";
    }
```

Keep `layer` as a field in `ImageLayerManagePanel` (store the constructor arg).

- [ ] **Step 2: Call it** — in the manage panel constructor, call `updateReadout()` once. In `LayerOptionSections`, after `manage.refresh(layer)` in `layerUpdated`, add `p.manage().updateReadout();`; also handle `timeUpdated(Layer layer)` the same way (frames stream in via time updates). In `setSelectedLayer`, after adding the manage panel, call `p.manage().updateReadout()`.

- [ ] **Step 3: Compile** — `ant compile` → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Launch check** — load a PUNCH layer with a known range/cadence; the readout shows correct start–stop, a plausible cadence, and a frame count matching the layer table. While a layer is still downloading, the frame count climbs as frames arrive.

- [ ] **Step 5: Commit**

```bash
git checkout -- lib/natives-macos/libjhvmetalhost.dylib
git add -A
git commit -m "Add selected-layer readout: start/stop, actual cadence, frame count

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

**→ PR2 boundary.** Push and open PR2 stacked on PR1.

---

## Self-review notes

- **Spec coverage:** four sections (T1–T3), request cadence by New Layer (T2), master range in Transport (T2), retarget-on-selection via controller (T3), "Layer options" name + non-image handling (T3), Geometry image-only (T3), download/meta/refresh relocation (T4), new-layers-only propagation (T2 keeps inheritance; sync unchanged), actual-stats readout (T5), two-PR split (T4/T5 boundaries). All covered.
- **Caching:** `LayerOptionSections` mirrors `LayerOptions`' per-layer lifecycle (evict on remove/clear, refresh on update) — avoids stale panels and the one-parent-per-component trap.
- **Risk:** the MoviePanel↔LayersSectionPanel `ObservationSelector` split (T2) is the highest-risk wiring; its launch check (add a layer, verify range+cadence) is the guard.
