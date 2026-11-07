package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.input.InputMouseListener;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

import spice.basic.SpiceErrorException;

/**
 * Where an image layer's observer is: a small map in the top-right corner of the main view, seen
 * from above the Sun's north pole with Earth straight below the Sun, showing the inner planets, every
 * enabled image layer's observer as a dot, and the chosen layer's observer with its field of view as
 * a wedge toward the Sun. Turned on per layer from the layer's "Where" button.
 *
 * <p>Clicking the map swaps the main camera to the Viewpoint layer's Overview, framed to the map's
 * extent, so the model layers placed at the spacecraft are seen in place; a second click puts the
 * camera back exactly as it was.
 *
 * <p>Drawn in the screen-space pass (GLRenderer.renderFullFloatScene), never into a capture.
 */
public final class LocatorInset {

    private static final LocatorInset INSTANCE = new LocatorInset();

    private static final double MIN_EXTENT_AU = 1.2;
    private static final String[] PLANETS = {"MERCURY", "VENUS", "MARS BARYCENTER"};
    private static final byte[] PANEL = Colors.bytes(14, 16, 22, 225);
    private static final byte[] RING = Colors.bytes(90, 100, 120, 160);
    private static final byte[] SUN = Colors.bytes(255, 200, 60);
    private static final byte[] EARTH = Colors.bytes(70, 140, 255);
    private static final byte[] PLANET = Colors.bytes(150, 150, 150);
    private static final byte[] OTHER = Colors.bytes(170, 170, 190, 200);
    private static final byte[] OBSERVER = Colors.bytes(255, 120, 60);
    private static final byte[] WEDGE = Colors.bytes(255, 120, 60, 70);
    private static final byte[] SUNLINE = Colors.bytes(255, 120, 60, 150);

    @Nullable
    private ImageLayer target;
    @Nullable
    private ViewpointLayerOptions.CameraBehaviour savedBehaviour;
    @Nullable
    private JSONObject savedCamera;
    private double extentAu = MIN_EXTENT_AU;
    /** The panel in GL pixels, origin bottom left: x, y, size. */
    private final double[] panel = new double[3];
    private boolean drawn;

    private final GLSLShape shapes = new GLSLShape(true);
    private final BufVertex vex = new BufVertex(8 * 1024);
    private boolean glReady;

    private final InputMouseListener clicks = new InputMouseListener() {
        @Override
        public void mouseClicked(PointerEvent e) {
            if (target != null && drawn && inside(e.x(), e.y()))
                toggleOverview();
        }
    };

    private LocatorInset() {}

    // ---- what the sidebar uses ----

    public static boolean isShowing(ImageLayer layer) {
        return INSTANCE.target == layer;
    }

    /** Show the map for {@code layer}, or hide it when it is already showing that layer. */
    public static void toggle(ImageLayer layer) {
        INSTANCE.target = INSTANCE.target == layer ? null : layer;
        DisplayController.display();
    }

    public static void forget(ImageLayer layer) {
        if (INSTANCE.target == layer) {
            INSTANCE.target = null;
            DisplayController.display();
        }
    }

    /** One line on where this layer's observer is, for the layer list's tooltip; null when unknown. */
    @Nullable
    public static String describe(ImageLayer layer) {
        Position p = observer(layer);
        if (p == null)
            return null;
        return describe(p, Sun.getEarth(p.time));
    }

    // ---- the geometry, pure so a check can pin it ----

    /**
     * Where {@code p} sits on the map, in au: x to the right, y up, seen from solar north, Sun at
     * the origin and Earth straight below it. Positions carry Studio's Carrington convention
     * (longitude is minus the SPICE angle, ModelPlacement.carringtonPosition), so the Carrington
     * angle ahead of Earth is the negated difference; a spacecraft leading Earth in its orbit (STEREO
     * Ahead) lands on the right, because the planets run counterclockwise seen from the north.
     */
    static double[] project(Position p, Position earth) {
        double ahead = -(p.lon - earth.lon);
        double r = p.distance * Math.cos(p.lat) / Sun.MeanEarthDistance;
        return new double[]{r * Math.sin(ahead), -r * Math.cos(ahead)};
    }

