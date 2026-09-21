package org.helioviewer.jhv.metadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONObject;

/**
 * What a LASCO layer worked out about pointing survives the session file, and a restore trusts it.
 *
 * <p>LascoPointingCheck covers the table itself. This one covers the decision the layer makes on the way
 * back in: whether the saved table is authoritative or the headers have to be read again. The two failure
 * modes are opposite and both costly. Treating an absent key as authoritative means a session restored
 * from its cached URI list never lends anything, and the 2025-08 C2 gap draws 178 degrees off. Treating an
 * empty table as unknown means every restore of every LASCO session re-reads every header, since an empty
 * table is the ordinary answer: only a request holding frames whose headers lack pointing produces entries.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.metadata.LascoSessionPointingCheck
 */
public final class LascoSessionPointingCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static LascoPointing.Frame frame(String detector, String name, String date, String time,
                                             double crota, double crpix1, double crpix2) {
        long milli = TimeUtils.parse(date.replace('/', '-') + 'T' + time.substring(0, 8));
        return new LascoPointing.Frame(name, detector, date, time, milli, 1024, 1024, crota, crpix1, crpix2,
                LascoPointing.isPlaceholder(crota, crota, crpix1, crpix2, 1024, 1024));
    }

    // A placeholder C2 header, as it comes out of an NRL level-0.5 file in the pointing gap.
    private static MetaDataContainer header(String date, String time) {
        Map<String, String> h = new HashMap<>();
        h.put("INSTRUME", "LASCO");
        h.put("DETECTOR", "C2");
        h.put("DATE-OBS", date);
        h.put("TIME-OBS", time);
        h.put("NAXIS1", "1024");
        h.put("NAXIS2", "1024");
        h.put("CRPIX1", "512.5");
        h.put("CRPIX2", "512.5");
        h.put("CROTA1", "0.00000000000");
        h.put("CROTA2", "0.00000000000");
        return LascoPointing.fill(new MapMetaDataContainer(h));
    }

    private static double crota(String date, String time) {
        return header(date, time).getDouble("CROTA2").orElse(Double.NaN);
    }

    public static void main(String[] args) {
        // No key: nothing has ever probed these URIs, so the restore has to.
        expect("a session file with no lascoPointing key asks for a probe", ImageLayer.needsProbe(null));

        // The regression this check exists for. A movie clear of any pointing gap lends nothing, so the
        // table it saves is empty -- and empty is a finding, not a blank. The writer emits the key only
        // on a run that actually probed, which is what makes the key's presence enough on its own.
        expect("an empty saved table is a result, not a gap: no probe",
                !ImageLayer.needsProbe(new JSONObject()));

        // A whole session's worth: probe, lend, save, quit, restore.
        LascoPointing.lend(List.of(
                frame("C2", "24000464.fts", "2025/08/26", "08:12:05.585", 0, 512.5, 512.5)), List.of(
                frame("C3", "32830760.fts", "2025/08/26", "08:42:05.464", -178.620, 519.2, 533.5)));
        JSONObject saved = LascoPointing.toJson("C2");
        expect("the run that lent writes a table into the session, got " + saved.length() + " entries",
                saved.length() == 1);
        expect("and a restore of it needs no probe", !ImageLayer.needsProbe(saved));

        LascoPointing.forget(); // stand in for the relaunch
        expect("in the new process, before the table is put back, the frame is unrotated",
                Math.abs(crota("2025/08/26", "08:12:05.585")) < 1e-9);

        LascoPointing.restore(saved);
        double back = crota("2025/08/26", "08:12:05.585");
        expect("after the restore it is C3's -178.620 + 0.732 = -177.888 again, got " + back,
                Math.abs(back + 177.888) < 1e-6);
        expect("and saving again writes the same table back out",
                LascoPointing.toJson("C2").similar(saved));

        // The ordinary movie: every header carries its own pointing, so nothing is lent and the table
        // written is empty. That is the case the empty-means-probe reading broke.
        LascoPointing.forget();
        LascoPointing.lend(List.of(
                frame("C2", "24001109.fts", "2025/08/31", "23:48:05.421", -178.576, 511.2, 507.5),
                frame("C2", "24001110.fts", "2025/09/01", "00:00:05.512", -178.577, 511.2, 507.5)), List.of());
        JSONObject none = LascoPointing.toJson("C2");
        expect("a movie with no gap frames saves an empty table, got " + none.length() + " entries",
                none.isEmpty());
        expect("and restoring that does not re-read every header", !ImageLayer.needsProbe(none));

        System.out.println(failures == 0 ? "LascoSessionPointingCheck: ok" : "LascoSessionPointingCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
