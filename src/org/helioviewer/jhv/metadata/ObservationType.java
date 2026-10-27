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

    /**
     * @param labelBackgroundSubtracted the label once the monthly background is off the pixels (LASCO);
     *                                  null where the product has no such step and label always holds
     * @param labelLocalBackgroundSubtracted the label once a background the user chose (a local file
     *                                  or pair) is off the pixels; null to fall back to the one above
     */
    record Row(@Nullable String instrument, @Nullable String detector, @Nullable String measurement,
               @Nullable String measurementEndsWith, @Nullable String product, String label,
               @Nullable String labelBackgroundSubtracted, @Nullable String labelLocalBackgroundSubtracted, String source) {

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
                    r.optString("product", null), r.getString("label"),
                    r.optString("labelBackgroundSubtracted", null), r.optString("labelLocalBackgroundSubtracted", null),
                    r.getString("source")));
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
        return of(m, false);
    }

    /**
     * As {@link #of(MetaData)}, for a frame that may have had its LASCO monthly background taken off:
     * a fixed label read "includes the F corona" over pixels it had been subtracted from.
     */
    public static String of(MetaData m, boolean backgroundSubtracted) {
        return of(m, backgroundSubtracted, false);
    }

    /** As above; local when the background taken off was the user's own file or pair rather than NRL's. */
    public static String of(MetaData m, boolean backgroundSubtracted, boolean local) {
        if (m instanceof FitsMetaData f)
            return label(f.getInstrument(), f.getDetector(), f.getMeasurement(), f.getDisplayName(), backgroundSubtracted, local);
        return UNKNOWN;
    }

    public static String of(FitsMetaData.Observation o) {
        return of(o, false);
    }

    public static String of(FitsMetaData.Observation o, boolean backgroundSubtracted) {
        return label(o.instrument(), o.detector(), o.measurement(), o.displayName(), backgroundSubtracted, false);
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

    static String label(String instrument, String detector, String measurement, String displayName, boolean backgroundSubtracted) {
        return label(instrument, detector, measurement, displayName, backgroundSubtracted, false);
    }

    static String label(String instrument, String detector, String measurement, String displayName, boolean backgroundSubtracted, boolean local) {
        String product = punchProduct(displayName);
        for (Row r : rows) {
            // a product row is PUNCH's alone; without a product code nothing else can match it
            if (r.product() == null && !product.isEmpty())
                continue;
            if (!r.matches(instrument, detector, measurement, product))
                continue;
            if (backgroundSubtracted && local && r.labelLocalBackgroundSubtracted() != null)
                return r.labelLocalBackgroundSubtracted();
            return backgroundSubtracted && r.labelBackgroundSubtracted() != null ? r.labelBackgroundSubtracted() : r.label();
        }
        return UNKNOWN;
    }

    private ObservationType() {}

}
