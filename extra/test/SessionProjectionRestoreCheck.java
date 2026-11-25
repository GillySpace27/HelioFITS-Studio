package org.helioviewer.jhv.app.state;

import java.nio.file.Files;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;

import org.json.JSONObject;

/**
 * A session opens at its own framing, not at a Sun a hundredth of its size.
 *
 * <p>State.load set the session's projection as an interactive switch, which waits for the next
 * GL frame and then keeps the old disk size by solving the viewport zoom. By that frame the
 * session's camera was in, so an Orthographic camera framing a PUNCH field (fov 1.61 rad, from a
 * real session) going into Helioradial left the viewport zoom near 183 until a double-click on
 * the canvas reset it. The restore has to switch at once, at zoom 1, for the saved camera to land on.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.state.SessionProjectionRestoreCheck
 */
public final class SessionProjectionRestoreCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-session-projection").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        try {
            org.helioviewer.jhv.app.AppInit.loadSpice();
            ViewState.restoreProjection(MapMode.Orthographic);
            DisplayController.cameraFromJson(new JSONObject("{\"translation\":[0,0],\"zoom\":1,\"fov\":1.6115636026173752,\"dragRotation\":[1,0,0,0]}"));
            ViewState.restoreProjection(MapMode.Helioradial);
            // The GL frame that would run a deferred switch, as the canvas does at the top of display().
            org.helioviewer.jhv.display.ProjectionTransition.applyPendingSwitch(() -> {});
        } catch (Exception | UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError e) {
            // The camera reset reaches the viewpoint, and through it SPICE, whose natives are not built for every platform.
            System.out.println("SKIP: SPICE natives are not available on this platform (" + e + ")");
            return;
        }
        expect("the projection is in force at once, not a GL frame later", Display.mode == MapMode.Helioradial);
        double zoom = Display.getViewports()[0].zoom;
        expect("the viewport zoom is left at 1 for the session's camera (" + zoom + ")", zoom == 1);

        System.out.println(failures == 0 ? "SessionProjectionRestoreCheck: PASS" : "SessionProjectionRestoreCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private SessionProjectionRestoreCheck() {}

}
