package org.helioviewer.jhv.layers.selector;

import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.JPanel;

import org.helioviewer.jhv.layers.filters.FilterDetails;

// Shared 3-column GridBagLayout row layout for a FilterDetails widget, used by both
// ImageLayerRenderingPanel and ImageLayerGeometryPanel so the layout logic lives in one place.
final class FilterRowLayout {

    /**
     * A section's worth of rows, stacked in one grid.
     *
     * <p>The grid is per section rather than per panel so each section's labels align within
     * itself. They no longer align across sections, which is the point: a column of twenty labels
     * reads as one list, and the sections exist to say it is not one list.
     */
    @SuppressWarnings("serial")
    static JPanel rows(FilterDetails... details) {
        JPanel panel = new JPanel(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        c.gridy = 0;
        for (FilterDetails row : details) {
            addFilterRow(panel, c, row);
            c.gridy++;
        }
        return panel;
    }

    static void addFilterRow(Container container, GridBagConstraints c, FilterDetails details) {
        c.gridwidth = 1;

        c.gridx = 0;
        c.weightx = 0;
        c.weighty = 1;
        c.anchor = GridBagConstraints.LINE_END;
        c.fill = GridBagConstraints.NONE;
        container.add(details.getFirst(), c);

        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;
        container.add(details.getSecond(), c);

        c.gridx = 2;
        c.weightx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        c.fill = GridBagConstraints.NONE;
        container.add(details.getThird(), c);
    }

    private FilterRowLayout() {}
}
