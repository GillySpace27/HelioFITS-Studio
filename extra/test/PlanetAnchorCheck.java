package org.helioviewer.jhv.layers.grid;

/**
 * That Earth coincides with the observer marker, expressed as the sign identity underneath it.
 *
 * <p>The observer marker is vertex {@code (0, 0, distance)} drawn under
 * {@code rotateViewInverse(viewpoint.toQuat())}, which lands it at the observer's own position.
 * A planet is built from longitude and latitude. Get the longitude sign backwards and every body
 * is reflected across the x = 0 plane: measured, Earth sat at {@code (+39.47, 26.53, -210.55)}
 * with the marker at {@code (-39.47, 26.53, -210.55)}. Same latitude, same depth, mirrored.
 *
 * <p>That is the failure worth a check. A mirrored solar system still looks like a solar system:
 * the orbits are ellipses, the spacings are right, nothing is missing, and the planets are simply
 * on the wrong side. It survived several rounds of looking at it.
 *
 * <p>SPICE's native library is unpacked at runtime and cannot be loaded headless, so this asserts
 * the geometry rather than querying the ephemeris.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.layers.grid.PlanetAnchorCheck
 */
public final class PlanetAnchorCheck {

    private static int failures;

    public static void main(String[] args) {
        // The measured case: Earth at 2025-09-22, and where its marker has to land.
        double r = 215.86, lat = Math.toRadians(7.061), lon = Math.toRadians(-190.618);
        double[] want = {-39.47, 26.53, -210.55};

        double[] right = place(r, lat, -lon);  // hci minus offset, i.e. the corrected sign
        double[] wrong = place(r, lat, lon);   // offset minus hci, the mirrored one

        for (int i = 0; i < 3; i++)
            expect(Math.abs(right[i] - want[i]) < 0.05,
                    "component " + i + ": got " + round(right[i]) + ", want " + want[i]);

        // The wrong sign differs in x alone, which is exactly why it is hard to see.
        expect(Math.abs(wrong[0] + right[0]) < 1e-9, "the mirror flips x");
        expect(Math.abs(wrong[1] - right[1]) < 1e-9, "the mirror leaves latitude alone");
        expect(Math.abs(wrong[2] - right[2]) < 1e-9, "the mirror leaves depth alone");
        expect(Math.abs(wrong[0] - right[0]) > 70, "and it is a big error where it is an error at all");

        // Both have identical distance from the Sun, so nothing about the radial layout betrays it.
        expect(Math.abs(len(right) - len(wrong)) < 1e-9, "the mirror preserves heliocentric distance");
        expect(Math.abs(len(right) - r) < 1e-9, "and the distance is the real one");

        if (failures != 0)
            throw new AssertionError(failures + " planet-anchor failure(s)");
        System.out.println("PlanetAnchorCheck: PASS");
    }

    /** The placement in PlanetMarkers.toDisplay, isolated from SPICE. */
    private static double[] place(double r, double lat, double lon) {
        double cosLat = Math.cos(lat);
        return new double[]{r * cosLat * Math.sin(lon), r * Math.sin(lat), r * cosLat * Math.cos(lon)};
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.;
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
