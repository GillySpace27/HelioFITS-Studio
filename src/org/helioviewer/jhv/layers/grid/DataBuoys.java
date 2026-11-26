package org.helioviewer.jhv.layers.grid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.input.InputMouseListener;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.ModelEphemeris;
import org.helioviewer.jhv.layers.ModelLayer;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.timelines.TimelineLayer;
import org.helioviewer.jhv.timelines.TimelineLayers;
import org.helioviewer.jhv.timelines.band.Band;

import org.joml.Vector3f;
import org.json.JSONObject;

/**
 * Buoys: a ring in the 3D scene at the place a timeline row's spacecraft was, in that row's line
 * colour, so a dot can be traced to the plot it feeds.
 *
 * <p>One buoy per spacecraft, however many rows come from it. Rows are matched to a spacecraft by
 * the name the user picked on the row, else by {@link #infer} from the row's name. A spacecraft
 * with a model layer gets a larger halo on the model instead of the small ring, unless the halo
 * is switched off. Names show on hover, or always when asked; trails show the path over the
 * movie's span.
 *
 * <p>Positions come from {@link ModelEphemeris}, drawn in the same world frame as model layers
 * and field-of-view cones, so a buoy sits on its model. Where the position is not known nothing
 * is drawn.
 */
public final class DataBuoys {

    /** A spacecraft a row can be tied to: {@code target} is the name {@link ModelEphemeris} takes. */
    public record Craft(String key, String target, String code, String[] words) {
        @Override
        public String toString() {
            return target;
        }
    }

    // Row names are matched as whole words, so "ace" does not hit "space"; "wind" alone would hit
    // "solar wind", so Wind needs the instrument beside it. The catalogue's own names are not
    // checked here: a miss is fixed with the spacecraft picker on the row.
    private static final Craft[] CRAFT = {
            new Craft("psp", "Parker Solar Probe", "P", new String[]{"psp", "parker"}),
            new Craft("solo", "Solar Orbiter", "SO", new String[]{"solo", "solar orbiter"}),
            new Craft("sta", "STEREO Ahead", "SA", new String[]{"stereo a", "stereo ahead", "stereoa", "sta"}),
            new Craft("stb", "STEREO Behind", "SB", new String[]{"stereo b", "stereo behind", "stereob", "stb"}),
            new Craft("soho", "SOHO", "SH", new String[]{"soho"}),
            new Craft("ace", "ACE", "ACE", new String[]{"ace"}),
            new Craft("wind", "Wind", "W", new String[]{"wind swe", "wind mfi", "wind 3dp", "wind waves"}),
            new Craft("dscovr", "DSCOVR", "D", new String[]{"dscovr"}),
    };

    public static Craft[] craft() {
        return CRAFT.clone();
    }

    @Nullable
    public static Craft byKey(@Nullable String key) {
        for (Craft c : CRAFT)
            if (c.key().equals(key))
                return c;
        return null;
    }

    /** The spacecraft a row name points at, by whole words, or null. */
    @Nullable
    public static Craft infer(@Nullable String rowName) {
        if (rowName == null)
            return null;
        String norm = " " + rowName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (Craft c : CRAFT)
            for (String w : c.words())
                if (norm.contains(" " + w + " "))
                    return c;
        return null;
    }

    // ---- settings, kept in the session beside the grid layer's

    private boolean show = true;
    private boolean alwaysLabels;
    private boolean modelHalo = true;
    private boolean trails;

    public void serialize(JSONObject jo) {
        jo.put("buoys", show);
        jo.put("buoyLabels", alwaysLabels);
        jo.put("buoyHalo", modelHalo);
        jo.put("buoyTrails", trails);
    }

    public void deserialize(JSONObject jo) {
        show = jo.optBoolean("buoys", show);
        alwaysLabels = jo.optBoolean("buoyLabels", alwaysLabels);
        modelHalo = jo.optBoolean("buoyHalo", modelHalo);
        trails = jo.optBoolean("buoyTrails", trails);
    }

    public boolean isShow() { return show; }
    public boolean isAlwaysLabels() { return alwaysLabels; }
    public boolean isModelHalo() { return modelHalo; }
    public boolean isTrails() { return trails; }

    public void setShow(boolean v) { show = v; }
    public void setAlwaysLabels(boolean v) { alwaysLabels = v; }
    public void setModelHalo(boolean v) { modelHalo = v; }
    public void setTrails(boolean v) { trails = v; }

    // ---- grouping: pure, so a check can reach it

