package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.dialog.TextDialog;
import org.helioviewer.jhv.layers.ViewpointLayer;
import org.helioviewer.jhv.layers.ViewpointLayerOptions;
import org.helioviewer.jhv.layers.ViewpointLayerOptions.CameraBehaviour;
import org.helioviewer.jhv.layers.ViewpointLayerOptions.FreeSource;

@SuppressWarnings("serial")
final class ViewpointLayerOptionsPanel extends JPanel {

    private static final String explanation = """
            <b>Free</b>: view from the active image layer's observer. Trackball, pan and axis are live.
            <b>Follow</b>: view from a selected object, interpolated over the movie.
            <b>Turntable</b>: revolve the Free view about an axis, to inspect or film the corona from all sides.
            <b>Overview</b>: look down on the solar equatorial plane, with the planets and the Parker spiral.

            One of the four holds at a time. "View from Earth" is a Free framing, not a fifth behaviour: it keeps the trackball.

            If "Use movie time interval" is unselected, the viewpoint time is interpolated in the configured time interval.""";

    private static final String flatReason = "<html>Turntable turns the camera, which a flat projection does not show. "
            + "A revolution already running is suspended until a 3D projection returns.";

    private final ViewpointLayerOptions options;
    private final ViewpointLayerOptionsExpertPanel locationPanel;
    private final ViewpointLayerOptionsExpertPanel equatorialPanel;
    private final TurntableOptions turntablePanel;
    private final JPanel freePanel;
    private final JRadioButton turntableRadio;
    private final JLabel flatNote = new JLabel(flatReason);
    private Component currentOptionPanel;

    ViewpointLayerOptionsPanel(ViewpointLayer layer) {
        options = layer.getOptions();
        locationPanel = new ViewpointLayerOptionsExpertPanel(options.getLocationOptions());
        equatorialPanel = new ViewpointLayerOptionsExpertPanel(options.getEquatorialOptions());
        turntablePanel = new TurntableOptions(options.getTurntable());
        freePanel = createFreePanel();
        setLayout(new GridBagLayout());

        JRadioButton turntable = null;
        // Leading, not trailing. The old sidebar packed every row against the right edge, so the
        // mode radios did too; the panels around this one now start their labels at the left, and
        // one row floating off to the right reads as a mistake rather than as a choice.
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        ButtonGroup behaviourGroup = new ButtonGroup();
        for (CameraBehaviour behaviour : CameraBehaviour.values()) {
            JRadioButton radio = new JRadioButton(behaviour.toString(), behaviour == options.getBehaviour());
            radio.setHorizontalTextPosition(SwingConstants.LEFT);
            radio.addItemListener(e -> {
                if (radio.isSelected()) {
                    options.setBehaviour(behaviour, DisplayController.ViewpointApplyMode.RESET);
                    switchOptionsPanel(getCurrentOptionPanel());
                }
            });
            if (behaviour == CameraBehaviour.TURNTABLE)
                turntable = radio;
            radioPanel.add(radio);
            behaviourGroup.add(radio);
        }
        turntableRadio = turntable;

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
        add(radioPanel, c);
        c.gridy = 1;
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

    private void refresh() {
        boolean spins = Display.mode.rendersIn3D();
        turntableRadio.setEnabled(spins);
        flatNote.setVisible(!spins);
        turntablePanel.refresh();
    }

    private Component getCurrentOptionPanel() {
        return switch (options.getBehaviour()) {
            case FREE -> freePanel;
            case FOLLOW -> locationPanel;
            case TURNTABLE -> turntablePanel;
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
