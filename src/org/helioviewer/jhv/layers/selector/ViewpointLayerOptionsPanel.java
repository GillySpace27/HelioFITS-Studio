package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.dialog.TextDialog;
import org.helioviewer.jhv.layers.ViewpointLayer;
import org.helioviewer.jhv.layers.ViewpointLayerOptions;
import org.helioviewer.jhv.layers.ViewpointLayerOptions.CameraBehaviour;
import org.helioviewer.jhv.layers.ViewpointLayerOptions.FreeSource;

/**
 * The camera behaviours and their settings. Public because it is built by the Camera palette
 * ({@code gui.component.CameraPaletteContent}), which is the one place it now exists; the
 * sidebar's Camera row points there. That matters here more than for the grid: the constructor
 * registers itself as THE panel {@code ViewpointLayerOptions.refreshPanel()} pokes, so two live
 * instances would leave one of them stale. One home, one hook.
 */
@SuppressWarnings("serial")
public final class ViewpointLayerOptionsPanel extends JPanel {

    private static final String explanation = """
            Two separate questions, which is why this panel is in two halves.

            <b>Seen from</b> is where the scene is viewed from, and holds one at a time:
            <b>Free</b>: from the active image layer's observer. Trackball, pan and axis are live.
            <b>Follow</b>: from a selected object, interpolated over the movie.
            <b>Overview</b>: down on the solar equatorial plane, with the planets and the Parker spiral.
            "View from Earth" is a Free framing, not a fourth behaviour: it keeps the trackball.

            <b>Camera motion</b> is the camera moving within whatever it is looking from. Revolving turns it \
            about an axis, to inspect or film the corona from all sides. It is not a viewpoint and does not \
            need one: it writes the same rotation the trackball does, so it runs whether or not this layer \
            is switched on and drives the view.

            If "Use movie time interval" is unselected, the viewpoint time is interpolated in the configured time interval.""";

    private static final String flatReason = "<html>Revolving turns the camera, which a flat projection does not show. "
            + "A revolution already running is suspended until a 3D projection returns.";

    private final ViewpointLayerOptions options;
    private final ViewpointLayerOptionsExpertPanel locationPanel;
    private final ViewpointLayerOptionsExpertPanel equatorialPanel;
    private final TurntableOptions turntablePanel;
    private final JPanel freePanel;
    private final JCheckBox revolveBox = new JCheckBox("Revolve the camera");
    private final JLabel flatNote = new JLabel(flatReason);
    private Component currentOptionPanel;

    public ViewpointLayerOptionsPanel(ViewpointLayer layer) {
        options = layer.getOptions();
        locationPanel = new ViewpointLayerOptionsExpertPanel(options.getLocationOptions());
        equatorialPanel = new ViewpointLayerOptionsExpertPanel(options.getEquatorialOptions());
        turntablePanel = new TurntableOptions(options.getTurntable());
        freePanel = createFreePanel();
        setLayout(new GridBagLayout());

        // Leading, not trailing. The old sidebar packed every row against the right edge, so the
        // mode radios did too; the panels around this one now start their labels at the left, and
        // one row floating off to the right reads as a mistake rather than as a choice.
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        ButtonGroup behaviourGroup = new ButtonGroup();
        for (CameraBehaviour behaviour : CameraBehaviour.values()) {
            // TURNTABLE is not a vantage point and no longer has a radio: revolving is the
            // checkbox below, and works with this layer off. The constant survives only so old
            // sessions naming it still parse (ViewpointLayerOptions migrates them to FREE).
            if (behaviour == CameraBehaviour.TURNTABLE)
                continue;
            JRadioButton radio = new JRadioButton(behaviour.toString(), behaviour == options.getBehaviour());
            radio.setHorizontalTextPosition(SwingConstants.LEFT);
            radio.addItemListener(e -> {
                if (radio.isSelected()) {
                    options.setBehaviour(behaviour, DisplayController.ViewpointApplyMode.RESET);
                    switchOptionsPanel(getCurrentOptionPanel());
                }
            });
            radioPanel.add(radio);
            behaviourGroup.add(radio);
        }

        JButton info = Buttons.flat(Buttons.info);
        info.setToolTipText("Show camera info");
        info.addActionListener(e -> new TextDialog("Camera Options Information", explanation, false).showDialog());
        radioPanel.add(info);

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.weightx = 1;
        c.weighty = 1;

        c.gridy = 0;
        add(heading("Seen from"), c);
        c.gridy = 1;
        add(radioPanel, c);
        // gridy 2 is the selected behaviour's own options, filled by switchOptionsPanel.

        // The second half: the camera moving within that vantage point, rather than a vantage
        // point of its own. Always present, not swapped in behind a radio, because it composes
        // with any of the three above rather than replacing them.
        c.gridy = 3;
        add(new JSeparator(), c);
        c.gridy = 4;
        add(heading("Camera motion"), c);
        c.gridy = 5;
        add(revolvePanel(), c);
        c.gridy = 6;
        add(turntablePanel, c);
        c.gridy = 7;
        add(flatNote, c);

        // The projection lives in the toolbar and image layers arrive from the menus, so this panel
        // has no event of its own to hang the greying and the turntable note on.
        ViewpointLayerOptions.setPanelRefresh(this::refresh);
        refresh();
        switchOptionsPanel(getCurrentOptionPanel());
    }

