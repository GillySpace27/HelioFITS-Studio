package org.helioviewer.jhv.gui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Moving a section up or down the right sidebar, which is list arithmetic and so is the part that
 * can silently corrupt the order without anything looking wrong on screen.
 *
 * <p>The failure this rules out is the classic one: removing before computing the destination, so
 * a move down lands one short, or an unclamped index at either end throwing and leaving the stack
 * half rebuilt. Everything else about the sidebar needs a window; this does not.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.RightSidebarOrderCheck
 */
public final class RightSidebarOrderCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static List<String> of(String... titles) {
        return new ArrayList<>(List.of(titles));
    }

    private static String move(List<String> titles, String title, int delta) {
        return String.join(",", RightSidebar.reordered(titles, title, delta));
    }

    public static void main(String[] args) {
        expect("up swaps with the one above",
                "Grid,Camera,HDR".equals(move(of("Camera", "Grid", "HDR"), "Grid", -1)));
        expect("down swaps with the one below",
                "Grid,Camera,HDR".equals(move(of("Camera", "Grid", "HDR"), "Camera", 1)));
        expect("down from the middle lands exactly one lower, not two",
                "Camera,HDR,Grid".equals(move(of("Camera", "Grid", "HDR"), "Grid", 1)));

        // The ends hold rather than throw or wrap.
        expect("up from the top is a no-op",
                "Camera,Grid,HDR".equals(move(of("Camera", "Grid", "HDR"), "Camera", -1)));
        expect("down from the bottom is a no-op",
                "Camera,Grid,HDR".equals(move(of("Camera", "Grid", "HDR"), "HDR", 1)));
        expect("a title that is not there changes nothing",
                "Camera,Grid".equals(move(of("Camera", "Grid"), "Fourier", 1)));
        expect("a single section cannot move",
                "Camera".equals(move(of("Camera"), "Camera", -1)));
        expect("an empty sidebar does not fall over",
                "".equals(move(of(), "Camera", 1)));

        // Nothing is ever lost or duplicated, whatever the move.
        List<String> five = of("Projection", "HDR", "Fourier", "Grid", "Camera");
        for (int delta : new int[]{-1, 1})
            for (String title : List.of("Projection", "HDR", "Fourier", "Grid", "Camera")) {
                List<String> moved = RightSidebar.reordered(new ArrayList<>(five), title, delta);
                expect("moving " + title + " by " + delta + " keeps all five, once each",
                        moved.size() == 5 && moved.containsAll(five));
            }

        // Repeated moves walk it to the end and stop there.
        List<String> walk = of("a", "b", "c");
        for (int i = 0; i < 5; i++)
            walk = RightSidebar.reordered(walk, "a", 1);
        expect("walking one down repeatedly parks it at the bottom", "b,c,a".equals(String.join(",", walk)));

        System.out.println(failures == 0 ? "RightSidebarOrderCheck: PASS" : "RightSidebarOrderCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private RightSidebarOrderCheck() {}

}
