package org.helioviewer.jhv.app.state;

import java.awt.EventQueue;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scene undo: a bounded history of whole-scene snapshots, one step per settled change, and an apply
 * that keeps every layer whose data did not change.
 *
 * <p>The stack half runs on hand-made snapshots in State.snapshot's shape and a fake clock, so it
 * pins pushes, the bound, coalescing, redo invalidation and the rule that the scene an undo leaves
 * behind is adopted rather than pushed (else every undo would clear its own redo). The apply half
 * builds a real image layer headless and checks that a snapshot differing only in display settings
 * changes the live layer in place: same object, nothing reloaded. A rebuilt layer would re-read its
 * whole movie, which is what undo exists to avoid.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.app.state.UndoStackCheck
 */
public final class UndoStackCheck {

    private static final String FIXTURE = "extra/test/data/mrzqs260301t2314c2308_169.fits";
    private static final long SETTLE = UndoStack.SETTLE_MS;

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    // One image layer entry as State.layer2json writes it.
    private static JSONObject layer(String id, String uri, String lut, double brightScale) {
        JSONObject params = new JSONObject().put("lut", lut).put("invert", false)
                .put("brightOffset", 0.0).put("brightScale", brightScale).put("opacity", 1.0);
        JSONObject data = new JSONObject().put("uris", new JSONArray().put(uri))
                .put("imageParams", params).put("filter", "None");
        return new JSONObject().put("className", ImageLayer.class.getName()).put("name", "GONG")
                .put("id", id).put("data", data).put("enabled", true);
    }

    // A scene as State.snapshot writes it, cut down to what the stack and the apply read.
    private static JSONObject scene(String time, JSONObject... imageLayers) {
        JSONObject main = new JSONObject().put("time", time).put("projection", "Orthographic")
                .put("layers", new JSONArray()).put("imageLayers", new JSONArray(List.of(imageLayers)));
        return new JSONObject().put("org.helioviewer.jhv.state", main);
    }

    private static JSONObject a(String lut, double brightScale) {
        return scene("2026-03-01T23:14:00", layer("A", "file:/a.fits", lut, brightScale));
    }

    private static String lutOf(JSONObject snapshot) {
        return snapshot.getJSONObject("org.helioviewer.jhv.state").getJSONArray("imageLayers")
                .getJSONObject(0).getJSONObject("data").getJSONObject("imageParams").getString("lut");
    }

