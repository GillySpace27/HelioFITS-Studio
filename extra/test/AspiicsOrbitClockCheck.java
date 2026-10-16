package org.helioviewer.jhv.gui.dialog;

import java.util.List;

/**
 * The ASPIICS dialog opens on the orbit that holds the master clock time.
 *
 * <p>The orbit boundaries are the aa_start_time and aa_end_time of P3SC's l3_orbit_time_ranges; the
 * times below are synthetic, written in the shapes that list may take (with and without an offset,
 * with a space or a 'T'), not copied from it.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.dialog.AspiicsOrbitClockCheck
 */
public final class AspiicsOrbitClockCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static int id(AspiicsDialog.Orbit orbit) {
        return orbit == null ? -1 : orbit.orbitId();
    }

    public static void main(String[] args) {
        long t0 = AspiicsDialog.orbitTime("2025-05-01T00:00:00");
        expect("plain ISO time parses as UTC", t0 == 1746057600000L);
        expect("a space instead of 'T' parses the same", AspiicsDialog.orbitTime("2025-05-01 00:00:00") == t0);
        expect("a +00:00 offset parses the same", AspiicsDialog.orbitTime("2025-05-01T00:00:00+00:00") == t0);
        expect("a Z suffix parses the same", AspiicsDialog.orbitTime("2025-05-01T00:00:00Z") == t0);
        expect("fractional seconds parse", AspiicsDialog.orbitTime("2025-05-01T00:00:00.500") == t0 + 500);
        expect("a +02:00 offset is honoured", AspiicsDialog.orbitTime("2025-05-01T02:00:00+02:00") == t0);
        expect("garbage is -1", AspiicsDialog.orbitTime("not a time") == -1);
        expect("empty is -1", AspiicsDialog.orbitTime("") == -1);

        List<AspiicsDialog.Orbit> orbits = List.of(
                new AspiicsDialog.Orbit(10, "2025-05-01T00:00:00", "2025-05-01T06:00:00"),
                new AspiicsDialog.Orbit(11, "2025-05-01 19:00:00", "2025-05-02 01:00:00"),
                new AspiicsDialog.Orbit(12, "2025-05-02T14:00:00+00:00", "2025-05-02T20:00:00+00:00"),
                new AspiicsDialog.Orbit(13, "", ""));
        long hour = 3600_000L;

        expect("a time inside an orbit picks it", id(AspiicsDialog.orbitFor(orbits, t0 + 3 * hour)) == 10);
        expect("an orbit's start is inside it", id(AspiicsDialog.orbitFor(orbits, t0)) == 10);
        expect("an orbit's end is inside it", id(AspiicsDialog.orbitFor(orbits, t0 + 6 * hour)) == 10);
        expect("a space-separated orbit is found", id(AspiicsDialog.orbitFor(orbits, t0 + 20 * hour)) == 11);
        expect("an offset orbit is found", id(AspiicsDialog.orbitFor(orbits, t0 + 40 * hour)) == 12);
        expect("between orbits, the nearer one", id(AspiicsDialog.orbitFor(orbits, t0 + 8 * hour)) == 10);
        expect("between orbits, the nearer one (later)", id(AspiicsDialog.orbitFor(orbits, t0 + 17 * hour)) == 11);
        expect("a tie goes to the later orbit", id(AspiicsDialog.orbitFor(orbits, t0 + 12 * hour + hour / 2)) == 11);
        expect("after the last orbit, the last one", id(AspiicsDialog.orbitFor(orbits, t0 + 1000 * hour)) == 12);
        expect("before the first orbit, the first one", id(AspiicsDialog.orbitFor(orbits, t0 - 1000 * hour)) == 10);
        expect("no readable orbit gives null", AspiicsDialog.orbitFor(List.of(orbits.get(3)), t0) == null);
        expect("an empty list gives null", AspiicsDialog.orbitFor(List.of(), t0) == null);

        System.out.println(failures == 0 ? "AspiicsOrbitClockCheck: all passed" : "AspiicsOrbitClockCheck: " + failures + " failed");
        if (failures != 0)
            System.exit(1);
    }

}
