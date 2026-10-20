package org.helioviewer.jhv.gui.dialog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.helioviewer.jhv.gui.ActionCatalog;

/**
 * The Welcome window's tips and demo card (HS-10). Every tip in resources/data/tips.json reads from
 * the classpath, has text with no em dash, and, when it names an ActionCatalog id, that id is
 * catalogued and the text spells out its menu path ("File > Copy Provenance"), so a renamed menu
 * item fails here instead of leaving a tip that points at nothing. The tips turn in order and wrap.
 * The demo scene is catalogued under Help and MenuBar adds it.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.dialog.TipsCheck
 */
public final class TipsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        List<Tips.Tip> tips = Tips.load();
        expect("tips.json reads from the classpath and holds at least three tips (" + tips.size() + ")", tips.size() >= 3);
        for (Tips.Tip tip : tips) {
            String head = tip.text().length() > 40 ? tip.text().substring(0, 40) + "..." : tip.text();
            expect("tip \"" + head + "\" has text and no em dash", !tip.text().isBlank() && tip.text().indexOf(0x2014) < 0);
            if (tip.action() == null)
                continue;
            ActionCatalog.Entry entry = ActionCatalog.get(tip.action());
            expect("  its action " + tip.action() + " is catalogued", entry != null);
            if (entry != null && !entry.menuPath().isEmpty()) {
                String path = entry.menuPath().replace("/", " > ");
                // The whole item: "View > Present" must not pass inside "View > Presentation".
                boolean named = Pattern.compile(Pattern.quote(path) + "(?![\\p{L}\\p{N}])").matcher(tip.text()).find();
                expect("  and its text names the menu path \"" + path + "\"", named);
            }
        }

        expect("the first opening shows the first tip", Tips.nextIndex(null, 5) == 0);
        expect("the next opening shows the next tip", Tips.nextIndex("1", 5) == 2);
        expect("after the last tip comes the first", Tips.nextIndex("4", 5) == 0);
        expect("a stored index past a shortened list wraps", Tips.nextIndex("9", 5) == 0);
        expect("an unreadable stored index starts over", Tips.nextIndex("x", 5) == 0);
        expect("no tips, no tip", Tips.nextIndex(null, 0) == -1);

        ActionCatalog.Entry demo = ActionCatalog.get("openDemo");
        expect("openDemo is catalogued under Help", demo != null && demo.menuPath().startsWith("Help/"));
        String menuBar = Files.readString(Path.of("src/org/helioviewer/jhv/gui/component/MenuBar.java"));
        expect("MenuBar adds openDemo", menuBar.contains("catalogItem(helpMenu, \"openDemo\")"));
        String welcome = Files.readString(Path.of("src/org/helioviewer/jhv/gui/dialog/WelcomeDialog.java"));
        expect("the Welcome window has the demo card", welcome.contains("run(\"openDemo\")"));

        System.out.println(failures == 0 ? "TipsCheck: ok" : "TipsCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private TipsCheck() {}

}