    /** Degrees ahead of Earth in its orbit (negative: behind), in (-180, 180]. */
    static double degreesAhead(Position p, Position earth) {
        double d = Math.toDegrees(-(p.lon - earth.lon)) % 360;
        if (d > 180)
            d -= 360;
        else if (d <= -180)
            d += 360;
        return d;
    }

    static String describe(Position p, Position earth) {
        double au = p.distance / Sun.MeanEarthDistance;
        double ahead = degreesAhead(p, earth);
        String where = Math.abs(ahead) < 0.05 ? "on the Sun-Earth line"
                : String.format("%.1f° %s Earth", Math.abs(ahead), ahead > 0 ? "ahead of" : "behind");
        return String.format("observer %.3f au from the Sun, %s, latitude %.1f°", au, where, Math.toDegrees(p.lat));
    }

    /** The map's half width in au: the inner planets, and every observer shown, with some room. */
    static double extent(List<double[]> points) {
        double r = MIN_EXTENT_AU;
        for (double[] q : points)
            r = Math.max(r, 1.15 * Math.hypot(q[0], q[1]));
        return r;
    }

    @Nullable
    private static Position observer(ImageLayer layer) {
        MetaData m = layer.getMetaData();
        if (m == null)
            return null;
        Position p = m.getViewpoint();
        return p.distance > 0 ? p : null;
    }

    // ---- drawing ----

    /** From GLRenderer's screen-space pass. */
    public static void render(Viewport vp) {
        INSTANCE.draw(vp);
    }

    public static void init() {
        INSTANCE.shapes.init();
        INSTANCE.glReady = true;
        InputController.addListener(INSTANCE.clicks);
    }

    public static void dispose() {
        INSTANCE.shapes.dispose();
        INSTANCE.glReady = false;
        InputController.removeListener(INSTANCE.clicks);
    }

