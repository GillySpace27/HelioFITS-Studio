package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JCheckBox;

import org.helioviewer.jhv.layers.ViewpointLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * The Camera palette's master toggle is the SAME switch as the sidebar's Camera row checkbox, in
 * both directions, or the two would disagree about whether the viewpoint layer holds the camera.
 * Ticking it here really does call ViewpointLayer.setEnabled, which takes or releases the camera.
 *
 * <p>Row to palette: the row calls setEnabled and then fires layerUpdated, which the palette
 * listens for and mirrors. Palette to row: ticking the palette's box sets the layer and fires the
 * same event, which the layer table listens for. Both halves are checked here by driving the
 * layer and the checkbox and reading the other side.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.CameraPaletteContentCheck
 */
public final class CameraPaletteContentCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JCheckBox findCheckBox(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JCheckBox box)
                return box;
            if (child instanceof Container inner) {
                JCheckBox found = findCheckBox(inner);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        ViewpointLayer layer = Layers.getViewpointLayer();
        expect("there is a viewpoint layer to bind to", layer != null);
        if (layer == null)
            System.exit(1);

        Component content = CameraPaletteContent.build();
        expect("the palette builds", content instanceof Container);
        JCheckBox box = findCheckBox((Container) content);
        expect("the palette carries a master toggle", box != null);
        if (box == null)
            System.exit(1);

        boolean was = layer.isEnabled();
        expect("the toggle starts as the layer is", box.isSelected() == was);

        // Row to palette: the layer changes underneath, the event fires, the box follows.
        layer.setEnabled(!was);
        Layers.fireLayerUpdated(layer);
        expect("a row toggle reaches the palette's checkbox", box.isSelected() == !was);

        // Palette to row: ticking the box sets the layer.
        box.doClick();
        expect("ticking the palette's checkbox sets the layer", layer.isEnabled() == was);
        expect("and the checkbox agrees with what it set", box.isSelected() == was);

        // The settings stay live either way: the toggle is right there, and a behaviour can be
        // set up before the layer takes the camera.
        layer.setEnabled(false);
        Layers.fireLayerUpdated(layer);
        Component options = null;
        for (Component child : ((Container) content).getComponents())
            if (!(child instanceof JCheckBox))
                options = child;
        expect("the settings panel is present", options != null);
        expect("and not greyed while the camera layer is off", options != null && options.isEnabled());

        layer.setEnabled(was);
        Layers.fireLayerUpdated(layer);

        System.out.println(failures == 0 ? "CameraPaletteContentCheck: PASS" : "CameraPaletteContentCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private CameraPaletteContentCheck() {}

}
