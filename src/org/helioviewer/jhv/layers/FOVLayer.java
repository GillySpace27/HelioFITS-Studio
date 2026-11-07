package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.identify.Drawn;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.Probes;
import org.helioviewer.jhv.layers.fov.FOVCatalog;

import org.json.JSONObject;

public class FOVLayer extends AbstractLayer {

    private final FOVCatalog catalog;

    @Override
    public void serialize(JSONObject jo) {
        catalog.serialize(jo);
    }

    public FOVLayer(JSONObject jo) {
        catalog = new FOVCatalog(jo);
        identifySource = this::identify;
    }

    // The cursor identifier names the outlines the pointer is inside ("inside EUI/FSI's field").
    private final Drawn.Source identifySource;

    private List<Drawn.Hit> identify(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
        if (!isEnabled() || !isVisible(vp.idx))
            return List.of();
        Identifier.Probe probe = Probes.at(mv, vp, x, y);
        if (probe == null)
            return List.of();
        List<Drawn.Hit> out = new ArrayList<>();
        for (String name : catalog.namesAt(probe, mv.viewpoint().time))
            out.add(new Drawn.Hit(name, Drawn.FOV, 0, Double.NaN));
        return out;
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        catalog.render(mv, vp);
    }

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        catalog.renderFlat(mv, vp);
    }

    @Override
    public void init() {
        catalog.init();
        Drawn.register(identifySource);
    }

    @Override
    public void dispose() {
        Drawn.unregister(identifySource);
        catalog.dispose();
    }


    @Override
    public void remove() {
        dispose();
    }

    @Override
    public String getName() {
        return "FOV";
    }

    public FOVCatalog getCatalog() {
        return catalog;
    }

}
