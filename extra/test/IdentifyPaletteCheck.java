package org.helioviewer.jhv.gui.component;

import java.util.List;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.dialog.IdentifyTab;
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
        IdentifySettings.set(IdentifySettings.GLOW, "false");
        IdentifySettings.set(IdentifySettings.ICON_COLOR, "#112233");
        long writes = Settings.WRITES.sum();
        IdentifySettings.resetToDefaults();
        expect("reset writes the settings file once, not once per key (" + (Settings.WRITES.sum() - writes) + ")", Settings.WRITES.sum() - writes == 1);
        writes = Settings.WRITES.sum();
        IdentifySettings.resetToDefaults();
        expect("and a reset that changes nothing writes nothing", Settings.WRITES.sum() == writes);
        expect("reset restores the numbers and finer switches",
                IdentifySettings.tolerancePixels() == IdentifySettings.DEFAULT_TOLERANCE_PX && IdentifySettings.pluto());
        expect("reset leaves the main switches as they were", IdentifySettings.enabled());

        // The panel: the same 25 settings, under subheadings, none renamed and none lost
        java.util.Set<String> want = new java.util.TreeSet<>();
        for (java.lang.reflect.Field f : IdentifySettings.class.getFields())
            if (f.getType() == String.class && java.lang.reflect.Modifier.isStatic(f.getModifiers()) && ((String) f.get(null)).startsWith("identify."))
                want.add((String) f.get(null));
        javax.swing.JComponent root = IdentifyPalette.build();
        java.util.Set<String> have = new java.util.TreeSet<>();
        java.util.List<String> titles = new java.util.ArrayList<>();
        walk(root, have, titles);
        expect("the settings file names 25 identify keys (" + want.size() + ")", want.size() == 25);
        java.util.Set<String> lost = new java.util.TreeSet<>(want);
        lost.removeAll(have);
        expect("every one of them has a control in the panel" + (lost.isEmpty() ? "" : "; missing " + lost), lost.isEmpty());
        expect("the planet markers are still two boxes, so all four combinations can be set",
                have.contains(IdentifySettings.SYMBOLS_INSTEAD) && have.contains(IdentifySettings.SYMBOLS_ALWAYS));
        expect("the subheadings are Under the pointer, What to name, How it looks, Advanced, with the observers behind a disclosure " + titles,
                titles.equals(java.util.List.of("Under the pointer", "Observers used", "What to name", "How it looks", "Advanced")));
        expect("Advanced and the observers start closed, the others open", expanded(root, "Advanced") == Boolean.FALSE && expanded(root, "Observers used") == Boolean.FALSE
                && expanded(root, "Under the pointer") == Boolean.TRUE && expanded(root, "What to name") == Boolean.TRUE && expanded(root, "How it looks") == Boolean.TRUE);
        System.out.println("  info panel preferred width " + root.getPreferredSize().width + " px");
        // Measured headless, where the old single column was 343 px wide; a section steps in 12 px
        expect("and is no wider than the old column plus one section indent", root.getPreferredSize().width <= 343 + 12);

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

    private static void walk(java.awt.Component c, java.util.Set<String> keys, java.util.List<String> titles) {
        if (c instanceof javax.swing.JComponent jc && jc.getClientProperty(IdentifyTab.KEY) instanceof String k)
            keys.add(k);
        if (c instanceof CollapsiblePane p)
            titles.add(p.getTitle());
        if (c instanceof java.awt.Container box)
            for (java.awt.Component child : box.getComponents())
                walk(child, keys, titles);
    }

    private static Boolean expanded(java.awt.Component c, String title) {
        if (c instanceof CollapsiblePane p && p.getTitle().equals(title))
            return p.isExpanded();
        if (c instanceof java.awt.Container box)
            for (java.awt.Component child : box.getComponents()) {
                Boolean b = expanded(child, title);
                if (b != null)
                    return b;
            }
        return null;
    }

    private IdentifyPaletteCheck() {}
}
