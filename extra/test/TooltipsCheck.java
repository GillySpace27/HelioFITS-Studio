package org.helioviewer.jhv.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JSlider;

import org.helioviewer.jhv.display.MapMode;

import org.json.JSONObject;

/**
 * Tooltips kept as data (resources/data/tooltips.json, Tooltips). The file parses; every tooltip
 * has text and no em dash; every key is either a name the code gives a component (as TourCheck
 * reads them, plus the Projection palette's "projection" + MapMode names, which are built at run
 * time), an "Owner/label" key, or a "*&#47;label" fallback. Then, on a panel built here: an
 * unnamed control is keyed by its owner and its own text, or the label just before it; a file
 * entry fills a control with no tooltip, "*&#47;Close" fills any Close button, a named control
 * with no entry gets its catalogued command's description, and a tooltip the code set is never
 * replaced. The report lists a control with no tooltip, with its key, and leaves out menu items
 * and scroll bar arrows.
 *
 * <p>Constructing the catalog reads the menu shortcut mask, which throws HeadlessException
 * without a display; run-checks.sh then reruns this with one.
 */
public final class TooltipsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** A panel of this program's own, so the controls in it have an owner: "TooltipsCheck". */
    @SuppressWarnings("serial")
    private static final class Owner extends JPanel {}

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-tooltips").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();

        Path file = Path.of("resources/data/tooltips.json");
        Map<String, String> tips = Tooltips.parse(new JSONObject(Files.readString(file)));
        expect("tooltips.json parses and has entries (" + tips.size() + ")", tips.size() >= 20);
        expect("Tooltips.load() reads the same file from the classpath", Tooltips.load().equals(tips));

        Set<String> names = org.helioviewer.jhv.gui.search.TourCheck.assignedNames();
        for (MapMode m : MapMode.values())
            names.add("projection" + m.name()); // ProjectionPaletteContent: item.setName("projection" + el.name())
        for (Map.Entry<String, String> e : tips.entrySet()) {
            String k = e.getKey();
            expect("\"" + k + "\" has text and no em dash", !e.getValue().isBlank() && (k + e.getValue()).indexOf(0x2014) < 0);
            int slash = k.indexOf('/');
            if (slash < 0)
                expect("  and names a component the code names", names.contains(k));
            else
                expect("  and has an owner and a label", slash > 0 && slash < k.length() - 1);
        }

        // Keys
        Owner panel = new Owner();
        JButton loop = new JButton("Loop");
        JLabel label = new JLabel("Time step: ");
        JSlider slider = new JSlider();
        JButton plain = new JButton("Something new");
        JButton close = new JButton("Close");
        JButton named = new JButton("x");
        named.setName("imageLayersNew");
        JButton catalogued = new JButton("y");
        catalogued.setName("zoomFit");
        JCheckBox coded = new JCheckBox("Solar axis");
        coded.setToolTipText("set in code");
        JMenuItem item = new JMenuItem("Menu item");
        JScrollBar bar = new JScrollBar();
        for (java.awt.Component c : List.of(loop, label, slider, plain, close, named, catalogued, coded, item, bar))
            panel.add(c);
        expect("a button's key is owner/text: " + Tooltips.key(loop), "TooltipsCheck/Loop".equals(Tooltips.key(loop)));
        expect("a slider's key is owner/the label before it, without the colon: " + Tooltips.key(slider),
                "TooltipsCheck/Time step".equals(Tooltips.key(slider)));
        expect("a named control's key is its name", "imageLayersNew".equals(Tooltips.key(named)));

        // Filling
        Tooltips.fill(panel);
        expect("a file entry fills a named control", tips.get("imageLayersNew").equals(named.getToolTipText()));
        expect("*/Close fills any Close button: " + close.getToolTipText(), tips.get("*/Close").equals(close.getToolTipText()));
        ActionCatalog.Entry zoomFit = ActionCatalog.get("zoomFit");
        expect("a named control with no entry gets its command's description: " + catalogued.getToolTipText(),
                zoomFit != null && zoomFit.description().equals(catalogued.getToolTipText()));
        expect("a tooltip set in code is kept", "set in code".equals(coded.getToolTipText()));
        expect("a menu item is left alone", item.getToolTipText() == null);

        // The report
        List<String> missing = Tooltips.missing(panel);
        expect("the report lists a control with no tooltip, with its key: " + missing,
                missing.stream().anyMatch(s -> s.startsWith("TooltipsCheck/Something new ")));
        expect("and not one that has a tooltip", missing.stream().noneMatch(s -> s.startsWith("TooltipsCheck/Close ")));
        expect("and not a menu item or a scroll bar's arrows",
                missing.stream().noneMatch(s -> s.contains("Menu item") || s.contains("ArrowButton") || s.contains("JScrollBar")));

        String menuBar = Files.readString(Path.of("src/org/helioviewer/jhv/gui/component/MenuBar.java"));
        expect("Help has Report Controls Without Tooltips", menuBar.contains("Tooltips.logReport()"));
        String main = Files.readString(Path.of("src/org/helioviewer/jhv/HFStudio.java"));
        expect("startup installs the tooltips", main.contains("Tooltips.install()"));

        System.out.println(failures == 0 ? "TooltipsCheck: ok" : "TooltipsCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private TooltipsCheck() {}

}
