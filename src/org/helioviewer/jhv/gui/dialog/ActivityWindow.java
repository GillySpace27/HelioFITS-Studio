package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import org.helioviewer.jhv.activity.Activity;
import org.helioviewer.jhv.activity.Driver;
import org.helioviewer.jhv.activity.MemoryHealth;
import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.io.api.LocalApi;

/**
 * What Studio is doing, as it does it: every command from a script or assistant and its answer,
 * every download, file read and export, HelioFITS Core's own output while it runs, the memory light's
 * changes, and Studio's log lines, one line each with its time and light. The footer's lights and
 * the driver chip open it on their own lines.
 *
 * <p>It reads {@link Activity}'s ring four times a second while open and nothing while closed. The
 * Task Manager stays the place for what is queued, with progress and cancel; this is the record.
 */
public final class ActivityWindow {

    private static final int POLL_MS = 250;
    private static final int MAX_LINES = Activity.CAPACITY;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.S").withZone(ZoneOffset.UTC);
    private static final List<Activity.Kind> SHOWN = List.of(Activity.Kind.CMD, Activity.Kind.CPU, Activity.Kind.DISK,
            Activity.Kind.NET, Activity.Kind.MEM, Activity.Kind.CORE, Activity.Kind.LOG);

    private static final ActivityWindow[] instance = {null};

    private final JDialog dialog;
    private final JTextArea text = new JTextArea();
    private final JScrollPane scroller = new JScrollPane(text);
    private final JLabel status = new JLabel(" ");
    private final JCheckBox pause = new JCheckBox("Pause");
    private final JButton apiOff = new JButton("Turn off local API");
    private final Map<Activity.Kind, JToggleButton> filters = new EnumMap<>(Activity.Kind.class);
    private final Timer timer = new Timer(POLL_MS, e -> poll());
    private long seq;
    private int lines;

    /** Opens the window (or brings it forward), showing only {@code kind}'s lines when one is given. */
    public static void open(@Nullable Activity.Kind kind) {
        if (instance[0] == null)
            instance[0] = new ActivityWindow();
        instance[0].show(kind);
    }

    private ActivityWindow() {
        text.setEditable(false);
        text.setFont(UIGlobals.uiFontMonoSmall);
        scroller.setPreferredSize(new Dimension(920, 460));

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 2));
        for (Activity.Kind k : SHOWN) {
            JToggleButton b = new JToggleButton(k.label, true);
            b.setToolTipText(k.meaning);
            b.addActionListener(e -> reload());
            filters.put(k, b);
            chips.add(b);
        }
        JButton all = new JButton("All");
        all.addActionListener(e -> {
            filters.values().forEach(b -> b.setSelected(true));
            reload();
        });
        chips.add(all);

        pause.setToolTipText("Stop adding lines while you read; they are kept and appear when you resume.");
        pause.addActionListener(e -> {
            if (!pause.isSelected())
                poll();
        });
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()), null));
        JButton clear = new JButton("Clear view");
        clear.addActionListener(e -> {
            text.setText("");
            lines = 0;
        });
        JButton log = new JButton("Log file…");
        log.setToolTipText("The whole log file, as Help > Show Log tails it");
        log.addActionListener(e -> LogWindow.get().showDialog());
        JButton tasks = new JButton("Task Manager…");
        tasks.setToolTipText("What is queued or running, with progress and cancel");
        tasks.addActionListener(e -> org.helioviewer.jhv.gui.component.JobQueuePanel.open());
        apiOff.setToolTipText("Stop answering scripts and assistants (Settings > General > Local API turns it on again)");
        apiOff.addActionListener(e -> {
            LocalApi.setEnabled(false);
            refreshStatus();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(pause);
        buttons.add(clear);
        buttons.add(copy);
        buttons.add(log);
        buttons.add(tasks);
        buttons.add(apiOff);

        status.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel top = new JPanel(new BorderLayout());
        top.add(chips, BorderLayout.PAGE_START);
        top.add(status, BorderLayout.PAGE_END);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.PAGE_START);
        content.add(scroller, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);

        dialog = new JDialog(MainFrame.get(), AppInfo.programName + " Activity", false); // never modal: the point is to watch
        dialog.setType(Window.Type.UTILITY);
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                timer.stop(); // nothing to read while nobody is looking
            }
        });
    }

    private void show(@Nullable Activity.Kind kind) {
        if (kind != null) {
            filters.forEach((k, b) -> b.setSelected(k == kind));
            reload();
        } else
            poll();
        timer.start();
        dialog.setVisible(true);
        dialog.toFront();
    }

    private Set<Activity.Kind> wanted() {
        Set<Activity.Kind> out = EnumSet.noneOf(Activity.Kind.class);
        filters.forEach((k, b) -> {
            if (b.isSelected())
                out.add(k);
        });
        return out;
    }

    private void reload() {
        text.setText("");
        lines = 0;
        seq = 0;
        poll();
    }

    private void poll() {
        refreshStatus();
        if (pause.isSelected())
            return;
        Set<Activity.Kind> want = wanted();
        if (want.isEmpty()) {
            seq = Activity.nextSeq() - 1;
            return;
        }
        List<Activity.Event> events = Activity.since(seq, want, MAX_LINES);
        // Advance past everything read, wanted or not, so a filtered view does not re-scan the same events.
        seq = Math.max(seq, Activity.nextSeq() - 1);
        if (events.isEmpty())
            return;
        StringBuilder sb = new StringBuilder();
        for (Activity.Event e : events)
            sb.append(line(e)).append('\n');
        JScrollBar bar = scroller.getVerticalScrollBar();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 2;
        text.append(sb.toString());
        lines += events.size();
        if (lines > MAX_LINES) { // keep the window to what the ring holds
            try {
                int cut = text.getLineEndOffset(lines - MAX_LINES - 1);
                text.replaceRange("", 0, cut);
                lines = MAX_LINES;
            } catch (javax.swing.text.BadLocationException ex) {
                text.setText("");
                lines = 0;
            }
        }
        if (atBottom) // follow only when the reader was following
            text.setCaretPosition(text.getDocument().getLength());
    }

    /** One event as the window shows it: time (UTC), light, source, text. */
    public static String line(Activity.Event e) {
        return TIME.format(Instant.ofEpochMilli(e.timeMillis())) + "  " + String.format("%-4s", e.kind().name()) + "  " + e.source() + "  " + e.text();
    }

    private void refreshStatus() {
        apiOff.setEnabled(LocalApi.isEnabled());
        Driver.State s = Driver.current(System.nanoTime());
        MemoryHealth.Reading m = MemoryHealth.latest();
        String driving = s == null ? (LocalApi.isEnabled() ? "Local API on; nobody is driving." : "Local API off.") : "Driven by " + s.name() + ": " + s.command() + ".";
        String memory = m == null ? "" : " Memory " + m.level().name().toLowerCase(java.util.Locale.ROOT) + (m.level() == MemoryHealth.Level.OK ? "." : ": " + m.reason() + ". " + m.advice());
        status.setText(driving + memory);
        status.setToolTipText(m == null ? null : m.summary());
    }

}
