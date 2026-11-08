package org.helioviewer.jhv.gui.component;

import java.util.List;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.identify.Bodies;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.IdentifyHover;
import org.helioviewer.jhv.identify.IdentifySettings;

/**
 * The Identify panel's parts that need no window: the readout text says how far the pointer was
 * against how close it had to be, Reset to defaults restores the numbers and leaves the three main
 * switches alone, and the section numbers carry the side they count on (L1, R1).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.IdentifyPaletteCheck
 */
public final class IdentifyPaletteCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-identify-panel").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        Identifier.Match m = new Identifier.Match(new Bodies.Body("Moon", "MOON", 0.0025), "PUNCH PTM", 0.42, 0.26, 0.10, 0.00257, 12.5,
                Identifier.Qualifier.NONE);
        String text = IdentifyPalette.Readout.matchText(new IdentifyHover.Snapshot(1, List.of(m), List.of(), IdentifyHover.State.LIVE));
        expect("the readout names the body and its layer", text.contains("Moon") && text.contains("PUNCH PTM"));
        expect("it shows the separation against the allowed radius plus pointer tolerance",
                text.contains("separation 0.420° of 0.360° allowed") && text.contains("radius 0.260°") && text.contains("pointer 0.100°"));
        expect("a close body is given in km, with its distance from the Sun in the sky", text.contains("km") && text.contains("12.50° from the Sun"));
        expect("an empty snapshot reads (none)", IdentifyPalette.Readout.matchText(new IdentifyHover.Snapshot(0, List.of(), List.of(), IdentifyHover.State.AWAY)).equals("(none)"));

        IdentifySettings.setEnabled(true);
        IdentifySettings.set(IdentifySettings.TOLERANCE_PX, "40");
        IdentifySettings.set(IdentifySettings.PLUTO, "false");
        IdentifySettings.resetToDefaults();
        expect("reset restores the numbers and finer switches",
                IdentifySettings.tolerancePixels() == IdentifySettings.DEFAULT_TOLERANCE_PX && IdentifySettings.pluto());
        expect("reset leaves the main switches as they were", IdentifySettings.enabled());

        RightSidebar bar = RightSidebar.getInstance();
        bar.setCollapsed(false);
        for (String t : new String[]{"Alpha", "Beta"})
            bar.addSection(t, null, new javax.swing.JPanel(), () -> {});
        CollapsiblePane beta = PanelKeys.at(true, 2);
        expect("a right sidebar title carries R and its place", !PanelVisibility.numbers() || beta.toggleButton.getText().startsWith("R2. "));
        expect("the prefix helper writes the side", !PanelVisibility.numbers() || "L3. ".equals(PanelVisibility.numberPrefix(3, 'L')));

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private IdentifyPaletteCheck() {}
}
