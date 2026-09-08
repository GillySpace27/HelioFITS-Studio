package org.helioviewer.jhv.gui.component;

/**
 * The one predicate that decides whether the right sidebar fits its sections to itself or lets
 * them run off the side, which is the fault that shipped.
 *
 * <p>A palette's content was written for a window that packs to it, so it contains things with no
 * width of their own: an HTML note reports its whole paragraph as a preferred width and wraps only
 * when laid out narrower. The sidebar believed those preferred widths, laid the section out far
 * wider than itself, and, with sideways scrolling suppressed, put everything past its own edge
 * beyond reach. That included the section's own move and float controls, which is why the feature
 * looked like it had no controls at all.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.RightSidebarLayoutCheck
 */
public final class RightSidebarLayoutCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("content that fits is squeezed to the sidebar, so an HTML note wraps instead of running off",
                RightSidebar.tracksWidth(500, 320));
        expect("content exactly as wide as the sidebar still fits",
                RightSidebar.tracksWidth(320, 320));
        expect("content that cannot be squeezed any further scrolls instead of being clipped",
                !RightSidebar.tracksWidth(200, 320));
        expect("a sidebar dragged narrower than its content starts scrolling",
                !RightSidebar.tracksWidth(160, 400));
        expect("and dragged wide again goes back to fitting",
                RightSidebar.tracksWidth(900, 400));

        // The degenerate cases a layout pass can genuinely hand it, before anything is realized.
        expect("a viewport with no width yet does not claim to fit anything",
                !RightSidebar.tracksWidth(0, 320));
        expect("content with no minimum fits any viewport", RightSidebar.tracksWidth(0, 0));

        System.out.println(failures == 0 ? "RightSidebarLayoutCheck: PASS" : "RightSidebarLayoutCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private RightSidebarLayoutCheck() {}

}
