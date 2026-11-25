package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.layers.CometLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.math.Vec3;

/**
 * The comets a {@link CometLayer} draws, for Identify (comet3d plan, revision 2, section 4): each
 * ring is named under the pointer, and the dot in the Orthographic view; hovering or selecting any
 * of them lights every ring of that comet, and a selection holds whichever image's ring is nearest
 * the followed point.
 *
 * <p>Keys: {@code comet:<designation>} for the dot, {@code comet:<designation>/<layer>} for the ring
 * in an image layer. The family (the comet) is recovered from the mark's own layer, never by
 * splitting on '/', which designations contain (C/2025 R3).
 *
 * <p>Marks exist only where the layer draws a ring (the same {@link CometLayer#ringDirection}), and
 * only for targets with an image (a WCS): the bare viewpoint draws no rings. Highlight All asks
 * nothing of this source, so no ambient copy lands on a ring; {@link CometSky} leaves a comet with a
 * layer to this source, so one comet never has two placements.
 */
public final class CometMarks implements Drawn.Source {

    static final String PREFIX = "comet:";
    private static final CometMarks SOURCE = new CometMarks();

    public static CometMarks source() {
        return SOURCE;
    }

    /** The comet a mark belongs to: its key without the image layer, for a comet ring; otherwise the key itself. */
    public static String family(Mark m) {
        String key = m.key(), layer = m.layer();
        if (m.kind() != Kind.COMET || layer == null || !key.startsWith(PREFIX))
            return key;
        String tail = '/' + layer;
        return key.endsWith(tail) && key.length() > PREFIX.length() + tail.length() ? key.substring(0, key.length() - tail.length()) : key;
    }

    /** The comet layers in the scene that are on. */
    static List<CometLayer> layers() {
        List<CometLayer> out = new ArrayList<>();
        for (Layer l : Layers.getLayers())
            if (l instanceof CometLayer c && c.isEnabled())
                out.add(c);
        return out;
    }

    /** Whether a comet layer that is on draws {@code designation}, so other comet sources leave it alone. */
    public static boolean ownsDesignation(String designation) {
        return ownsDesignation(designation, layers());
    }

    static boolean ownsDesignation(String designation, List<CometLayer> layers) {
        for (CometLayer c : layers)
            if (c.designation().equals(designation))
                return true;
        return false;
    }

    /** The comet layer of a family ({@code comet:<designation>}), or null. */
    @Nullable
    static CometLayer layerOf(String family, List<CometLayer> layers) {
        for (CometLayer c : layers)
            if ((PREFIX + c.designation()).equals(family))
                return c;
        return null;
    }

    /** What decides the comet marks, for {@link MarkFrame}'s key. */
    static String signature() {
        StringBuilder sb = new StringBuilder();
        for (CometLayer c : layers())
            sb.append(c.identifySignature()).append(';');
        return sb.toString();
    }

    static Mark ringMark(CometLayer layer, Identifier.Target t) {
        return new Mark(PREFIX + layer.designation() + '/' + t.name(), Kind.COMET, t.name(), observer -> layer.ringDirection(
                new Identifier.Target(t.name(), observer, t.wcs(), t.region(), t.innerRadius(), t.outerRadius())), 0);
    }

    /** The dot's mark: no sky direction (the glow skips it), since the layer draws the dot's own halo. */
    static Mark dotMark(CometLayer layer) {
        return new Mark(PREFIX + layer.designation(), Kind.COMET, null, observer -> null, 0);
    }

    /**
     * Every ring of one comet, each in its own image: what the glow lights when any ring or the dot
     * of that comet is hovered or selected. Asked directly, so hovering never builds the frame's
     * whole mark list.
     */
    static List<Mark> familyMarks(String family, Viewport vp, MapView mv, List<Identifier.Target> targets) {
        return familyMarks(family, targets, layers());
    }

    static List<Mark> familyMarks(String family, List<Identifier.Target> targets, List<CometLayer> layers) {
        CometLayer layer = layerOf(family, layers);
        List<Mark> out = new ArrayList<>();
        if (layer == null)
            return out;
        for (Identifier.Target t : targets)
            if (t.wcs() != null && layer.ringDirection(t) != null)
                out.add(ringMark(layer, t));
        return out;
    }

    /**
     * The first of {@code centres} (in target order, null for no ring) whose ring the point (x, y)
     * is within {@code reach} pixels of, or -1. The first, not the nearest: with C2 and C3 rings a
     * fraction of a pixel apart the nearest flips between them as the comet moves, and the chosen
     * image (the active layer first) would flip with it.
     */
    static int firstWithin(List<double[]> centres, double x, double y, double reach) {
        for (int i = 0; i < centres.size(); i++) {
            double[] c = centres.get(i);
            if (c != null && Math.hypot(c[0] - x, c[1] - y) <= reach)
                return i;
        }
        return -1;
    }

    @Override
    public List<Drawn.Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
        List<CometLayer> layers = layers();
        return layers.isEmpty() ? List.of() : at(vp, mv, x, y, tolerancePixels, IdentifyHover.targets(vp, mv), layers);
    }

    static List<Drawn.Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels, List<Identifier.Target> targets, List<CometLayer> layers) {
        List<Drawn.Hit> out = new ArrayList<>();
        double reach = Math.max(tolerancePixels, CometLayer.ringRadiusOnScreen());
        for (CometLayer layer : layers) {
            if (!layer.isVisible(vp.idx))
                continue;
            Vec3 now = layer.positionAt(mv.viewpoint().time);
            double au = now == null ? Double.NaN : now.length() * Sun.MeanEarthDistanceInv;
            List<double[]> centres = new ArrayList<>();
            for (Identifier.Target t : targets) {
                Vec3 dir = t.wcs() == null ? null : layer.ringDirection(t);
                centres.add(dir == null ? null : SkyScreen.place(mv, vp, t.observer(), dir));
            }
            int i = firstWithin(centres, x, y, reach);
            if (i >= 0) {
                Identifier.Target t = targets.get(i);
                double px = Math.hypot(centres.get(i)[0] - x, centres.get(i)[1] - y);
                out.add(new Drawn.Hit(layer.getName(), "comet, as " + t.name() + " saw it", px, au, Kind.COMET, ringMark(layer, t)));
                continue;
            }
            Double px = now == null || !layer.showDot() || !mv.isOrthographic() ? null : dotPixels(vp, mv, x, y, now, tolerancePixels);
            if (px != null)
                out.add(new Drawn.Hit(layer.getName(), "comet, true position", px, au, Kind.COMET, dotMark(layer)));
        }
        return out;
    }

    /** Pixels from the pointer to the dot at world point {@code at} in the orthographic view, or null; as Drawn places model layers. */
    @Nullable
    private static Double dotPixels(Viewport vp, MapView mv, int x, int y, Vec3 at, double tolerancePixels) {
        double width = mv.cameraWidth(vp);
        double upX = ViewportMath.computeUpX(vp, width, mv.cameraTranslationX(), x);
        double upY = ViewportMath.computeUpY(vp, width, mv.cameraTranslationY(), y);
        double perPixel = Math.abs(ViewportMath.computeUpX(vp, width, mv.cameraTranslationX(), x + 1) - upX);
        if (!(perPixel > 0))
            return null;
        return Drawn.pixelsFrom(mv.viewRotation(), at, upX, upY, perPixel, CometLayer.dotRadius(width), tolerancePixels);
    }

    private CometMarks() {}
}
