package org.helioviewer.jhv.layers.filters;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.component.CircularProgressUI;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.gui.component.TerminatedFormatterFactory;
import org.helioviewer.jhv.image.fourier.FourierFilter;
import org.helioviewer.jhv.image.fourier.FourierParams;
import org.helioviewer.jhv.image.fourier.NoiseGateParams;
import org.helioviewer.jhv.image.fourier.SequenceParams;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.ComputedView;
import org.helioviewer.jhv.view.View;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideSplitButton;

/**
 * The "Sequence" row: a filter computed over every frame of the layer (a radial or angular
 * velocity filter, or the noise gate), chosen in the combo, set up in the popup, run with Apply.
 * Off swaps the original frames back in place at once; a kind change only edits the popup.
 *
 * <p>The row is live only for a fully loaded FITS or PNG movie of at least eight frames: a JPEG
 * 2000 stream cannot hand over whole frames. While a job runs the Apply button shows its progress
 * and cancels on a click.
 */
public class SequencePanel implements FilterDetails {

    private static final String OFF = "Off", RADIAL = "Radial velocity", ANGULAR = "Angular velocity", GATE = "Noise gate";
    private static final double DEG_PER_HOUR = 3600 * 180 / Math.PI; // rad/s to deg/hour

    private final ImageLayer layer;
    private final JLabel title = new JLabel("Sequence ", JLabel.RIGHT);
    private final JPanel second = new JPanel(new BorderLayout());
    private final JPanel third = new JPanel(new BorderLayout());
    private final JComboBox<String> kindCombo = new JComboBox<>(new String[]{OFF, RADIAL, ANGULAR, GATE});
    private final JideSplitButton settingsButton = new JideSplitButton("…");
    private final JideButton applyButton = new JideButton("Apply");
    private final JProgressBar spinner = new JProgressBar(0, 100);
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JLabel readout = new JLabel();
    private final JPanel settings = new JPanel(new BorderLayout()); // the card stack plus the readout
    private boolean syncing;

    // velocity settings
    private final JRadioButton passButton = new JRadioButton("Pass", true);
    private final JRadioButton notchButton = new JRadioButton("Notch");
    private final JFormattedTextField loField = new JFormattedTextField();
    private final JFormattedTextField hiField = new JFormattedTextField();
    private final JFormattedTextField periodField = new JFormattedTextField(new TerminatedFormatterFactory("%.1f", " min", 1, 1e6));
    private final JLabel periodLabel = new JLabel("Period ");
    private final JComboBox<String> directionCombo = new JComboBox<>();
    private final JHVSlider gainSlider = new JHVSlider(1, 100, 10);
    private final JLabel gainLabel = new JLabel("1.0", JLabel.RIGHT);
    private final JComboBox<Integer> nRCombo = new JComboBox<>(new Integer[]{512, 1024, 2048});
    private final JComboBox<Integer> nPhiCombo = new JComboBox<>(new Integer[]{256, 512, 1024});
    private final JideButton spectrumButton = new JideButton("Spectrum…");
    private SpectrumDialog spectrumDialog;

    // noise gate settings
    private final JComboBox<NoiseGateParams.Model> modelCombo = new JComboBox<>(NoiseGateParams.Model.values());
    private final JComboBox<NoiseGateParams.Gate> gateCombo = new JComboBox<>(NoiseGateParams.Gate.values());
    private final JHVSlider gammaSlider = new JHVSlider(0, 60, 30);
    private final JLabel gammaLabel = new JLabel("3.0", JLabel.RIGHT);
    private final JHVSlider percentileSlider = new JHVSlider(10, 50, 50);
    private final JLabel percentileLabel = new JLabel("50", JLabel.RIGHT);
    private final JComboBox<Integer> nCombo = new JComboBox<>(new Integer[]{8, 16});
    private final JCheckBox residualBox = new JCheckBox("Show the residual (what was removed)");
    private final JCheckBox radialBox = new JCheckBox("Noise level varies with radius", true);