    // Free's distance sub-choice, plus the one-click Earth framing. Earth is a button rather than a
    // third radio because it is an act ("look from Earth now"), but it is stored as a source, so a
    // later re-install of the behaviour does not quietly put the camera back on the instrument.
    private JPanel createFreePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        ButtonGroup group = new ButtonGroup();
        for (FreeSource source : new FreeSource[]{FreeSource.OBSERVER, FreeSource.OBSERVER_1AU}) {
            JRadioButton radio = new JRadioButton(source.toString(), source == options.getFreeSource());
            radio.addItemListener(e -> {
                if (radio.isSelected())
                    options.setFreeSource(source, DisplayController.ViewpointApplyMode.KEEP_TRANSFORM);
            });
            group.add(radio);
            panel.add(radio);
        }

        JButton earth = new JButton(FreeSource.EARTH.toString());
        earth.setToolTipText("Put the camera at Earth's longitude, 1 au out");
        earth.addActionListener(e -> {
            // RESET, not KEEP_TRANSFORM: a button that says "view from Earth" and leaves whatever
            // rotation was dragged in does not view from Earth.
            options.setFreeSource(FreeSource.EARTH, DisplayController.ViewpointApplyMode.RESET);
            group.clearSelection();
        });
        panel.add(earth);

        if (options.getFreeSource() == FreeSource.EARTH)
            group.clearSelection();
        return panel;
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        return label;
    }

    private JPanel revolvePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        revolveBox.setSelected(options.isRevolving());
        revolveBox.setToolTipText("Turn the camera about an axis, to inspect or film the corona from all sides. "
                + "Works whether or not this layer is switched on: revolving moves the camera, it does not "
                + "decide where the scene is seen from.");
        revolveBox.addActionListener(e -> {
            options.setRevolving(revolveBox.isSelected());
            refresh();
        });
        panel.add(revolveBox);
        return panel;
    }

    private void refresh() {
        boolean spins = Display.mode.rendersIn3D();
        revolveBox.setEnabled(spins);
        if (revolveBox.isSelected() != options.isRevolving())
            revolveBox.setSelected(options.isRevolving()); // a restored session, or the projection suspending it
        flatNote.setVisible(!spins);
        ComponentUtils.setEnabled(turntablePanel, spins && options.isRevolving());
        turntablePanel.refresh();
    }

    private Component getCurrentOptionPanel() {
        return switch (options.getBehaviour()) {
            case FREE -> freePanel;
            case FOLLOW -> locationPanel;
            // Unreachable: migrated to FREE on read, and the radio for it is not built. The case
            // exists because the constant does, and a switch over the enum has to be exhaustive.
            case TURNTABLE -> freePanel;
            case OVERVIEW -> equatorialPanel;
        };
    }

    private void switchOptionsPanel(Component newOptionPanel) {
        if (currentOptionPanel == newOptionPanel)
            return;

        if (currentOptionPanel != null)
            remove(currentOptionPanel);

        if (newOptionPanel != null) {
            GridBagConstraints c = new GridBagConstraints();
            c.weightx = 1;
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            c.gridx = 0;
            c.gridy = 2;
            add(newOptionPanel, c);
        }
        currentOptionPanel = newOptionPanel;
        revalidate();
        repaint();
    }

}
