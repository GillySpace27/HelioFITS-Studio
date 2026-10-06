package org.helioviewer.jhv.metadata;

import java.net.URI;
import java.util.List;

import org.helioviewer.jhv.layers.ImageLayer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A saved LASCO pointing table says whether the probe behind it read every header (X9).
 *
 * <p>An empty table used to mean either "probed, nothing to lend" or "the probe failed and lent nothing".
 * The first should never be probed again; the second must be, or one bad run turns the correction off in
 * that session file for good. The marker lascoPointingComplete, written beside the table, tells them apart.
 * Files from before the marker keep their old reading for a table with entries and probe once on an empty one.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.metadata.LascoProbeMarkerCheck
 */
public final class LascoProbeMarkerCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JSONObject layer(JSONObject pointing, Boolean complete) {
        JSONObject jo = new JSONObject().put("lascoPointing", pointing);
        if (complete != null)
            jo.put(ImageLayer.PROBE_COMPLETE_KEY, complete.booleanValue());
        return jo;
    }

    private static boolean needsProbe(JSONObject layer) {
        return ImageLayer.needsProbe(layer.optJSONObject("lascoPointing"), ImageLayer.savedProbeComplete(layer));
    }

    public static void main(String[] args) {
        LascoPointing.forget();
        URI a = URI.create("https://lasco-www.nrl.navy.mil/lz/level_05/250920/c3/32840001.fts");
        URI b = URI.create("https://lasco-www.nrl.navy.mil/lz/level_05/250920/c3/32840002.fts");
        JSONObject empty = new JSONObject();
        JSONObject entries = new JSONObject().put("C2 2025/08/26 08:12:05.585",
                new JSONArray().put(-177.888).put(519.2).put(533.5).put("C3 32830760.fts"));

        // Failed probe: one header could not be read, so the run is not a complete answer.
        LascoPointing.probed(a, true);
        LascoPointing.probed(b, false);
        boolean complete = LascoPointing.allRead(List.of(a, b));
        expect("a probe that missed a header is recorded as incomplete", !complete);
        expect("an empty table from that probe is probed again on restore", needsProbe(layer(empty, complete)));
        expect("so is a table with entries from that probe", needsProbe(layer(entries, complete)));

        // The header comes back on the retry: now the answer is whole.
        LascoPointing.probed(b, true);
        complete = LascoPointing.allRead(List.of(a, b));
        expect("a later good read of the missed header makes the probe complete", complete);
        expect("an empty table from a complete probe is believed: no probe", !needsProbe(layer(empty, complete)));
        expect("a table with entries from a complete probe is believed", !needsProbe(layer(entries, complete)));

        // The marker round-trips through the layer's JSON exactly as written.
        expect("the marker reads back true", Boolean.TRUE.equals(ImageLayer.savedProbeComplete(layer(empty, true))));
        expect("the marker reads back false", Boolean.FALSE.equals(ImageLayer.savedProbeComplete(layer(empty, false))));

        // Files written before the marker: no marker at all.
        JSONObject old = layer(empty, null);
        expect("an old file has no marker", ImageLayer.savedProbeComplete(old) == null);
        expect("an old file with no lascoPointing key probes, as before", ImageLayer.needsProbe(null, null));
        expect("an old file with an empty table probes again: it may hide a failed run", needsProbe(old));
        expect("an old file with entries keeps them, as before", !needsProbe(layer(entries, null)));
        expect("a marker never rescues a missing table", ImageLayer.needsProbe(null, true));

        System.out.println(failures == 0 ? "LascoProbeMarkerCheck: ok" : "LascoProbeMarkerCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
