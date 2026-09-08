package org.helioviewer.jhv.layers.selector;

import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.component.ToolBar;

/**
 * What the sidebar shows for the Grid row: the way to the settings, not the settings.
 *
 * <p>The grid, Thomson sphere, celestial sphere, ecliptic and planet controls used to be here,
 * and then also in the Grid palette, two live copies of the same sliders bound to one layer. The
 * sidebar answers what is in the scene (the row's checkbox stays the master on/off); the palettes
 * answer how it is drawn, which is where Projection and HDR already live. So the settings moved
 * there, once, and this row points at them. Greyed with the layer like every other options panel,
 * which is fine: the toolbar button and the View menu reach the palette regardless.
 */
@SuppressWarnings("serial")
final class GridSettingsPointer extends JPanel {

    GridSettingsPointer() {
        super(new FlowLayout(FlowLayout.LEADING, 6, 4));
        JButton open = new JButton("Grid settings…");
        open.setToolTipText("Open the Grid palette: the same controls the toolbar's Grid button opens");
        open.addActionListener(e -> ToolBar.showGridPalette());
        add(open);
        add(new JLabel("Also the Grid button on the toolbar, or View > Grid Settings."));
    }

}
