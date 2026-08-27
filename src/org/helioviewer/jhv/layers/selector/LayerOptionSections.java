package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.CollapsiblePane;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

// Fills the three section wrappers for the selected layer. Image layers get a
// split rendering/geometry pair (cached per layer); other layer types get their
// generic options panel in the Layer options wrapper only.
public final class LayerOptionSections implements Layers.Listener, Interfaces.LazyComponent {

    private record ImagePanels(ImageLayerRenderingPanel rendering, ImageLayerGeometryPanel geometry, ImageLayerManagePanel manage) {}

    private final JPanel layerOptionsWrapper;
    private final JPanel geometryWrapper;
    private final JPanel manageWrapper;
    private final Map<ImageLayer, ImagePanels> cache = new IdentityHashMap<>();
    @Nullable
    private ImageLayerManagePanel currentManage; // the manage panel currently shown, polled for live readout

    public LayerOptionSections(JPanel layerOptionsWrapper, JPanel geometryWrapper, JPanel manageWrapper) {
        this.layerOptionsWrapper = layerOptionsWrapper;
        this.geometryWrapper = geometryWrapper;
        this.manageWrapper = manageWrapper;
        Layers.addListener(this);
        UITimer.register(this); // poll the readout so its frame count updates live as a download lands
    }

    // Called ~10 Hz by UITimer; updateReadout is memoized, so it only rebuilds when the count changes.
    @Override
    public void lazyRepaint() {
        if (currentManage != null)
            currentManage.updateReadout();
    }

    /**
     * Show the options for a whole selection.
     *
     * <p>The panels are built against one layer and cached per layer, so the lead layer (the
     * topmost selected) is what gets displayed and what the readouts track. Edits made in those
     * panels reach the rest of the selection through {@link Layers#applyToSelected}, so what is
     * on screen is the lead's state and what a control does is apply to all of them.
     *
     * <p>Only options that can be applied to every selected layer stay enabled. In practice
     * that means: image layers share the full set, so selecting several of them keeps
     * everything live; but a selection mixing an image layer with a non-image layer (a point
     * cloud, the grid) has nothing in common, so the panel says so rather than offering
     * controls that would silently only affect one of them.
     */
    public void setSelection(List<Layer> selection) {
        if (selection.size() <= 1) {
            setSelectedLayer(selection.isEmpty() ? null : selection.getFirst());
            return;
        }

        Layer lead = selection.getFirst();
        boolean allImage = selection.stream().allMatch(l -> l instanceof ImageLayer);
        if (!allImage) {
            layerOptionsWrapper.removeAll();
            geometryWrapper.removeAll();
            manageWrapper.removeAll();
            currentManage = null;
            geometryWrapper.setVisible(false);
            JLabel note = new JLabel("No options apply to all " + selection.size() + " selected layers");
            note.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            note.setToolTipText("These layers are of different kinds, so they share no controls. Select layers of one kind to edit them together.");
            layerOptionsWrapper.add(note);
            CollapsiblePane mixedPane = enclosingPane(layerOptionsWrapper);
            if (mixedPane != null)
                mixedPane.setTitle(selection.size() + " Layers Selected");
            revalidateAll();
            return;
        }

        setSelectedLayer(lead);
        // Everything an image layer offers applies to any other image layer, so nothing extra is
        // greyed here. The one exception is already handled inside the rendering panel: a
        // categorical LUT disables the value-affecting rows, and applyIndexedGating() below is
        // driven by whether ANY selected layer is categorical, not just the lead -- otherwise a
        // control would look live while being meaningless for one of the layers it would hit.
        ImagePanels p = cache.get((ImageLayer) lead);
        if (p != null && lead.isEnabled())
            p.rendering().refreshForSelection(selection);

        CollapsiblePane optionsPane = enclosingPane(layerOptionsWrapper);
        if (optionsPane != null)
            optionsPane.setTitle(selection.size() + " Layers Selected");
        revalidateAll();
    }

    public void setSelectedLayer(@Nullable Layer layer) {
        layerOptionsWrapper.removeAll();
        geometryWrapper.removeAll();
        manageWrapper.removeAll();
        currentManage = null;

        if (layer instanceof ImageLayer il) {
            // A layer edited through a multi-selection had its GLImage changed behind its own
            // panel's back, so the cached widgets are showing values the imagery no longer has.
            // Drop it and rebuild: every filter panel reads its layer in its constructor, so a
            // fresh one is correct by construction.
            if (Layers.consumeFannedEdit(il))
                cache.remove(il);
            ImagePanels p = cache.computeIfAbsent(il, k -> new ImagePanels(new ImageLayerRenderingPanel(il), new ImageLayerGeometryPanel(il), new ImageLayerManagePanel(il)));
            ComponentUtils.setEnabled(p.rendering(), il.isEnabled());
            ComponentUtils.setEnabled(p.geometry(), il.isEnabled());
            ComponentUtils.setEnabled(p.manage(), il.isEnabled());
            // The blanket enable above is keyed only on the layer's on/off checkbox, so it just
            // re-enabled every control in the rendering panel -- including the ones refresh()
            // disables for an indexed categorical layer (Levels, Sharpen, Filter, ...). Reapply
            // that finer-grained pass on top so selecting the layer doesn't undo it every time.
            // Skip it when the layer itself is off: refresh() only ever disables the indexed rows,
            // so running it here would partially re-enable a layer the checkbox just turned off.
            if (il.isEnabled())
                p.rendering().refresh(il);
            layerOptionsWrapper.add(p.rendering());
            geometryWrapper.add(p.geometry());
            manageWrapper.add(p.manage());
            currentManage = p.manage();
            p.manage().updateReadout();
        } else if (layer != null) {
            Component generic = LayerOptions.getOptionsPanel(layer);
            if (generic != null) {
                ComponentUtils.setEnabled(generic, layer.isEnabled());
                layerOptionsWrapper.add(generic);
            }
        }
        // Retitle the enclosing "Layer options" section to match the selected layer, e.g.
        // "SUVI 171 Layer Options", "Grid Layer Options".
        CollapsiblePane optionsPane = enclosingPane(layerOptionsWrapper);
        if (optionsPane != null) {
            optionsPane.setTitle(layer == null ? "Layer Options" : layer.getName() + " Layer Options");
            // Default the options open on every layer switch; hiding them is opt-in each time.
            if (layer != null)
                optionsPane.setExpanded(true);
        }

        // Hide the geometry controls entirely (not just leave them empty) unless the selected layer
        // actually has geometry options.
        geometryWrapper.setVisible(layer instanceof ImageLayer);
        revalidateAll();
    }

    @Nullable
    private static CollapsiblePane enclosingPane(Component c) {
        for (Component p = c; p != null; p = p.getParent())
            if (p instanceof CollapsiblePane pane)
                return pane;
        return null;
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
        if (layer instanceof ImageLayer il && cache.get(il) instanceof ImagePanels p) {
            p.rendering().refresh(layer);
            p.manage().refresh(layer);
            p.manage().forceReadoutRefresh();
        }
    }

    @Override
    public void timeUpdated(Layer layer) {
        if (layer instanceof ImageLayer il && cache.get(il) instanceof ImagePanels p) {
            p.manage().updateReadout();
        }
    }
}
