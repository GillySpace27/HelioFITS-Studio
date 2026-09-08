package org.helioviewer.jhv.layers.selector;

import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * What the sidebar shows for a layer whose settings live in a palette: the way there, not a copy.
 *
 * <p>The grid's and the camera's controls used to be here, and then also in their palettes: two
 * live copies of the same widgets bound to one layer, and for the camera two panels competing for
 * the one refresh hook. The sidebar answers what is in the scene (the row's checkbox stays the
 * master on/off); the palettes answer how it is drawn, which is where Projection and HDR already
 * live. So the settings moved there, once, and this row points at them. Greyed with the layer like
 * every other options panel, which is fine: the toolbar button and the View menu reach the
 * palette regardless.
 */
@SuppressWarnings("serial")
final class PalettePointer extends JPanel {

    PalettePointer(String buttonText, String note, Runnable open) {
        super(new FlowLayout(FlowLayout.LEADING, 6, 4));
        JButton button = new JButton(buttonText);
        button.setToolTipText("Open the palette: the same controls its toolbar button opens");
        button.addActionListener(e -> open.run());
        add(button);
        add(new JLabel(note));
    }

}
