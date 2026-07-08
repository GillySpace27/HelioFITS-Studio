package org.helioviewer.jhv.gui.component;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

// Inner container for the four nested sections shown under the "Image Layers" pane.
// The layer-options and geometry wrappers are owned and filled by MainFrame's
// LayerOptionSections controller; this pane only assembles the four CollapsiblePanes.
@SuppressWarnings("serial")
public final class ImageLayersPane extends JPanel {

    public ImageLayersPane(JComponent transport, JComponent layers, JPanel layerOptionsWrapper, JPanel geometryWrapper) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        add(new CollapsiblePane("Transport control", transport, true));
        add(new CollapsiblePane("Layers", layers, true));
        add(new CollapsiblePane("Layer options", layerOptionsWrapper, true));
        add(new CollapsiblePane("Geometry / crop", geometryWrapper, false));
    }
}