    /** One buoy's worth of rows: the spacecraft, the rows it feeds, the colour of the first. */
    public record Group(Craft craft, List<String> rows, java.awt.Color color) {}

    /** Rows that are on, grouped by spacecraft in first-seen order; rows with no spacecraft make no buoy. */
    public static List<Group> groups(List<? extends TimelineLayer> layers) {
        Map<Craft, List<String>> names = new LinkedHashMap<>();
        Map<Craft, java.awt.Color> colors = new LinkedHashMap<>();
        for (TimelineLayer tl : layers) {
            if (!(tl instanceof Band band) || !tl.isEnabled())
                continue;
            Craft c = band.getSpacecraft();
            if (c == null)
                continue;
            names.computeIfAbsent(c, k -> new ArrayList<>()).add(band.getName());
            java.awt.Color col = band.getDataColor();
            colors.putIfAbsent(c, col == null ? java.awt.Color.WHITE : col);
        }
        List<Group> out = new ArrayList<>();
        names.forEach((c, rows) -> out.add(new Group(c, rows, colors.get(c))));
        return out;
    }

    // ---- drawing

    private static final double RING_FRACTION = 0.012; // of the camera width, as the planet markers
    private static final int SEGMENTS = 40;
    private static final int TRAIL_SAMPLES = 64;
    private static final double TEXT_SCALE = 1.0;
    private static final double HOVER_PX = 14;

    private final Map<String, ModelEphemeris> ephemerides = new LinkedHashMap<>();
    private final GLSLLine lines = new GLSLLine(true);
    private final GLSLShape points = new GLSLShape(true);
    private final BufVertex lineBuf = new BufVertex();
    private final BufVertex pointBuf = new BufVertex();

    /** Where each buoy last landed on the canvas, for the pointer; rebuilt every frame. */
    private record Spot(String key, double x, double y) {}

    private volatile List<Spot> spots = List.of();
    private volatile String hovered;
    private final Hover hover = new Hover();

    private final class Hover implements InputMouseListener {
        @Override
        public void mouseMoved(PointerEvent e) {
            String near = null;
            double best = HOVER_PX * HOVER_PX;
            for (Spot s : spots) {
                double d = (s.x() - e.x()) * (s.x() - e.x()) + (s.y() - e.y()) * (s.y() - e.y());
                if (d < best) {
                    best = d;
                    near = s.key();
                }
            }
            if (!java.util.Objects.equals(near, hovered)) {
                hovered = near;
                DisplayController.display();
            }
        }

        @Override
        public void mouseExited(PointerEvent e) {
            if (hovered != null) {
                hovered = null;
                DisplayController.display();
            }
        }
    }

    /** Name of the buoy under the pointer, or null: a row's panel can use it to light the row. */
    @Nullable
    public String hoveredKey() {
        return hovered;
    }

    public void init() {
        lines.init();
        points.init();
        InputController.addListener(hover);
    }

    public void dispose() {
        InputController.removeListener(hover);
        lines.dispose();
        points.dispose();
        ephemerides.values().forEach(ModelEphemeris::cancel);
        ephemerides.clear();
    }

    private ModelEphemeris ephemeris(Craft c) {
        ModelEphemeris e = ephemerides.get(c.key());
        if (e == null) {
            e = new ModelEphemeris();
            e.setTarget(c.target());
            ephemerides.put(c.key(), e);
        }
        return e;
    }

    private static boolean hasModel(Craft c) {
        for (Layer l : Layers.getLayers())
            if (l instanceof ModelLayer m && m.isEnabled() && c.target().equals(m.getTarget()))
                return true;
        return false;
    }