    private void draw(Viewport vp) {
        drawn = false;
        ImageLayer layer = target;
        if (layer == null || !glReady || org.helioviewer.jhv.movie.ExportMovie.isRecording())
            return;
        if (!Layers.getImageLayers().contains(layer)) { // removed without telling us
            target = null;
            return;
        }
        Position obs = observer(layer);
        JHVTime time = obs == null ? Player.getTime() : obs.time;
        Position earth = Sun.getEarth(time);

        List<double[]> planets = new ArrayList<>();
        for (String name : PLANETS) {
            try {
                double[] v = Spice.getPosition("SUN", name, "SOLO_IAU_SUN_2009", time);
                planets.add(project(ModelPlacement.carringtonPosition(time, v[0], v[1], v[2]), earth));
            } catch (SpiceErrorException e) { // outside the bundled kernel: leave that planet out
            }
        }
        List<double[]> others = new ArrayList<>();
        for (ImageLayer il : Layers.getImageLayers()) {
            if (il == layer || !il.isEnabled())
                continue;
            Position p = observer(il);
            if (p != null) // against Earth at that layer's own time: its frame can be from another day
                others.add(project(p, Sun.getEarth(p.time)));
        }
        double[] me = obs == null ? null : project(obs, earth);
        List<double[]> all = new ArrayList<>(others);
        all.add(project(earth, earth));
        if (me != null)
            all.add(me);
        extentAu = extent(all);

        double ps = Display.pixelScale[1];
        double size = Math.clamp(0.3 * Math.min(vp.width, vp.height), 210 * ps, 360 * ps);
        double margin = 10 * ps;
        double px = vp.width - size - margin, py = vp.height - size - margin;
        panel[0] = px;
        panel[1] = py;
        panel[2] = size;
        double title = 18 * ps, foot = 30 * ps;
        double mapSize = size - title - foot;
        double cx = px + size / 2, cy = py + foot + mapSize / 2;
        double k = mapSize / 2 / extentAu; // pixels per au

        GL.glDisable(GL.DEPTH_TEST);
        vex.clear();
        quad(px, py, px + size, py + size, PANEL);
        ring(cx, cy, k, Math.max(1, ps), RING); // Earth's orbit, 1 au
        disc(cx, cy, 5 * ps, SUN);
        for (double[] q : planets)
            if (Math.hypot(q[0], q[1]) < extentAu)
                disc(cx + k * q[0], cy + k * q[1], 2.5 * ps, PLANET);
        double[] ep = project(earth, earth);
        disc(cx + k * ep[0], cy + k * ep[1], 3.5 * ps, EARTH);
        for (double[] q : others)
            disc(cx + k * q[0], cy + k * q[1], 2.5 * ps, OTHER);
        if (me != null) {
            wedge(layer, obs, cx + k * me[0], cy + k * me[1], cx, cy);
            disc(cx + k * me[0], cy + k * me[1], 4 * ps, OBSERVER);
        }
        drawShapes(vp);

        List<String[]> text = new ArrayList<>();
        text.add(new String[]{"Where: " + layer.getName(), "top"});
        if (obs == null)
            text.add(new String[]{"No observer position in this layer", "foot1"});
        else {
            double ahead = degreesAhead(obs, earth);
            text.add(new String[]{String.format("%.3f au, %s", obs.distance / Sun.MeanEarthDistance,
                    Math.abs(ahead) < 0.05 ? "Sun-Earth line" : String.format("%.1f° %s Earth", Math.abs(ahead), ahead > 0 ? "ahead of" : "behind")), "foot1"});
        }
        text.add(new String[]{savedBehaviour != null ? "Click to go back" : "Seen from solar north. Click for 3D", "foot2"});
        drawText(vp, text, px, py, size, ps);
        GL.glEnable(GL.DEPTH_TEST);
        drawn = true;
    }

    private void wedge(ImageLayer layer, Position obs, double ox, double oy, double sx, double sy) {
        double dx = sx - ox, dy = sy - oy, len = Math.hypot(dx, dy);
        if (len < 1)
            return;
        Region r = layer.getMetaData().getPhysicalRegion();
        double half = Math.atan2(0.5 * Math.max(r.width, r.height), obs.distance);
        double reach = len * 1.15 / Math.cos(half);
        double a = Math.atan2(dy, dx);
        triangle(ox, oy, ox + reach * Math.cos(a - half), oy + reach * Math.sin(a - half), ox + reach * Math.cos(a + half), oy + reach * Math.sin(a + half), WEDGE);
        line(ox, oy, sx, sy, Math.max(1, Display.pixelScale[1]), SUNLINE);
    }

    private void drawText(Viewport vp, List<String[]> lines, double px, double py, double size, double ps) {
        SdfTextRenderer renderer = GLText.renderer();
        float scale = (float) (12 * ps / renderer.getFontSize());
        renderer.beginRendering(vp.width, vp.height);
        for (String[] l : lines) {
            String s = fit(renderer, l[0], scale, size - 12 * ps);
            double y = switch (l[1]) {
                case "top" -> py + size - 15 * ps;
                case "foot1" -> py + 16 * ps;
                default -> py + 4 * ps;
            };
            renderer.setColor(GLText.SHADOW_COLOR);
            renderer.draw(s, (int) (px + 6 * ps) + GLText.SHADOW_OFFSET_X, (int) y + GLText.SHADOW_OFFSET_Y, 0, scale);
            renderer.setColor(l[1].equals("foot2") ? Colors.MiddleGrayFloat : Colors.LightGrayFloat);
            renderer.draw(s, (int) (px + 6 * ps), (int) y, 0, scale);
        }
        renderer.endRendering();
    }

