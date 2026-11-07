package org.helioviewer.jhv.layers.fov;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;

import org.json.JSONObject;

/**
 * The fields of view the FOV layer can draw. Fields added with the 3D cones come from HelioFleet's
 * instruments.js (S. Dash, MIT; its own sources: Brueckner 1995 for LASCO, Howard 2008 for SECCHI
 * HI, Howard 2020 for SoloHI, Vourlidas 2016 for WISPR, NOAA SWPC for CCOR-1, DeForest 2025 for
 * PUNCH) and, for K-Cor and UCoMP, from the MLSO instrument pages (HAO).
 */
public final class FOVCatalog {

    private static final String KEY_CONES = "cones";
    private static final String KEY_LABELS = "labels";

    private final List<FOVPlatform> platforms = new ArrayList<>();
    private final List<FOVLabels.Label> labels = new ArrayList<>();
    private boolean cones;
    private boolean showLabels;

    public FOVCatalog(JSONObject jo) {
        JSONObject empty = new JSONObject();
        if (jo == null)
            jo = empty;
        cones = jo.optBoolean(KEY_CONES, true);
        showLabels = jo.optBoolean(KEY_LABELS, true);
        buildCatalog(jo, empty);
    }

    /** In a 3D view, draw each field as a solid from its spacecraft instead of an outline on the plane of the sky. */
    boolean cones() {
        return cones;
    }

    void setCones(boolean v) {
        cones = v;
    }

    boolean labels() {
        return showLabels;
    }

    void setLabels(boolean v) {
        showLabels = v;
    }

    List<FOVPlatform> platforms() {
        return platforms;
    }

    private void buildCatalog(JSONObject jo, JSONObject empty) {
        addSolarOrbiter(jo, empty);
        addStereoAhead(jo, empty);
        addEarthOrbit(jo, empty);
        addSoho(jo, empty);
        addParker(jo, empty);
        addMlso(jo, empty);
    }

    private void addSolarOrbiter(JSONObject jo, JSONObject empty) {
        String uiName = "Solar Orbiter";
        JSONObject jpo = jo.optJSONObject(uiName, empty);
        FOVPlatform plat = new FOVPlatform(uiName, "SOLO", "Solar Orbiter", SpaceObject.SOLO.getColor(), jpo);
        plat.add(new FOVInstrument("EUI/HRI", FOVInstrument.FOVType.RECTANGULAR, 0, 16.6 / 60., 16.6 / 60., jpo));
        plat.add(new FOVInstrument("EUI/FSI", FOVInstrument.FOVType.RECTANGULAR, 0, 228 / 60., 228 / 60., jpo));
        plat.add(new FOVInstrument("Metis", FOVInstrument.FOVType.CIRCULAR, 3.2, 6.8, 6.8, jpo));
        plat.add(new FOVInstrument("PHI/HRT", FOVInstrument.FOVType.RECTANGULAR, 0, 0.28, 0.28, jpo));
        plat.add(new FOVInstrument("PHI/FDT", FOVInstrument.FOVType.RECTANGULAR, 0, 2, 2, jpo));
        plat.add(new FOVInstrument("SPICE", FOVInstrument.FOVType.RECTANGULAR, 0, 16 / 60., 11 / 60., jpo));
        plat.add(new FOVInstrument("STIX", FOVInstrument.FOVType.RECTANGULAR, 0, 2, 2, jpo));
        plat.add(FOVInstrument.elongation("SoloHI", 5.4, 44.9, FOVInstrument.Side.EAST, null, jpo));
        addPlatform(plat);
    }

    private void addStereoAhead(JSONObject jo, JSONObject empty) {
        String uiName = "STEREO Ahead";
        JSONObject jpo = jo.optJSONObject(uiName, empty);
        FOVPlatform plat = new FOVPlatform(uiName, "STEREO AHEAD", "STEREO Ahead", SpaceObject.STA.getColor(), jpo);
        plat.add(new FOVInstrument("EUVI", FOVInstrument.FOVType.RECTANGULAR, 0, 1.5877740 * 2048 / 3600., 1.5877740 * 2048 / 3600., jpo));
        plat.add(new FOVInstrument("COR1", FOVInstrument.FOVType.RECTANGULAR, 0, 15.008600 * 512 / 3600., 15.008600 * 512 / 3600., jpo));
        plat.add(new FOVInstrument("COR2", FOVInstrument.FOVType.CIRCULAR, 0, 14.700000 * 2048 / 3600., 14.700000 * 2048 / 3600., jpo));
        plat.add(FOVInstrument.elongation("HI-1", 4, 24, FOVInstrument.Side.EARTH, null, jpo));
        plat.add(FOVInstrument.elongation("HI-2", 18.7, 88.7, FOVInstrument.Side.EARTH, null, jpo));
        addPlatform(plat);
    }

