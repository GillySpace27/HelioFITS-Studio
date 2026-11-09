package org.helioviewer.jhv.layers.selector;

/**
 * Which cursor the layer lists show. Kept free of Swing so a headless check can pin it.
 *
 * <p>The open hand says "drag this row" and sits on the grip only, because only the grip starts a
 * drag; it closes while the mouse is pressed there and stays closed through the drag. The pointing
 * hand sits on the columns that react to a plain click: the show checkbox, the drive radio and the
 * remove x. Everywhere else is the ordinary arrow.
 */
final class LayerListCursor {

    private LayerListCursor() {
    }

    /** True where a plain click does something. */
    static boolean isClickControl(int col) {
        return col == LayersPanel.ENABLED_COL || col == LayersPanel.MASTER_COL || col == LayersPanel.REMOVE_COL;
    }

    /** True over the grip of a row. {@code col} is -1 outside the columns. */
    static boolean handOver(int col, boolean anyRow) {
        return anyRow && col == LayersPanel.HANDLE_COL;
    }

    enum Kind {ARROW, POINTER, OPEN_HAND, CLOSED_HAND}

    /** The cursor at a cell of a row; {@code onRow} is false below the last row. */
    static Kind kindAt(int col, boolean onRow, boolean pressed) {
        if (handOver(col, onRow))
            return pressed ? Kind.CLOSED_HAND : Kind.OPEN_HAND;
        return onRow && isClickControl(col) ? Kind.POINTER : Kind.ARROW;
    }
}
