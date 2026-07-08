package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.MainFrame;

// Inner container for the four nested sections shown under the "Image Layers" pane.
@SuppressWarnings("serial")
public final class ImageLayersPane extends JPanel {

    private final JPanel layerOptionsWrapper = new JPanel(new BorderLayout());
    private final JPanel geometryWrapper = new JPanel(new BorderLayout());
    private final JPanel manageWrapper = new JPanel(new BorderLayout());

    public ImageLayersPane(JComponent transport, JComponent layers) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        add(new CollapsiblePane("Transport control", transport, true));
        add(new CollapsiblePane("Layers", layers, true));
        add(new CollapsiblePane("Layer options", layerOptionsWrapper, true));
        add(new CollapsiblePane("Geometry / crop", geometryWrapper, false));
    }

    public JPanel getLayerOptionsWrapper() {
        return layerOptionsWrapper;
    }

    public JPanel getGeometryWrapper() {
        return geometryWrapper;
    }

    public JPanel getManageWrapper() {
        return manageWrapper;
    }
}
