package org.helioviewer.jhv.layers.selector;

import java.util.List;

import javax.annotation.Nullable;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

/**
 * Half of the layer registry, as a table.
 *
 * <p>There is one list of layers and three tables onto it, one per {@link Layer.Kind}: the images,
 * which are the observation; the overlays, which are drawn over it; and the viewpoint layers, which
 * decide where it is all seen from. They were one table until the defaults alone outnumbered the
 * images, and finding a layer meant reading past the grid, the timestamp, the field of view and the
 * miniview to reach it.
 *
 * <p>The split is a view, not a second registry: {@link Layers} still holds one ordered list and the
 * tables answer from it, so a layer reaches exactly one of them.
 */
@SuppressWarnings("serial")
final class LayersTableModel extends AbstractTableModel implements Layers.Listener, Reorderable {

    private final Layer.Kind kind;
    private List<Layer> rows;

    LayersTableModel(Layer.Kind _kind) {
        kind = _kind;
        rows = filtered();
        Layers.addListener(this);
    }

    private List<Layer> filtered() {
        return Layers.getLayers().stream().filter(layer -> layer.kind() == kind).toList();
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
        return LayersPanel.numberColumns();
    }

    @Nullable
    @Override
    public Object getValueAt(int row, int col) {
        return row >= 0 && row < rows.size() ? rows.get(row) : null;
    }

    /**
     * Drop a dragged row at another row's place.
     *
     * <p>Rows are positions in this table and the registry holds all three kinds in one list, so
     * the target is the dropped-on sibling's index there. A drop past the last row means after the
     * last sibling, not at the end of the registry, or a layer would jump over the other kinds.
     */
    @Override
    public void reorder(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= rows.size())
            return;
        List<Layer> all = Layers.getLayers();
        Layers.reorder(rows.get(fromIndex),
                toIndex < rows.size() ? all.indexOf(rows.get(toIndex)) : all.indexOf(rows.getLast()) + 1);
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
