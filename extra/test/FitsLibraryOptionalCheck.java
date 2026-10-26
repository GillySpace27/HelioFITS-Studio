package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JLabel;

import org.helioviewer.jhv.io.FitsCoreIngest;
import org.helioviewer.jhv.io.FitsLibrary;

/**
 * HelioFITS Core adds to Studio and never takes anything away: on a computer with no FITS library,
 * every download goes ahead as it always did, nothing is handed to HelioFITS Core, nothing is created
 * where HelioFITS Core keeps its settings, and the FITS Library tab says there is no library instead of failing.
 * Gilly, 2026-10-20: the library app (then Heliad) and Peek are upgrades, never requirements.
 *
 * <p>Runs headless, so a dialog that should not appear would throw rather than wait for a click.
 * The HelioFITS Cache Manager may be installed here (it is on Gilly's Mac); only the library's absence is
 * simulated, through a throwaway home.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.dialog.FitsLibraryOptionalCheck
 */
public final class FitsLibraryOptionalCheck {

    private static final Path FIXTURE = Path.of("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // These point HelioFITS Core's config somewhere this check cannot control; a temp home cannot hide it.
        for (String env : new String[]{"HFCORE_HOME", "HELIAD_HOME", "XDG_CONFIG_HOME", "APPDATA"})
            if (System.getenv(env) != null && !System.getenv(env).isEmpty()) {
                System.out.println("SKIP: " + env + " is set, so the library lookup does not follow user.home");
                return;
            }
        Path home = Files.createTempDirectory("hfs-plain-home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.awt.headless", "true");

        expect("no FITS library is found in an empty home", FitsLibrary.find() == null);

        long end = System.currentTimeMillis(), start = end - 86_400_000L;
        FitsLibraryFirst.Choice choice = FitsLibraryFirst.offer(null, FitsLibrary.Wanted.of("soho", "lasco", "C2", null), start, end);
        expect("a download goes ahead without asking (got " + choice + ")", choice == FitsLibraryFirst.Choice.DOWNLOAD);

        File fits = FIXTURE.toFile();
        expect("fixture is present", fits.isFile());
        expect("a downloaded FITS file is not handed to HelioFITS Core", !FitsCoreIngest.offer(URI.create("https://example.org/a.fits"), fits));
        FitsCoreIngest.importCacheInBackground(); // must return quietly

        FitsLibraryTab tab = new FitsLibraryTab(() -> {}, () -> {}, () -> start, () -> end);
        tab.update(false);
        tab.setFront(true);
        expect("the FITS Library tab says there is no library", labelStartingWith(tab, "No FITS library"));
        expect("the FITS Library tab offers nothing to load", !tab.canLoad());

        try (var walk = Files.walk(home)) {
            java.util.List<Path> made = walk.skip(1).filter(p -> p.getFileName().toString().toLowerCase().matches(".*(heliad|hfcore|heliofits).*")).toList();
            expect("nothing HelioFITS Core-named is created in the home folder" + (made.isEmpty() ? "" : " (found " + made + ")"), made.isEmpty());
        }

        System.out.println(failures == 0 ? "FitsLibraryOptionalCheck: ok" : "FitsLibraryOptionalCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean labelStartingWith(Component c, String prefix) {
        if (c instanceof JLabel l && l.getText() != null && l.getText().startsWith(prefix))
            return true;
        if (c instanceof Container parent)
            for (Component child : parent.getComponents())
                if (labelStartingWith(child, prefix))
                    return true;
        return false;
    }

}
