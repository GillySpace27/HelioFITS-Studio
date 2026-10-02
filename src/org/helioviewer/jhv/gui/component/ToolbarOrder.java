package org.helioviewer.jhv.gui.component;

import org.helioviewer.jhv.app.Settings;

/**
 * Which tools the toolbar shows, in what order, and which sit behind the More divider: the rules
 * and their persisted ids (ui.toolbar.order, ui.toolbar.seeded), moved out of ToolBar.java
 * unchanged (HS-8). Static only. Ids are persisted, so they are API: never rename one; a new tool
 * goes into SEED_ONCE and into DEFAULT_ORDER before MORE_DIVIDER.
 */
final class ToolbarOrder {

    static final String SEPARATOR = "---"; // a gap, not a control: allowed more than once

    /**
     * The cut between the bar and the More menu. At most one, and it is a place rather than a gap.
     *
     * <p>The order is one priority list. Everything before this is on the bar; everything after it
     * lives in More however wide the window is. Overflow then is not a second mechanism: a narrow
     * window moves the EFFECTIVE cut leftward, and More shows everything after it, in order. So an
     * icon pushed off the bar lands above the ones parked there on purpose, because that is where
     * it already was in the list, and the menu grows upward from the cut instead of reshuffling.
     */
    static final String MORE_DIVIDER = ">>>";
    static final String ORDER_KEY = "ui.toolbar.order";

    // The bar as it has always looked, and the fallback whenever the stored order is missing or
    // has rotted. Ids are persisted, so they are API: rename one and a saved bar loses that tool.
    /** Tools this bar has already been offered once, so declining one is not undone on the next launch. */
    private static final String SEEDED_KEY = "ui.toolbar.seeded";

    /**
     * Tools new enough that a saved bar omitting them means "did not exist yet", not "taken off".
     *
     * <p>An explicit list, and it has to be. The first attempt at this seeded everything in
     * DEFAULT_ORDER that a stored order lacked, which cannot tell a tool that postdates the bar
     * from one the user dragged off it, so a curated seven-button bar came back with fifteen. Add
     * an id here when the tool is introduced; remove it once nobody is running a build older than
     * that.
     */
    private static final java.util.Set<String> SEED_ONCE = java.util.Set.of("timelines", "sidebarLeft", "sidebarRight", "trackCme");

    static final String DEFAULT_ORDER = String.join("|",
            "present", SEPARATOR,
            "zoomIn", "zoomOut", "zoomFit", "zoomOne", SEPARATOR,
            "resetCamera", "resetAxis", "rotate90", SEPARATOR,
            "pan", "rotate", "axis", SEPARATOR,
            "track", "diffRotation", "corona", "multiview", "sidebarLeft", "timelines", "sidebarRight", SEPARATOR,
            "projection", "trackCme", "colour", "sequence", "grid", "camera", "annotate", SEPARATOR,
            MORE_DIVIDER,
            "refresh", "sdoCutout", "samp");

    /**
     * The stored order, dropped down to ids that still exist.
     *
     * <p>Edit used to be appended here when missing, because it is the way back and a bar you can
     * customise into a state with no way to customise it again is a trap. It is not a tool any
     * more: it is a fixed control in the bar's trailing corner, which closes that trap outright
     * rather than by patching every saved order. An "edit" left in an older settings file is
     * simply an id that no longer exists, and is dropped like any other.
     */
    static java.util.List<String> order(java.util.Set<String> known) {
        String stored = Settings.getProperty(ORDER_KEY);
        if (stored == null || stored.isBlank()) // a fresh bar is DEFAULT_ORDER, which has everything
            return resolveOrder(stored, known);

        // Before resolveOrder, which drops ids no tool answers to and "more" is now one of them.
        String migrated = migrateMore(stored, known);
        if (!migrated.equals(stored)) {
            stored = migrated;
            Settings.setProperty(ORDER_KEY, stored);
        }
        String seeded = Settings.getProperty(SEEDED_KEY);
        // Seeded into the stored ids, not the resolved ones. Resolving drops every id no tool answers
        // to on this launch, and not every tool is built on every launch (samp only with the SAMP hub
        // on), so writing the resolved list back would erase a tool for good because of one launch
        // without it. The stored string is the user's; this only ever inserts into it.
        java.util.List<String> raw = new java.util.ArrayList<>(java.util.Arrays.asList(stored.split("\\|")));
        java.util.List<String> placed = seedNewTools(raw, known, seeded);
        if (!placed.isEmpty()) {
            stored = String.join("|", raw);
            Settings.setProperty(ORDER_KEY, stored);
            Settings.setProperty(SEEDED_KEY,
                    seeded == null || seeded.isBlank() ? String.join("|", placed) : seeded + "|" + String.join("|", placed));
        }
        return resolveOrder(stored, known);
    }

    /**
     * A bar saved when More was a tool with four controls written into it.
     *
     * <p>The divider says the same thing and says it better, so it takes More's place in the order
     * and the three menu-only ones move in behind it: what was in More stays in More, and is now
     * draggable out of it. Annotation goes on the bar instead, because it is a palette now and
     * belongs with the other palette toggles. Operates on the raw stored string, because by the
     * time resolveOrder has run, "more" has been dropped as an id nothing answers to.
     *
     * <p>Cannot fire twice: it leaves no "more" behind. Pure, so ToolbarOrderCheck can pin it.
     */
    static String migrateMore(String stored, java.util.Set<String> known) {
        java.util.List<String> ids = new java.util.ArrayList<>(java.util.Arrays.asList(stored.split("\\|")));
        int at = ids.indexOf("more");
        if (at < 0 || ids.contains(MORE_DIVIDER))
            return stored;

        ids.set(at, MORE_DIVIDER);
        if (known.contains("annotate") && !ids.contains("annotate"))
            ids.add(at, "annotate"); // ahead of the divider: on the bar, beside the other palettes
        for (String id : java.util.List.of("refresh", "sdoCutout", "samp"))
            if (known.contains(id) && !ids.contains(id))
                ids.add(id);
        return String.join("|", ids);
    }

