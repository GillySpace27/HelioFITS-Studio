package org.helioviewer.jhv.app.state;

import java.nio.file.Files;

import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * Start New Session puts back the scene a fresh install opens with, not just an empty layer list.
 *
 * <p>The old New Session removed the deletable layers one by one, so the projection, the camera's
 * mode and the built-in layers' settings (grid, viewpoint, timestamp) all carried over into the
 * "new" session. BlankSession goes through the two steps a session load takes instead. This
 * pins both halves: ViewState.DEFAULT_MODE is what a fresh process reports (so it cannot drift
 * from the real defaults unnoticed), and after a changed projection and a changed grid, apply()
 * brings back that mode and a newly built grid layer.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.app.state.BlankSessionCheck
 */
public final class BlankSessionCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // Settings may be touched by layer constructors; never reach the real user.properties.
        System.setProperty("user.home", Files.createTempDirectory("hfs-blank-session").toString());
        // Same boilerplate as GridPaletteCheck: the default layers reach SPICE.
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        ViewState.ModeData fresh = ViewState.modeData();
        expect("DEFAULT_MODE is what a fresh process reports: " + fresh, ViewState.DEFAULT_MODE.equals(fresh));

        MapMode other = fresh.projection() == MapMode.Orthographic ? MapMode.Latitudinal : MapMode.Orthographic;
        ViewState.applyMode(new ViewState.ModeData(other, fresh.surfaceModel(), 0.5, fresh.annotationMode(),
                !fresh.multiview(), fresh.tracking(), fresh.refresh(), !fresh.showCorona(),
                fresh.differentialRotation(), fresh.helioradial3D()));
        expect("the mode changed before the reset (projection " + ViewState.getProjection() + ")",
                !ViewState.DEFAULT_MODE.equals(ViewState.modeData()));

        GridLayer before = Layers.getGridLayer();
        if (before != null)
            before.setShowLabels(false);
        int layerCount = Layers.getLayers().size();

        BlankSession.apply();

        expect("after apply the mode is DEFAULT_MODE again: " + ViewState.modeData(),
                ViewState.DEFAULT_MODE.equals(ViewState.modeData()));
        GridLayer after = Layers.getGridLayer();
        expect("the grid layer is rebuilt, not the edited one kept", after != null && after != before);
        expect("the built-in layers are all there again (" + Layers.getLayers().size() + " of " + layerCount + ")",
                Layers.getLayers().size() == layerCount);
        expect("no image layer is left", Layers.getImageLayers().isEmpty());

        System.out.println(failures == 0 ? "BlankSessionCheck: PASS" : "BlankSessionCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private BlankSessionCheck() {}

}