    /** Call in the scene's world frame (no extra view rotation), after the planets, in 3D only. */
    public void render(MapView mv, Viewport vp, double pixelFactor) {
        List<Group> groups = show ? groups(TimelineLayers.get()) : List.of();
        if (groups.isEmpty()) {
            spots = List.of();
            return;
        }
        double[][] axes = ViewpointMarker.screenAxes(vp.width, vp.height);
        double width = mv.cameraWidth(vp);
        if (axes == null || width <= 0)
            return;

        JHVTime time = mv.viewpoint().time;
        double radius = RING_FRACTION * width;
        lineBuf.clear();
        pointBuf.clear();
        List<Spot> found = new ArrayList<>();
        List<Group> placed = new ArrayList<>();
        List<Vec3> where = new ArrayList<>();
        Vector3f ndc = new Vector3f();
        for (Group g : groups) {
            ModelEphemeris eph = ephemeris(g.craft());
            Position p = eph.at(time);
            if (p == null || !(p.distance > 0))
                continue;
            Vec3 w = ModelPlacement.toWorld(p);
            byte[] col = Colors.bytes(g.color());
            boolean halo = modelHalo && hasModel(g.craft());
            if (trails)
                putTrail(eph, time, col);
            putRing(w, halo ? 1.9 * radius : radius, axes, col);
            if (!halo)
                pointBuf.putVertex((float) w.x, (float) w.y, (float) w.z, (float) (0.4 * radius), col);
            Transform.toNdc((float) w.x, (float) w.y, (float) w.z, ndc);
            if (Float.isFinite(ndc.x) && Float.isFinite(ndc.y))
                found.add(new Spot(g.craft().key(), vp.x + (ndc.x + 1) * .5 * vp.width, vp.yAWT + (1 - ndc.y) * .5 * vp.height));
            placed.add(g);
            where.add(w);
        }
        spots = found;

        GL.glDisable(GL.DEPTH_TEST);
        lines.uploadAndClear(lineBuf);
        lines.renderLine(vp, GLSLLine.LINEWIDTH_BASIC * 1.5);
        points.uploadAndClear(pointBuf);
        points.renderPoints(pixelFactor);
        drawNames(placed, where, axes, radius);
        GL.glEnable(GL.DEPTH_TEST);
    }

    /** A circle facing the screen: the view's own right and up, not an assumed rotation. */
    private void putRing(Vec3 c, double r, double[][] axes, byte[] col) {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS, cs = Math.cos(a) * r, sn = Math.sin(a) * r;
            float x = (float) (c.x + cs * axes[0][0] + sn * axes[1][0]);
            float y = (float) (c.y + cs * axes[0][1] + sn * axes[1][1]);
            float z = (float) (c.z + cs * axes[0][2] + sn * axes[1][2]);
            if (i == 0)
                lineBuf.startLine(x, y, z, 1, col);
            else
                lineBuf.putVertex(x, y, z, 1, col);
        }
        lineBuf.endLine();
    }

    private void putTrail(ModelEphemeris eph, JHVTime now, byte[] col) {
        long a = Player.getStartTime(), b = Player.getEndTime();
        if (b <= a)
            return;
        boolean started = false;
        for (int i = 0; i < TRAIL_SAMPLES; i++) {
            Position p = eph.at(new JHVTime(a + (b - a) * i / (TRAIL_SAMPLES - 1)));
            if (p == null || !(p.distance > 0)) {
                if (started)
                    lineBuf.endLine();
                started = false;
                continue;
            }
            Vec3 w = ModelPlacement.toWorld(p);
            if (!started)
                lineBuf.startLine((float) w.x, (float) w.y, (float) w.z, 1, col);
            else
                lineBuf.putVertex((float) w.x, (float) w.y, (float) w.z, 1, col);
            started = true;
        }
        if (started)
            lineBuf.endLine();
    }

    /** Code beside every ring, the full name and its rows on hover (or always, by the setting). */
    private void drawNames(List<Group> placed, List<Vec3> where, double[][] axes, double radius) {
        if (placed.isEmpty())
            return;
        Vector3f right = new Vector3f((float) axes[0][0], (float) axes[0][1], (float) axes[0][2]);
        Vector3f up = new Vector3f((float) axes[1][0], (float) axes[1][1], (float) axes[1][2]);
        Vector3f origin = new Vector3f();
        SdfTextRenderer renderer = GLText.renderer();
        float scale = (float) (radius * 1.6 * TEXT_SCALE / renderer.getFontSize());
        GL.glDisable(GL.CULL_FACE);
        renderer.begin3DRendering();
        for (int i = 0; i < placed.size(); i++) {
            Group g = placed.get(i);
            Vec3 w = where.get(i);
            boolean on = alwaysLabels || g.craft().key().equals(hovered);
            String text = on ? g.craft().target() + ": " + String.join(", ", g.rows()) : g.craft().code();
            java.awt.Color c = g.color();
            renderer.setColor(new float[]{c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1});
            double d = 1.6 * radius;
            origin.set((float) (w.x + d * (axes[0][0] + axes[1][0])), (float) (w.y + d * (axes[0][1] + axes[1][1])),
                    (float) (w.z + d * (axes[0][2] + axes[1][2])));
            renderer.draw(text, origin, right, up, scale);
        }
        renderer.end3DRendering();
        GL.glEnable(GL.CULL_FACE);
    }
}
