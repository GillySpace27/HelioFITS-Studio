package org.helioviewer.jhv.io;

import org.json.JSONObject;

/**
 * The contract that makes a FITS layer follow the date.
 *
 * <p>Before this existed, a FITS layer was a frozen list of URIs, and both time-range paths in
 * ImageLayers opened with "get the APIRequest, skip if null" -- so every FITS layer was passed
 * over in silence. Nothing failed; the layer simply never moved. These assertions pin the pieces
 * that make the re-issue possible: a query that survives a session, and a span swap that changes
 * only the span.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.io.FitsRequestCheck
 */
public final class FitsRequestCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        FitsRequest r = new FitsRequest(FitsRequest.Archive.PUNCH, "3", "CAM", "Latest",
                60_000, 1_700_000_000_000L, 1_700_086_400_000L);

        // A session round trip has to preserve every field, or a restored layer re-queries for
        // something other than what the user asked for -- quietly, and only on the next date change.
        //
        // Only the write half runs here. fromJson goes through TimeUtils.optParse, which calls
        // SPICE unconditionally, and the SPICE native library is unpacked at runtime rather than
        // shipped on a stable path -- the same reason TimelineSourceCheck cannot run headless. So
        // this asserts every field reaches the JSON, and leaves parsing to the app.
        JSONObject jo = r.toJson();
        expect(FitsRequest.Archive.PUNCH.name().equals(jo.optString("archive")), "archive is written");
        expect(r.level().equals(jo.optString("level")), "level is written");
        expect(r.product().equals(jo.optString("product")), "product is written");
        expect(r.version().equals(jo.optString("version")), "version is written");
        expect(r.cadence() == jo.optLong("cadence"), "cadence is written");
        expect(!jo.optString("startTime").isEmpty(), "start is written");
        expect(!jo.optString("endTime").isEmpty(), "end is written");
        // Every field the record carries must be in the JSON. A field added to the record and
        // forgotten here is exactly the silent loss this guards.
        expect(jo.length() == FitsRequest.class.getRecordComponents().length,
                "every record component is serialized: " + jo.length() + " keys for "
                        + FitsRequest.class.getRecordComponents().length + " components");

        FitsRequest moved = r.withSpan(2_000_000_000_000L, 2_000_086_400_000L);
        expect(moved.startTime() == 2_000_000_000_000L && moved.endTime() == 2_000_086_400_000L, "withSpan moves the span");
        expect(moved.archive() == r.archive() && moved.level().equals(r.level())
                && moved.product().equals(r.product()) && moved.version().equals(r.version())
                && moved.cadence() == r.cadence(), "withSpan changes nothing else");

        // A resync carries the master cadence too. Without the three-argument form, a FITS layer
        // re-issued over a new span kept the cadence it was created with, so changing the frame
        // count and pressing Sync left LASCO and SUVI at whatever they first loaded at.
        FitsRequest resynced = r.withSpan(2_000_000_000_000L, 2_000_086_400_000L, 600_000);
        expect(resynced.cadence() == 600_000, "withSpan(start, end, cadence) takes the new cadence");
        expect(resynced.archive() == r.archive() && resynced.level().equals(r.level())
                && resynced.product().equals(r.product()) && resynced.version().equals(r.version()),
                "and changes nothing but the span and the cadence");

        // The master control speaks seconds and "get all"; this record speaks milliseconds and 0.
        expect(FitsRequest.cadenceMillis(1800) == 1_800_000L, "seconds become milliseconds");
        expect(FitsRequest.cadenceMillis(APIRequest.CADENCE_ALL) == 0, "\"get all\" becomes every frame");

        // An inverted span would otherwise reach the archive as a backwards range.
        expect(new FitsRequest(FitsRequest.Archive.PUNCH, "3", "CAM", "L", 0, 500, 100).endTime() == 500,
                "an inverted span is clamped, not passed through");

        // Restorability of a query-only layer is asserted in SessionStateCheck, which sits in
        // the package that owns hasRestorableData.

        // The two buttons that add a FITS layer have to read the master cadence control, not
        // TimeUtils.defaultCadence. They read the default for years, which is span/96 -- so on
        // the two-day range the app opens with it landed on 1800 s and every LASCO and SUVI
        // layer came in at 97 frames no matter what the frame-count field said. The spelling is
        // what regressed, so the spelling is what this asserts.
        noDefaultCadence("src/org/helioviewer/jhv/layers/selector/LayersSectionPanel.java");
        noDefaultCadence("src/org/helioviewer/jhv/gui/component/VsoSelectorPanel.java");

        if (failures != 0)
            throw new AssertionError(failures + " FitsRequest failure(s)");
        System.out.println("FitsRequestCheck: PASS");
    }

    private static void noDefaultCadence(String path) throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of(path);
        if (!java.nio.file.Files.isReadable(file)) { // run from somewhere other than the checkout root
            System.out.println("SKIP: " + path + " not readable from " + System.getProperty("user.dir"));
            return;
        }
        expect(!java.nio.file.Files.readString(file).contains("defaultCadence"),
                path + " adds FITS layers at the master cadence, not the span's default");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
