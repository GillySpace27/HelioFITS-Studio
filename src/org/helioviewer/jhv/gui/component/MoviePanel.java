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
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SpinnerNumberModel;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.gui.CompletionNotifications;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.time.TimeSelectorPanel;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;
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
        // Fitting the window to the output aspect, rather than reshaping to it automatically:
        // inscribing 2:1 in a tall window leaves a cramped letterbox to compose in, but forcing
        // the window would fight presentation mode, where the projector and presenter windows
        // are deliberately different shapes.
        javax.swing.JButton fitWindowButton = new javax.swing.JButton("Fit");
        fitWindowButton.setToolTipText("Resize the view to the output aspect, so composition is not done in a letterbox");
        fitWindowButton.addActionListener(e -> fitWindowToOutputAspect());
        recordPanel.add(fitWindowButton, c);

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

        c.gridy = 3;
        c.gridx = 3;
        recordDerivedLabel = new JLabel();
        recordDerivedLabel.setFont(UIGlobals.uiFontSmall);
        recordDerivedLabel.setToolTipText("The size that will actually be written");
        recordPanel.add(recordDerivedLabel, c);
        c.gridy = 1;

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

    private static void fitWindowToOutputAspect() {
        ViewState.RecordingData data = ViewState.recordingData();
        if (!data.aspect().isFixed())
            return;
        org.helioviewer.jhv.display.Viewport vp = org.helioviewer.jhv.display.Display.fullViewport;
        if (vp.width <= 0 || vp.height <= 0)
            return;
        java.awt.Frame frame = org.helioviewer.jhv.gui.MainFrame.get();
        if (frame == null)
            return;
        // Keep the canvas area roughly constant while changing its shape, so the window neither
        // grows off the screen nor collapses.
        double area = (double) vp.width * vp.height;
        double ratio = data.aspect().ratio();
        int targetW = (int) Math.round(Math.sqrt(area * ratio));
        int targetH = (int) Math.round(Math.sqrt(area / ratio));
        java.awt.Dimension frameSize = frame.getSize();
        frame.setSize(frameSize.width + (targetW - vp.width), frameSize.height + (targetH - vp.height));
        frame.validate();
    }

}
