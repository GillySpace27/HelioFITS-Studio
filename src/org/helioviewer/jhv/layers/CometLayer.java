package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.DisplaySettings;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.identify.CometSight;
import org.helioviewer.jhv.identify.CometStyle;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.SkyScreen;
import org.helioviewer.jhv.layers.selector.CometLayerOptions;
import org.helioviewer.jhv.layers.selector.LayerOptions;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.view.View;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A comet in the scene (comet3d plan, revision 2, section 2.6): its heliocentric path from JPL
 * Horizons ({@link CometEphemeris}), and in every visible image layer that shows it a hollow ring
 * where that layer's own spacecraft saw it at that layer's own time ({@link CometSight}). Rings
 * from different spacecraft sit in different places: that is parallax, not an error.
 *
 * <p>The rings are drawn in the float pass, so they are in exported movies and, in a layered
 * export, isolated in this layer's own pass, never in an image layer's data channel. The path is
 * fetched off the GL thread, only while the layer is enabled, and cached on disk.
 */
public final class CometLayer extends AbstractLayer {

    static {
        LayerOptions.register(CometLayer.class, l -> new CometLayerOptions((CometLayer) l));
    }

    static final double RING_RADIUS_PX = 7; // judgement
    static final double RING_WIDTH_PX = 1.5;
    static final int RING_POINTS = 24;
    /** Fetches started, for CometLayerCheck. */
    static final AtomicInteger FETCHES = new AtomicInteger();
    private static final long DAY = 86_400_000L;

    private final Comets.Comet comet;
    private int[] rgb;
    private boolean showRings = true;
    private boolean lightTime = true;

    private volatile CometEphemeris path;
    private volatile CometEphemeris soho;
    private volatile String fetchStatus = "";
    private volatile List<String> observerLines = List.of();
    private long fetchedStart = Long.MIN_VALUE, fetchedEnd = Long.MIN_VALUE;
    private int generation;
    @Nullable
    private Future<?> load;

    private final GLSLLine line = new GLSLLine(true);
    private final BufVertex buf = new BufVertex(RING_POINTS * 8);

    public CometLayer(Comets.Comet _comet, int[] _rgb) {
        comet = _comet;
        rgb = _rgb.clone();
        setEnabled(true);
    }

    /** State.json2Object's constructor. A missing designation throws, which State logs and skips; damaged fields keep defaults. */
    public CometLayer(JSONObject jo) throws JSONException {
        String designation = jo.getString("designation");
        if (designation.isBlank())
            throw new JSONException("comet layer without a designation");
        comet = new Comets.Comet(designation, jo.optString("name", ""), jo.optDouble("perihelionAu", Double.NaN), Math.max(0, jo.optLong("perihelion", 0)));
        int[] c = CometStyle.fromHex(jo.optString("color", null));
        rgb = c == null ? CometStyle.palette(0) : c;
        showRings = jo.optBoolean("showRings", true);
        lightTime = jo.optBoolean("lightTime", true);
        setEnabled(true);
    }

    /** Show {@code comet} in the scene: enable its layer if there is one, else add one in the next free colour. */
    public static void show(Comets.Comet comet) {
        List<int[]> inUse = new ArrayList<>();
        for (Layer l : Layers.getLayers())
            if (l instanceof CometLayer cl) {
                if (cl.designation().equals(comet.designation())) {
                    cl.setEnabled(true);
                    Layers.fireLayerUpdated(cl);
                    DisplayController.display();
                    return;
                }
                inUse.add(cl.rgb());
            }
        Layers.add(new CometLayer(comet, CometStyle.next(inUse)));
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("designation", comet.designation());
        jo.put("name", comet.name() == null ? "" : comet.name());
        if (Double.isFinite(comet.perihelionAu())) // JSON has no NaN
            jo.put("perihelionAu", comet.perihelionAu());
        jo.put("perihelion", comet.perihelion());
        jo.put("color", CometStyle.hex(rgb));
        jo.put("showRings", showRings);
        jo.put("lightTime", lightTime);
    }

