package org.helioviewer.jhv.gui.component;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;

/**
 * The arithmetic behind a resizable palette, which is the half of it a person cannot check by
 * dragging: the mouse handling is visible the moment it is wrong, but a cap that is off by the
 * docking margin looks fine and still leaves the Camera palette lying across the sidebar.
 *
 * <p>The claim worth a run is the last one here. Capping and docking are two different pieces of
 * code reading the same canvas rectangle, and they only add up if the cap subtracts exactly what
 * docking puts back: a palette capped to the canvas and then docked into its corner must have its
 * far edge still inside the canvas, whatever the canvas is. That is the property that keeps a
 * palette off the sidebar, and it is asserted here over a range of canvas widths rather than
 * argued from the two formulas.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PaletteSizeCheck
 */
public final class PaletteSizeCheck {

    private static final int MARGIN = 12; // Palette.DOCK_MARGIN, restated so a change to it fails here
    private static final int MIN_WIDTH = 180;
    private static final int MIN_HEIGHT = 90;

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void checkCap() {
        Dimension natural = new Dimension(520, 420); // roughly the Camera palette, the case that motivated this

        Dimension roomy = Palette.capToCanvas(natural, new Rectangle(0, 0, 1200, 800));
        expect("a palette that already fits its canvas is left at its natural size", roomy.equals(natural));

        // The canvas is what is left of the window beside the sidebar, so this is the real case.
        Dimension tight = Palette.capToCanvas(natural, new Rectangle(300, 100, 460, 300));
        expect("a palette wider than the canvas is capped to the canvas less both margins",
                tight.width == 460 - 2 * MARGIN && tight.height == 300 - 2 * MARGIN);

        Dimension pinched = Palette.capToCanvas(natural, new Rectangle(0, 0, 100, 60));
        expect("a canvas smaller than the minimum caps down to the minimum, not to nothing",
                pinched.width == MIN_WIDTH && pinched.height == MIN_HEIGHT);

        expect("with no canvas on screen yet the natural size is used unchanged",
                Palette.capToCanvas(natural, null).equals(natural));
    }

    private static void checkZones() {
        int w = 300, h = 200;
        expect("the corners ask for the corner cursors",
                Palette.zoneAt(w, h, 0, 0) == Cursor.NW_RESIZE_CURSOR
                        && Palette.zoneAt(w, h, w - 1, 0) == Cursor.NE_RESIZE_CURSOR
                        && Palette.zoneAt(w, h, 0, h - 1) == Cursor.SW_RESIZE_CURSOR
                        && Palette.zoneAt(w, h, w - 1, h - 1) == Cursor.SE_RESIZE_CURSOR);
        expect("the edges ask for the edge cursors",
                Palette.zoneAt(w, h, 150, 0) == Cursor.N_RESIZE_CURSOR
                        && Palette.zoneAt(w, h, 150, h - 1) == Cursor.S_RESIZE_CURSOR
                        && Palette.zoneAt(w, h, 0, 100) == Cursor.W_RESIZE_CURSOR
                        && Palette.zoneAt(w, h, w - 1, 100) == Cursor.E_RESIZE_CURSOR);
        // Everything inboard of the border insets belongs to the controls: a press there must not
        // start a resize, or the palette would grow whenever a slider was dragged.
        expect("the interior asks for nothing", Palette.zoneAt(w, h, 150, 100) == Cursor.DEFAULT_CURSOR
                && Palette.zoneAt(w, h, 6, 6) == Cursor.DEFAULT_CURSOR
                && Palette.zoneAt(w, h, w - 7, h - 7) == Cursor.DEFAULT_CURSOR);
    }

    private static void checkDrag() {
        Rectangle start = new Rectangle(100, 100, 300, 200);

        Rectangle grown = Palette.resizeBounds(start, Cursor.SE_RESIZE_CURSOR, 50, 40);
        expect("dragging the bottom-right corner moves only the size",
                grown.equals(new Rectangle(100, 100, 350, 240)));

        // A leading edge has to move the origin too, or the palette slides instead of resizing.
        Rectangle pulled = Palette.resizeBounds(start, Cursor.NW_RESIZE_CURSOR, 50, 40);
        expect("dragging the top-left corner moves the origin with the size",
                pulled.equals(new Rectangle(150, 140, 250, 160)));

        expect("dragging one edge leaves the other axis alone",
                Palette.resizeBounds(start, Cursor.E_RESIZE_CURSOR, 60, 999).equals(new Rectangle(100, 100, 360, 200))
                        && Palette.resizeBounds(start, Cursor.S_RESIZE_CURSOR, 999, 60).equals(new Rectangle(100, 100, 300, 260)));

        expect("a trailing edge dragged past the minimum stops at the minimum",
                Palette.resizeBounds(start, Cursor.E_RESIZE_CURSOR, -1000, 0).equals(new Rectangle(100, 100, MIN_WIDTH, 200))
                        && Palette.resizeBounds(start, Cursor.S_RESIZE_CURSOR, 0, -1000).equals(new Rectangle(100, 100, 300, MIN_HEIGHT)));

        // The far edge is the one being held still, so it is the near edge that must stop moving.
        Rectangle squeezed = Palette.resizeBounds(start, Cursor.NW_RESIZE_CURSOR, 1000, 1000);
        expect("a leading edge dragged past the minimum pins the trailing edge",
                squeezed.width == MIN_WIDTH && squeezed.height == MIN_HEIGHT
                        && squeezed.x + squeezed.width == start.x + start.width
                        && squeezed.y + squeezed.height == start.y + start.height);
    }

    private static void checkCapAndDockAgree() {
        Dimension natural = new Dimension(520, 420);
        boolean inside = true;
        for (int canvasWidth = 200; canvasWidth <= 1600; canvasWidth += 37) {
            Rectangle canvas = new Rectangle(340, 90, canvasWidth, 700); // x: the sidebar's right edge
            Dimension capped = Palette.capToCanvas(natural, canvas);
            int x = canvas.x + canvas.width - capped.width - MARGIN; // exactly what dock() computes
            if (canvasWidth >= MIN_WIDTH + 2 * MARGIN && x < canvas.x)
                inside = false;
        }
        expect("a capped palette docked to the canvas corner never reaches back past the canvas", inside);
    }

    public static void main(String[] args) {
        checkCap();
        checkZones();
        checkDrag();
        checkCapAndDockAgree();

        System.out.println(failures == 0 ? "PaletteSizeCheck: PASS" : "PaletteSizeCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private PaletteSizeCheck() {}

}