    private static double levelsOf(JSONObject snapshot) {
        return snapshot.getJSONObject("org.helioviewer.jhv.state").getJSONArray("imageLayers")
                .getJSONObject(0).getJSONObject("data").getJSONObject("imageParams").getDouble("brightScale");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-undo-stack").toString()); // never the real settings

        stack();
        apply();

        System.out.println(failures == 0 ? "UndoStackCheck: ok" : "UndoStackCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void stack() {
        UndoStack s = new UndoStack(3);
        long t = 0;

        // The first settled scene is where history starts, not a step.
        s.observe(a("Gray", 1), t);
        s.observe(a("Gray", 1), t += SETTLE);
        expect("the first settled scene is the baseline, not a step", !s.canUndo() && !s.canRedo());

        // A change is a step only once it has held still for the settle delay.
        boolean early = s.observe(a("Red Temperature", 1), t += 1000);
        boolean settled = s.observe(a("Red Temperature", 1), t += SETTLE);
        expect("a change is not a step before it settles", !early);
        expect("and is one once it has, labelled " + s.undoLabel(), settled && s.canUndo() && "Colormap change".equals(s.undoLabel()));

        // A drag: many values within the settle delay, then still. One step, not one per tick.
        int before = s.undoDepth();
        s.observe(a("Red Temperature", 0.9), t += 1000);
        s.observe(a("Red Temperature", 0.7), t += 100);
        s.observe(a("Red Temperature", 0.5), t += 100);
        s.observe(a("Red Temperature", 0.5), t += SETTLE);
        expect("a burst of edits coalesces into one step, depth " + before + " -> " + s.undoDepth(),
                s.undoDepth() == before + 1 && "Levels change".equals(s.undoLabel()));

        // The playhead and a layer's file list are outside what undo restores: never a step.
        JSONObject moved = scene("2026-03-02T00:00:00", layer("A", "file:/elsewhere.fits", "Red Temperature", 0.5));
        s.observe(moved, t += 1000);
        boolean excluded = s.observe(moved, t += SETTLE);
        expect("a playhead move or a layer's new files are not steps", !excluded && s.undoDepth() == before + 1);

        // Undo hands back the scene before the drag, and the drag becomes redoable.
        JSONObject back = s.undo();
        expect("undo returns the scene before the last step", back != null && levelsOf(back) == 1 && "Red Temperature".equals(lutOf(back)));
        expect("and offers it as redo, labelled " + s.redoLabel(), s.canRedo() && "Levels change".equals(s.redoLabel()));

        // Applying that snapshot changes the scene. What settles afterwards is the undo's result,
        // adopted as the present, even when it is not byte-identical to the snapshot: pushing it
        // would make every undo clear its own redo.
        int depth = s.undoDepth();
        s.observe(a("Red Temperature", 0.999), t += 1000);
        boolean pushed = s.observe(a("Red Temperature", 0.999), t += SETTLE);
        expect("applying a snapshot does not push one", !pushed && s.undoDepth() == depth && s.canRedo());

        // Redo walks forward again.
        JSONObject forward = s.redo();
        expect("redo returns the undone scene", forward != null && levelsOf(forward) == 0.5 && !s.canRedo());
        s.observe(a("Red Temperature", 0.5), t += 1000);
        s.observe(a("Red Temperature", 0.5), t += SETTLE);

        // A new change after an undo ends the redo branch.
        s.undo();
        s.observe(a("Red Temperature", 1), t += 1000);
        s.observe(a("Red Temperature", 1), t += SETTLE); // adopted: the undo's own result
        s.observe(a("Blue", 1), t += 1000);
        boolean branched = s.observe(a("Blue", 1), t += SETTLE);
        expect("a new change after an undo invalidates redo", branched && !s.canRedo());

        // Bounded: depth 3 keeps the three newest steps and drops the oldest.
        for (String lut : List.of("Gray", "Red Temperature", "Blue", "Gray", "Red Temperature")) {
            s.observe(a(lut, 1), t += 1000);
            s.observe(a(lut, 1), t += SETTLE);
        }
        expect("the history is bounded at its depth, got " + s.undoDepth(), s.undoDepth() == 3);
        int undone = 0;
        while (s.undo() != null)
            undone++;
        expect("and undoes exactly that many steps, got " + undone, undone == 3);
        s.clear();

        // An Undo pressed straight after an edit undoes that edit, settled or not.
        s.observe(a("Gray", 1), t += 1000);
        s.observe(a("Gray", 1), t += SETTLE);
        s.observe(a("Blue", 1), t += 100);
        boolean flushed = s.flush(a("Blue", 1));
        JSONObject prior = s.undo();
        expect("flush records an unsettled edit so Undo reaches it", flushed && prior != null && "Gray".equals(lutOf(prior)));

        // Layers added and removed are steps too, named for what they did.
        s.clear();
        s.observe(a("Gray", 1), t += 1000);
        s.observe(a("Gray", 1), t += SETTLE);
        JSONObject two = scene("2026-03-01T23:14:00", layer("A", "file:/a.fits", "Gray", 1), layer("B", "file:/b.fits", "Gray", 1));
        s.observe(two, t += 1000);
        s.observe(two, t += SETTLE);
        expect("adding a layer is a step named " + s.undoLabel(), "Add layer".equals(s.undoLabel()));
        s.observe(a("Gray", 1), t += 1000);
        s.observe(a("Gray", 1), t += SETTLE);
        expect("removing one is a step named " + s.undoLabel(), "Remove layer".equals(s.undoLabel()));

        // A session load finishing is adopted: the scene it settles into is the present, not a step,
        // and an edit made while it loaded is still undoable.
        s.clear();
        s.observe(a("Gray", 1), t += 1000);
        s.observe(a("Gray", 1), t += SETTLE);
        s.observe(a("Blue", 1), t += 1000);
        s.observe(a("Blue", 1), t += SETTLE); // the edit made mid-load
        s.adopt();
        s.observe(a("Blue", 0.5), t += 1000); // the load's own last touch
        boolean loadPushed = s.observe(a("Blue", 0.5), t += SETTLE);
        expect("a finished load is adopted, not a step", !loadPushed && s.undoDepth() == 1);
        JSONObject midLoad = s.undo();
        expect("and the edit made while it loaded still undoes", midLoad != null && "Gray".equals(lutOf(midLoad)));
        UndoStack empty = new UndoStack(3);
        empty.adopt();
        empty.observe(a("Gray", 1), t += 1000);
        empty.observe(a("Gray", 1), t += SETTLE);
        empty.observe(a("Blue", 1), t += 1000);
        expect("adopting with no history yet leaves the next change a step", empty.observe(a("Blue", 1), t += SETTLE));

        // Session load, New Session and Revert clear it.
        s.clear();
        expect("clear leaves nothing to undo or redo", !s.canUndo() && !s.canRedo() && s.undo() == null && s.redo() == null);
    }

    // A real ImageLayer, headless: the apply must change it in place, not rebuild it.
    private static void apply() throws Exception {
        File fits = new File(FIXTURE);
        if (!fits.isFile()) {
            expect("fixture " + FIXTURE + " readable from the repository root (run from there)", false);
            return;
        }
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice(); // the registry's null layer reaches SPICE

        String uri = fits.getAbsoluteFile().toURI().toString();
        String red = LUT.get("Red Temperature") != null ? "Red Temperature" : LUT.names()[1];
        EventQueue.invokeAndWait(() -> {
            JSONObject entry = layer("A", uri, LUT.gray().name(), 1);
            ImageLayer live = ImageLayer.createDetached(entry.getJSONObject("data"));
            live.restoreId("A");
            Layers.add(live);

            // Same id, same files, a different colour table and window: changed in place.
            StateApply.applyImageLayers(new JSONArray().put(layer("A", uri, red, 0.5)));
            List<ImageLayer> after = Layers.getImageLayers();
            expect("a display-only change keeps the same layer object (no rebuild, no reload)",
                    after.size() == 1 && after.getFirst() == live);
            expect("and the layer now shows the snapshot's colour table, got " + live.getDisplaySettings().getLUT().name(),
                    red.equals(live.getDisplaySettings().getLUT().name()));
            expect("and its Levels window, got " + live.getDisplaySettings().getBrightScale(),
                    live.getDisplaySettings().getBrightScale() == 0.5);

            // A layer the snapshot has and the scene does not is created; one it lacks is removed.
            StateApply.applyImageLayers(new JSONArray().put(layer("B", uri, red, 1)));
            List<ImageLayer> swapped = Layers.getImageLayers();
            expect("a layer missing from the snapshot is removed and one missing from the scene created",
                    swapped.size() == 1 && swapped.getFirst() != live && "B".equals(swapped.getFirst().getId()));
            swapped.forEach(Layers::remove);
        });
    }
}