    private static String fit(SdfTextRenderer renderer, String s, float scale, double avail) {
        if (renderer.measureWidth(s) * scale <= avail)
            return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String c = s.substring(0, n) + '.';
            if (renderer.measureWidth(c) * scale <= avail)
                return c;
        }
        return "";
    }

    private void drawShapes(Viewport vp) {
        if (vex.getCount() == 0)
            return;
        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        shapes.upload(vex);
        shapes.renderShape(GL.TRIANGLES);
        Transform.popView();
        Transform.popProjection();
    }

    private void quad(double ax, double ay, double bx, double by, byte[] c) {
        triangle(ax, ay, bx, ay, bx, by, c);
        triangle(ax, ay, bx, by, ax, by, c);
    }

    /** Counterclockwise always: the shape pass culls back faces. */
    private void triangle(double ax, double ay, double bx, double by, double cx, double cy, byte[] c) {
        if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) < 0) {
            double tx = bx, ty = by;
            bx = cx;
            by = cy;
            cx = tx;
            cy = ty;
        }
        vex.putVertex((float) ax, (float) ay, 0, 1, c);
        vex.putVertex((float) bx, (float) by, 0, 1, c);
        vex.putVertex((float) cx, (float) cy, 0, 1, c);
    }

    private void line(double ax, double ay, double bx, double by, double w, byte[] c) {
        double dx = bx - ax, dy = by - ay, l = Math.hypot(dx, dy);
        if (l == 0)
            return;
        double nx = -dy / l * w / 2, ny = dx / l * w / 2;
        quadPoints(ax + nx, ay + ny, ax - nx, ay - ny, bx - nx, by - ny, bx + nx, by + ny, c);
    }

    private void quadPoints(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy, byte[] c) {
        triangle(ax, ay, bx, by, cx, cy, c);
        triangle(ax, ay, cx, cy, dx, dy, c);
    }

    private void disc(double x, double y, double r, byte[] c) {
        int n = 20;
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            triangle(x, y, x + r * Math.cos(a0), y + r * Math.sin(a0), x + r * Math.cos(a1), y + r * Math.sin(a1), c);
        }
    }

    private void ring(double x, double y, double r, double w, byte[] c) {
        int n = 96;
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            quadPoints(x + (r - w / 2) * c0, y + (r - w / 2) * s0, x + (r + w / 2) * c0, y + (r + w / 2) * s0,
                    x + (r + w / 2) * c1, y + (r + w / 2) * s1, x + (r - w / 2) * c1, y + (r - w / 2) * s1, c);
        }
    }

    private boolean inside(int awtX, int awtY) {
        Viewport vp = Display.fullViewport;
        double lx = awtX - vp.x, ly = vp.height - (awtY - vp.yAWT);
        return lx >= panel[0] && lx <= panel[0] + panel[2] && ly >= panel[1] && ly <= panel[1] + panel[2];
    }

    // ---- the click: the Viewpoint layer's Overview, framed to the map, and back ----

    private void toggleOverview() {
        ViewpointLayer vl = Layers.getViewpointLayer();
        if (vl == null || !vl.isEnabled())
            return;
        ViewpointLayerOptions o = vl.getOptions();
        if (savedBehaviour == null) {
            savedBehaviour = o.getBehaviour();
            savedCamera = DisplayController.cameraToJson();
            o.setBehaviour(ViewpointLayerOptions.CameraBehaviour.OVERVIEW, DisplayController.ViewpointApplyMode.RESET);
            DisplayController.frameAround(extentAu * Sun.MeanEarthDistance);
            DisplayController.turnToBelow(ModelPlacement.toWorld(Sun.getEarth(Player.getTime()))); // as on the map
        } else {
            o.setBehaviour(savedBehaviour, DisplayController.ViewpointApplyMode.RESET);
            if (savedCamera != null)
                DisplayController.cameraFromJson(savedCamera);
            savedBehaviour = null;
            savedCamera = null;
        }
        ViewpointLayerOptions.refreshPanel();
        DisplayController.render(1);
    }

}