    /**
     * Put a tool that did not exist when this bar was saved where it belongs, once.
     *
     * <p>Without this a new tool is on nobody's bar but a fresh install's: everyone who has ever
     * opened the editor has a stored order, and an id missing from that order is a tool that only
     * exists down in the Tools menu. That is the wrong default for something added because the
     * fast path was missing.
     *
     * <p>Once, and recorded under its own key rather than inferred from the order, because
     * otherwise taking the tool off the bar would be undone on the next launch: the id would be
     * missing again and look new again. Seeded means offered, not kept.
     *
     * <p>Only ids in {@link #SEED_ONCE}, which is the whole difference between a tool that did not
     * exist when the bar was saved and one the user took off it. Everything else a stored order
     * omits, it omits deliberately.
     *
     * <p>Placed beside the neighbour it has in DEFAULT_ORDER, so it arrives in the company it was
     * designed for rather than at the end of the bar. Mutates {@code ids}; returns what it placed.
     */
    static java.util.List<String> seedNewTools(java.util.List<String> ids, java.util.Set<String> known, @javax.annotation.Nullable String seeded) {
        java.util.Set<String> already = new java.util.HashSet<>(ids);
        if (seeded != null && !seeded.isBlank())
            already.addAll(java.util.Arrays.asList(seeded.split("\\|")));

        java.util.List<String> defaults = java.util.Arrays.asList(DEFAULT_ORDER.split("\\|"));
        java.util.List<String> placed = new java.util.ArrayList<>();
        for (int i = 0; i < defaults.size(); i++) {
            String id = defaults.get(i);
            if (SEPARATOR.equals(id) || MORE_DIVIDER.equals(id) || !SEED_ONCE.contains(id)
                    || already.contains(id) || !known.contains(id))
                continue;
            int at = ids.size();
            for (int j = i - 1; j >= 0; j--) { // after the tool it follows by default
                int found = ids.indexOf(defaults.get(j));
                if (!SEPARATOR.equals(defaults.get(j)) && !MORE_DIVIDER.equals(defaults.get(j)) && found >= 0) {
                    at = found + 1;
                    break;
                }
            }
            ids.add(at, id);
            already.add(id);
            placed.add(id);
        }
        return placed;
    }

    /** The same, with the stored string handed in: pure, so ToolbarOrderCheck can pin the rules. */
    static java.util.List<String> resolveOrder(@javax.annotation.Nullable String stored, java.util.Set<String> known) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        boolean seenDivider = false;
        for (String id : (stored == null || stored.isBlank() ? DEFAULT_ORDER : stored).split("\\|")) {
            if (MORE_DIVIDER.equals(id)) {
                if (seenDivider) // a place, not a gap: a second one would make "after it" ambiguous
                    continue;
                seenDivider = true;
                ids.add(id);
            } else if (SEPARATOR.equals(id) || known.isEmpty() || known.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Where the bar stops and More begins, or the end of the list when there is no divider. */
    private static int cut(java.util.List<String> order) {
        int at = order.indexOf(MORE_DIVIDER);
        return at < 0 ? order.size() : at;
    }

    /** The ids the bar lays out, in order: everything before the cut. */
    static java.util.List<String> barIds(java.util.List<String> order) {
        return new java.util.ArrayList<>(order.subList(0, cut(order)));
    }

    /** The ids parked in More, in order: everything after the cut, gaps dropped. */
    static java.util.List<String> moreIds(java.util.List<String> order) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : order.subList(Math.min(cut(order) + 1, order.size()), order.size()))
            if (!SEPARATOR.equals(id) && !MORE_DIVIDER.equals(id))
                out.add(id);
        return out;
    }

    static void setOrder(java.util.List<String> ids) {
        Settings.setProperty(ORDER_KEY, String.join("|", ids));
        ToolBar.recreateCurrent();
    }

    static void resetOrder() {
        Settings.setProperty(ORDER_KEY, DEFAULT_ORDER);
        ToolBar.recreateCurrent();
    }

    /**
     * The controls an order puts on the bar, out of the ids handed in.
     *
     * <p>With {@link #missing} this is a partition, and it has to be: the Tools menu lists every
     * tool once, taking the ones on the bar as items that click them and the rest as themselves.
     * A tool in neither set would vanish from both the bar and the menu; one in both would be
     * listed twice, and the second copy would steal the control out of the first. Pure, so
     * ToolbarOrderCheck can hold the two sides against each other.
     */
    static java.util.Set<String> onBar(java.util.List<String> order) {
        java.util.Set<String> ids = new java.util.HashSet<>(order);
        ids.remove(SEPARATOR);
        ids.remove(MORE_DIVIDER);
        return ids;
    }

    /** Placed, whether on the bar or parked in More. The other side of {@link #missing}. */
    static java.util.Set<String> placed(java.util.List<String> order) {
        return onBar(order);
    }

    /** The other half: what exists and the order leaves off. */
    static java.util.List<String> missing(java.util.List<String> order, java.util.Collection<String> known) {
        java.util.Set<String> shown = onBar(order);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : known)
            if (!shown.contains(id))
                out.add(id);
        return out;
    }

    private ToolbarOrder() {}
}
