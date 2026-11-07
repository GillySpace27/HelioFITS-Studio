package org.helioviewer.jhv.layers;

import java.util.List;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.ModelAssets;

/**
 * Which 3D model an image layer's "Spacecraft" button shows (ObserverModels.match), against the
 * shipped model list. Observatory names as FitsMetaData gives them: TELESCOP ("SDO", "SDO/AIA",
 * "PUNCH 1-2-3-4" and "NSO-GONG" are in extra/test/data headers) or, for SECCHI, OBSRVTRY with
 * '_' made '-' ("STEREO-A"). Offline.
 */
public final class ObserverModelsCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        List<ModelAssets.Entry> list = ModelAssets.listed();

        same("SDO", "sdo", "SDO", list);
        same("SDO/AIA", "sdo", "SDO", list);
        same("SOHO", "soho", "SOHO", list);
        same("STEREO-A", "stereo", "STEREO Ahead", list);
        same("STEREO-B", "stereo", "STEREO Behind", list);
        same("Parker Solar Probe", "psp", "Parker Solar Probe", list);
        same("PSP", "psp", "Parker Solar Probe", list);
        none("STEREO", list); // which of the two is unknown, so no guess
        none("PUNCH 1-2-3-4", list);
        none("NSO-GONG", list);
        none("", list);

        expect(ObserverModels.halfSize(0) == 0.6 * Sun.MeanEarthDistance, "the outside view frames at least 0.6 au either side");
        expect(ObserverModels.halfSize(Sun.MeanEarthDistance) == 1.25 * Sun.MeanEarthDistance, "and holds an observer at 1 au with a margin");

        if (failures != 0) {
            System.out.println("ObserverModelsCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ObserverModelsCheck: PASS");
    }

    private static void same(String observatory, String id, String target, List<ModelAssets.Entry> list) {
        ObserverModels.Match m = ObserverModels.match(observatory, list);
        expect(m != null && m.entry().id().equals(id) && m.target().equals(target),
                "\"" + observatory + "\" shows " + id + " at " + target + " (got " + (m == null ? "none" : m.entry().id() + " at " + m.target()) + ")");
    }

    private static void none(String observatory, List<ModelAssets.Entry> list) {
        ObserverModels.Match m = ObserverModels.match(observatory, list);
        expect(m == null, "\"" + observatory + "\" has no model (got " + (m == null ? "none" : m.entry().id()) + ")");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
