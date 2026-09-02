package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SpinnerNumberModel;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.gui.CompletionNotifications;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.time.TimeSelectorPanel;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportFormat;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.ExportPreset;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.TimeUtils;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideToggleButton;

@SuppressWarnings("serial")
public class MoviePanel extends JPanel implements Player.StatusListener, ExportMovie.StatusListener, ViewState.PlaybackConfigListener, ViewState.RecordingConfigListener {

    private static final int FRAME_HOLD_REPEAT_MS = 125;
    private int fixedPreferredWidth = -1;

    private final TimeSelectorPanel timeSelectorPanel = new TimeSelectorPanel();

    private static TimeSlider timeSlider;
    private final JideButton playButton;

    private final RecordButton recordButton;

    private final JHVSpinner speedSpinner;
    private final JComboBox<ViewState.PlaybackSpeedUnit> speedUnitComboBox;
    private final JComboBox<Player.AdvanceMode> advanceModeComboBox;
    private final JRadioButton loopButton;
    private final JRadioButton shotButton;
    private final JRadioButton freeButton;
    private final JComboBox<ViewState.RecordingAspect> recordAspectComboBox;
    // Powers of two only: every consumer downstream (GPU textures, fulldome masters, video
    // encoders) is happiest there, and a free spinner mostly collected typos.
    private static final Integer[] LONG_SIDE_CHOICES = {256, 512, 1024, 2048, 4096, 8192, 16384};
    private final JComboBox<Integer> recordLongSideComboBox;
    private final JComboBox<ExportFormat> recordFormatComboBox;
    private final JComboBox<ExportFormat.Chroma> recordChromaComboBox;
    private final JComboBox<ExportFormat.Depth> recordDepthComboBox;
    private final JComboBox<String> recordPresetComboBox;
    private final javax.swing.JCheckBox allIntraCheckBox;
    private boolean syncingRecordFormat; // repopulating the two dependent combos fires their listeners

    /** The persisted format, falling back to H.264 for an absent or stale name. */
    public static ExportFormat storedFormat() {
        try {
            return ExportFormat.valueOf(Settings.getProperty("video.format"));
        } catch (Exception ignore) {
            return ExportFormat.H264;
        }
    }

    // Both are clamped to what the current codec can actually carry. A setting saved under one
    // codec is routinely impossible under the next (12-bit is HEVC-only here, RGB is not an x264
    // option at all), and the alternative to clamping is handing ffmpeg a pixel format its encoder
    // will reject at the very end of a long recording.
    public static ExportFormat.Chroma storedChroma() {
        ExportFormat.Chroma stored;
        try {
            stored = ExportFormat.Chroma.valueOf(Settings.getProperty("video.chroma"));
        } catch (Exception ignore) {
            stored = ExportFormat.Chroma.YUV420;
        }
        return storedFormat().clamp(stored);
    }

    public static ExportFormat.Depth storedDepth() {
        ExportFormat.Depth stored;
        try {
            stored = ExportFormat.Depth.valueOf(Settings.getProperty("video.depth"));
        } catch (Exception ignore) {
            stored = ExportFormat.Depth.EIGHT;
        }
        return storedFormat().clamp(storedChroma(), stored);
    }

    public static boolean isAllIntra() {
        return !"false".equals(Settings.getProperty("video.allIntra"));
    }
    private final JLabel recordDerivedLabel;
    private boolean syncingRecordSize;

    private final JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    private final JPanel recordPanel = new JPanel(new GridBagLayout());
    private final JLabel videoLengthLabel = new JLabel(); // estimated length of the recorded video

    private JPanel buttonPanel;
    private JComponent frameNumberPanel;
    private JPanel northTransport; // scrubber + play/prev/next/record + frame counter, docked at the top
    private JPanel playbackOptions; // speed / advance-mode / recording settings — the "Playback options" pane

    private static MoviePanel instance;

    public static MoviePanel getInstance() {
        return instance == null ? instance = new MoviePanel() : instance;
    }

