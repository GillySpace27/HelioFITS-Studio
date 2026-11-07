package org.helioviewer.jhv.layers.selector;

/**
 * Which cursor the Image Layers list shows. Kept free of Swing so a headless check can pin it.
 *
 * <p>The open hand says "drag this row". It belongs on the body of an image row and not on the
 * columns that react to a plain click: the show checkbox, the drive radio and the remove x. Over
 * those the hand promised a drag where a click was what happened, so they get the ordinary arrow.
 * The hand closes while the mouse is pressed on a row and stays closed while the row is dragged.
 */
final class LayerListCursor {

    private LayerListCursor() {
    }

    /** True where a plain click does something, so the drag hand would be the wrong promise. */
    static boolean isClickControl(int col) {
        return col == LayersPanel.ENABLED_COL || col == LayersPanel.MASTER_COL || col == LayersPanel.REMOVE_COL;
    }

    /** True for the open hand; false for the default arrow. {@code col} is -1 outside the columns. */
    static boolean handOver(int col, boolean imageRow) {
        return imageRow && col >= 0 && !isClickControl(col);
    }

    enum Kind {ARROW, OPEN_HAND, CLOSED_HAND}

    /** The cursor at a cell: the open hand on the body of an image row, closed while the mouse is pressed there. */
    static Kind kindAt(int col, boolean imageRow, boolean pressed) {
        if (!handOver(col, imageRow))
            return Kind.ARROW;
        return pressed ? Kind.CLOSED_HAND : Kind.OPEN_HAND;
    }
}
