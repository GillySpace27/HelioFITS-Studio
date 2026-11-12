package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.ModelLayer;
import org.helioviewer.jhv.layers.ViewpointLayer;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;

/**
 * The objects the 3D scene draws, under the pointer: what the orbited view names, as decided for
 * the identifier (2D imagery reports every ephemeris body; the orbited 3D view only what it draws).
 *
 * <p>Two kinds are drawn as objects rather than as imagery: model layers placed at an ephemeris
 * target (a spacecraft model at SOHO, the image layer's own observatory) and the orbit plot's
 * markers. A model is named when the pointer is within its drawn size or the pointer tolerance of
 * its centre and the Sun does not hide it; a marker when the orbit plot itself would label it.
 */
public final class Drawn {

    /** A drawn object under the pointer. {@code distanceAu} is from the Sun's centre, NaN when unknown. */
    public record Hit(String name, String kind, double pixels, double distanceAu, Kind tone, @Nullable Mark mark) {
        /** A hit with no kind colour of its own and no icon. */
        public Hit(String name, String kind, double pixels, double distanceAu) {
            this(name, kind, pixels, distanceAu, Kind.OTHER, null);
        }

        public String details() {
            return Double.isNaN(distanceAu) ? "  " + kind : String.format("  %s, %.3f AU from the Sun", kind, distanceAu);
        }
    }

    /**
     * How far the camera may turn from a layer's own line of sight, in degrees, before the view
     * counts as orbited and that layer's sky stops naming bodies the scene does not draw.
     */
    public static final double ORBITED_DEGREES = 1;

    /** The kind of a hit on the orbit plot, which draws its own hover label. */
    public static final String ORBIT_PLOT = "orbit plot";

    /** The kind of a catalog event (SWEK), whose popup is its label; a source adds the supplier after a comma. */
    public static final String EVENT = "event";

    /** The kind of an FOV outline the pointer is inside. */
    public static final String FOV = "inside the FOV outline";

    /** Whether the hit's own layer already labels it beside the pointer, so the identifier's label leaves it out. */
    public static boolean labelledElsewhere(Hit h) {
        return h.kind().equals(ORBIT_PLOT) || h.kind().equals(EVENT) || h.kind().startsWith(EVENT + ",");
    }

    /**
     * Something else the scene draws that can name itself under the pointer, in any view mode: a
     * plugin registers one (the SWEK events) so the identifier does not depend on the plugin.
     */
    public interface Source {
        List<Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels);