    private MoviePanel() {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        // Time slider
        timeSlider = new TimeSlider(TimeSlider.HORIZONTAL, 0, 0, 0);

        // Control buttons
        buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 1, 0));
        int small = 18, big = 26;

        JideButton prevFrameButton = new JideButton(Buttons.backward);
        prevFrameButton.setFont(Buttons.getMaterialFont(small));
        prevFrameButton.setToolTipText("Step to previous frame");
        prevFrameButton.addActionListener(Actions.PREVIOUS_FRAME);
        HoldRepeat.install(prevFrameButton, FRAME_HOLD_REPEAT_MS);
        buttonPanel.add(prevFrameButton);

        playButton = new JideButton(Buttons.play);
        playButton.setFont(Buttons.getMaterialFont(big));
        playButton.setToolTipText("Play movie");
        playButton.addActionListener(Actions.PLAY_PAUSE);
        buttonPanel.add(playButton);

        JideButton nextFrameButton = new JideButton(Buttons.forward);
        nextFrameButton.setFont(Buttons.getMaterialFont(small));
        nextFrameButton.setToolTipText("Step to next frame");
        nextFrameButton.addActionListener(Actions.NEXT_FRAME);
        HoldRepeat.install(nextFrameButton, FRAME_HOLD_REPEAT_MS);
        buttonPanel.add(nextFrameButton);

        recordButton = new RecordButton(small);
        buttonPanel.add(recordButton);

        // Current frame number
        frameNumberPanel = timeSlider.getFrameNumberPanel();
        frameNumberPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));

        // The scrubber + play/prev/next/record + frame counter live in an always-visible top bar
        // (MainFrame docks northTransport); the sidebar pane keeps only settings + the time range.
        northTransport = new JPanel(new BorderLayout());
        northTransport.add(buttonPanel, BorderLayout.LINE_START);
        northTransport.add(timeSlider, BorderLayout.CENTER);
        northTransport.add(frameNumberPanel, BorderLayout.LINE_END);

        // Speed
        modePanel.add(new JLabel(" Play ", JLabel.RIGHT));

        speedSpinner = new JHVSpinner(ViewState.playbackData().speed(), ViewState.PLAYBACK_SPEED_MIN, ViewState.PLAYBACK_SPEED_MAX, 1);
        speedSpinner.setToolTipText("Maximum " + ViewState.PLAYBACK_SPEED_MAX + " fps");
        speedSpinner.addChangeListener(e -> updatePlaybackConfig());
        modePanel.add(speedSpinner);

        speedUnitComboBox = new JComboBox<>(ViewState.PlaybackSpeedUnit.values());
        speedUnitComboBox.addActionListener(e -> updatePlaybackConfig());
        modePanel.add(speedUnitComboBox);

        // Animation mode
        modePanel.add(new JLabel(" and ", JLabel.RIGHT));

        advanceModeComboBox = new JComboBox<>(new Player.AdvanceMode[]{Player.AdvanceMode.Loop, Player.AdvanceMode.Stop, Player.AdvanceMode.Swing});
        advanceModeComboBox.addActionListener(e -> ViewState.setPlaybackAdvanceMode((Player.AdvanceMode) advanceModeComboBox.getSelectedItem()));
        modePanel.add(advanceModeComboBox);

        // Record — right-justified and compact (no stretching)
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_END;
        c.weighty = 1;
        c.fill = GridBagConstraints.NONE;

        loopButton = new JRadioButton(ViewState.RecordingMode.LOOP.toString());
        shotButton = new JRadioButton(ViewState.RecordingMode.SHOT.toString());
        freeButton = new JRadioButton(ViewState.RecordingMode.FREE.toString());

        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 1; // glue column absorbs slack so the record controls pack to the right
        recordPanel.add(new JLabel("Record ", JLabel.RIGHT), c);
        c.weightx = 0;
        c.gridx = 1;
        recordPanel.add(loopButton, c);
        c.gridx = 2;
        recordPanel.add(shotButton, c);
        c.gridx = 3;
        recordPanel.add(freeButton, c);

        ButtonGroup group = new ButtonGroup();
        group.add(loopButton);
        group.add(shotButton);
        group.add(freeButton);

        loopButton.addActionListener(e -> ViewState.setRecordingMode(ViewState.RecordingMode.LOOP));
        shotButton.addActionListener(e -> ViewState.setRecordingMode(ViewState.RecordingMode.SHOT));
        freeButton.addActionListener(e -> ViewState.setRecordingMode(ViewState.RecordingMode.FREE));

        c.gridy = 1;
        c.gridx = 0;
        com.jidesoft.swing.JideToggleButton printableToggle = new com.jidesoft.swing.JideToggleButton("Frame");
        printableToggle.setToolTipText("Show the recorded video's printable area (the output resolution's aspect) on the canvas");
        printableToggle.setSelected(org.helioviewer.jhv.display.Display.showPrintableArea);
        printableToggle.addActionListener(e -> {
            org.helioviewer.jhv.display.Display.showPrintableArea = printableToggle.isSelected();
            org.helioviewer.jhv.display.DisplayController.display();
        });
        recordPanel.add(printableToggle, c);

        c.gridx = 1;
        videoLengthLabel.setFont(UIGlobals.uiFontSmall);
        videoLengthLabel.setToolTipText("Estimated length of the recorded video at the current speed and frame count");
        recordPanel.add(videoLengthLabel, c);
        c.gridx = 2;
        recordPanel.add(new JLabel("Aspect ", JLabel.RIGHT), c);

        // Aspect and resolution are separate choices, and the short side is derived from them
        // rather than typed. That makes an inconsistent width/height pair unrepresentable, and
        // makes "2:1 at 8K" one decision instead of a lookup in a table of fixed pairs.
        recordAspectComboBox = new JComboBox<>(ViewState.RecordingAspect.values());
        recordAspectComboBox.setSelectedItem(ViewState.getRecordingAspect());
        recordAspectComboBox.setToolTipText("Output aspect ratio. 2:1 is the equirectangular master a fulldome projector wants.");
        recordAspectComboBox.addActionListener(e -> {
            if (!syncingRecordSize)
                ViewState.setRecordingAspect((ViewState.RecordingAspect) recordAspectComboBox.getSelectedItem());
        });
        c.gridx = 3;
        recordPanel.add(recordAspectComboBox, c);

        c.gridy = 2;
        c.gridx = 0;

        c.gridx = 2;
        recordPanel.add(new JLabel("Long side ", JLabel.RIGHT), c);

        recordLongSideComboBox = new JComboBox<>(LONG_SIDE_CHOICES);
        recordLongSideComboBox.setSelectedItem(nearestLongSide(ViewState.getRecordingLongSide()));
        recordLongSideComboBox.setToolTipText("Pixels on the long axis; the short side follows from the aspect. Clamped to what the GPU can render.");
        recordLongSideComboBox.addActionListener(e -> {
            if (!syncingRecordSize)
                ViewState.setRecordingLongSide((Integer) recordLongSideComboBox.getSelectedItem());
        });
        c.gridx = 3;
        recordPanel.add(recordLongSideComboBox, c);

        // Format sits with the record controls rather than in Settings, where it was: it is a
        // per-recording decision made at the same moment as aspect and resolution, not a
        // preference set once. Both persist through Settings, so the choice still survives a
        // restart and old sessions keep whatever they had.
        // Built before the format combo, whose listener greys it out: a frame-per-file format is
        // all-intra by definition, so the choice would be meaningless there. Default on, because
        // this footage is faint low-contrast structure over noise, which is exactly what
        // inter-frame prediction spends its bits away from.
        allIntraCheckBox = new javax.swing.JCheckBox("Every frame a keyframe", isAllIntra());
        allIntraCheckBox.setFont(UIGlobals.uiFontSmall);
        allIntraCheckBox.setToolTipText("No inter-frame prediction: nothing is smeared or motion-compensated between frames, and scrubbing is frame-exact. Roughly 3-10x the file size, and still lossy within each frame -- use the PNG series for frame fidelity.");
        allIntraCheckBox.addItemListener(e -> {
            Settings.setProperty("video.allIntra", Boolean.toString(allIntraCheckBox.isSelected()));
            if (!syncingRecordFormat)
                syncPresetSelection();
        });
        allIntraCheckBox.setEnabled(!storedFormat().isSeries());

        c.gridy = 3;
        c.gridx = 2;
        recordPanel.add(new JLabel("Preset ", JLabel.RIGHT), c);

        recordPresetComboBox = new JComboBox<>();
        recordPresetComboBox.addActionListener(e -> {
            if (syncingRecordFormat)
                return;
            Object sel = recordPresetComboBox.getSelectedItem();
            if (sel == null || ExportPreset.CUSTOM.equals(sel))
                return;
            ExportPreset preset = ExportPreset.byName(sel.toString());
            if (preset != null)
                applyPreset(preset);
        });

        JButton savePreset = new JButton("Save\u2026");
        savePreset.setFont(UIGlobals.uiFontSmall);
        savePreset.setToolTipText("Name the current settings as a preset, or overwrite an existing one");
        savePreset.addActionListener(e -> saveCurrentAsPreset());

        JButton deletePreset = new JButton("Delete");
        deletePreset.setFont(UIGlobals.uiFontSmall);
        deletePreset.setToolTipText("Remove the selected saved preset. A built-in rung you have overwritten reverts to its original.");
        deletePreset.addActionListener(e -> deleteSelectedPreset());

        JPanel presetPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        presetPanel.add(recordPresetComboBox);
        presetPanel.add(savePreset);
        presetPanel.add(deletePreset);
        c.gridx = 3;
        recordPanel.add(presetPanel, c);

        c.gridy = 4;
        c.gridx = 2;
        recordPanel.add(new JLabel("Format ", JLabel.RIGHT), c);

        recordFormatComboBox = new JComboBox<>(ExportFormat.values());
        recordFormatComboBox.setSelectedItem(storedFormat());
        recordFormatComboBox.setToolTipText("Container and codec. The series formats write one file per frame into their own directory.");
        c.gridx = 3;
        recordPanel.add(recordFormatComboBox, c);

        recordChromaComboBox = new JComboBox<>();
        recordChromaComboBox.setToolTipText("How colour is sampled. 4:2:0 keeps one colour sample per 2x2 pixels and is what plays everywhere; 4:4:4 keeps one per pixel; RGB skips the colour conversion entirely. Subsampling assumes the eye resolves colour poorly, which is false for a colour table.");
        recordDepthComboBox = new JComboBox<>();
        recordDepthComboBox.setToolTipText("Bits per channel written. Above 8 the capture is taken at 16-bit float too. More depth mainly buys smooth gradients free of banding, which barely shows in PSNR and plainly shows on a corona.");

        recordFormatComboBox.addActionListener(e -> {
            ExportFormat sel = (ExportFormat) recordFormatComboBox.getSelectedItem();
            if (sel != null) {
                Settings.setProperty("video.format", sel.name());
                allIntraCheckBox.setEnabled(!sel.isSeries() && sel != ExportFormat.FFV1);
                syncPixelCombos();
            }
        });
        recordChromaComboBox.addActionListener(e -> {
            if (syncingRecordFormat)
                return;
            if (recordChromaComboBox.getSelectedItem() instanceof ExportFormat.Chroma sel) {
                Settings.setProperty("video.chroma", sel.name());
                syncPixelCombos(); // the depths on offer depend on it (FFV1 has no 8-bit RGB)
            }
        });
        recordDepthComboBox.addActionListener(e -> {
            if (syncingRecordFormat)
                return;
            if (recordDepthComboBox.getSelectedItem() instanceof ExportFormat.Depth sel)
                Settings.setProperty("video.depth", sel.name());
        });

        c.gridy = 5;
        c.gridx = 2;
        recordPanel.add(new JLabel("Colour ", JLabel.RIGHT), c);
        JPanel pixelPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        pixelPanel.add(recordChromaComboBox);
        pixelPanel.add(recordDepthComboBox);
        c.gridx = 3;
        recordPanel.add(pixelPanel, c);

        c.gridy = 6;
        c.gridx = 3;
        recordPanel.add(allIntraCheckBox, c);

        c.gridy = 7;
        c.gridx = 3;
        recordDerivedLabel = new JLabel();
        recordDerivedLabel.setFont(UIGlobals.uiFontSmall);
        recordDerivedLabel.setToolTipText("The size that will actually be written");
        recordPanel.add(recordDerivedLabel, c);
        c.gridy = 1;

        syncPixelCombos();
        syncPresetList(null);

        timeSelectorPanel.addListener(Layers.timeSelectionListener);

        // Playback/recording settings, exposed as their own top-level "Playback options" pane.
        // The master time range is exposed separately and placed atop the Image Layers pane.
        playbackOptions = new JPanel();
        playbackOptions.setLayout(new BoxLayout(playbackOptions, BoxLayout.PAGE_AXIS));
        playbackOptions.add(modePanel);
        playbackOptions.add(recordPanel);

        Player.addStatusListener(this);
        ExportMovie.addStatusListener(this);
        ViewState.addPlaybackConfigListener(this);
        ViewState.addRecordingConfigListener(this);

        updateVideoLength();
    }

    private void applyPreset(ExportPreset preset) {
        // Written through Settings rather than by driving the combos: syncPixelCombos reads
        // Settings, so setting the stored values first and refreshing once is both shorter and
        // free of the half-applied states a sequence of setSelectedItem calls passes through.
        Settings.setProperty("video.format", preset.format().name());
        Settings.setProperty("video.chroma", preset.chroma().name());
        Settings.setProperty("video.depth", preset.depth().name());
        Settings.setProperty("video.allIntra", Boolean.toString(preset.allIntra()));

        syncingRecordFormat = true;
        try {
            recordFormatComboBox.setSelectedItem(preset.format());
            allIntraCheckBox.setSelected(preset.allIntra());
            allIntraCheckBox.setEnabled(!preset.format().isSeries() && preset.format() != ExportFormat.FFV1);
        } finally {
            syncingRecordFormat = false;
        }
        syncPixelCombos();
    }

    private void saveCurrentAsPreset() {
        Object current = recordPresetComboBox.getSelectedItem();
        String suggested = current == null || ExportPreset.CUSTOM.equals(current) ? "" : current.toString();
        String name = JOptionPane.showInputDialog(this, "Name for these settings:", suggested);
        if (name == null)
            return;
        name = name.strip();
        if (name.isEmpty() || ExportPreset.CUSTOM.equals(name)) {
            JOptionPane.showMessageDialog(this, "That name is reserved.", "Preset", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String note = JOptionPane.showInputDialog(this,
                "What is it for, and what does it give up?\n(Shown as the tooltip.)", "");
        ExportPreset.save(new ExportPreset(name, note == null ? "" : note.strip(),
                storedFormat(), storedChroma(), storedDepth(), isAllIntra(), false));
        syncPresetList(name);
    }

    private void deleteSelectedPreset() {
        Object sel = recordPresetComboBox.getSelectedItem();
        if (sel == null || ExportPreset.CUSTOM.equals(sel))
            return;
        String name = sel.toString();
        ExportPreset existing = ExportPreset.byName(name);
        if (existing != null && existing.builtIn()) {
            JOptionPane.showMessageDialog(this, "\"" + name + "\" is a built-in rung and cannot be removed.",
                    "Preset", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete the preset \"" + name + "\"?", "Preset",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        ExportPreset.delete(name);
        syncPresetList(null);
    }

    /** Rebuild the preset list, then point it at {@code select} or at whatever the settings match. */
    private void syncPresetList(String select) {
        syncingRecordFormat = true;
        try {
            recordPresetComboBox.removeAllItems();
            recordPresetComboBox.addItem(ExportPreset.CUSTOM);
            ExportPreset.all().forEach(p -> recordPresetComboBox.addItem(p.name()));
        } finally {
            syncingRecordFormat = false;
        }
        if (select != null && ExportPreset.byName(select) != null) {
            ExportPreset p = ExportPreset.byName(select);
            applyPreset(p);
            syncingRecordFormat = true;
            try {
                recordPresetComboBox.setSelectedItem(select);
            } finally {
                syncingRecordFormat = false;
            }
        } else {
            syncPresetSelection();
        }
    }

    /**
     * Point the preset box at whichever rung the current settings are, or at Custom when they are
     * none of them. Called after every change to the four controls a preset covers, so the box is
     * a readout of the settings rather than a separate thing that can disagree with them.
     */
    private void syncPresetSelection() {
        ExportPreset match = ExportPreset.matching(storedFormat(), storedChroma(), storedDepth(), isAllIntra());
        syncingRecordFormat = true;
        try {
            recordPresetComboBox.setSelectedItem(match == null ? ExportPreset.CUSTOM : match.name());
            recordPresetComboBox.setToolTipText(match == null
                    ? "The controls below do not match any saved preset."
                    : "<html><body style='width:340px'>" + match.description() + "</body></html>");
        } finally {
            syncingRecordFormat = false;
        }
    }

    /**
     * Refill the colour and depth combos with what the current codec can carry, keeping the user's
     * choice where it survives and falling to the nearest legal one where it does not.
     *
     * <p>The guard matters: removeAllItems and addItem both fire the listeners, so without it a
     * repopulation writes whatever lands in the box first back into Settings, and switching to a
     * codec that cannot do the user's depth silently rewrites their preference rather than just
     * greying it out for the moment.
     */
    private void syncPixelCombos() {
        ExportFormat format = storedFormat();
        ExportFormat.Chroma chroma = storedChroma();
        ExportFormat.Depth depth = storedDepth();

        syncingRecordFormat = true;
        try {
            recordChromaComboBox.removeAllItems();
            format.chromas().forEach(recordChromaComboBox::addItem);
            recordChromaComboBox.setSelectedItem(chroma);

            recordDepthComboBox.removeAllItems();
            format.depths(chroma).forEach(recordDepthComboBox::addItem);
            recordDepthComboBox.setSelectedItem(depth);
        } finally {
            syncingRecordFormat = false;
        }

        // A series fixes both; leaving live combos there would imply a choice that is not offered.
        boolean configurable = format.isConfigurable();
        recordChromaComboBox.setEnabled(configurable);
        recordDepthComboBox.setEnabled(configurable);

        // Write back the clamped pair, so what is shown and what a recording will use agree even
        // when the stored setting was impossible under this codec.
        Settings.setProperty("video.chroma", chroma.name());
        Settings.setProperty("video.depth", depth.name());

        syncPresetSelection();
    }

    public void setTime(long start, long end) {
        timeSelectorPanel.setTime(start, end);
    }

    public long getStartTime() {
        return timeSelectorPanel.getStartTime();
    }

    public long getEndTime() {
        return timeSelectorPanel.getEndTime();
    }

    public TimeSelectorPanel getTimeSelectorPanel() {
        return timeSelectorPanel;
    }

    // The always-visible top transport bar (scrubber + play/prev/next/record + frame counter).
    // MainFrame docks this at the top so playback is reachable whether or not the sidebar is open.
    public JComponent getNorthTransport() {
        return northTransport;
    }

    private static class RecordButton extends JideToggleButton implements ActionListener {
        RecordButton(float fontSize) {
            super(Buttons.record);
            setFont(Buttons.getMaterialFont(fontSize));
            setForeground(Color.decode("#800000"));
            setToolTipText("Record movie");
            addActionListener(this);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (isSelected()) {
                Commands.recordStart(CompletionNotifications.recordingContext(), null);
            } else {
                Commands.recordStop();
            }
        }
    }

    // The playback speed / advance-mode / recording settings, shown as the "Playback options" pane.
    public JComponent getPlaybackOptions() {
        return playbackOptions;
    }

    public void setFixedPreferredWidth(int width) {
        fixedPreferredWidth = width;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        if (fixedPreferredWidth > 0)
            size.width = fixedPreferredWidth;
        return size;
    }

    private void updatePlaybackConfig() {
        int speed = ((Number) speedSpinner.getValue()).intValue();
        ViewState.PlaybackSpeedUnit unit = (ViewState.PlaybackSpeedUnit) speedUnitComboBox.getSelectedItem();
        if (unit == null)
            return;
        ViewState.setPlaybackSpeed(speed, unit);
    }

    // Length of the recorded video for the actually loaded movie at the current speed.
    private void updateVideoLength() {
        if (!Player.isAvailable()) {
            videoLengthLabel.setText("");
            return;
        }
        double seconds = ViewState.estimateVideoSeconds(Player.getMaximumFrameNumber() + 1, Player.getEndTime() - Player.getStartTime());
        videoLengthLabel.setText("≈ " + TimeUtils.formatDurationSig(Math.round(seconds * 1000)));
    }

    public static TimeSlider getTimeSlider() {
        return timeSlider;
    }

    @Override
    public void movieStatusChanged() {
        boolean playing = Player.isPlaying();

        if (playing) {
            playButton.setText(Buttons.pause);
            playButton.setToolTipText("Pause movie");
        } else {
            playButton.setText(Buttons.play);
            playButton.setToolTipText("Play movie");
        }
        updateVideoLength(); // frame count / span may have changed
    }

    @Override
    public void recordingStatusChanged() {
        boolean recording = ExportMovie.isRecording();
        if (recordButton.isSelected() != recording)
            recordButton.setSelected(recording);
        ComponentUtils.setEnabled(modePanel, !recording);
        ComponentUtils.setEnabled(recordPanel, !recording);
    }

    @Override
    public void playbackConfigChanged() {
        ViewState.PlaybackData playbackData = ViewState.playbackData();

        if (advanceModeComboBox.getSelectedItem() != playbackData.advanceMode())
            advanceModeComboBox.setSelectedItem(playbackData.advanceMode());

        int speed = playbackData.speed();
        // Do not call speedSpinner.getValue() here: JHVSpinner commits editor text on read,
        // and this passive UI sync must not force-commit an in-progress edit.
        Number spinnerSpeed = ((SpinnerNumberModel) speedSpinner.getModel()).getNumber();
        if (spinnerSpeed.intValue() != speed)
            speedSpinner.setValue(speed);

        if (speedUnitComboBox.getSelectedItem() != playbackData.speedUnit())
            speedUnitComboBox.setSelectedItem(playbackData.speedUnit());

        updateVideoLength();
    }

    @Override
    public void recordingConfigChanged() {
        ViewState.RecordingData recordingData = ViewState.recordingData();
        switch (recordingData.mode()) {
            case LOOP -> loopButton.setSelected(true);
            case SHOT -> shotButton.setSelected(true);
            case FREE -> freeButton.setSelected(true);
        }
        syncingRecordSize = true;
        try {
            if (recordAspectComboBox.getSelectedItem() != recordingData.aspect())
                recordAspectComboBox.setSelectedItem(recordingData.aspect());
            // SAMP and old sessions can still carry an off-list long side; show the nearest
            // choice without writing it back, and let the derived label report the true size.
            Integer shown = nearestLongSide(recordingData.longSide());
            if (!shown.equals(recordLongSideComboBox.getSelectedItem()))
                recordLongSideComboBox.setSelectedItem(shown);
        } finally {
            syncingRecordSize = false;
        }
        boolean fixed = recordingData.aspect().isFixed();
        recordLongSideComboBox.setEnabled(fixed);
        ViewState.Size out = recordingData.size();
        recordDerivedLabel.setText(fixed ? out.width() + " \u00d7 " + out.height() : "follows the window");
        org.helioviewer.jhv.display.DisplayController.display(); // the capture overlay moved
    }

    /**
     * Resize the view so its aspect matches the output, so composition is not done inside a
     * letterbox. A button rather than automatic, because reshaping on every aspect change would
     * fight presentation mode.
     */
    private static Integer nearestLongSide(int longSide) {
        Integer best = LONG_SIDE_CHOICES[0];
        for (Integer choice : LONG_SIDE_CHOICES)
            if (Math.abs(choice - longSide) < Math.abs(best - longSide))
                best = choice;
        return best;
    }

}
