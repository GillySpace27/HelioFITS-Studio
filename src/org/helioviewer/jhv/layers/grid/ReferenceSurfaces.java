package org.helioviewer.jhv.layers.grid;

import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.time.JHVTime;

/**
 * Two reference surfaces drawn as wireframes: the Thomson sphere and the ecliptic plane.
 *
 * <p>Both are emitted as world-space geometry in solar radii, which matters more than it sounds.
 * The vertex stage warps a raw vertex before the MVP, so a wireframe carrying true heliocentric
 * distances is compressed by exactly the same radial law as the imagery and lands on it. One built
 * in already-warped coordinates would drift away from the picture as soon as lambda moved.
 *
 * <p>Neither surface is a measurement. The Thomson sphere is where a coronagraph's line of sight
 * is <em>assumed</em> to have originated, and drawing it explicitly is the point: with the display
 * on plane-of-sky you can see how far the assumption moves things, which is 6 to 40 percent across
 * a wide field and invisible otherwise.
 */
public final class ReferenceSurfaces {

    private static final int RING_SUBDIVISIONS = 96;
    private static final int SPOKE_COUNT = 12;
    private static final int SPOKE_STEPS = 48;

    /**
     * The Thomson sphere: the locus where the scattering angle is 90 degrees, which for an
     * observer at distance D is the sphere of diameter D through the Sun and the observer. In the
     * observer's frame a point at heliocentric radius r sits at depth {@code z = r^2 / D} with
     * in-plane radius {@code rho = sqrt(r^2 - z^2)}, which is {@link SurfaceModel}'s own geometry.
     *
     * <p>Drawn in the OBSERVER's frame, so the caller must rotate into it the way the observer dot
     * and the radial grid do. Rings are lines of constant r, spokes lines of constant position
     * angle, so the mesh reads as the bowl it is.
     *
     * @param outerRadius the largest heliocentric radius to draw, in solar radii
     */
    public static void buildThomsonSphere(GLSLLine line, double observerDistance, double outerRadius, byte[] color, double density) {
        if (observerDistance <= 0 || outerRadius <= 0) {
            line.setVertex(new BufVertex(0));
            return;
        }
        // Past D/sqrt(2) the surface folds back toward the axis and the rings shrink again. That
        // is real geometry, not an artefact, so it is drawn; it is simply capped at D, where the
        // sphere closes on the observer and rho reaches zero.
        double maxRadius = Math.min(outerRadius, observerDistance);

        int rings = ringCount(maxRadius, density);
        int vertices = rings * (RING_SUBDIVISIONS + 3) + SPOKE_COUNT * (SPOKE_STEPS + 3);
        BufVertex buf = new BufVertex(vertices * GLSLLine.stride);

        for (int i = 1; i <= rings; i++) {
            double r = maxRadius * i / rings;
            for (int j = 0; j <= RING_SUBDIVISIONS; j++) {
                double pa = 2 * Math.PI * j / RING_SUBDIVISIONS;
                putSurfacePoint(buf, r, pa, observerDistance, color, j == 0, j == RING_SUBDIVISIONS);
            }
        }

        for (int s = 0; s < SPOKE_COUNT; s++) {
            double pa = 2 * Math.PI * s / SPOKE_COUNT;
            for (int j = 0; j <= SPOKE_STEPS; j++) {
                double r = maxRadius * j / SPOKE_STEPS;
                putSurfacePoint(buf, r, pa, observerDistance, color, j == 0, j == SPOKE_STEPS);
            }
        }

        line.setVertex(buf);
    }

    private static void putSurfacePoint(BufVertex buf, double r, double positionAngle, double observerDistance,
                                        byte[] color, boolean first, boolean last) {
        double z = SurfaceModel.ThomsonSphere.depth(r, observerDistance);
        double rho = Math.sqrt(Math.max(0, r * r - z * z));
        // Polar basis: 0 at north, increasing anti-clockwise. Matches warpSurface.vert.
        float x = (float) (-rho * Math.sin(positionAngle));
        float y = (float) (rho * Math.cos(positionAngle));
        float zf = (float) z;
        if (first)
            buf.putVertex(x, y, zf, 1, Colors.Null);
        buf.putVertex(x, y, zf, 1, color);
        if (last)
            buf.putVertex(x, y, zf, 1, Colors.Null);
    }

    /**
     * Rings enough to read as a surface without turning a wide field into a solid wash. Scales
     * with the log of the field, because a linear count leaves a 4 solar-radii view nearly bare
     * and a 250 one solid; {@code density} is the user's multiplier on top of that.
     */
    private static int ringCount(double maxRadius, double density) {
        int base = (int) Math.round(Math.log10(Math.max(maxRadius, 1.5)) * 8) + 4;
        return Math.clamp((int) Math.round(base * Math.clamp(density, 0.25, 4)), 2, 64);
    }

