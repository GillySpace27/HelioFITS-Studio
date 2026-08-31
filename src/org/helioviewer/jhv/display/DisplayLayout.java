package org.helioviewer.jhv.display;

public final class DisplayLayout {

    static Viewport fullViewport(int x, int y, int width, int height, int fullHeight) {
        return viewport(-1, x, y, width, height, fullHeight);
    }

    public static Viewport viewport(int idx, int x, int y, int width, int height, int fullHeight) {
        return new Viewport(idx, x, y, width, height, fullHeight);
    }

    private static final int[][] ROW_LAYOUTS = {
            {}, // 0
            {1}, // 1
            {2}, // 2
            {2, 1}, // 3
            {2, 2}, // 4
            {3, 2}, // 5
            {3, 3}  // 6
    };

    /**
     * Lay out {@code count} viewports inside a rectangle of the drawable.
     *
     * <p>The rectangle is not always the whole drawable: when the recording has a fixed aspect
     * the render area is inset to match it, and the untouched margin is left showing the clear
     * colour as letterbox bars. Everything downstream reads the viewport -- rendering, mouse
     * picking, overlays -- so insetting here is what makes the on-screen view and the recorded
     * frame the same picture, rather than each one deriving its own idea of the frame.
     *
     * @param originX     left edge of the render area within the drawable
     * @param originY     top edge of the render area within the drawable (AWT direction)
     * @param canvasHeight full drawable height, which is what GL viewport y is measured against
     */
    static Viewport[] viewports(int originX, int originY, int width, int height, int canvasHeight, int count) {
        if (count < 1 || count >= ROW_LAYOUTS.length) {
            return new Viewport[]{viewport(0, originX, originY, width, height, canvasHeight)};
        }

        Viewport[] vps = new Viewport[count];
        int[] layout = ROW_LAYOUTS[count];
        int numRows = layout.length;
        int idx = 0;
        int y = 0;

        for (int r = 0; r < numRows; r++) {
            int numCols = layout[r];
            int nextY = ((r + 1) * height) / numRows;
            int rowHeight = nextY - y;
            int x = 0;

            for (int c = 0; c < numCols; c++) {
                int nextX = ((c + 1) * width) / numCols;
                vps[idx] = viewport(idx, originX + x, originY + y, nextX - x, rowHeight, canvasHeight);
                x = nextX;
                idx++;
            }
            y = nextY;
        }
        return vps;
    }

    private DisplayLayout() {}
}
