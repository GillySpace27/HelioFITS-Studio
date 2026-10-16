package org.helioviewer.jhv.layers;

import java.awt.EventQueue;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.helioviewer.jhv.image.ImageFilter;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hold to compare (RH-17): the held layer alone loses RHEF, even with a selected peer on RHEF, and a
 * save taken while it is held writes RHEF, so a peek is never stored. Release puts it back.
 *
 * <p>Run (from the repository root): java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.RhefPeekCheck
 */
public final class RhefPeekCheck {

    private static final String FIXTURE = "extra/test/data/mrzqs260301t2314c2308_169.fits"; // as UndoStackCheck

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static ImageLayer layer(String uri, String filter) {
        JSONObject data = new JSONObject().put("uris", new JSONArray().put(uri))
                .put("imageParams", new JSONObject()).put("filter", filter);
        return ImageLayer.createDetached(data);
    }

    private static String saved(ImageLayer l) {
        JSONObject jo = new JSONObject();
        l.serialize(jo);
        return jo.optString("filter");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-rhef-peek").toString()); // never the real settings
        File fits = new File(FIXTURE);
        if (!fits.isFile()) {
            expect("fixture " + FIXTURE + " readable from the repository root (run from there)", false);
            System.exit(1);
        }
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        String uri = fits.getAbsoluteFile().toURI().toString();

        EventQueue.invokeAndWait(() -> {
            ImageLayer a = layer(uri, "RHEF");
            ImageLayer b = layer(uri, "RHEF");
            ImageLayer none = layer(uri, "None");
            Layers.add(a);
            Layers.add(b);
            Layers.setSelection(List.of(a, b)); // a selected peer is what a fan-out would reach

            RhefPeek.begin(a);
            expect("held: the layer shows no filter", a.getFilter() == ImageFilter.Type.None && RhefPeek.isHeld(a));
            expect("held: its selected peer keeps RHEF", b.getFilter() == ImageFilter.Type.RHEF && !RhefPeek.isHeld(b));
            expect("held: a save writes RHEF, not the peek (got " + saved(a) + ")", "RHEF".equals(saved(a)));

            RhefPeek.end(a);
            expect("released: RHEF is back", a.getFilter() == ImageFilter.Type.RHEF && !RhefPeek.isHeld(a));
            expect("released: a save writes RHEF", "RHEF".equals(saved(a)));

            RhefPeek.end(a);
            expect("a second release changes nothing", a.getFilter() == ImageFilter.Type.RHEF);
            RhefPeek.begin(none);
            expect("a layer not on RHEF is not touched", none.getFilter() == ImageFilter.Type.None && !RhefPeek.isHeld(none));

            Layers.setSelection(List.of());
            Layers.remove(a);
            Layers.remove(b);
        });

        System.out.println(failures == 0 ? "RhefPeekCheck: PASS" : "RhefPeekCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}