        /** Everything this source can put its icon on in the current view, for Highlight All; none by default. */
        default List<Mark> marks(Viewport vp, MapView mv, Identifier.Target first) {
            return List.of();
        }
    }

    /** Every mark the registered sources and the stars offer for the current view. */
    public static List<Mark> allMarks(Viewport vp, MapView mv, Identifier.Target first) {
        List<Mark> out = new ArrayList<>();
        for (Source source : sources)
            out.addAll(source.marks(vp, mv, first));
        out.addAll(Stars.marks(first));
        return out;
    }

    private static final List<Source> sources = new CopyOnWriteArrayList<>();

    public static void register(Source source) {
        if (!sources.contains(source))
            sources.add(source);
    }

    public static void unregister(Source source) {
        sources.remove(source);
    }

    /**
     * Every drawn object at canvas point (x, y): the registered sources first, then the orbit
     * plot's marker, then (in the orthographic view only) the model layers, nearest first.
     */
    public static List<Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
        List<Hit> out = new ArrayList<>();
        for (Source source : sources)
            out.addAll(source.at(vp, mv, x, y, tolerancePixels));
        Stars.Star star = Stars.at(vp, mv, x, y, tolerancePixels);
        if (star != null)
            out.add(new Hit(star.label(), star.details().strip(), star.pixels(), Double.NaN, Kind.STAR,
                    new Mark("star:" + star.label(), Kind.STAR, null, observer -> star.ray(), 0)));
        int fixed = out.size();
        ViewpointLayer orbits = Layers.getViewpointLayer();
        String marker = orbits == null ? null : orbits.markerAt(mv, vp, x, y);
        if (marker != null)
            out.add(new Hit(marker, ORBIT_PLOT, 0, Double.NaN));
        // Model layers are named in the orthographic view only. In 3D Helioradial shape.vert warps a
        // model's own vertices about the model's origin and the layer's translate then places the
        // result, so where the model stands depends on its origin offset and the warp: not a
        // position this class can state without a look at the running scene. Left unnamed there.
        if (!mv.isOrthographic())
            return out;

        double width = mv.cameraWidth(vp);
        double upX = ViewportMath.computeUpX(vp, width, mv.cameraTranslationX(), x);
        double upY = ViewportMath.computeUpY(vp, width, mv.cameraTranslationY(), y);
        double perPixel = Math.abs(ViewportMath.computeUpX(vp, width, mv.cameraTranslationX(), x + 1) - upX);
        if (!(perPixel > 0))
            return out;
        for (Layer l : Layers.getLayers()) {
            if (!(l instanceof ModelLayer m) || !m.isVisible(vp.idx))
                continue;
            Vec3 at = m.placedAt(mv.viewpoint().time);
            if (at == null)
                continue;
            Double px = pixelsFrom(mv.viewRotation(), at, upX, upY, perPixel, m.drawnRadius(mv, vp), tolerancePixels);
            if (px != null)
                out.add(new Hit(m.getTarget(), "model", px, at.length() * Sun.MeanEarthDistanceInv));
        }
        out.subList(fixed, out.size()).sort(Comparator.comparingDouble(Hit::pixels));
        return out;
    }

    /** The pointer as a sight line from the first target's observer, and the angle one screen pixel spans there. */
    public record Pointer(org.helioviewer.jhv.astronomy.Position observer, Vec3 dir, double perPixel) {}

    /**
     * The pointer at canvas point (x, y) for sources of objects at fixed directions, or null in a
     * mode with no sky under it and in the orbited 3D view (where deep sky, comets and CMEs are not
     * drawn as a sky at infinity; the stars are, on the imagery, and use the plain probe).
     */
    @Nullable
    public static Pointer pointer(Viewport vp, MapView mv, int x, int y) {
        org.helioviewer.jhv.astronomy.Position obs = IdentifyHover.targets(vp, mv).getFirst().observer();
        if (mv.isOrthographic() && orbited(mv.viewRotation(), obs.toQuat()))
            return null;
        Identifier.Probe probe = Probes.at(mv, vp, x, y), probe1 = Probes.at(mv, vp, x + 1, y);
        if (probe == null || probe1 == null)
            return null;
        Vec3 dir = probe.direction(obs), dir1 = probe1.direction(obs);
        if (dir == null || dir1 == null)
            return null;
        double perPixel = Identifier.angle(dir, dir1);
        return perPixel > 0 ? new Pointer(obs, dir, perPixel) : null;
    }

    /**
     * A thing drawn at a plane-of-sky point (radius in solar radii, position angle in degrees
     * counter-clockwise from solar north, as the helioradial view and the trackers use them), seen
     * from the first target's observer: a hit when the pointer's sight line is within the
     * tolerance of it, or null. A comet track names its comet this way.
     */
    @Nullable
    public static Hit planeOfSky(Viewport vp, MapView mv, int x, int y, double tolerancePixels, String name, String kind, double radius, double paDeg) {
        Identifier.Probe probe = Probes.at(mv, vp, x, y);
        Identifier.Probe probe1 = Probes.at(mv, vp, x + 1, y);
        if (probe == null || probe1 == null || !Double.isFinite(radius) || !Double.isFinite(paDeg))
            return null;
        org.helioviewer.jhv.astronomy.Position obs = IdentifyHover.targets(vp, mv).getFirst().observer();
        Vec3 dir = probe.direction(obs), dir1 = probe1.direction(obs);
        if (dir == null || dir1 == null)
            return null;
        double theta = Math.toRadians(paDeg);
        Vec3 at = Probes.plane(PolarBasis.x(radius, theta), PolarBasis.y(radius, theta)).direction(obs);
        double perPixel = Identifier.angle(dir, dir1);
        if (!(perPixel > 0))
            return null;
        double px = Identifier.angle(dir, at) / perPixel;
        return px <= tolerancePixels ? new Hit(name, kind, px, Double.NaN) : null;
    }

    /**
     * How many pixels the pointer (upX, upY) is from a drawn object centred at world point
     * {@code at} with drawn radius {@code radius} (both in solar radii), or null when it is not
     * within the radius or the tolerance, or the Sun hides it. The screen frame is the view
     * rotation applied to the world, the camera looking down -z (Transform.ortho).
     */
    @Nullable
    static Double pixelsFrom(Quat viewRotation, Vec3 at, double upX, double upY, double perPixel, double radius, double tolerancePixels) {
        Vec3 s = screen(viewRotation, at);
        if (s.z < 0 && Math.hypot(s.x, s.y) < 1)
            return null; // behind the solar disk
        double px = Math.hypot(s.x - upX, s.y - upY) / perPixel;
        return px <= Math.max(tolerancePixels, radius / perPixel) ? px : null;
    }

    /** A world point in the orthographic view's frame: x, y on the page in solar radii, +z toward the camera. */
    public static Vec3 screen(Quat viewRotation, Vec3 world) {
        return viewRotation.rotateVector(world);
    }

    /**
     * True when the camera's line of sight is more than ORBITED_DEGREES from the line of sight of
     * the layer whose frame is {@code layerQuat}. A roll about the line of sight does not count.
     */
    public static boolean orbited(Quat viewRotation, Quat layerQuat) {
        Vec3 axis = Quat.rotateWithConjugate(viewRotation, layerQuat).rotateVector(Vec3.ZAxis);
        double angle = Math.atan2(Math.hypot(axis.x, axis.y), axis.z);
        return Math.toDegrees(angle) > ORBITED_DEGREES;
    }

    private Drawn() {}
}