    @Override
    public Kind kind() {
        return Kind.SCENE;
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public String getName() {
        return comet.label();
    }

    public String designation() {
        return comet.designation();
    }

    public Comets.Comet comet() {
        return comet;
    }

    public int[] rgb() {
        return rgb.clone();
    }

    public void setRgb(int[] _rgb) {
        rgb = _rgb.clone();
        DisplayController.display();
    }

    public boolean showRings() {
        return showRings;
    }

    public void setShowRings(boolean b) {
        showRings = b;
        DisplayController.display();
    }

    public boolean lightTime() {
        return lightTime;
    }

    public void setLightTime(boolean b) {
        lightTime = b;
        DisplayController.display();
    }

    // Fetching

    /** The span the path is fetched for: the movie's; CometEphemeris adds its own pad. */
    static long[] fetchWindow(long movieStart, long movieEnd) {
        return new long[]{Math.min(movieStart, movieEnd), Math.max(movieStart, movieEnd)};
    }

    @Override
    public void prerender() {
        if (!isEnabled())
            return; // no network for a layer that is off
        long[] w = Player.isAvailable() ? fetchWindow(Player.getStartTime(), Player.getEndTime())
                : fetchWindow(Player.getTime().milli - DAY, Player.getTime().milli + DAY);
        ensureFetch(w[0], w[1], false);
    }

    /** Ask Horizons again (Refresh), ignoring the cache. */
    public void refresh() {
        long s = fetchedStart, e = fetchedEnd;
        if (s == Long.MIN_VALUE)
            return;
        fetchedStart = fetchedEnd = Long.MIN_VALUE;
        ensureFetch(s, e, true);
    }

    void ensureFetch(long start, long end, boolean refresh) {
        if (start == fetchedStart && end == fetchedEnd)
            return;
        cancel();
        fetchedStart = start;
        fetchedEnd = end;
        FETCHES.incrementAndGet();
        int mine = ++generation;
        fetchStatus = "Fetching the path of " + comet.label() + " from JPL Horizons...";
        load = Task.submitBackground("comet-layer", () -> new CometEphemeris[]{
                CometEphemeris.fetch(comet, start, end, refresh),
                CometEphemeris.fetchBody("-21", "SOHO", start, end, refresh)}, got -> {
            if (mine != generation)
                return; // the window moved on
            path = got[0];
            soho = got[1];
            fetchStatus = "";
            DisplayController.display();
        }, (logContext, t) -> {
            if (mine != generation)
                return;
            fetchStatus = "No path for " + comet.label() + ": " + (t.getMessage() == null ? t.toString() : t.getMessage());
            DisplayController.display();
        });
    }

    private void cancel() {
        generation++;
        if (load != null)
            load.cancel(true);
        load = null;
    }

    /** Lines for the options panel: the fetch, the orbit solution, and per layer where its observer came from. */
    public String status() {
        List<String> out = new ArrayList<>();
        if (!fetchStatus.isEmpty())
            out.add(fetchStatus);
        CometEphemeris p = path;
        if (p != null) {
            out.add(p.sampleTimes().length + " positions from JPL Horizons" + (p.sourceTag().isEmpty() ? "" : " " + p.sourceTag()) + '.');
            if (!p.note().isEmpty())
                out.add(p.note() + '.');
        }
        out.addAll(observerLines);
        return String.join("\n", out);
    }

    // Rings

    /** Ring radius in pixels: scaled for the display and for an export's capture scale. */
    static double ringRadiusPx(double pixelScale, double captureScale) {
        return RING_RADIUS_PX * pixelScale * captureScale;
    }

    static double ringWidthPx(double pixelScale, double captureScale) {
        return RING_WIDTH_PX * pixelScale * captureScale;
    }

    private record Row(Identifier.Target target, String observatory) {}

    /** The image layers visible in {@code vp}, each as the identifier's target, with its observatory. */
    private static List<Row> targets(Viewport vp) {
        List<Row> out = new ArrayList<>();
        for (ImageLayer layer : Layers.getImageLayers()) {
            View.ImageData data = layer.getImageData();
            if (data == null || !layer.isVisible(vp.idx))
                continue;
            MetaData m = data.metaData();
            Identifier.Target t = new Identifier.Target(layer.getName(), m.getViewpoint(), m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius());
            out.add(new Row(t, m instanceof FitsMetaData f ? f.getObservatory() : ""));
        }
        return out;
    }

    @Override
    public void renderFloat(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        CometEphemeris p = path, s = soho;
        if (p == null)
            return;
        CometEphemeris.WorldPath sohoPath = s == null ? null : s::world;
        DisplaySettings.TimeMode mode = DisplaySettings.getTimeMode();
        double radius = ringRadiusPx(Display.pixelScale[1], Display.captureScale);
        byte[] col = {(byte) rgb[0], (byte) rgb[1], (byte) rgb[2], (byte) 255};
        List<String> lines = new ArrayList<>();
        buf.clear();
        int rings = 0;
        for (Row row : targets(vp)) {
            Identifier.Target t = row.target();
            CometSight.Look look = CometSight.look(p::world, t, row.observatory(), sohoPath, lightTime, mode);
            lines.add(t.name() + ": " + look.source().text + (look.drawn() ? "" : look.sight() == null ? "; no position" : "; no ring (" + look.qualifier().text + ')'));
            if (!showRings || !look.drawn())
                continue;
            double[] c = SkyScreen.place(mv, vp, t.observer(), look.sight().dir());
            if (c == null)
                continue;
            putRing(buf, c[0] - vp.x, vp.height - (c[1] - vp.yAWT), radius, col);
            rings++;
        }
        if (vp.idx == 0)
            observerLines = List.copyOf(lines);
        if (rings == 0 || vp.height <= 0)
            return;

        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        GL.glDisable(GL.DEPTH_TEST);
        try {
            line.upload(buf);
            line.renderLine(vp, ringWidthPx(Display.pixelScale[1], Display.captureScale) / vp.height);
        } finally {
            GL.glEnable(GL.DEPTH_TEST);
            Transform.popView();
            Transform.popProjection();
        }
    }

    /** A closed ring of {@link #RING_POINTS} around (x, y), viewport GL pixels. */
    static void putRing(BufVertex b, double x, double y, double radius, byte[] col) {
        for (int i = 0; i <= RING_POINTS; i++) {
            double a = 2 * Math.PI * i / RING_POINTS;
            float px = (float) (x + radius * Math.cos(a)), py = (float) (y + radius * Math.sin(a));
            if (i == 0)
                b.startLine(px, py, 0, 1, col);
            else
                b.putVertex(px, py, 0, 1, col);
        }
        b.endLine();
    }

    @Override
    public void init() {
        line.init();
    }

    @Override
    public void dispose() {
        line.dispose();
    }

    @Override
    public void remove() {
        cancel();
        dispose();
    }

}
