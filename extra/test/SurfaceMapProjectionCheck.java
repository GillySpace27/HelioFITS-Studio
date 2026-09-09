package org.helioviewer.jhv.display;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A CAR/CEA surface map reaches every projection, not just the ones somebody remembered.
 *
 * <p>A synoptic map is a map OF THE SPHERE: each texel is a longitude and a latitude, so the only
 * way to sample it is to intersect the sight line with the solar surface. The observer-image path
 * cannot do that. It routes an unknown projection code through projectHelioprojectiveToWcsPlane,
 * which has no CAR or CEA branch and falls through to TAN, and the result is not blank (which
 * would at least read as an error) but a smeared band across the page that reads as data.
 *
 * <p>Orthographic, Latitudinal and both Helioradial implementations were given surface-map paths
 * one at a time. HPC, Helioradial Unrolled and Observer Sky were not, and went on sampling as if
 * every image were a picture taken of the Sun. That is the fault this pins: the sight-line modes
 * all sample through the one shared sampleLayerTexcoord, and none of them calls the observer-image
 * sampler directly any more.
 *
 * <p>The mode table is checked against MapMode.values(), so a projection added later fails here
 * until someone has said which of the two routes it takes.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.display.SurfaceMapProjectionCheck
 */
public final class SurfaceMapProjectionCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** How a mode gets at a surface map: along the sight line, or through its own lon/lat branch. */
    private enum Route { SIGHT_LINE, OWN_BRANCH }

    private record Mode(Route route, String... shaders) {}

    // Every MapMode and the fragment shader it draws imagery with. Helioradial names two, because
    // it has two implementations: a fragment-space inverse map when flat, a surface mesh in 3D.
    private static final Map<String, Mode> MODES = new LinkedHashMap<>();

    static {
        // Reconstructs a world point from screen space rather than a sight line, so it samples
        // lon/lat directly and needs no shared helper.
        MODES.put("Orthographic", new Mode(Route.OWN_BRANCH, "solarOrtho.frag"));
        MODES.put("HPC", new Mode(Route.SIGHT_LINE, "solarHpc.frag"));
        MODES.put("Helioradial", new Mode(Route.SIGHT_LINE, "solarRadialWarp.frag", "warpSurface.frag"));
        MODES.put("HelioradialUnrolled", new Mode(Route.SIGHT_LINE, "solarRectWarp.frag"));
        // Its page IS longitude and latitude, so there is no sight line anywhere in it.
        MODES.put("Latitudinal", new Mode(Route.OWN_BRANCH, "solarLati.frag"));
        MODES.put("ObserverSky", new Mode(Route.SIGHT_LINE, "solarSky.frag"));
    }

    public static void main(String[] args) throws IOException {
        Path glsl = Path.of("resources", "glsl");
        if (!Files.isDirectory(glsl)) {
            System.out.println("  FAIL run me from the repository root: no " + glsl);
            System.exit(1);
        }

        for (MapMode mode : MapMode.values())
            expect(mode.name() + " is listed here, so a new projection cannot slip past",
                    MODES.containsKey(mode.name()));
        expect("and nothing is listed that is not a projection", MODES.size() == MapMode.values().length);

        String common = Files.readString(glsl.resolve("solarCommon.frag"));
        expect("the shared sampler exists", common.contains("vec2 sampleLayerTexcoord("));
        expect("and the surface-map sampler it delegates to", common.contains("bool sampleSurfaceMapTexcoord("));
        // GLSL has no forward declarations here: a callee defined below its caller does not link.
        expect("the shared sampler is defined below the observer-image one it calls",
                common.indexOf("vec2 sampleHpcTexcoord(") < common.indexOf("vec2 sampleLayerTexcoord("));
        expect("the observer-image path still has no CAR branch, which is why the split exists",
                !projectionBody(common).contains("WCS_PROJECTION_CAR"));

        for (Map.Entry<String, Mode> entry : MODES.entrySet()) {
            Mode mode = entry.getValue();
            for (String file : mode.shaders()) {
                String src = Files.readString(glsl.resolve(file));
                if (mode.route() == Route.SIGHT_LINE) {
                    expect(file + " samples through the shared sampler", src.contains("sampleLayerTexcoord("));
                    expect(file + " never samples as if every image were an observer image",
                            !src.contains("sampleHpcTexcoord("));
                } else {
                    expect(file + " carries its own CAR branch", src.contains("WCS_PROJECTION_CAR"));
                    expect(file + " carries its own CEA branch", src.contains("WCS_PROJECTION_CEA"));
                }
            }
        }

        System.out.println(failures == 0 ? "SurfaceMapProjectionCheck: PASS" : "SurfaceMapProjectionCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The body of projectHelioprojectiveToWcsPlane, the function that must NOT grow such a branch. */
    private static String projectionBody(String common) {
        int start = common.indexOf("vec2 projectHelioprojectiveToWcsPlane(");
        if (start < 0)
            return "WCS_PROJECTION_CAR"; // gone or renamed: fail loudly rather than pass by accident
        int end = common.indexOf("\n}", start);
        return end < 0 ? common.substring(start) : common.substring(start, end);
    }

    private SurfaceMapProjectionCheck() {}

}