    private void addEarthOrbit(JSONObject jo, JSONObject empty) {
        String uiName = "Earth orbit";
        JSONObject jpo = jo.optJSONObject(uiName, empty);
        FOVPlatform plat = new FOVPlatform(uiName, "EARTH", "Earth", Colors.Blue.bytes(), jpo); // Earth approximate
        plat.add(new FOVInstrument("AIA & HMI", FOVInstrument.FOVType.RECTANGULAR, 0, (0.6 * 4096) / 3600., (0.6 * 4096) / 3600., jpo));
        plat.add(new FOVInstrument("SWAP", FOVInstrument.FOVType.RECTANGULAR, 0, (3.1646941 * 1024) / 3600., (3.1646941 * 1024) / 3600., jpo));
        plat.add(new FOVInstrument("SUVI", FOVInstrument.FOVType.RECTANGULAR, 0, (2.5 * 1280) / 3600., (2.5 * 1280) / 3600., jpo));
        plat.add(new FOVInstrument("ASPIICS", FOVInstrument.FOVType.RECTANGULAR, .5850334, 1.6, 1.6, jpo));
        plat.add(FOVInstrument.solarRadii("CCOR-1", 3.7, 17, "2024-06-25", jpo));
        plat.add(FOVInstrument.elongation("PUNCH NFI", 1.5, 8, FOVInstrument.Side.CENTRED, "2025-03-12", jpo));
        plat.add(FOVInstrument.elongation("PUNCH WFI", 3, 45, FOVInstrument.Side.CENTRED, "2025-03-12", jpo));
        addPlatform(plat);
    }

    private void addSoho(JSONObject jo, JSONObject empty) {
        String uiName = "SOHO";
        JSONObject jpo = jo.optJSONObject(uiName, empty);
        FOVPlatform plat = new FOVPlatform(uiName, null, "SOHO", Colors.bytes(255, 140, 0), jpo); // positions from the ROB service
        plat.add(FOVInstrument.solarRadii("LASCO C2", 2.2, 6, null, jpo));
        plat.add(FOVInstrument.solarRadii("LASCO C3", 3.7, 30, null, jpo));
        addPlatform(plat);
    }

    private void addParker(JSONObject jo, JSONObject empty) {
        String uiName = "Parker Solar Probe";
        SpaceObject psp = SpaceObject.get(uiName);
        JSONObject jpo = jo.optJSONObject(uiName, empty);
        FOVPlatform plat = new FOVPlatform(uiName, null, uiName, psp == null ? Colors.Yellow.bytes() : psp.getColor(), jpo);
        plat.add(FOVInstrument.elongation("WISPR-I", 13.5, 53, FOVInstrument.Side.RAM, null, jpo));
        plat.add(FOVInstrument.elongation("WISPR-O", 50, 108, FOVInstrument.Side.RAM, null, jpo));
        addPlatform(plat);
    }

    /** Mauna Loa, drawn from Earth's centre: the offset of an observatory on the ground is far below what is drawn here. */
    private void addMlso(JSONObject jo, JSONObject empty) {
        String uiName = "MLSO (ground)";
        JSONObject jpo = jo.optJSONObject(uiName, empty);
        FOVPlatform plat = new FOVPlatform(uiName, "EARTH", "Earth", Colors.bytes(0, 190, 160), jpo);
        plat.add(FOVInstrument.solarRadii("K-Cor", 1.05, 3, null, jpo));
        plat.add(FOVInstrument.solarRadii("UCoMP", 1.03, 1.95, null, jpo));
        addPlatform(plat);
    }

    private void addPlatform(FOVPlatform platform) {
        platforms.add(platform);
    }

    public void init() {
        platforms.forEach(FOVPlatform::init);
    }

    public void dispose() {
        platforms.forEach(FOVPlatform::dispose);
    }

    public void render(MapView mv, Viewport vp) {
        boolean asCones = cones && mv.rendersIn3D();
        labels.clear();
        platforms.forEach(platform -> platform.render(mv, vp, asCones, labels));
        if (asCones && showLabels)
            FOVLabels.draw(vp, labels);
    }

    public void serialize(JSONObject jo) {
        jo.put(KEY_CONES, cones);
        jo.put(KEY_LABELS, showLabels);
        platforms.forEach(platform -> jo.put(platform.toString(), platform.toJson()));
    }

}
