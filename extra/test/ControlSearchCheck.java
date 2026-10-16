package org.helioviewer.jhv.gui.search;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.Action;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.DesktopIntegration;

/**
 * Help > Search Controls (HS-15): the query ranks as described, and every result resolves.
 *
 * <p>First the matching and ranking on made-up items, with no window and no catalog. Then the real
 * index: every catalogued command is offered and resolves through ActionCatalog.get; every listed
 * control is a name the code assigns (read from the source, as TourCheck reads tour targets) with a
 * catalogued reveal; every name the code assigns is a command, a listed control or one of the few
 * named here as not searchable. Building the catalog reads the menu shortcut mask, which throws
 * HeadlessException without a display; run-checks.sh then reruns this with one.
 */
public final class ControlSearchCheck {

    /** Names the code assigns that are not controls to search for. */
    private static final Set<String> NOT_SEARCHED = Set.of(
            "tour",                 // the spotlight's own window
            "searchControlsWindow", // the search's own window
            "helpMenu");            // a menu, which the screen menu bar on macOS draws outside the window

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static ControlIndex.Item item(boolean action, String id, String title, String where, String words) {
        return new ControlIndex.Item(action, id, title, where, words, null);
    }

    private static List<String> ids(List<ControlIndex.Item> items) {
        return items.stream().map(ControlIndex.Item::id).toList();
    }

