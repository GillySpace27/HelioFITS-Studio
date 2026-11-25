package org.helioviewer.jhv.layers;

import java.awt.EventQueue;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.ViewpointListener;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.identify.IdentifyHover;
import org.helioviewer.jhv.identify.SkyScreen;
import org.helioviewer.jhv.layers.stars.StarCatalog;
import org.helioviewer.jhv.layers.stars.StarSky;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLSLWarp;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.thread.Task;

import org.json.JSONObject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * The starfield background: Hipparcos stars and, optionally, the planets, drawn behind the imagery
 * in the observer's view. Off by default; its row is under Overlays.
 *
 * <p>The catalogue is read from the disk cache, or fetched from ESA the first time the layer is
 * switched on (StarCatalog). A failed fetch says so in the options panel and stays quiet otherwise:
 * this is a background, and a modal error over the picture would be worse than no stars.
 *
 * <p>Every star is placed where the imagery shows its own direction, in the frame of one observer:
 * the active image layer's, the viewpoint when there is none (IdentifyHover.frameObserver, the
 * frame Identify names stars in). Orthographic puts it on the layer's plane of sky through the Sun
 * (StarSky.plane); Helioradial in 3D on the selected surface (SkyScreen.Layout#surface); HPC,
 * Observer Sky and flat Helioradial through the page mapping (SkyScreen.Layout#page). All three
 * are {@link SkyScreen#place} written out for a whole sky. Latitudinal and the unrolled helioradial
 * map are maps of the Sun, not of the sky, and get none. Orbiting the camera moves the stars with
 * the imagery they are on, like everything else Identify rings.
 */
public final class StarLayer extends AbstractLayer implements ViewpointListener {

    public static final double MIN_MAG = 2;
    public static final double DEFAULT_MAG = 6;
    public static final double MAX_SATURATION = 2;
    public static final double MIN_SIZE = 0.25;
    public static final double MAX_SIZE = 4;

    public interface StatusListener {
        void statusChanged(String status);
    }

    private enum CatalogState {NONE, LOADING, READY, FAILED}

    private double magLimit = DEFAULT_MAG;
    private boolean colour = true;
    private double saturation = 1;
    private double sizeScale = 1;
    private boolean planets = true;
    private boolean moon = false;
    // Over the imagery instead of under it: to see which specks in a coronagraph frame are stars
    private boolean onTop = false;

    private CatalogState catalogState = CatalogState.NONE;
    @Nullable
    private StarCatalog catalog;
    private String status = "";
    @Nullable
    private StatusListener statusListener;

    private record Key(Position observer, StarSky.Style style) {}

    // One observer's sky: its directions, and their Orthographic placement, built together off the GUI thread
    private record Sky(StarSky.Points points, BufVertex plane) {}

    private final Cache<Key, Sky> cache = Caffeine.newBuilder().softValues().maximumSize(64).build();
    private final Set<Key> pending = new HashSet<>();
    @Nullable
    private Sky lastSky;
    private final GLSLShape points = new GLSLShape(true);
    @Nullable
    private BufVertex uploadedBuf;
    // The flat views and Helioradial in 3D re-place every point each frame: the page mapping moves
    // with zoom, pan, the Observer Sky aim and the warp, and a few thousand placements cost less
    // than tracking all of those
    private final GLSLShape flatPoints = new GLSLShape(true);
    private final BufVertex flatBuf = new BufVertex();

    public StarLayer(JSONObject jo) {
        if (jo == null)
            return;
        magLimit = Math.clamp(jo.optDouble("magLimit", magLimit), MIN_MAG, StarCatalog.MAX_MAGNITUDE);
        colour = jo.optBoolean("colour", colour);
        saturation = Math.clamp(jo.optDouble("saturation", saturation), 0, MAX_SATURATION);
        sizeScale = Math.clamp(jo.optDouble("sizeScale", sizeScale), MIN_SIZE, MAX_SIZE);
        planets = jo.optBoolean("planets", planets);
        moon = jo.optBoolean("moon", moon);
        onTop = jo.optBoolean("onTop", onTop);
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("magLimit", magLimit);
        jo.put("colour", colour);
        jo.put("saturation", saturation);
        jo.put("sizeScale", sizeScale);
        jo.put("planets", planets);
        jo.put("moon", moon);
        jo.put("onTop", onTop);
    }

    private StarSky.Style style() {
        return new StarSky.Style(magLimit, colour, saturation, sizeScale, planets, moon);
    }

    // Catalogue

    private void ensureCatalog() {
        if (catalogState == CatalogState.LOADING || catalogState == CatalogState.READY)
            return;
        catalogState = CatalogState.LOADING;
        setStatus("Loading the star catalogue…");
        Task.submitBackground(() -> {
            StarCatalog cached = StarCatalog.loadCached();
            if (cached != null)
                return cached;
            EventQueue.invokeLater(() -> setStatus("Downloading the Hipparcos catalogue from ESA (first use only)…"));
            return StarCatalog.download();
        }, this::catalogLoaded, this::catalogFailed);
    }

    private void catalogLoaded(StarCatalog c) {
        catalog = c;
        catalogState = CatalogState.READY;
        Log.info("Star catalogue ready: " + c.size() + " stars");
        setStatus(String.format("%,d stars to V = %.0f", c.size(), StarCatalog.MAX_MAGNITUDE));
        cache.invalidateAll();
        lastSky = null;
        DisplayController.display();
    }

    private void catalogFailed(Throwable t) {
        catalogState = CatalogState.FAILED;
        Log.warn("Star catalogue unavailable", t);
        setStatus("Could not get the star catalogue (" + t.getMessage() + "). Switch the layer off and on to retry.");
    }

    public String getStatus() {
        return status;
    }

    public void setStatusListener(@Nullable StatusListener l) {
        statusListener = l;
    }

    private void setStatus(String s) {
        status = s;
        if (statusListener != null)
            statusListener.statusChanged(s);
    }

    // Per-observer vertices

    @Override
    public void viewpointChanged(Position viewpoint) {
        request(IdentifyHover.frameObserver(Display.getActiveViewport(), viewpoint));
    }

    private void request(Position observer) {
        StarCatalog c = catalog;
        if (c == null)
            return;
        Key key = new Key(observer, style());
        if (cache.getIfPresent(key) != null || !pending.add(key))
            return;
        Task.submitBackground(() -> {
            StarSky.Points p = StarSky.compute(observer, c, key.style());
            return new Sky(p, StarSky.plane(p, observer.distance));
        }, sky -> {
            pending.remove(key);
            cache.put(key, sky);
            DisplayController.display();
        }, t -> {
            pending.remove(key);
            Log.warn("Star positions failed", t);
        });
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        Position observer = IdentifyHover.frameObserver(vp, mv.viewpoint());
        Sky sky = sky(vp, observer);
        if (sky == null)
            return;
        if (mv.isHelioradial()) {
            // On the selected surface, where the mesh shows the star's own direction. The vertices are
            // in the surface observer's frame, before the warp that shape.vert applies as for any overlay.
            SkyScreen.Layout layout = SkyScreen.layout(mv, vp, observer);
            StarSky.Points p = sky.points();
            float[] ray = p.ray();
            byte[] colour = new byte[4];
            for (int i = 0; i < p.count(); i++) {
                if (!StarSky.inCut(ray[3 * i + 2]))
                    continue;
                Vec3 at = layout.surface(new Vec3(ray[3 * i], ray[3 * i + 1], ray[3 * i + 2]));
                if (at == null)
                    continue;
                System.arraycopy(p.colour(), 4 * i, colour, 0, 4);
                flatBuf.putVertex((float) at.x, (float) at.y, (float) at.z, p.size()[i], colour);
            }
            flatPoints.uploadAndClear(flatBuf);
            Transform.pushView();
            Transform.rotateViewInverse(layout.rotation());
            flatPoints.renderPoints(1);
            Transform.popView();
            return;
        }
        // Orthographic: the layer's plane of sky through the Sun, which turns with the imagery
        BufVertex buf = sky.plane();
        if (buf != uploadedBuf) {
            points.upload(buf);
            uploadedBuf = buf;
        }
        Transform.pushView();
        Transform.rotateViewInverse(observer.toQuat()); // the layer's own pose: the plane the imagery is sampled on
        points.renderPoints(1); // vertex sizes are in pixels
        Transform.popView();
    }

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        if (!mv.isHpc() && !mv.isObserverSky() && !mv.isHelioradial())
            return;
        Position observer = IdentifyHover.frameObserver(vp, mv.viewpoint());
        Sky sky = sky(vp, observer);
        if (sky == null)
            return;
        SkyScreen.Layout layout = SkyScreen.layout(mv, vp, observer);
        StarSky.Points p = sky.points();
        float[] ray = p.ray();
        byte[] colour = new byte[4];
        for (int i = 0; i < p.count(); i++) {
            double[] pt = layout.page(new Vec3(ray[3 * i], ray[3 * i + 1], ray[3 * i + 2]));
            if (pt == null)
                continue;
            System.arraycopy(p.colour(), 4 * i, colour, 0, 4);
            flatBuf.putVertex((float) pt[0], (float) pt[1], 0, p.size()[i], colour);
        }
        flatPoints.uploadAndClear(flatBuf);
        // renderScene leaves the radial warp on for every Helioradial draw; these are page coordinates already
        if (mv.isHelioradial())
            GLSLWarp.disable();
        flatPoints.renderPoints(1);
        if (mv.isHelioradial())
            GLSLWarp.enable(mv.scale(vp));
    }

    @Nullable
    private Sky sky(Viewport vp, Position observer) {
        if (!isVisible[vp.idx] || catalog == null)
            return null;
        Sky sky = cache.getIfPresent(new Key(observer, style()));
        if (sky != null) {
            lastSky = sky;
            return sky;
        }
        request(observer); // first frame after a style change, or an interpolated viewpoint
        // Keep drawing the previous sky until the new one is built; returning nothing blanked every star
        // for the frame in between, which showed as flicker on any redraw at a viewpoint not yet cached.
        return lastSky;
    }

    // Options

    public double getMagLimit() {
        return magLimit;
    }

    public void setMagLimit(double m) {
        magLimit = Math.clamp(m, MIN_MAG, StarCatalog.MAX_MAGNITUDE);
        DisplayController.display();
    }

    public boolean isColour() {
        return colour;
    }

    public void setColour(boolean c) {
        colour = c;
        DisplayController.display();
    }

    public double getSaturation() {
        return saturation;
    }

    public void setSaturation(double s) {
        saturation = Math.clamp(s, 0, MAX_SATURATION);
        DisplayController.display();
    }

    public double getSizeScale() {
        return sizeScale;
    }

    public void setSizeScale(double s) {
        sizeScale = Math.clamp(s, MIN_SIZE, MAX_SIZE);
        DisplayController.display();
    }

    public boolean isPlanets() {
        return planets;
    }

    public void setPlanets(boolean p) {
        planets = p;
        DisplayController.display();
    }

    public boolean isMoon() {
        return moon;
    }

    public void setMoon(boolean m) {
        moon = m;
        DisplayController.display();
    }

    public boolean isOnTop() {
        return onTop;
    }

    public void setOnTop(boolean t) {
        onTop = t;
        DisplayController.display();
    }

    // Lifecycle

    @Override
    public void setEnabled(boolean _enabled) {
        super.setEnabled(_enabled);
        if (enabled) {
            DisplayController.addViewpointListener(this);
            if (catalogState == CatalogState.FAILED)
                catalogState = CatalogState.NONE;
            ensureCatalog();
        } else {
            DisplayController.removeViewpointListener(this);
        }
    }

    @Override
    public void init() {
        points.init();
        flatPoints.init();
    }

    @Override
    public void dispose() {
        uploadedBuf = null;
        points.dispose();
        flatPoints.dispose();
    }

    @Override
    public void remove() {
        setEnabled(false);
        dispose();
    }

    @Override
    public String getName() {
        return "Stars";
    }

}