    public SequencePanel(ImageLayer _layer) {
        layer = _layer;

        // velocity card
        ButtonGroup modes = new ButtonGroup();
        modes.add(passButton);
        modes.add(notchButton);
        JPanel modeRow = new JPanel(new GridLayout(1, 2));
        modeRow.add(passButton);
        modeRow.add(notchButton);
        setVelocityFormats(FourierParams.Kind.RADIAL);
        loField.setValue(200.);
        hiField.setValue(800.);
        periodField.setValue(FourierParams.PUNCH_ORBIT_MINUTES_ASSUMED);
        periodField.setToolTipText("Assumed PUNCH orbit; verify with Spectrum before trusting a notch set from it");
        periodField.addPropertyChangeListener("value", e -> {
            if (syncing || !(periodField.getValue() instanceof Number minutes))
                return;
            double omega = 2 * Math.PI / (minutes.doubleValue() * 60);
            syncing = true;
            loField.setValue(omega * 0.85 * DEG_PER_HOUR);
            hiField.setValue(omega * 1.15 * DEG_PER_HOUR);
            syncing = false;
        });
        gainSlider.addChangeListener(e -> gainLabel.setText(String.format("%.1f", gainSlider.getValue() / 10.)));
        nRCombo.setSelectedItem(1024);
        nPhiCombo.setSelectedItem(512);
        spectrumButton.setEnabled(false);
        spectrumButton.addActionListener(e -> showSpectrum());

        JPanel velocity = new JPanel(new GridLayout(0, 1));
        velocity.add(modeRow);
        velocity.add(row("From ", loField));
        velocity.add(row("To ", hiField));
        velocity.add(row(periodLabel, periodField));
        velocity.add(row("Direction ", directionCombo));
        velocity.add(row("Gain ", gainSlider, gainLabel));
        velocity.add(row("Radial bins ", nRCombo));
        velocity.add(row("Angular bins ", nPhiCombo));
        velocity.add(spectrumButton);

        // noise gate card
        gammaSlider.addChangeListener(e -> gammaLabel.setText(String.format("%.1f", gammaSlider.getValue() / 10.)));
        percentileSlider.addChangeListener(e -> percentileLabel.setText(String.valueOf(percentileSlider.getValue())));
        nCombo.setSelectedItem(16);
        modelCombo.setToolTipText("Shot: noise grows with the square root of intensity (photon counting). Additive: one level everywhere (a background-subtracted product, a magnetogram)");
        gammaSlider.setToolTipText("Threshold factor over the estimated noise; 3 is the paper's solar default");
        percentileSlider.setToolTipText("Percentile across neighbourhoods that estimates the noise; lower it for highly structured data");
        JLabel caveat = new JLabel("<html><i>Strict gating removes weak signal below the noise floor: read faint features off the original too.</i></html>");
        JPanel gate = new JPanel(new GridLayout(0, 1));
        gate.add(row("Noise ", modelCombo));
        gate.add(row("Gate ", gateCombo));
        gate.add(row("Gamma ", gammaSlider, gammaLabel));
        gate.add(row("Percentile ", percentileSlider, percentileLabel));
        gate.add(row("Size ", nCombo));
        radialBox.setToolTipText("Estimate the noise in radial bands (interpolated in radius) instead of one level for the whole image; right for a coronagraph, which gets darker and noisier outward");
        gate.add(radialBox);
        gate.add(residualBox);
        gate.add(caveat);

        cardPanel.add(velocity, RADIAL);
        cardPanel.add(gate, GATE);
        settings.add(cardPanel, BorderLayout.CENTER);
        readout.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
        settings.add(readout, BorderLayout.PAGE_END);
        settingsButton.setAlwaysDropdown(true);
        settingsButton.setToolTipText("Settings of the sequence filter");
        settingsButton.add(settings);

        kindCombo.addActionListener(e -> {
            if (syncing)
                return;
            String kind = (String) kindCombo.getSelectedItem();
            showCard(kind);
            if (OFF.equals(kind)) {
                Layers.applyToSelectedLayers(layer, il -> il.setSequence(null));
                DisplayController.render(1);
            }
            updateReadout();
        });

        spinner.setUI(new CircularProgressUI());
        spinner.setPreferredSize(new Dimension(20, 20));
        spinner.setVisible(false);
        applyButton.setToolTipText("Compute the filter over every frame (or cancel a running one)");
        applyButton.addActionListener(e -> {
            ComputedView running = layer.getComputedView();
            if (running != null && running.isRunning()) {
                Layers.applyToSelectedLayers(layer, il -> il.setSequence(null));
                syncing = true;
                kindCombo.setSelectedItem(OFF);
                syncing = false;
                DisplayController.render(1);
                return;
            }
            apply();
        });

        second.add(kindCombo, BorderLayout.CENTER);
        second.add(settingsButton, BorderLayout.LINE_END);
        third.add(applyButton, BorderLayout.CENTER);

        syncFromLayer();
    }

