package org.helioviewer.jhv.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a layer shows, in one line (HS-19): "EUV 171 Å (Fe IX, log T 5.8)", "white-light total
 * brightness", or "unknown".
 *
 * <p>Every label is an instrument claim, so it comes from resources/data/observation-types.json, whose
 * rows each name the vault instruments/ file they were read from. A header no row matches reads
 * "unknown" rather than a guess: an instrument without a sourced file has no row.
 */
public final class ObservationType {

    public static final String UNKNOWN = "unknown";

    record Row(@Nullable String instrument, @Nullable String detector, @Nullable String measurement,
               @Nullable String measurementEndsWith, @Nullable String product, String label, String source) {

        boolean matches(String inst, String det, String meas, String prod) {
            return (instrument == null || instrument.equals(inst))
                    && (detector == null || detector.equals(det))
                    && (measurement == null || measurement.equals(meas))
                    && (measurementEndsWith == null || meas.endsWith(measurementEndsWith))
                    && (product == null || product.equals(prod));
        }
    }

    private static final List<Row> rows = load();

    static List<Row> parse(JSONObject jo) {
        JSONArray array = jo.getJSONArray("rows");
        List<Row> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject r = array.getJSONObject(i);
            list.add(new Row(r.optString("instrument", null), r.optString("detector", null),
                    r.optString("measurement", null), r.optString("measurementEndsWith", null),
                    r.optString("product", null), r.getString("label"), r.getString("source")));
        }
        return List.copyOf(list);
    }

    static List<Row> load() {
        try (InputStream is = FileUtils.getResource("/data/observation-types.json")) {
            return parse(JSONUtils.get(is));
        } catch (IOException | RuntimeException e) { // ObservationTypeCheck keeps the file readable; never a crash here
            Log.warn("Observation types could not be read", e);
            return List.of();
        }
    }

    /** The label for a layer's metadata; "unknown" for anything not read from a FITS header. */
    public static String of(MetaData m) {
        if (m instanceof FitsMetaData f)
            return label(f.getInstrument(), f.getDetector(), f.getMeasurement(), f.getDisplayName());
        return UNKNOWN;
    }

    public static String of(FitsMetaData.Observation o) {
        return label(o.instrument(), o.detector(), o.measurement(), o.displayName());
    }

    /**
     * The PUNCH product code ("CAM", "CQM", ...) in a name FitsMetaData built as "PUNCH CAM v0l"; empty
     * for anything else.
     */
    public static String punchProduct(String displayName) {
        if (!displayName.startsWith("PUNCH "))
            return "";
        String[] parts = displayName.split(" ");
        return parts.length > 1 && parts[1].length() == 3 ? parts[1] : "";
    }

    static String label(String instrument, String detector, String measurement, String displayName) {
        String product = punchProduct(displayName);
        for (Row r : rows) {
            // a product row is PUNCH's alone; without a product code nothing else can match it
            if (r.product() == null && !product.isEmpty())
                continue;
            if (r.matches(instrument, detector, measurement, product))
                return r.label();
        }
        return UNKNOWN;
    }

    private ObservationType() {}

}