    private static String top(String query, List<ControlIndex.Item> items) {
        List<ControlIndex.Item> hits = ControlIndex.search(query, items);
        return hits.isEmpty() ? "(none)" : hits.get(0).id();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-search").toString()); // never the real settings

        // Matching and ranking, on items made up here.
        List<ControlIndex.Item> made = List.of(
                item(true, "zoomIn", "Zoom In", "View menu", ""),
                item(true, "zoomFit", "Zoom to Fit", "View menu", ""),
                item(false, "outfit", "Outfit", "Toolbar", ""),
                item(false, "projectionReset", "Reset view", "Projection palette", "warp crop zoom defaults"),
                item(false, "projectionZoom", "Zoom", "Projection palette", "magnification"));
        expect("an empty query lists everything, in index order", ids(ControlIndex.search("  ", made)).equals(ids(made)));
        List<String> zoom = ids(ControlIndex.search("zoom", made));
        expect("'zoom': the exact title first, then titles starting with it, then other fields: " + zoom,
                zoom.equals(List.of("projectionZoom", "zoomIn", "zoomFit", "projectionReset")));
        expect("matching ignores case", ids(ControlIndex.search("ZOOM", made)).equals(zoom));
        expect("'fit': a word start in a title beats a match inside a word: " + ids(ControlIndex.search("fit", made)),
                ids(ControlIndex.search("fit", made)).equals(List.of("zoomFit", "outfit")));
        expect("every word must match: 'reset zoo' finds only Reset view: " + ids(ControlIndex.search("reset zoo", made)),
                ids(ControlIndex.search("reset zoo", made)).equals(List.of("projectionReset")));
        expect("'magni' matches a control's words", top("magni", made).equals("projectionZoom"));
        expect("no match, no results", ControlIndex.search("xyzzy", made).isEmpty());
        expect("ties keep index order: 'view' lists the View menu commands in order before nothing else",
                ids(ControlIndex.search("view menu", made)).equals(List.of("zoomIn", "zoomFit")));
        expect("wordStart after punctuation", ControlIndex.wordStart("box-cox lambda", "cox") && !ControlIndex.wordStart("boxcox", "cox"));
        expect("plain() drops '...' and the ellipsis character",
                ControlIndex.plain("Open Session...").equals("Open Session") && ControlIndex.plain("New PUNCH Layer…").equals("New PUNCH Layer"));
        expect("menu(): a label with '/' keeps its menu: " + ControlIndex.menu("Movie/Play/Pause Movie", "Play/Pause Movie"),
                ControlIndex.menu("Movie/Play/Pause Movie", "Play/Pause Movie").equals("Movie menu"));
        expect("menu(): a submenu reads with '>': " + ControlIndex.menu("File/Open Recent/Clear Menu", "Clear Menu"),
                ControlIndex.menu("File/Open Recent/Clear Menu", "Clear Menu").equals("File > Open Recent menu"));
        expect("menu(): no menu reads Command", ControlIndex.menu("", "x").equals("Command"));

        // The real index. From here the catalog is built (a display is needed).
        org.helioviewer.jhv.app.Platform.init();
        List<ControlIndex.Item> base = ControlIndex.base();
        Set<String> names = TourCheck.assignedNames();
        Set<String> catalog = new LinkedHashSet<>();
        for (ActionCatalog.Entry e : ActionCatalog.all())
            catalog.add(e.id());

        Set<String> actionIds = new LinkedHashSet<>();
        Set<String> controlIds = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> dashes = new LinkedHashSet<>();
        int duplicates = 0;
        for (ControlIndex.Item it : base) {
            if (!(it.action() ? actionIds : controlIds).add(it.id()))
                duplicates++;
            if (it.action() ? ActionCatalog.get(it.id()) == null
                    : !names.contains(it.id()) || (it.reveal() != null && ActionCatalog.get(it.reveal()) == null))
                unresolved.add(it.id());
            if ((it.title() + it.where() + it.words()).indexOf(0x2014) >= 0) // U+2014
                dashes.add(it.id());
        }
        expect("every result resolves to a command or a named control with a catalogued reveal, unresolved " + unresolved,
                unresolved.isEmpty());
        expect("no id twice in the index: " + duplicates, duplicates == 0);
        Set<String> both = new LinkedHashSet<>(controlIds);
        both.retainAll(catalog);
        expect("no listed control is also a command, both " + both, both.isEmpty());
        Set<String> offered = new LinkedHashSet<>(catalog);
        offered.remove(CommandPalette.ID);
        expect("every catalogued command but the search itself is offered: " + actionIds.size() + " of " + offered.size(),
                actionIds.equals(offered));
        Set<String> unlisted = new LinkedHashSet<>(names);
        unlisted.removeAll(catalog);
        unlisted.removeAll(controlIds);
        unlisted.removeAll(NOT_SEARCHED);
        unlisted.removeIf(n -> !n.matches("\\w+")); // the toolbar order's separator and More divider
        expect("every name the code assigns is a command or a listed control, unlisted " + unlisted, unlisted.isEmpty());
        expect("every toolbar control is listed", controlIds.containsAll(ActionCatalog.TOOLBAR_CONTROLS));
        expect("no em dash in a title, place or word list, in " + dashes, dashes.isEmpty());

        // Queries a user types, against the real index.
        expect("'take the tour' finds Take the Tour first", top("take the tour", base).equals("takeTour"));
        expect("'noise gate' finds the Filters control first", top("noise gate", base).equals("sequence"));
        expect("'RHEF' finds the Filters control", ids(ControlIndex.search("RHEF", base)).contains("sequence"));
        expect("'knee' finds the HDR control", ids(ControlIndex.search("knee", base)).contains("colour"));
        expect("'dome' finds Project onto the sky", ids(ControlIndex.search("dome", base)).contains("projectionSky"));
        List<String> record = ids(ControlIndex.search("record", base));
        expect("'record' finds the command and the button: " + record, record.contains("record") && record.contains("transportRecord"));

        // The menu item and its shortcut, which no other command takes.
        ActionCatalog.Entry search = ActionCatalog.get(CommandPalette.ID);
        expect("searchControls is catalogued under Help", search != null && search.menuPath().equals("Help/Search Controls..."));
        expect("MenuBar adds searchControls to a menu", names.contains(CommandPalette.ID));
        KeyStroke key = search == null ? null : (KeyStroke) search.action().getValue(Action.ACCELERATOR_KEY);
        expect("its shortcut is the menu shortcut key with K: " + key,
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_K, DesktopIntegration.menuShortcutMask).equals(key));
        Map<KeyStroke, String> taken = new HashMap<>();
        Set<String> clashes = new LinkedHashSet<>();
        for (ActionCatalog.Entry e : ActionCatalog.all()) {
            Object k = e.action().getValue(Action.ACCELERATOR_KEY);
            if (k instanceof KeyStroke ks) {
                String other = taken.put(ks, e.id());
                if (other != null)
                    clashes.add(other + " and " + e.id() + " (" + ks + ")");
            }
        }
        expect("no two catalogued commands share a shortcut, clashes " + clashes, clashes.isEmpty());
        ActionCatalog.Entry reveal = ActionCatalog.get("showProjection");
        expect("showProjection is catalogued with no menu", reveal != null && reveal.menuPath().isEmpty());

        System.out.println(failures == 0 ? "ControlSearchCheck: ok" : "ControlSearchCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