    private static JPanel row(String label, Component c) {
        return row(new JLabel(label), c);
    }

    private static JPanel row(JLabel label, Component c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(label, BorderLayout.LINE_START);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private static JPanel row(String label, Component c, Component readout) {
        JPanel p = row(label, c);
        p.add(readout, BorderLayout.LINE_END);
        return p;
    }

    private void showCard(String kind) {
        cards.show(cardPanel, GATE.equals(kind) ? GATE : RADIAL);
        boolean angular = ANGULAR.equals(kind);
        periodLabel.setVisible(angular);
        periodField.setVisible(angular);
        if (!OFF.equals(kind) && !GATE.equals(kind))
            setVelocityFormats(angular ? FourierParams.Kind.ANGULAR : FourierParams.Kind.RADIAL);
    }

    private FourierParams.Kind currentVelocityKind;

    private void setVelocityFormats(FourierParams.Kind kind) {
        if (kind == currentVelocityKind)
            return;
        currentVelocityKind = kind;
        boolean angular = kind == FourierParams.Kind.ANGULAR;
        syncing = true;
        Object lo = loField.getValue(), hi = hiField.getValue();
        loField.setFormatterFactory(new TerminatedFormatterFactory(angular ? "%.2f" : "%.0f", angular ? " °/h" : " km/s", 0, 1e7));
        hiField.setFormatterFactory(new TerminatedFormatterFactory(angular ? "%.2f" : "%.0f", angular ? " °/h" : " km/s", 0, 1e7));
        directionCombo.removeAllItems();
        for (String s : angular ? new String[]{"Both", "Prograde", "Retrograde"} : new String[]{"Both", "Outward", "Inward"})
            directionCombo.addItem(s);
        directionCombo.setSelectedIndex(angular ? 0 : 1);
        if (angular) {
            double omega = 2 * Math.PI / (FourierParams.PUNCH_ORBIT_MINUTES_ASSUMED * 60);
            loField.setValue(omega * 0.85 * DEG_PER_HOUR);
            hiField.setValue(omega * 1.15 * DEG_PER_HOUR);
            notchButton.setSelected(true);
        } else {
            loField.setValue(lo instanceof Number ? 200. : 200.);
            hiField.setValue(hi instanceof Number ? 800. : 800.);
            passButton.setSelected(true);
        }
        syncing = false;
    }

    @Nullable
    private SequenceParams paramsFromWidgets() {
        String kind = (String) kindCombo.getSelectedItem();
        if (OFF.equals(kind) || kind == null)
            return null;
        try {
            if (GATE.equals(kind))
                return new NoiseGateParams((NoiseGateParams.Model) modelCombo.getSelectedItem(), (NoiseGateParams.Gate) gateCombo.getSelectedItem(),
                        gammaSlider.getValue() / 10., percentileSlider.getValue(), (Integer) nCombo.getSelectedItem(), residualBox.isSelected(),
                        radialBox.isSelected() ? NoiseGateParams.DEFAULT_BANDS : 0);
            boolean angular = ANGULAR.equals(kind);
            double lo = ((Number) loField.getValue()).doubleValue(), hi = ((Number) hiField.getValue()).doubleValue();
            if (angular) {
                lo /= DEG_PER_HOUR;
                hi /= DEG_PER_HOUR;
            }
            FourierParams.Direction direction = switch (directionCombo.getSelectedIndex()) {
                case 1 -> FourierParams.Direction.POSITIVE;
                case 2 -> FourierParams.Direction.NEGATIVE;
                default -> FourierParams.Direction.BOTH;
            };
            return new FourierParams(angular ? FourierParams.Kind.ANGULAR : FourierParams.Kind.RADIAL,
                    passButton.isSelected() ? FourierParams.Mode.PASS : FourierParams.Mode.NOTCH,
                    lo, hi, direction, gainSlider.getValue() / 10., (Integer) nRCombo.getSelectedItem(), (Integer) nPhiCombo.getSelectedItem());
        } catch (Exception e) {
            Message.warn("Sequence filter", "Check the settings: " + e.getMessage());
            return null;
        }
    }

    private void apply() {
        SequenceParams params = paramsFromWidgets();
        if (params == null)
            return;
        long budget = estimateBytes(params);
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - rt.totalMemory() + rt.freeMemory();
        if (budget > 0.6 * free) {
            Message.warn("Sequence filter", String.format("This would need about %d MB of working memory and %d MB are free. Shorten the time range or lower the grid size.", budget >> 20, free >> 20));
            return;
        }
        // The output is off-heap and the view holds all of it at once, so the heap check above
        // says nothing about it. A full-resolution PUNCH mosaic movie is 33 MB a frame: the run
        // that prompted this check produced 8.2 GB of output from 245 of them.
        long output = outputBytes();
        if (output > (2L << 30) && JOptionPane.showConfirmDialog(second,
                String.format("This will hold about %.1f GB of filtered frames in memory for as long as the filter is on.\nShorten the time range to reduce it.\n\nContinue?", output / (double) (1L << 30)),
                "Sequence filter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        Layers.applyToSelectedLayers(layer, il -> il.setSequence(params));
        DisplayController.render(1);
        updateReadout();
    }

    /** What the finished movie occupies off-heap, which is what the layer then holds. */
    private long outputBytes() {
        View.ImageData data = layer.getImageData();
        if (data == null)
            return 0;
        return 2L * (layer.getView().getMaximumFrameNumber() + 1) * data.imageBuffer().width * data.imageBuffer().height;
    }

    // Working set of the job on this layer, in bytes, for the budget check and the readout.
    private long estimateBytes(SequenceParams params) {
        View view = layer.getView();
        int frames = view.getMaximumFrameNumber() + 1;
        View.ImageData data = layer.getImageData();
        long pixels = data == null ? 1L << 20 : (long) data.imageBuffer().width * data.imageBuffer().height;
        if (params instanceof FourierParams f)
            return 4L * f.nR() * f.nPhi() * Integer.highestOneBit(Math.max(1, frames) * 2 - 1) + 3 * 4 * pixels;
        return 4L * 2 * 288 * 288 * (frames + 32) + 33L << 20;
    }

    private void updateReadout() {
        View view = layer.getView();
        int frames = view.getMaximumFrameNumber() + 1;
        if (frames < 2) {
            readout.setText("<html>No movie loaded</html>");
            return;
        }
        long[] times = new long[frames];
        for (int k = 0; k < frames; k++)
            times[k] = view.getFrameTime(k).milli;
        double dt = org.helioviewer.jhv.image.fourier.FrameStack.medianCadence(times);
        double gap = org.helioviewer.jhv.image.fourier.FrameStack.maxGap(times);
        String kind = (String) kindCombo.getSelectedItem();
        StringBuilder sb = new StringBuilder("<html>").append(frames).append(" frames, cadence ").append(fmt(dt)).append(" s");
        if (gap > 2 * dt)
            sb.append(" (largest gap ").append(fmt(gap)).append(" s)");
        View.ImageData data = layer.getImageData();
        if (data != null && !OFF.equals(kind) && !GATE.equals(kind)) {
            double kmPerPixel = data.metaData().getUnitPerPixelY() * FourierParams.KM_PER_RSUN;
            int nR = Math.min((Integer) nRCombo.getSelectedItem(), Math.min(data.imageBuffer().width, data.imageBuffer().height) / 2);
            int nT = Integer.highestOneBit(frames * 2 - 1);
            if (ANGULAR.equals(kind)) {
                double omegaMax = Math.PI / dt, omegaMin = 2 * Math.PI / (frames * dt) / ((Integer) nPhiCombo.getSelectedItem() / 2.);
                sb.append("<br>resolvable ").append(String.format("%.2f to %.0f °/h", omegaMin * DEG_PER_HOUR, omegaMax * DEG_PER_HOUR))
                        .append(String.format(" (periods %.0f min to %.0f h)", 2 * Math.PI / omegaMax / 60, 2 * Math.PI / omegaMin / 3600));
            } else {
                sb.append("<br>resolvable ").append(String.format("%.0f to %.0f km/s", 2 * kmPerPixel / (frames * dt), nR * kmPerPixel / (2 * dt)));
            }
            sb.append(String.format("<br>cube %d MB", 4L * nR * (Integer) nPhiCombo.getSelectedItem() * nT >> 20));
        } else if (data != null && GATE.equals(kind)) {
            int n = (Integer) nCombo.getSelectedItem();
            int nt = frames >= 32 ? n : frames >= 16 ? 8 : 1;
            sb.append("<br>neighbourhoods ").append(n).append(" x ").append(n).append(" x ").append(nt).append(nt == 1 ? " (2D, per frame)" : "");
            if (data.imageBuffer().width * data.imageBuffer().height > 3_000_000)
                sb.append("<br>a 4K movie takes minutes; Apply shows progress and cancels");
        }
        if (data != null)
            sb.append(String.format("<br>output %d MB", 2L * frames * data.imageBuffer().width * data.imageBuffer().height >> 20));
        readout.setText(sb.append("</html>").toString());
    }

    private static String fmt(double seconds) {
        return seconds >= 100 ? String.format("%.0f", seconds) : String.format("%.1f", seconds);
    }

    private void showSpectrum() {
        ComputedView view = layer.getComputedView();
        FourierFilter.Spectrum spectrum = view == null ? null : view.spectrum();
        if (spectrum == null) {
            Message.warn("Spectrum", "Apply a velocity filter first: the spectrum is measured while it runs.");
            return;
        }
        if (spectrumDialog == null)
            spectrumDialog = new SpectrumDialog();
        spectrumDialog.show(spectrum, currentBand());
    }

    private double[] currentBand() {
        try {
            double lo = ((Number) loField.getValue()).doubleValue(), hi = ((Number) hiField.getValue()).doubleValue();
            boolean angular = ANGULAR.equals(kindCombo.getSelectedItem());
            return angular ? new double[]{lo / DEG_PER_HOUR, hi / DEG_PER_HOUR} : new double[]{lo, hi};
        } catch (Exception e) {
            return new double[]{1, 2};
        }
    }

    private void setBandCentre(double rate) {
        double[] band = currentBand();
        double ratio = Math.sqrt(band[1] / band[0]);
        boolean angular = ANGULAR.equals(kindCombo.getSelectedItem());
        double scale = angular ? DEG_PER_HOUR : 1;
        syncing = true;
        loField.setValue(rate / ratio * scale);
        hiField.setValue(rate * ratio * scale);
        if (angular)
            periodField.setValue(2 * Math.PI / rate / 60);
        syncing = false;
    }

    /** Called on every layer update: gates the row, mirrors the layer's state, animates the spinner. */
    public void refresh(ImageLayer imageLayer) {
        String blocker = imageLayer.sequenceBlocker();
        boolean can = blocker == null;
        ComponentUtils.setEnabled(kindCombo, can);
        ComponentUtils.setEnabled(settingsButton, can);
        // The reason, not a menu of four: "1 frame(s) loaded so far" and "JPX frames cannot be
        // handed over whole" call for entirely different things from the person reading it.
        title.setToolTipText(can ? null : "No sequence filter yet: " + blocker);
        ComputedView view = imageLayer.getComputedView();
        boolean running = view != null && view.isRunning();
        applyButton.setEnabled(can || running);
        showSpinner(running);
        if (running) {
            spinner.setIndeterminate(view.progress() <= 0);
            spinner.setValue((int) Math.round(100 * view.progress()));
        }
        spectrumButton.setEnabled(view != null && view.spectrum() != null);
        syncFromLayer();
    }

    /**
     * The progress spinner lives in the button only while a job runs.
     *
     * <p>Adding a component to a button makes it a container, and its preferred size then comes
     * from that child instead of from its own text. Parking the spinner there at construction
     * therefore shrank Apply to a 20-pixel square for the whole life of the row: the third column
     * of a filter row takes the component's preferred size, so the label had nowhere to go and the
     * button read as an unlabelled box.
     */
    private void showSpinner(boolean running) {
        if (running == spinnerShown)
            return;
        spinnerShown = running;
        if (running) {
            applyButton.setText(null);
            applyButton.add(spinner);
        } else {
            applyButton.remove(spinner);
            applyButton.setText("Apply");
        }
        spinner.setVisible(running);
        applyButton.revalidate();
        applyButton.repaint();
    }

    private boolean spinnerShown;

    private void syncFromLayer() {
        SequenceParams params = layer.getSequence();
        syncing = true;
        if (params == null)
            kindCombo.setSelectedItem(OFF);
        else if (params instanceof NoiseGateParams g) {
            kindCombo.setSelectedItem(GATE);
            modelCombo.setSelectedItem(g.model());
            gateCombo.setSelectedItem(g.gate());
            gammaSlider.setValue((int) Math.round(g.gamma() * 10));
            percentileSlider.setValue(g.percentile());
            nCombo.setSelectedItem(g.n());
            residualBox.setSelected(g.residual());
            radialBox.setSelected(g.radialBands() > 0);
        } else if (params instanceof FourierParams f) {
            boolean angular = f.kind() == FourierParams.Kind.ANGULAR;
            kindCombo.setSelectedItem(angular ? ANGULAR : RADIAL);
            setVelocityFormats(f.kind());
            (f.mode() == FourierParams.Mode.PASS ? passButton : notchButton).setSelected(true);
            double scale = angular ? DEG_PER_HOUR : 1;
            loField.setValue(f.lo() * scale);
            hiField.setValue(f.hi() * scale);
            if (angular)
                periodField.setValue(2 * Math.PI / Math.sqrt(f.lo() * f.hi()) / 60);
            directionCombo.setSelectedIndex(switch (f.direction()) {
                case POSITIVE -> 1;
                case NEGATIVE -> 2;
                default -> 0;
            });
            gainSlider.setValue((int) Math.round(f.gain() * 10));
            nRCombo.setSelectedItem(f.nR());
            nPhiCombo.setSelectedItem(f.nPhi());
        }
        showCard((String) kindCombo.getSelectedItem());
        syncing = false;
        updateReadout();
    }

    /**
     * The same controls laid out for a palette: the kind, the settings inline rather than behind
     * the dropdown, and Apply. Moves `settings` out of the split button, so one SequencePanel
     * serves either the layer row or the palette, never both at once.
     */
    public JComponent getPaletteContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setOpaque(false);
        settingsButton.remove(settings);
        settingsButton.setVisible(false);
        for (JComponent c : new JComponent[]{kindCombo, settings, applyButton}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(c);
        }
        return content;
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return second;
    }

    @Override
    public Component getThird() {
        return third;
    }

    /**
     * Power against rate, the two senses as two curves, the current band shaded: for the angular
     * kind a pattern turning with the spacecraft orbit is a peak, and clicking it sets the band.
     */
    private final class SpectrumDialog {
        private final JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(second), "Rate spectrum");
        private final Plot plot = new Plot();

        SpectrumDialog() {
            dialog.setModal(false);
            dialog.add(plot);
            dialog.setSize(640, 400);
            dialog.setLocationRelativeTo(second);
        }

        void show(FourierFilter.Spectrum spectrum, double[] band) {
            plot.spectrum = spectrum;
            plot.band = band;
            plot.repaint();
            dialog.setVisible(true);
        }

        private final class Plot extends JComponent {
            FourierFilter.Spectrum spectrum;
            double[] band;
            final int left = 60, right = 20, top = 30, bottom = 40;

            Plot() {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (spectrum == null)
                            return;
                        double rate = rateAt(e.getX());
                        if (rate > 0) {
                            setBandCentre(rate);
                            band = currentBand();
                            repaint();
                        }
                    }
                });
            }

