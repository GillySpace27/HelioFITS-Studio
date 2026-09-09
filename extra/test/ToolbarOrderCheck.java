package org.helioviewer.jhv.gui.component;

import java.util.List;
import java.util.Set;

/**
 * The rules that turn a saved toolbar order back into a toolbar.
 *
 * <p>The order is persisted as a list of ids, which makes those ids API: the arithmetic here is
 * what stands between a settings file written by an older build and a bar with a tool missing, a
 * tool twice, or no way to get the editor back.
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

    private static final Set<String> KNOWN = Set.of("present", "zoomIn", "zoomOut", "grid", "more", "edit");

    private static List<String> resolve(String stored) {
        return ToolBar.resolveOrder(stored, KNOWN);
    }

    public static void main(String[] args) {
        List<String> fallback = resolve(null);
        expect("no stored order falls back to the default", fallback.equals(resolve("")));
        expect("and the default is the bar as it has always been, minus what this check pretends exists",
                fallback.equals(List.of("present", ToolBar.SEPARATOR, "zoomIn", "zoomOut",
                        ToolBar.SEPARATOR, ToolBar.SEPARATOR, ToolBar.SEPARATOR, ToolBar.SEPARATOR,
                        "grid", ToolBar.SEPARATOR, "more", ToolBar.SEPARATOR, "edit")));

        expect("a stored order is honoured as given",
                resolve("grid|zoomIn|edit").equals(List.of("grid", "zoomIn", "edit")));
        expect("an id from a build that had a tool this one does not is dropped, not shown blank",
                resolve("grid|fourierWhatsit|zoomIn|edit").equals(List.of("grid", "zoomIn", "edit")));

        expect("separators survive, and repeat as often as they were placed",
                resolve("grid|---|---|zoomIn|edit")
                        .equals(List.of("grid", ToolBar.SEPARATOR, ToolBar.SEPARATOR, "zoomIn", "edit")));

        // The one rule that is not "do as you are told": a bar with no editor on it cannot be
        // edited back, so Edit is put on the end rather than left off.
        expect("an order without Edit gets it back, at the end",
                resolve("grid|zoomIn").equals(List.of("grid", "zoomIn", "edit")));
        expect("an order with Edit is not given a second one",
                resolve("edit|grid").equals(List.of("edit", "grid")));
        expect("an empty bar is still editable", resolve("|").equals(List.of("edit")));

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
            "track", "diffRotation", "corona", "multiview",
            "projection", "colour", "sequence", "grid", "camera",
            "more", "edit");

    private ToolbarOrderCheck() {}

}
