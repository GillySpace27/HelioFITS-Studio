package org.helioviewer.jhv.gui.component;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * A locked header shows one padlock, not four refusing buttons.
 *
 * <p>Every sidebar section carried up, down, across and pop-out, dimmed whenever the panels were
 * locked, which is nearly always. Gilly asked for them gone unless unlocked, with a single control
 * left that offers the unlock when clicked, as the dimmed buttons did.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PanelLockHeaderCheck
 */
public final class PanelLockHeaderCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("jhv-panel-lock").toString());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(org.helioviewer.jhv.io.Directories.SETTINGS.getPath()));

        JPanel bar = new JPanel();
        JButton[] movers = {new JButton("up"), new JButton("down"), new JButton("across"), new JButton("out")};
        for (JButton b : movers) {
            PanelLock.register(b);
            bar.add(b);
        }
        AbstractButton padlock = PanelLock.standIn("Test");
        bar.add(padlock);

        expect("locked by default", PanelLock.isLocked());
        expect("locked, none of the four movers shows", !movers[0].isVisible() && !movers[1].isVisible() && !movers[2].isVisible() && !movers[3].isVisible());
        expect("locked, the one padlock does", padlock.isVisible());

        PanelLock.setLocked(false);
        expect("unlocked, all four movers show", movers[0].isVisible() && movers[1].isVisible() && movers[2].isVisible() && movers[3].isVisible());
        expect("unlocked, the padlock steps aside", !padlock.isVisible());

        PanelLock.setLocked(true);
        expect("locked again, back to the one padlock", padlock.isVisible() && !movers[3].isVisible());

        System.out.println(failures == 0 ? "PanelLockHeaderCheck: PASS" : "PanelLockHeaderCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PanelLockHeaderCheck() {}
}
