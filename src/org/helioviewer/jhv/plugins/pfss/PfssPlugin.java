package org.helioviewer.jhv.plugins.pfss;

import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.selector.LayerOptions;
import org.helioviewer.jhv.plugins.Plugin;

import org.json.JSONObject;

public class PfssPlugin extends Plugin {

    private static final PfssCache pfssCache = new PfssCache();
    private static final java.util.concurrent.atomic.AtomicReference<PfssLayer> layer = new java.util.concurrent.atomic.AtomicReference<>(new PfssLayer(null));

    public PfssPlugin() {
        super("PFSS", "Visualize PFSS model data");
    }

    static PfssCache getPfssCache() {
        return pfssCache;
    }

    /** A layer restored from a session replaces the one made at class load, so uninstall removes the right one. */
    static void restoreLayer(PfssLayer _layer) {
        layer.set(_layer);
    }

    @Override
    public void install() {
        Layers.add(layer.get());
    }

    @Override
    public void uninstall() {
        Layers.remove(layer.get());
        pfssCache.clear();
    }

    @Override
    public void installGUI() {
        LayerOptions.register(PfssLayer.class, layer -> new PfssLayerOptions((PfssLayer) layer));
    }

    @Override
    public void uninstallGUI() {
        LayerOptions.unregister(PfssLayer.class);
    }

    @Override
    public void saveState(JSONObject jo) {}

    @Override
    public void loadState(JSONObject jo) {}

}
