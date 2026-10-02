package org.helioviewer.jhv.gui.search;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.helioviewer.jhv.gui.ActionCatalog;

import org.json.JSONObject;

/**
 * Every guided tour step can be shown: its JSON parses, it has a title and a body, its target is a
 * name the code gives a component, and its action, when it has one, is a catalogued action.
 *
 * <p>"A name the code gives a component" is read from the source, since the window cannot be built
 * here: every setName("literal") under src/, every catalog id MenuBar names an item by
 * (catalogItem, which sets the item's name to the id), and every toolbar id (ToolBar.register sets
 * the control's name to its id; DEFAULT_ORDER lists them). A name built at run time is not seen,
 * so a tour should target a literal one.
 *
 * <p>Constructing the catalog reads the menu shortcut mask, which throws HeadlessException without
 * a display; run-checks.sh then reruns this with one, as it does ActionCatalogCheck.
 */
public final class TourCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static Set<String> assignedNames() throws Exception {
        Set<String> names = new TreeSet<>();
        Pattern setName = Pattern.compile("\\.setName\\(\"(\\w+)\"\\)");
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = setName.matcher(Files.readString(p));
                while (m.find())
                    names.add(m.group(1));
            }
        }
        Matcher m = Pattern.compile("catalogItem\\(\\w+, \"(\\w+)\"\\)")
                .matcher(Files.readString(Path.of("src/org/helioviewer/jhv/gui/component/MenuBar.java")));
        while (m.find())
            names.add(m.group(1));
        java.lang.reflect.Field order = Class.forName("org.helioviewer.jhv.gui.component.ToolbarOrder").getDeclaredField("DEFAULT_ORDER");
        order.setAccessible(true);
        names.addAll(List.of(((String) order.get(null)).split("\\|")));
        return names;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-tour").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();

        Path dir = Path.of("resources/data/tours");
        if (!Files.isDirectory(dir)) {
            expect(dir + " readable from the repository root (run from there)", false);
            System.exit(1);
        }
        Set<String> names = assignedNames();
        List<Path> tours;
        try (Stream<Path> files = Files.list(dir)) {
            tours = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        expect("at least one tour in " + dir + ", found " + tours.size(), !tours.isEmpty());

        for (Path file : tours) {
            String tour = file.getFileName().toString();
            List<Tour.Step> steps;
            try {
                steps = Tour.parse(new JSONObject(Files.readString(file)));
            } catch (RuntimeException e) {
                expect(tour + " parses: " + e.getMessage(), false);
                continue;
            }
            expect(tour + " has steps: " + steps.size(), !steps.isEmpty());
            for (int i = 0; i < steps.size(); i++) {
                Tour.Step s = steps.get(i);
                String at = tour + " step " + (i + 1) + " (" + s.target() + ")";
                expect(at + " has a title and a body", !s.title().isBlank() && !s.body().isBlank());
                expect(at + " has no em dash", (s.title() + s.body()).indexOf(0x2014) < 0); // U+2014
                expect(at + " targets a name the code assigns", names.contains(s.target()));
                if (s.action() != null)
                    expect(at + " action " + s.action() + " is catalogued", ActionCatalog.get(s.action()) != null);
            }
        }

        // The tour Help > Take the Tour starts loads from the classpath, as the jar holds it.
        expect("Tour.load(" + Tour.GETTING_STARTED + ") reads it from the classpath", !Tour.load(Tour.GETTING_STARTED).isEmpty());
        ActionCatalog.Entry take = ActionCatalog.get("takeTour");
        expect("takeTour is catalogued under Help", take != null && take.menuPath().equals("Help/Take the Tour"));
        expect("MenuBar adds takeTour to a menu", names.contains("takeTour"));

        // The card goes beside the cut-out, inside the window, and never over it.
        Rectangle area = new Rectangle(0, 0, 1200, 800);
        Dimension card = new Dimension(300, 150);
        Rectangle middle = new Rectangle(400, 300, 100, 40);
        Rectangle r = Spotlight.beside(card, middle, area);
        expect("card right of a cut-out with room: " + r, r.x >= middle.x + middle.width && area.contains(r));
        Rectangle edge = new Rectangle(1050, 100, 100, 40);
        r = Spotlight.beside(card, edge, area);
        expect("card left of a cut-out at the right edge: " + r, r.x + r.width <= edge.x && area.contains(r));
        Rectangle huge = new Rectangle(20, 20, 1160, 760);
        r = Spotlight.beside(card, huge, area);
        expect("card inside a cut-out that fills the window: " + r, huge.contains(r));
        r = Spotlight.beside(card, null, area);
        expect("card centred with no cut-out: " + r, r.x == 450 && r.y == 325);

        System.out.println(failures == 0 ? "TourCheck: ok" : "TourCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
