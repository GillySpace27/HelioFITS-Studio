package org.helioviewer.jhv.layers;

import java.awt.EventQueue;

import org.helioviewer.jhv.io.FitsRequest;

import org.json.JSONObject;

/**
 * A layer asked for but not yet answered is visible, named for what was asked.
 *
 * <p>Until the server answered, the layer row read "Loading..." and the timeline drew a tick at the
 * current time, so a request the server took a minute over looked like nothing had happened. The layer
 * now says what it was asked for and over which range before any view exists, and the timeline draws
 * that range.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.PlaceholderLayerCheck
 */
public final class PlaceholderLayerCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        EventQueue.invokeAndWait(() -> {
            ImageLayer layer = ImageLayer.createDetached(new JSONObject());
            expect("a layer asked for nothing has no placeholder", layer.placeholderLabel() == null && layer.placeholderSpan() == null);
            expect("and is named Loading...", "Loading...".equals(layer.getName()));

            long start = 1_774_000_000_000L, end = start + 23 * 86_400_000L;
            layer.setFitsRequest(new FitsRequest(FitsRequest.Archive.LASCO, "05", "C3", "", 3_600_000L, start, end));
            expect("a layer asked for LASCO C3 says so before any view exists, got " + layer.placeholderLabel(),
                    "LASCO C3".equals(layer.placeholderLabel()));
            long[] span = layer.placeholderSpan();
            expect("and carries the requested range for the timeline", span != null && span[0] == start && span[1] == end);
            expect("and is named for it, got " + layer.getName(), "LASCO C3, loading".equals(layer.getName()));
        });

        System.out.println(failures == 0 ? "PlaceholderLayerCheck: ok" : "PlaceholderLayerCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
