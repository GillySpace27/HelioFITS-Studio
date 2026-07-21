package org.helioviewer.jhv.plugins.pointcloud;

import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.selector.LayerOptions;
import org.helioviewer.jhv.plugins.Plugin;

import org.json.JSONObject;

public class PointCloudPlugin extends Plugin {

    private final PointCloudLayer layer = new PointCloudLayer(null);

    public PointCloudPlugin() {
        super("Point Cloud Plugin", "Render point clouds and their alpha-shape meshes");
    }

    @Override
    public void install() {
        Layers.add(layer);
    }

    @Override
    public void uninstall() {
        Layers.remove(layer);
    }

    @Override
    public void installGUI() {
        LayerOptions.register(PointCloudLayer.class, l -> new PointCloudOptions((PointCloudLayer) l));
    }

    @Override
    public void uninstallGUI() {
        LayerOptions.unregister(PointCloudLayer.class);
    }

    @Override
    public void saveState(JSONObject jo) {
    }

    @Override
    public void loadState(JSONObject jo) {
    }

}
