package org.helioviewer.jhv.layers.filters;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;

import com.jidesoft.swing.JideSplitButton;

public class ImageFilterPanel implements FilterDetails {

    private final JPanel filterPanel = new JPanel(new BorderLayout());
    private final JPanel buttonPanel = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel("Filter ", JLabel.RIGHT);

    private static String formatLabel(double value) {
        return String.format("%.1f", value);
    }

    private static String formatUpsilon(double value) {
        return String.format("%.2f", value);
    }

    private static JPanel createUpsilonRow(String title, JHVSlider slider, JLabel label) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(title), BorderLayout.LINE_START);
        panel.add(slider, BorderLayout.CENTER);
        panel.add(label, BorderLayout.LINE_END);
        return panel;
    }

    private static JPanel createEnhancePanel(ImageLayer layer) {
        JHVSlider slider = new JHVSlider(0, 30, (int) (layer.getGLImage().getEnhanced() * 10));
        JLabel label = new JLabel(formatLabel(slider.getValue() / 10.), JLabel.RIGHT);
        label.setToolTipText("<html><body>pixel⋅R<sup>v");
        slider.addChangeListener(e -> {
            double value = slider.getValue() / 10.;
            layer.getGLImage().setEnhanced(value);
            label.setText(formatLabel(value));
            DisplayController.display();
        });
        JPanel enhancePanel = new JPanel(new BorderLayout());
        enhancePanel.add(slider, BorderLayout.LINE_START);
        enhancePanel.add(label, BorderLayout.LINE_END);
        return enhancePanel;
    }

    public ImageFilterPanel(ImageLayer layer) {
        JComboBox<ImageFilter.Type> filterCombo = new JComboBox<>(ImageFilter.Type.values());
        filterCombo.setSelectedItem(layer.getView().getFilter());
        filterCombo.setToolTipText(layer.getView().getFilter().description);

        JPanel enhancePanel = createEnhancePanel(layer);
        JideSplitButton enhanceButton = new JideSplitButton(Buttons.corona);
        enhanceButton.setToolTipText("Enhance radially the off-disk corona");
        enhanceButton.setAlwaysDropdown(true);
        enhanceButton.add(enhancePanel);

        JHVSlider upsilonLowSlider = new JHVSlider(5, 100, (int) (layer.getGLImage().getUpsilonLow() * 100));
        JLabel upsilonLowLabel = new JLabel(formatUpsilon(upsilonLowSlider.getValue() / 100.), JLabel.RIGHT);
        upsilonLowSlider.addChangeListener(e -> {
            double value = upsilonLowSlider.getValue() / 100.;
            layer.getGLImage().setUpsilon(value, layer.getGLImage().getUpsilonHigh());
            upsilonLowLabel.setText(formatUpsilon(value));
            DisplayController.display();
        });
        JHVSlider upsilonHighSlider = new JHVSlider(5, 100, (int) (layer.getGLImage().getUpsilonHigh() * 100));
        JLabel upsilonHighLabel = new JLabel(formatUpsilon(upsilonHighSlider.getValue() / 100.), JLabel.RIGHT);
        upsilonHighSlider.addChangeListener(e -> {
            double value = upsilonHighSlider.getValue() / 100.;
            layer.getGLImage().setUpsilon(layer.getGLImage().getUpsilonLow(), value);
            upsilonHighLabel.setText(formatUpsilon(value));
            DisplayController.display();
        });
        JPanel upsilonPanel = new JPanel(new GridLayout(2, 1));
        upsilonPanel.add(createUpsilonRow("ΥL ", upsilonLowSlider, upsilonLowLabel));
        upsilonPanel.add(createUpsilonRow("ΥH ", upsilonHighSlider, upsilonHighLabel));

        JideSplitButton upsilonButton = new JideSplitButton("Υ");
        upsilonButton.setToolTipText("Soften shadows (ΥL, below median) and highlights (ΥH, above median) of RHEF output independently");
        upsilonButton.setAlwaysDropdown(true);
        upsilonButton.add(upsilonPanel);

        upsilonButton.setVisible(layer.getView().getFilter() == ImageFilter.Type.RHEF);
        filterCombo.addActionListener(e -> {
            if (filterCombo.getSelectedItem() instanceof ImageFilter.Type type && type != layer.getView().getFilter()) {
                filterCombo.setToolTipText(type.description);
                upsilonButton.setVisible(type == ImageFilter.Type.RHEF);
                layer.getView().clearCache();
                layer.getView().setFilter(type);
                DisplayController.render(1);
            }
        });

        filterPanel.add(filterCombo, BorderLayout.CENTER);
        filterPanel.add(upsilonButton, BorderLayout.LINE_END);
        // Disk imagers render flat automatically in a disk projection (see ImageLayer.render
        // and ImageLayers.isDiskImager); no manual toggle needed.
        // Disk imagers render flat by default in a disk projection (see ImageLayer.render and
        // ImageLayers.isDiskImager). This toggle overrides that and applies the radial warp anyway.
        // The icon itself shows the state: concentric rings = warp applied, single circle = held flat.
        JToggleButton radialButton = new JToggleButton(layer.getDiskScaling() ? Buttons.radialScale : Buttons.radialScaleOff, layer.getDiskScaling());
        radialButton.setMargin(new Insets(0, 4, 0, 4));
        radialButton.setToolTipText("Disk radial scaling for this disk imager — circle = radial warp applied, rectangle = held flat (the default). Turn on to make an off-disk-masked disk imager a virtual coronagraph");
        radialButton.addActionListener(e -> {
            boolean on = radialButton.isSelected();
            radialButton.setText(on ? Buttons.radialScale : Buttons.radialScaleOff);
            layer.setDiskScaling(on);
            DisplayController.display();
        });
        // Only meaningful for a disk imager in the PowerDisk projection; grey out otherwise. Track
        // projection changes only while the button is on screen, so the listener is not leaked.
        Runnable updateRadialEnabled = () -> radialButton.setEnabled(layer.isDiskImager() && ViewState.getProjection() == MapMode.PowerDisk);
        updateRadialEnabled.run();
        ViewState.ModeListener radialModeListener = updateRadialEnabled::run;
        radialButton.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent e) {
                ViewState.addModeListener(radialModeListener);
                updateRadialEnabled.run();
            }

            @Override
            public void ancestorRemoved(AncestorEvent e) {
                ViewState.removeModeListener(radialModeListener);
            }

            @Override
            public void ancestorMoved(AncestorEvent e) {
            }
        });
        buttonPanel.add(radialButton, BorderLayout.LINE_START);
        buttonPanel.add(enhanceButton, BorderLayout.LINE_END);
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return filterPanel;
    }

    @Override
    public Component getThird() {
        return buttonPanel;
    }

}
