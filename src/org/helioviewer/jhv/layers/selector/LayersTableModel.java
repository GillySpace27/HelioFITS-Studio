package org.helioviewer.jhv.layers.selector;

import java.util.List;

import javax.annotation.Nullable;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

/**
 * Half of the layer registry, as a table.
 *
 * <p>There is one list of layers and two tables onto it: the image layers, which are the
 * observation, and everything else, which is drawn over it. They were one table until the seven
 * default overlays alone outnumbered the images, and finding a layer meant reading past the grid,
 * the timestamp, the field of view and the miniview to reach it.
 *
 * <p>The split is a view, not a second registry: {@link Layers} still holds one ordered list and
 * both tables answer from it, so a layer cannot be in both or in neither.
 */
@SuppressWarnings("serial")
final class LayersTableModel extends AbstractTableModel implements Layers.Listener, Reorderable {

    private final boolean images;
    private List<Layer> rows;

    LayersTableModel(boolean _images) {
        images = _images;
        rows = filtered();
        Layers.addListener(this);
    }

    private List<Layer> filtered() {
        return Layers.getLayers().stream().filter(layer -> layer instanceof ImageLayer == images).toList();
    }

    // Recomputed on every structural change and cached, so a paint never rebuilds the list and
    // never sees a row count that disagrees with the event JTable is in the middle of handling.
    private List<Layer> refresh() {
        return rows = filtered();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return LayersPanel.NUMBER_COLUMNS;
    }

    @Nullable
    @Override
    public Object getValueAt(int row, int col) {
        return row >= 0 && row < rows.size() ? rows.get(row) : null;
    }

    @Override
    public void reorder(int fromIndex, int toIndex) {
        if (!images)
            return; // only image layers have an order that means anything; the overlays table cannot drag
        // Image layers occupy the front of the registry, so a row here IS its index there.
        Layers.reorderImageLayer(fromIndex, toIndex);
        refresh();
        fireTableDataChanged();
    }

    @Override
    public void layerAdded(int index, Layer layer) {
        int row = refresh().indexOf(layer);
        if (row >= 0)
            fireTableRowsInserted(row, row);
    }

    @Override
    public void layerRemoved(int index, Layer layer) {
        int row = rows.indexOf(layer); // the stale list still holds it; the registry already dropped it
        refresh();
        if (row >= 0)
            fireTableRowsDeleted(row, row);
    }

    @Override
    public void layersCleared() {
        refresh();
        fireTableDataChanged();
    }

    @Override
    public void nameUpdated(Layer layer) {
        int row = rows.indexOf(layer);
        if (row >= 0)
            fireTableCellUpdated(row, LayersPanel.NAME_COL);
    }

    @Override
    public void layerUpdated(Layer layer) {
        int row = rows.indexOf(layer);
        if (row >= 0)
            fireTableRowsUpdated(row, row);
    }

    @Override
    public void timeUpdated(Layer layer) {
        int row = rows.indexOf(layer);
        if (row >= 0)
            fireTableCellUpdated(row, LayersPanel.TIME_COL);
    }

    public void updateCell(int row, int col) {
        if (row >= 0)
            fireTableCellUpdated(row, col);
    }

}