    /**
     * The ecliptic plane, drawn as concentric rings and spokes.
     *
     * <p>Its orientation is taken from Earth's own orbit rather than from a tabulated inclination:
     * the ecliptic IS the plane of that orbit, so two Earth positions a quarter year apart span it
     * exactly. Deriving it keeps it consistent with the Earth marker beside it by construction,
     * which a hard-coded 7.25 degrees would not.
     *
     * <p><b>The positions must be INERTIAL.</b> This was first written with {@code Sun.getEarth},
     * which reports Carrington longitude, and Carrington rotates. Two directions sampled in a
     * rotating frame are separated mostly by the Sun's spin rather than by Earth's orbit, so the
     * plane they span is not the ecliptic and it drifts as the Sun turns. Measured against the
     * shipped ephemeris over one solar rotation, the normal built that way left the solar pole by
     * 7.482 degrees and arrived at 6.094, sliding the whole way; built in HCI it reads 7.252
     * degrees at every epoch, which is the known inclination of the solar equator to the ecliptic
     * and is the check that this is now the right plane. Same class of error as the one that made
     * the planets orbit at the Sun's rotation rate; see PlanetMarkers.
     *
     * <p>Drawn in the display frame. The caller supplies the angle that carries an inertial
     * direction into it, so the ecliptic, the planets and the observer marker are all placed by
     * one convention.
     */
    public static void buildEcliptic(GLSLLine line, JHVTime time, double outerRadius, byte[] color, double density) {
        if (outerRadius <= 0) {
            line.setVertex(new BufVertex(0));
            return;
        }
        // One offset for both samples: it is the frame conversion, not a per-epoch correction, and
        // evaluating it twice would fold a quarter turn of the Sun into the plane's orientation.
        double offset = PlanetMarkers.frameOffset(time);
        Vec3 u = earthDirection(time, offset);
        // A quarter orbit later, so the two directions are near-orthogonal and their cross product
        // is well conditioned. Any separation would span the plane; 90 degrees is just the steadiest.
        Vec3 w = earthDirection(new JHVTime(time.milli + 91 * 86400_000L), offset);
        if (u.length() == 0 || w.length() == 0) { // no ephemeris: draw nothing rather than a guess
            line.setVertex(new BufVertex(0));
            return;
        }

        Vec3 normal = Vec3.cross(u, w);
        if (normal.length() < 1e-9) { // degenerate: fall back to the solar equator rather than draw nonsense
            line.setVertex(new BufVertex(0));
            return;
        }
        Vec3 v = unit(Vec3.cross(unit(normal), u));

        int rings = ringCount(outerRadius, density);
        int vertices = rings * (RING_SUBDIVISIONS + 3) + SPOKE_COUNT * 3;
        BufVertex buf = new BufVertex(vertices * GLSLLine.stride);

        for (int i = 1; i <= rings; i++) {
            double r = outerRadius * i / rings;
            for (int j = 0; j <= RING_SUBDIVISIONS; j++) {
                double a = 2 * Math.PI * j / RING_SUBDIVISIONS;
                double cx = r * Math.cos(a), cy = r * Math.sin(a);
                putPlanePoint(buf, u, v, cx, cy, color, j == 0, j == RING_SUBDIVISIONS);
            }
        }

        for (int s = 0; s < SPOKE_COUNT; s++) {
            double a = 2 * Math.PI * s / SPOKE_COUNT;
            double cx = outerRadius * Math.cos(a), cy = outerRadius * Math.sin(a);
            putPlanePoint(buf, u, v, 0, 0, color, true, false);
            putPlanePoint(buf, u, v, cx, cy, color, false, true);
        }

        line.setVertex(buf);
    }

    private static void putPlanePoint(BufVertex buf, Vec3 u, Vec3 v, double a, double b,
                                      byte[] color, boolean first, boolean last) {
        float x = (float) (a * u.x + b * v.x);
        float y = (float) (a * u.y + b * v.y);
        float z = (float) (a * u.z + b * v.z);
        if (first)
            buf.putVertex(x, y, z, 1, Colors.Null);
        buf.putVertex(x, y, z, 1, color);
        if (last)
            buf.putVertex(x, y, z, 1, Colors.Null);
    }

    /**
     * Unit vector from the Sun to Earth, taken inertially and carried into the display frame.
     *
     * <p>The same two steps the planet markers use, and for the same reason: the ephemeris is
     * asked in HCI, where Earth moves at its own rate, and one angle brings the answer into the
     * frame everything is drawn in. Returns a zero vector when the ephemeris cannot place Earth.
     */
    private static Vec3 earthDirection(JHVTime time, double offset) {
        double[] v = PlanetMarkers.hci(SpaceObject.get("Earth"), time);
        return v == null ? new Vec3(0, 0, 0) : unit(PlanetMarkers.toDisplay(v, offset));
    }

    // Vec3.normalize is commented out upstream, so keep it local rather than reviving it here.
    private static Vec3 unit(Vec3 v) {
        double len = v.length();
        return len == 0 ? new Vec3(0, 0, 0) : new Vec3(v.x / len, v.y / len, v.z / len);
    }

    private ReferenceSurfaces() {}
}
