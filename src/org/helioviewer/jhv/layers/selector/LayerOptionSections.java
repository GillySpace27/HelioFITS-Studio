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

    private record ImagePanels(ImageLayerRenderingPanel rendering, ImageLayerGeometryPanel geometry, ImageLayerManagePanel manage) {}

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
            ImagePanels p = cache.computeIfAbsent(il, k -> new ImagePanels(new ImageLayerRenderingPanel(il), new ImageLayerGeometryPanel(il), new ImageLayerManagePanel(il)));
            ComponentUtils.setEnabled(p.rendering(), il.isEnabled());
            ComponentUtils.setEnabled(p.geometry(), il.isEnabled());
            ComponentUtils.setEnabled(p.manage(), il.isEnabled());
            layerOptionsWrapper.add(p.rendering());
            geometryWrapper.add(p.geometry());
            manageWrapper.add(p.manage());
            // readout in Task 5
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
        if (layer instanceof ImageLayer il && cache.get(il) instanceof ImagePanels p) {
            p.rendering().refresh(layer);
            p.manage().refresh(layer);
        }
    }

    @Override
    public void timeUpdated(Layer layer) {}
}
