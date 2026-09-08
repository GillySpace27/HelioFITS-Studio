package org.helioviewer.jhv.layers.selector;

import java.awt.Component;

import javax.swing.JButton;
import javax.swing.JLabel;

import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.ToolBar;
import org.helioviewer.jhv.image.fourier.SequenceParams;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.view.ComputedView;

/**
 * The Fourier row of a layer's rendering panel: what filter is on this movie, and the way to the
 * palette that edits it, bound to THIS layer.
 *
 * <p>The row used to build a whole SequencePanel of its own, a second live copy of the controls
 * the Fourier palette also builds for the same layer (the class itself says it can serve one or
 * the other, never both at once, and the app was making it do both). The palette is the one home
 * now; this row reads the layer and opens the palette on it, so a layer selected in the sidebar
 * is one click from its filter without the palette having to guess which layer was meant.
 */
final class SequencePointer implements FilterDetails {

    private final JLabel title = new JLabel("Fourier ", JLabel.RIGHT);
    private final JLabel readout = new JLabel();
    private final JButton open = Buttons.flat("Open\u2026");

    SequencePointer(ImageLayer layer) {
        open.setToolTipText("Open the Fourier palette on this layer");
        open.addActionListener(e -> ToolBar.showSequencePalette(layer));
        refresh(layer);
    }

    /** What is applied to this movie: the filter's own description, or Off; running while a run is in flight. */
    void refresh(ImageLayer layer) {
        SequenceParams params = layer.getSequence();
        String text = params == null ? "Off" : params.describe();
        ComputedView computed = layer.getComputedView();
        if (computed != null && computed.isRunning())
            text += " (running)";
        readout.setText(text);
        readout.setToolTipText(text);
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return readout;
    }

    @Override
    public Component getThird() {
        return open;
    }

}
