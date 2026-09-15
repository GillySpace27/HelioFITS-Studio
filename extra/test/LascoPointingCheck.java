package org.helioviewer.jhv.metadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.time.TimeUtils;

/**
 * A native LASCO frame with no pointing borrows CROTA from the nearest frame of either telescope that has
 * some, and CRPIX only from a frame of its own telescope and size.
 *
 * <p>Frame names, times and values are NRL's level-0.5 frames from 2025-08. On 08-31 the C2 placeholder
 * pointing (CROTA 0, CRPIX at the array centre) gives way to the real one at 23:48; the day also holds
 * 24001094, a 512x512 binned frame with placeholder CRPIX 256.5, nearer in time than any real frame, which
 * once lent CROTA 0 to the whole morning. On 08-26 C2 has no pointing at all while C3 does.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.metadata.LascoPointingCheck
 */
public final class LascoPointingCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static Map<String, String> c2(String date, String time, String crota, String crpix1, String crpix2) {
        Map<String, String> h = new HashMap<>();
        h.put("TELESCOP", "SOHO");
        h.put("INSTRUME", "LASCO");
        h.put("DETECTOR", "C2");
        h.put("DATE-OBS", date);
        h.put("TIME-OBS", time);
        h.put("NAXIS", "2");
        h.put("NAXIS1", "1024");
        h.put("NAXIS2", "1024");
        h.put("CTYPE1", "SOLAR-X");
        h.put("CTYPE2", "SOLAR-Y");
        h.put("CUNIT1", "ARCSEC");
        h.put("CUNIT2", "ARCSEC");
        h.put("CDELT1", "11.9");
        h.put("CDELT2", "11.9");
        h.put("CRPIX1", crpix1);
        h.put("CRPIX2", crpix2);
        h.put("CROTA1", crota);
        h.put("CROTA2", crota);
        return h;
    }

    private static double angle(Map<String, String> h) {
        var m = new FitsMetaData(new MapMetaDataContainer(h)).getWcsHeader().imageToPlane;
        return Math.toDegrees(Math.atan2(m.m10, m.m00));
    }

    private static LascoPointing.Frame frame(String detector, String name, String date, String time, int size,
                                             double crota, double crpix1, double crpix2) {
        long milli = TimeUtils.parse(date.replace('/', '-') + 'T' + time.substring(0, 8));
        return new LascoPointing.Frame(name, detector, date, time, milli, size, size, crota, crpix1, crpix2,
                LascoPointing.isPlaceholder(crota, crota, crpix1, crpix2, size, size));
    }

    public static void main(String[] args) throws Exception {
        // Building any FitsMetaData initialises Sun, which asks SPICE for Earth's position; as PunchNameCheck.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("jhv-lasco-pointing").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        expect("a binned frame with CRPIX 256.5 and CROTA 0 counts as a placeholder",
                LascoPointing.isPlaceholder(0, 0, 256.5, 256.5, 512, 512));
        expect("a full frame with CRPIX 256.5 does not",
                !LascoPointing.isPlaceholder(0, 0, 256.5, 256.5, 1024, 1024));

        LascoPointing.lend(List.of(
                frame("C2", "24001004.fts", "2025/08/31", "00:00:05.586", 1024, 0, 512.5, 512.5),
                frame("C2", "24001094.fts", "2025/08/31", "20:36:05.000", 512, 0, 256.5, 256.5),
                frame("C2", "24001108.fts", "2025/08/31", "23:36:06.830", 1024, 0, 512.5, 512.5),
                frame("C2", "24001109.fts", "2025/08/31", "23:48:05.421", 1024, -178.576, 511.2, 507.5),
                frame("C2", "24001110.fts", "2025/09/01", "00:00:05.512", 1024, -178.577, 511.2, 507.5)), List.of());

        double near = angle(c2("2025/08/31", "23:36:06.830", "0.00000000000", "512.5", "512.5"));
        expect("a placeholder 12 min before real pointing borrows CROTA -178.576, got " + near, Math.abs(near + 178.576) < 1e-6);

        double far = angle(c2("2025/08/31", "00:00:05.586", "0.00000000000", "512.5", "512.5"));
        expect("one a day earlier borrows from the real frame, not the nearer binned placeholder, got " + far,
                Math.abs(far + 178.576) < 1e-6);

        double own = angle(c2("2025/09/01", "00:00:05.512", "-178.577", "511.2", "507.5"));
        expect("a frame with real pointing keeps its own, got " + own, Math.abs(own + 178.577) < 1e-6);

        double unlent = angle(c2("2025/08/25", "12:00:05.000", "0.00000000000", "512.5", "512.5"));
        expect("a placeholder nothing was lent to stays unrotated, got " + unlent, Math.abs(unlent) < 1e-9);

        Map<String, String> jp2 = c2("2025/08/31", "23:36:06.830", "0.00000000000", "512.5", "512.5");
        jp2.put("HV_SOURCE_PROGRAM", "HV_LAS_C2_WRITE_HVS2");
        double hv = angle(jp2);
        expect("a Helioviewer JP2 is never rotated, got " + hv, Math.abs(hv) < 1e-9);

        // no C2 pointing anywhere in the request: C3 lends its roll plus the mounting offset
        LascoPointing.lend(List.of(
                frame("C2", "24000464.fts", "2025/08/26", "08:12:05.585", 1024, 0, 512.5, 512.5)), List.of(
                frame("C3", "32830760.fts", "2025/08/26", "08:42:05.464", 1024, -178.620, 519.2, 533.5)));
        double fromC3 = angle(c2("2025/08/26", "08:12:05.585", "0.00000000000", "512.5", "512.5"));
        expect("with no C2 lender, CROTA is C3's -178.620 + 0.732 = -177.888, got " + fromC3, Math.abs(fromC3 + 177.888) < 1e-6);

        // both telescopes lend: the nearer in time wins
        LascoPointing.lend(List.of(
                frame("C2", "24001108.fts", "2025/08/31", "23:36:06.830", 1024, 0, 512.5, 512.5),
                frame("C2", "24001109.fts", "2025/08/31", "23:48:05.421", 1024, -178.576, 511.2, 507.5)), List.of(
                frame("C3", "32831350.fts", "2025/08/31", "23:40:05.000", 1024, -179.300, 519.2, 533.5)));
        double nearer = angle(c2("2025/08/31", "23:36:06.830", "0.00000000000", "512.5", "512.5"));
        expect("C3 4 min away beats C2 12 min away: -179.300 + 0.732 = -178.568, got " + nearer, Math.abs(nearer + 178.568) < 1e-6);

        System.out.println(failures == 0 ? "LascoPointingCheck: ok" : "LascoPointingCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
