package org.helioviewer.jhv.layers;

import org.json.JSONObject;

/**
 * What a saved session means by "the camera".
 *
 * <p>The four behaviours used to be two independent rows: a Viewpoint layer carrying
 * {@code "mode"} and a separate Camera layer carrying driver, axis and rate. Every session file
 * written before 2026-09-08 is in that shape, and a migration that silently picks the wrong
 * behaviour fails invisibly: the session opens, nothing errors, the camera is simply somewhere
 * else than it was left. The worst case is a revolving camera restoring as a still one, because
 * the Camera layer's tick lives on the layer entry rather than in its data.
 *
 * <p>Also pins the five turntable numbers coming back off a legacy blob, since the merged layer
 * reads them from a JSON object it no longer writes in that shape.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.layers.CameraBehaviourCheck
 */
public final class CameraBehaviourCheck {

    private static int failures;

    public static void main(String[] args) {
        // No camera information at all: a state from before any of this existed, or a fresh start.
        expect(ViewpointLayerOptions.behaviourFromJson(null, false) == ViewpointLayerOptions.CameraBehaviour.FREE,
                "absent state is FREE");
        expect(ViewpointLayerOptions.behaviourFromJson(new JSONObject(), false) == ViewpointLayerOptions.CameraBehaviour.FREE,
                "empty state is FREE");

        // The three old modes.
        expect(fromMode("ObserverAt1au", false) == ViewpointLayerOptions.CameraBehaviour.FREE, "ObserverAt1au maps to FREE");
        expect(fromMode("Location", false) == ViewpointLayerOptions.CameraBehaviour.FOLLOW, "Location maps to FOLLOW");
        expect(fromMode("Heliosphere", false) == ViewpointLayerOptions.CameraBehaviour.OVERVIEW, "Heliosphere maps to OVERVIEW");
        expect(fromMode("SomethingElse", false) == ViewpointLayerOptions.CameraBehaviour.FREE, "an unknown mode falls back to FREE");

        // A ticked Camera layer beside any of them: that revolution is what was on screen, so it
        // wins over the mode it sat next to.
        expect(fromMode("ObserverAt1au", true) == ViewpointLayerOptions.CameraBehaviour.TURNTABLE, "legacy turntable beats ObserverAt1au");
        expect(fromMode("Location", true) == ViewpointLayerOptions.CameraBehaviour.TURNTABLE, "legacy turntable beats Location");
        expect(fromMode("Heliosphere", true) == ViewpointLayerOptions.CameraBehaviour.TURNTABLE, "legacy turntable beats Heliosphere");
        expect(ViewpointLayerOptions.behaviourFromJson(null, true) == ViewpointLayerOptions.CameraBehaviour.TURNTABLE,
                "legacy turntable alone is TURNTABLE");

        // Current states name the behaviour, and that name is final: it is written by a build that
        // already had all four, so nothing else in the file can outrank it.
        for (ViewpointLayerOptions.CameraBehaviour behaviour : ViewpointLayerOptions.CameraBehaviour.values()) {
            JSONObject jo = new JSONObject().put("behaviour", behaviour.name()).put("mode", "Heliosphere");
            expect(ViewpointLayerOptions.behaviourFromJson(jo, false) == behaviour, "round trip of " + behaviour.name());
            expect(ViewpointLayerOptions.behaviourFromJson(jo, true) == behaviour, "named " + behaviour.name() + " outranks a legacy tick");
        }

        // A name this build does not know (a newer state, a hand-edited file) must not throw; it
        // falls through to the legacy reading rather than taking the session down.
        JSONObject unknown = new JSONObject().put("behaviour", "SIDEREAL").put("mode", "Location");
        expect(ViewpointLayerOptions.behaviourFromJson(unknown, false) == ViewpointLayerOptions.CameraBehaviour.FOLLOW,
                "an unknown behaviour name falls back to the mode");

        // The legacy Camera layer's own five numbers.
        Turntable t = new Turntable(new JSONObject()
                .put("driver", "PLAYBACK")
                .put("axisLon", 30.)
                .put("axisLat", -12.)
                .put("degPerSec", 24.)
                .put("framesPerRev", 90));
        expect(t.getDriver() == Turntable.Driver.PLAYBACK, "driver restored");
        expect(t.getAxisLon() == 30., "axisLon restored");
        expect(t.getAxisLat() == -12., "axisLat restored");
        expect(t.getDegPerSec() == 24., "degPerSec restored");
        expect(t.getFramesPerRev() == 90, "framesPerRev restored");

        // Defaults when the blob is absent or partial, so a half-written legacy entry restores a
        // usable turntable rather than one revolving about a degenerate axis at zero rate.
        Turntable fresh = new Turntable(null);
        Turntable partial = new Turntable(new JSONObject().put("axisLon", 45.));
        expect(fresh.getDriver() == Turntable.Driver.TURNTABLE, "default driver");
        expect(partial.getDriver() == Turntable.Driver.TURNTABLE, "partial blob keeps the default driver");
        expect(partial.getAxisLon() == 45., "partial blob takes what it has");
        expect(partial.getAxisLat() == fresh.getAxisLat(), "partial blob keeps the default axis lat");
        expect(partial.getFramesPerRev() == fresh.getFramesPerRev(), "partial blob keeps the default frames/rev");

        // The gate that decides whether a legacy entry is recognised at all. A typo in the class
        // name would drop the whole blob with nothing said, and the turntable settings would come
        // back as defaults on every old session.
        expect(!ViewpointLayerOptions.stashLegacyCameraLayer(
                        new JSONObject().put("className", "org.helioviewer.jhv.layers.GridLayer")),
                "another layer's entry is left alone");
        expect(ViewpointLayerOptions.stashLegacyCameraLayer(
                        new JSONObject().put("className", "org.helioviewer.jhv.layers.ObserverLayer").put("enabled", true)),
                "the old Camera layer's entry is taken over");

        if (failures > 0) {
            System.out.println("CameraBehaviourCheck: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("CameraBehaviourCheck: PASS");
    }

    private static ViewpointLayerOptions.CameraBehaviour fromMode(String mode, boolean legacyTurntableEnabled) {
        return ViewpointLayerOptions.behaviourFromJson(new JSONObject().put("mode", mode), legacyTurntableEnabled);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private CameraBehaviourCheck() {}
}
