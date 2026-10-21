package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JLabel;

import org.helioviewer.jhv.io.HeliadIngest;
import org.helioviewer.jhv.io.HeliadLibrary;

/**
 * Heliad adds to Studio and never takes anything away: on a computer with no Heliad library,
 * every download goes ahead as it always did, nothing is handed to Heliad, nothing is created
 * where Heliad keeps its settings, and the Heliad tab says there is no library instead of failing.
 * Gilly, 2026-10-20: Heliad and Peek are upgrades, never requirements.
 *
 * <p>Runs headless, so a dialog that should not appear would throw rather than wait for a click.
 * The Heliad app may be installed here (it is on Gilly's Mac); only the library's absence is
 * simulated, through a throwaway home.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.dialog.HeliadOptionalCheck
 */
public final class HeliadOptionalCheck {

    private static final Path FIXTURE = Path.of("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // These point Heliad's config somewhere this check cannot control; a temp home cannot hide it.
        for (String env : new String[]{"HELIAD_HOME", "XDG_CONFIG_HOME", "APPDATA"})
            if (System.getenv(env) != null && !System.getenv(env).isEmpty()) {
                System.out.println("SKIP: " + env + " is set, so the library lookup does not follow user.home");
                return;
            }
        Path home = Files.createTempDirectory("hfs-plain-home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.awt.headless", "true");

        expect("no Heliad library is found in an empty home", HeliadLibrary.find() == null);

        long end = System.currentTimeMillis(), start = end - 86_400_000L;
        HeliadFirst.Choice choice = HeliadFirst.offer(null, HeliadLibrary.Wanted.of("soho", "lasco", "C2", null), start, end);
        expect("a download goes ahead without asking (got " + choice + ")", choice == HeliadFirst.Choice.DOWNLOAD);

        File fits = FIXTURE.toFile();
        expect("fixture is present", fits.isFile());
        expect("a downloaded FITS file is not handed to Heliad", !HeliadIngest.offer(URI.create("https://example.org/a.fits"), fits));
        HeliadIngest.importCacheInBackground(); // must return quietly

        HeliadTab tab = new HeliadTab(() -> {}, () -> {}, () -> start, () -> end);
        tab.update(false);
        tab.setFront(true);
        expect("the Heliad tab says there is no library", labelStartingWith(tab, "No Heliad library"));
        expect("the Heliad tab offers nothing to load", !tab.canLoad());

        try (var walk = Files.walk(home)) {
            java.util.List<Path> made = walk.skip(1).filter(p -> p.getFileName().toString().toLowerCase().contains("heliad")).toList();
            expect("nothing Heliad-named is created in the home folder" + (made.isEmpty() ? "" : " (found " + made + ")"), made.isEmpty());
        }

        System.out.println(failures == 0 ? "HeliadOptionalCheck: ok" : "HeliadOptionalCheck: " + failures + " FAIL");
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
