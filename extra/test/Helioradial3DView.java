package org.helioviewer.jhv.identify;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;

/**
 * Helioradial with Render in 3D, for the identify checks that run headless. GLRenderer builds its
 * map view once, in the mode of the moment, so a check that wants the 3D mesh builds the same view
 * itself from the same pieces (GLRenderer.createMapView). Not a check: run-checks.sh runs only
 * classes whose names end in "Check".
 */
final class Helioradial3DView {

    /**
     * The 3D view from {@code viewpoint}, with a field of {@code field} solar radii when that is positive
     * (nothing is loaded here, so the field is otherwise the 1.1 minimum and the page is all disk).
     */
    static MapView build(Position viewpoint, double field) throws Exception {
        if (field > 0) {
            java.lang.reflect.Field f = Display.class.getDeclaredField("fieldRadius");
            f.setAccessible(true);
            f.set(null, (java.util.function.DoubleSupplier) () -> field);
        }
        Display.setWarpLambda(1); // no radial warp: a ring about a body is a circle on the page, which is what the round check asks
        Display.setHelioradial3D(true);
        Display.setMapMode(MapMode.Helioradial);
        return MapView.create(Display.getCamera(), viewpoint, MapMode.Helioradial, GridType.Viewpoint,
                new MapScale[]{MapScale.boxCoxRadial(Display.fullWarpFieldRadius())});
    }

    private Helioradial3DView() {}
}
