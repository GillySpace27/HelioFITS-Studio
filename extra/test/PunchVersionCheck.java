package org.helioviewer.jhv.io;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;

/**
 * PUNCH v0m is on the SDAC archive beside v0k and v0l, and a search finds it.
 *
 * <p>The archive file name is the one a real v0m frame carries in its own FILENAME card,
 * PUNCH_L3_PAM_20250918234400_v0m.fits (read 2026-11-19 from a frame Studio fetched on 2026-11-04).
 * "Latest" keeps only the newest version present, so a range holding v0l and v0m loads v0m; asking
 * for v0l still loads v0l.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.PunchVersionCheck
 */
public final class PunchVersionCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static PunchClient.DataItem item(String version) {
        return new PunchClient.DataItem("f", URI.create("https://example.invalid/f"), 0, version);
    }

    public static void main(String[] args) {
        Matcher m = PunchClient.FILE_PATTERN.matcher("<a href=\"PUNCH_L3_PAM_20250918234400_v0m.fits\">");
        expect("a v0m archive listing entry is recognised", m.find() && "0m".equals(m.group(3)));

        List<PunchClient.DataItem> mixed = List.of(item("0k"), item("0m"), item("0l"));
        expect("Latest picks v0m over v0l and v0k", "0m".equals(PunchClient.targetVersion(mixed, PunchClient.LATEST_VERSION)));
        expect("a blank version means Latest too", "0m".equals(PunchClient.targetVersion(mixed, "")));
        expect("asking for an older version still gets it", "0l".equals(PunchClient.targetVersion(mixed, "0l")));
        // The dialog offers "v0m"; the archive name parses to "0m". They used to be compared as they were,
        // so picking any version by name found 0 frames and only "Latest" worked.
        expect("picking v0m in the dialog keeps the 0m frames", "0m".equals(PunchClient.targetVersion(mixed, "v0m")));
        expect("picking v0l in the dialog keeps the 0l frames", "0l".equals(PunchClient.targetVersion(mixed, " v0l ")));
        expect("an empty range has no version", PunchClient.targetVersion(List.of(), PunchClient.LATEST_VERSION) == null);

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
    }
}