            private double logMin() {
                return Math.log(spectrum.rate()[0]);
            }

            private double logMax() {
                return Math.log(spectrum.rate()[spectrum.rate().length - 1]);
            }

            private int xOf(double rate) {
                double f = (Math.log(rate) - logMin()) / (logMax() - logMin());
                return left + (int) (f * (getWidth() - left - right));
            }

            private double rateAt(int x) {
                double f = (x - left) / (double) (getWidth() - left - right);
                return f < 0 || f > 1 ? -1 : Math.exp(logMin() + f * (logMax() - logMin()));
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                if (spectrum == null)
                    return;
                boolean angular = spectrum.kind() == FourierParams.Kind.ANGULAR;
                double unit = angular ? DEG_PER_HOUR : 1;
                int w = getWidth(), h = getHeight();
                int plotH = h - top - bottom;
                // log power range over both curves
                double pmax = 1e-300, pmin = Double.MAX_VALUE;
                for (int i = 0; i < spectrum.rate().length; i++) {
                    double a = spectrum.powerPositive()[i], b = spectrum.powerNegative()[i];
                    if (a > 0) { pmax = Math.max(pmax, a); pmin = Math.min(pmin, a); }
                    if (b > 0) { pmax = Math.max(pmax, b); pmin = Math.min(pmin, b); }
                }
                if (pmax <= pmin)
                    return;
                double lpmax = Math.log10(pmax), lpmin = Math.max(Math.log10(pmin), lpmax - 6);
                // band
                if (band != null) {
                    g.setColor(new Color(255, 200, 0, 60));
                    int x0 = xOf(Math.max(band[0], spectrum.rate()[0])), x1 = xOf(Math.min(band[1], spectrum.rate()[spectrum.rate().length - 1]));
                    g.fillRect(Math.min(x0, x1), top, Math.abs(x1 - x0), plotH);
                }
                // axes
                g.setColor(Color.GRAY);
                g.drawRect(left, top, w - left - right, plotH);
                for (int i = 0; i <= 5; i++) {
                    int x = left + i * (w - left - right) / 5;
                    double rate = Math.exp(logMin() + i / 5. * (logMax() - logMin())) * unit;
                    String label = angular ? String.format("%.2g °/h", rate) : String.format("%.3g km/s", rate);
                    g.drawString(label, x - 20, h - bottom + 15);
                    if (angular)
                        g.drawString(String.format("%.3g min", 2 * Math.PI / (rate / unit) / 60), x - 20, top - 8);
                }
                g.drawString("log10 power", 4, top + 12);
                // curves
                drawCurve(g, spectrum.powerPositive(), lpmin, lpmax, plotH, new Color(30, 120, 220));
                drawCurve(g, spectrum.powerNegative(), lpmin, lpmax, plotH, new Color(220, 80, 30));
                g.setColor(new Color(30, 120, 220));
                g.drawString(angular ? "prograde" : "outward", w - right - 140, top + 14);
                g.setColor(new Color(220, 80, 30));
                g.drawString(angular ? "retrograde" : "inward", w - right - 70, top + 14);
                // peak of the positive curve
                int peak = 0;
                for (int i = 1; i < spectrum.rate().length; i++)
                    if (spectrum.powerPositive()[i] > spectrum.powerPositive()[peak])
                        peak = i;
                double pr = spectrum.rate()[peak];
                g.setColor(Color.DARK_GRAY);
                g.drawString(angular
                        ? String.format("peak %.2f °/h = %.1f min", pr * unit, 2 * Math.PI / pr / 60)
                        : String.format("peak %.0f km/s", pr), left + 6, h - bottom - 6);
                g.drawString("click to centre the band", left + 6, h - 6);
            }

            private void drawCurve(Graphics2D g, double[] power, double lpmin, double lpmax, int plotH, Color color) {
                g.setColor(color);
                int px = -1, py = -1;
                for (int i = 0; i < spectrum.rate().length; i++) {
                    if (power[i] <= 0)
                        continue;
                    int x = xOf(spectrum.rate()[i]);
                    double f = (Math.log10(power[i]) - lpmin) / (lpmax - lpmin);
                    int y = top + plotH - (int) (Math.clamp(f, 0, 1) * plotH);
                    if (px >= 0)
                        g.drawLine(px, py, x, y);
                    px = x;
                    py = y;
                }
            }
        }
    }

}
