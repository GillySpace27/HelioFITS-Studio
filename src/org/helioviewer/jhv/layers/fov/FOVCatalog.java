package org.helioviewer.jhv.layers.fov;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.time.JHVTime;

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
    private static final String KEY_COVERAGE = "coverage";
    private static final String KEY_BODIES = "bodies";
    /** The planets marked in 2D, by SpaceObject name: the inner ones and Jupiter. */
    private static final String[] PLANETS = {"Mercury", "Venus", "Earth", "Mars", "Jupiter"};
    private static final float BODY_POINT = 0.005f;
    private static final double ARROW_PX = 16, WING_PX = 6, EDGE_PX = 12;

    private final List<FOVPlatform> platforms = new ArrayList<>();
    private final List<FOVLabels.Label> labels = new ArrayList<>();
    private final GLSLShape bodyPoints = new GLSLShape(true);
    private final GLSLLine bodyArrows = new GLSLLine(true);
    private final BufVertex pointBuf = new BufVertex();
    private final BufVertex arrowBuf = new BufVertex();
    private boolean cones;
    private boolean showLabels;
    private boolean coverage;
    private boolean bodies;

    public FOVCatalog(JSONObject jo) {
        JSONObject empty = new JSONObject();
        if (jo == null)
            jo = empty;
        cones = jo.optBoolean(KEY_CONES, true);
        showLabels = jo.optBoolean(KEY_LABELS, true);
        coverage = jo.optBoolean(KEY_COVERAGE, true);
        bodies = jo.optBoolean(KEY_BODIES, true);
        buildCatalog(jo, empty);
    }

    /** In a flat projection, the edge of the solar surface each spacecraft with a field on sees. */
    boolean coverage() {
        return coverage;
    }

    void setCoverage(boolean v) {
        coverage = v;
    }

    /** In a flat projection, where the planets and spacecraft are, with an arrow at the edge for those off screen. */
    boolean bodies() {
        return bodies;
    }

    void setBodies(boolean v) {
        bodies = v;
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

    /** Every enabled outline the pointer's sight line passes through, for the cursor identifier. */
    public List<String> namesAt(org.helioviewer.jhv.identify.Identifier.Probe probe, org.helioviewer.jhv.time.JHVTime time) {
        List<String> out = new ArrayList<>();
        platforms.forEach(platform -> out.addAll(platform.namesAt(probe, time)));
        return out;
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
        bodyPoints.init();
        bodyArrows.init();
    }

    public void dispose() {
        platforms.forEach(FOVPlatform::dispose);
        bodyPoints.dispose();
        bodyArrows.dispose();
    }

    /** The flat projections (FOVFlat). */
    public void renderFlat(MapView mv, Viewport vp) {
        labels.clear();
        bodyLabels = 0;
        platforms.forEach(platform -> platform.renderFlat(mv, vp, coverage, labels));
        if (bodies)
            renderBodies(mv, vp);
        if (showLabels || bodies)
            FOVLabels.draw(vp, showLabels ? labels : labels.subList(labels.size() - bodyLabels, labels.size()));
    }

    private int bodyLabels;

    private record Body(String name, double[] at, byte[] color) {}

    private List<Body> bodies(JHVTime time) {
        List<Body> list = new ArrayList<>();
        for (String planet : PLANETS) {
            SpaceObject so = SpaceObject.get(planet);
            Position p = so == null ? null : Spice.getCarrington(so.getSpiceName(), time);
            if (p != null && p.distance > 0)
                list.add(new Body(planet, FOVPlatform.world(p), so.getColor()));
        }
        for (FOVPlatform platform : platforms) {
            if (platform.atEarth() || !platform.hasEnabledField())
                continue; // a spacecraft is marked once one of its fields is on, so nothing is fetched unasked
            Position p = platform.position(time);
            if (p != null)
                list.add(new Body(platform.toString(), FOVPlatform.world(p), platform.color()));
        }
        return list;
    }

    private void renderBodies(MapView mv, Viewport vp) {
        int before = labels.size();
        double[] viewer = FOVPlatform.world(mv.viewpoint());
        double w = mv.cameraWidth(vp);
        // The visible part of the map, from the viewport's corners in window pixels.
        double xa = ViewportMath.computeUpX(vp, w, mv.cameraTranslationX(), vp.x), xb = ViewportMath.computeUpX(vp, w, mv.cameraTranslationX(), vp.x + vp.width);
        double ya = ViewportMath.computeUpY(vp, w, mv.cameraTranslationY(), vp.yAWT), yb = ViewportMath.computeUpY(vp, w, mv.cameraTranslationY(), vp.yAWT + vp.height);
        double perPx = Math.abs(xb - xa) / vp.width;
        double edge = EDGE_PX * perPx;
        double x0 = Math.min(xa, xb) + edge, x1 = Math.max(xa, xb) - edge, y0 = Math.min(ya, yb) + edge, y1 = Math.max(ya, yb) - edge;
        boolean surfaceMap = mv.isLatitudinal();
        for (Body b : bodies(mv.viewpoint().time)) {
            if (FOVCone.len(FOVCone.sub(b.at(), viewer)) < 0.01 * FOVCone.len(viewer))
                continue; // where the observer is (SDO and SOHO are at Earth, to a hundredth of their distance)
            Vec2 v = surfaceMap ? mv.projectToScreen(vp, new Vec3(b.at()[0], b.at()[1], b.at()[2]))
                    : FOVFlat.project(mv, vp, viewer, b.at());
            if (v == null && !surfaceMap) {
                double[] edgeward = FOVFlat.towardEdge(viewer, b.at());
                v = edgeward == null ? null : FOVFlat.project(mv, vp, viewer, edgeward);
            }
            if (v == null || !Double.isFinite(v.x) || !Double.isFinite(v.y))
                continue;
            String text = surfaceMap ? b.name() : b.name() + ' ' + Math.round(FOVCone.elongation(viewer, b.at())) + '°';
            double[] e = FOVFlat.edgePoint(v.x, v.y, x0, x1, y0, y1);
            if (e == null) {
                pointBuf.putVertex((float) v.x, (float) v.y, 0, BODY_POINT, b.color());
                labels.add(new FOVLabels.Label(text, new double[]{v.x, v.y, 0}, b.color()));
                continue;
            }
            double dx = v.x - 0.5 * (x0 + x1), dy = v.y - 0.5 * (y0 + y1), dl = Math.hypot(dx, dy);
            dx /= dl;
            dy /= dl;
            double[] tail = {e[0] - dx * ARROW_PX * perPx, e[1] - dy * ARROW_PX * perPx};
            byte[] c = b.color();
            arrowBuf.startLine((float) tail[0], (float) tail[1], 0, 1, c);
            arrowBuf.putVertex((float) e[0], (float) e[1], 0, 1, c);
            arrowBuf.endLine();
            double bx = e[0] - dx * WING_PX * perPx, by = e[1] - dy * WING_PX * perPx, px = -dy * WING_PX * perPx, py = dx * WING_PX * perPx;
            arrowBuf.startLine((float) (bx + px), (float) (by + py), 0, 1, c);
            arrowBuf.putVertex((float) e[0], (float) e[1], 0, 1, c);
            arrowBuf.putVertex((float) (bx - px), (float) (by - py), 0, 1, c);
            arrowBuf.endLine();
            // The label sits inside the arrow's tail, aligned away from the edge it points at.
            boolean side = Math.abs(dx) * (y1 - y0) >= Math.abs(dy) * (x1 - x0);
            double align = side ? (dx > 0 ? 1 : 0) : 0.5;
            double ly = side ? tail[1] : tail[1] - dy * 10 * perPx;
            labels.add(new FOVLabels.Label(text, new double[]{tail[0], ly, 0}, c, align));
        }
        bodyLabels = labels.size() - before;
        bodyPoints.uploadAndClear(pointBuf);
        bodyPoints.renderPoints(ViewportMath.getPixelFactor(vp, w));
        bodyArrows.uploadAndClear(arrowBuf);
        bodyArrows.renderLine(vp, GLSLLine.LINEWIDTH_BASIC);
    }

    public void render(MapView mv, Viewport vp) {
        boolean asCones = cones && mv.rendersIn3D();
        labels.clear();
        bodyLabels = 0;
        platforms.forEach(platform -> platform.render(mv, vp, asCones, labels));
        if (asCones && showLabels)
            FOVLabels.draw(vp, labels);
    }

    public void serialize(JSONObject jo) {
        jo.put(KEY_CONES, cones);
        jo.put(KEY_LABELS, showLabels);
        jo.put(KEY_COVERAGE, coverage);
        jo.put(KEY_BODIES, bodies);
        platforms.forEach(platform -> jo.put(platform.toString(), platform.toJson()));
    }

}
