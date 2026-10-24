package org.helioviewer.jhv.movie;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.view.View;
import org.json.JSONObject;

/**
 * Who an export credits, per instrument: resources/data/attribution.json, a byte copy of
 * GillySpace27.github.io heliosoftware/spec/attribution.json (docs/spec/README.md). Its credits
 * map gives each instrument the line an export writes and the team's acknowledgement; its
 * credits_rule says how the lines of several layers are joined.
 */
public final class InstrumentCredit {

    private static final JSONObject ATTRIBUTION = load();

    private InstrumentCredit() {}

    static JSONObject load() {
        try (InputStream is = FileUtils.getResource("/data/attribution.json")) {
            return JSONUtils.get(is);
        } catch (IOException | RuntimeException e) { // InstrumentCreditCheck keeps the file readable; never a crash here
            Log.warn("attribution.json could not be read", e);
            return new JSONObject();
        }
    }

    /**
     * The attribution.json credits key for a layer's observatory, instrument, detector and display
     * name as FitsMetaData reads them (TELESCOP, INSTRUME up to its first underscore, DETECTOR), or
     * null when none applies.
     */
    @Nullable
    static String key(String observatory, String instrument, String detector, String displayName) {
        String o = observatory.toUpperCase(Locale.ROOT), i = instrument.toUpperCase(Locale.ROOT), d = detector.toUpperCase(Locale.ROOT);
        if (i.equals("AIA"))
            return "SDO/AIA";
        if (i.contains("HMI"))
            return "SDO/HMI";
        if (i.equals("LASCO") || d.equals("C2") || d.equals("C3"))
            return "SOHO/LASCO";
        if (i.equals("EIT"))
            return "SOHO/EIT";
        if (i.equals("SECCHI") || Set.of("COR1", "COR2", "EUVI", "HI1", "HI2").contains(d))
            return "STEREO/SECCHI";
        if (i.equals("SUVI"))
            return "GOES/SUVI";
        if (i.equals("COSMO K-CORONAGRAPH") || i.contains("KCOR") || i.contains("K-COR"))
            return "MLSO/K-Cor";
        if (i.equals("EUI"))
            return "Solar Orbiter/EUI";
        if (i.equals("XRT"))
            return "Hinode/XRT";
        if (i.contains("ASPIICS") || o.contains("ASPIICS"))
            return "Proba-3/ASPIICS";
        if (o.contains("PUNCH") || displayName.startsWith("PUNCH "))
            return "PUNCH";
        return null;
    }

    /** The key of the frame a layer shows, or null. On the event thread. */
    @Nullable
    public static String key(ImageLayer layer) {
        View.ImageData data = layer.getImageData();
        MetaData m = data == null ? null : data.metaData();
        return m instanceof FitsMetaData f ? key(f.getObservatory(), f.getInstrument(), f.getDetector(), f.getDisplayName()) : null;
    }

    @Nullable
    private static JSONObject entry(@Nullable String key) {
        JSONObject credits = ATTRIBUTION.optJSONObject("credits");
        return key == null || credits == null ? null : credits.optJSONObject(key);
    }

    /** The team's acknowledgement for a key, verbatim, or null when the source gives none. */
    @Nullable
    static String acknowledgement(@Nullable String key) {
        JSONObject e = entry(key);
        return e == null || e.isNull("acknowledgement") ? null : e.optString("acknowledgement", null);
    }

    /** The export credit line for the enabled layers' keys, in layer order (attribution.json credits_rule). */
    static String line(List<String> keys) {
        Set<String> lines = new LinkedHashSet<>();
        for (String k : keys) {
            JSONObject e = entry(k);
            if (e != null && !e.isNull("credit"))
                lines.add(e.getString("credit"));
        }
        return lines.isEmpty() ? ATTRIBUTION.optString("credit_fallback", "") : String.join(" ", lines);
    }
}
