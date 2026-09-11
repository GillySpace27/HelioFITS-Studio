package org.helioviewer.jhv.gui.component;

import java.util.List;
import java.util.Set;

/**
 * The rules that turn a saved toolbar order back into a toolbar.
 *
 * <p>The order is persisted as a list of ids, which makes those ids API: the arithmetic here is
 * what stands between a settings file written by an older build and a bar with a tool missing, a
 * tool twice, or a place on it that silently disappears.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.gui.component.ToolbarOrderCheck
 */
public final class ToolbarOrderCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final Set<String> KNOWN = Set.of("present", "zoomIn", "zoomOut", "grid", "more");

    private static List<String> resolve(String stored) {
        return ToolBar.resolveOrder(stored, KNOWN);
    }

    public static void main(String[] args) {
        List<String> fallback = resolve(null);
        expect("no stored order falls back to the default", fallback.equals(resolve("")));
        expect("and the default is the bar as it has always been, minus what this check pretends exists",
                fallback.equals(List.of("present", ToolBar.SEPARATOR, "zoomIn", "zoomOut",
                        ToolBar.SEPARATOR, ToolBar.SEPARATOR, ToolBar.SEPARATOR, ToolBar.SEPARATOR,
                        "grid", ToolBar.SEPARATOR, "more")));

        expect("a stored order is honoured as given",
                resolve("grid|zoomIn").equals(List.of("grid", "zoomIn")));
        expect("an id from a build that had a tool this one does not is dropped, not shown blank",
                resolve("grid|fourierWhatsit|zoomIn").equals(List.of("grid", "zoomIn")));

        expect("separators survive, and repeat as often as they were placed",
                resolve("grid|---|---|zoomIn")
                        .equals(List.of("grid", ToolBar.SEPARATOR, ToolBar.SEPARATOR, "zoomIn")));

        // Edit used to be forced onto the end here, because a bar with no editor on it could not
        // be edited back. It is a fixed corner control now, outside the order entirely, so the
        // order is simply obeyed: an "edit" in a settings file written by an older build names a
        // tool that no longer exists and is dropped like any other.
        expect("an order without Edit is left as it is: the corner control is not a tool",
                resolve("grid|zoomIn").equals(List.of("grid", "zoomIn")));
        expect("an \"edit\" left in an older settings file is dropped, like any other unknown id",
                resolve("edit|grid").equals(List.of("grid")));
        expect("an empty bar stays empty, and the corner control is still there",
                resolve("|").isEmpty());

        // Seeding a tool that did not exist when the bar was saved. Everyone who has ever opened
        // the editor has a stored order, so without this a new tool is on nobody's bar.
        // Its own KNOWN: the one above deliberately pretends most tools do not exist, to pin what
        // resolveOrder drops, and seeding is about tools that DO exist.
        Set<String> seedKnown = Set.of("pan", "rotate", "multiview", "timelines", "grid");
        java.util.List<String> bar = new java.util.ArrayList<>(java.util.List.of("pan", "rotate", "multiview", "grid"));
        java.util.List<String> placed = ToolBar.seedNewTools(bar, seedKnown, null);
        expect("a tool missing from a saved bar is placed", placed.contains("timelines"));
        expect("beside the neighbour it has in the default order, not at the end",
                bar.indexOf("timelines") == bar.indexOf("multiview") + 1);

        // The bit that matters most, and the bit the first version got wrong: a curated bar must
        // not be repopulated. Everything a stored order omits, it omits on purpose, EXCEPT the
        // handful of ids that postdate it.
        java.util.List<String> curated = new java.util.ArrayList<>(java.util.List.of("pan", "multiview", "grid"));
        ToolBar.seedNewTools(curated, seedKnown, null);
        expect("a tool the user took off the bar is not put back",
                !curated.contains("rotate") && curated.size() == 4);

        // Declining it has to stick, or every launch puts it back.
        java.util.List<String> without = new java.util.ArrayList<>(java.util.List.of("pan", "multiview"));
        expect("a tool already offered once is not offered again",
                !ToolBar.seedNewTools(without, seedKnown, "timelines").contains("timelines"));
        expect("and the bar is left as the user left it", !without.contains("timelines"));

        // Already on the bar is not "new", whatever the seeded list says.
        java.util.List<String> has = new java.util.ArrayList<>(java.util.List.of("timelines", "pan"));
        ToolBar.seedNewTools(has, seedKnown, null);
        expect("a tool already on the bar is never added twice",
                has.stream().filter("timelines"::equals).count() == 1);

        expect("every id in the default order is a tool the bar actually builds, or a separator",
                java.util.Arrays.stream(ToolBar.DEFAULT_ORDER.split("\\|"))
                        .allMatch(id -> ToolBar.SEPARATOR.equals(id) || DEFAULT_IDS.contains(id)));

        // The Tools menu lists every tool exactly once: the ones on the bar as items that click
        // them, the rest as the controls themselves. That is only true while these two are a
        // partition of what exists. A tool in neither would be gone from the bar AND the menu; one
        // in both would appear twice, and the second copy would take the control out of the first.
        for (String stored : new String[]{null, "", "grid|zoomIn|edit", "---|grid|---|---|more",
                "zoomIn|zoomIn|grid", "nosuchtool|grid", ToolBar.DEFAULT_ORDER}) {
            List<String> order = resolve(stored);
            Set<String> onBar = ToolBar.onBar(order);
            List<String> missing = ToolBar.missing(order, KNOWN);
            String label = stored == null ? "no stored order" : "\"" + stored + "\"";
            expect(label + ": nothing is both on the bar and missing from it",
                    missing.stream().noneMatch(onBar::contains));
            expect(label + ": every tool that exists is in exactly one of the two",
                    KNOWN.size() == missing.size() + KNOWN.stream().filter(onBar::contains).count());
            expect(label + ": the missing list never repeats one",
                    missing.size() == Set.copyOf(missing).size());
            expect(label + ": and never names a tool that does not exist",
                    KNOWN.containsAll(missing));
        }

        System.out.println(failures == 0 ? "ToolbarOrderCheck: PASS" : "ToolbarOrderCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // Every id createNewToolBar() registers. Written out rather than read off a live toolbar,
    // which would need a display: if the two drift, the default order names a tool that no longer
    // exists and that place on the bar silently disappears.
    private static final Set<String> DEFAULT_IDS = Set.of(
            "present", "zoomIn", "zoomOut", "zoomFit", "zoomOne",
            "resetCamera", "resetAxis", "rotate90",
            "pan", "rotate", "axis",
            "track", "diffRotation", "corona", "multiview", "timelines",
            "projection", "colour", "sequence", "grid", "camera",
            "more");

    private ToolbarOrderCheck() {}

}
