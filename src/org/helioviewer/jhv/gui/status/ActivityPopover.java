package org.helioviewer.jhv.gui.status;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.Timer;

import org.helioviewer.jhv.activity.Activity;
import org.helioviewer.jhv.activity.Driver;
import org.helioviewer.jhv.activity.IoCounters;
import org.helioviewer.jhv.activity.MemoryHealth;
import org.helioviewer.jhv.activity.NetActivity;
import org.helioviewer.jhv.app.MemoryBudget;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.component.JobQueuePanel;
import org.helioviewer.jhv.gui.component.MenuBar;
import org.helioviewer.jhv.gui.dialog.ActivityWindow;
import org.helioviewer.jhv.io.api.LocalApi;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.study.JobQueue;

/**
 * What clicking a footer light opens: a small live panel, the same shape for every light, that says
 * what that kind of work is doing right now, lets you stop what can be stopped, and links to where
 * that kind of work is managed.
 *
 * <ul>
 * <li>CMD: who is driving, their last commands, Turn off local API.
 * <li>CPU: what is computing (a Fourier filter, a recording, the export queue), with Stop where it
 * can be stopped; Task Manager.
 * <li>DISK: the traffic this run and the last files touched; Cache Manager, Exports folder.
 * <li>NET: each transfer under way with its progress and Stop; Task Manager.
 * <li>MEM: the health reading, what is short and what to do; Free up memory, Memory settings.
 * </ul>
 *
 * <p>Every panel ends with Show log, which opens the Activity window on that light's lines. The
 * panel is a popup, so a click anywhere else closes it, and it redraws twice a second while open.
 * Stop is only offered where Studio can stop the work; a row that cannot be stopped says why.
 */
public final class ActivityPopover {

    private static final int WIDTH = 440, REFRESH_MS = 500;
    private static final int RECENT = 6;
    private static final JPopupMenu[] open = {null};

    private final Activity.Kind kind;
    private final JPopupMenu popup = new JPopupMenu();
    private final JPanel root = new JPanel(new BorderLayout());
    private final Timer timer = new Timer(REFRESH_MS, e -> refresh());
    @Nullable
    private String note; // what the last button did, shown until the next one

    /** Opens the panel for {@code kind} above {@code anchor}, closing any that is open. */
    public static void show(Activity.Kind kind, Component anchor, int x) {
        if (open[0] != null)
            open[0].setVisible(false);
        ActivityPopover p = new ActivityPopover(kind);
        p.refresh();
        p.timer.start();
        Dimension size = p.popup.getPreferredSize();
        p.popup.show(anchor, x - size.width / 2, -size.height - 4);
        open[0] = p.popup;
    }

    private ActivityPopover(Activity.Kind _kind) {
        kind = _kind;
        root.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        popup.add(root);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                timer.stop();
                if (open[0] == popup)
                    open[0] = null;
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                timer.stop();
            }
        });
    }

    private void refresh() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(header());
        body.add(Box.createVerticalStrut(6));
        body.add(new JSeparator());
        body.add(Box.createVerticalStrut(4));
        List<JComponent> rows = rows();
        if (rows.isEmpty())
            rows.add(plain(emptyText(), true));
        rows.forEach(body::add);
        if (note != null)
            body.add(plain(note, true));
        body.add(Box.createVerticalStrut(4));
        body.add(new JSeparator());
        body.add(footer());
        root.removeAll();
        root.add(body, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(WIDTH, body.getPreferredSize().height + 16));
        root.revalidate();
        popup.pack();
        root.repaint();
    }

    // header and footer: the same on every light ---------------------------------------------------

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(new Dot(ActivityLightsPanel.color(kind, Theme.current()), Activity.lit(kind, System.nanoTime())), BorderLayout.LINE_START);
        JLabel title = new JLabel(kind.label);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JLabel state = new JLabel(stateLine());
        state.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        JPanel text = new JPanel(new BorderLayout());
        text.add(title, BorderLayout.PAGE_START);
        text.add(state, BorderLayout.PAGE_END);
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    private JComponent footer() {
        JPanel p = new JPanel(new BorderLayout());
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 4));
        buttons().forEach(left::add);
        JButton log = link("Show log…", "Open the Activity window on " + kind.label + " lines", () -> ActivityWindow.open(kind));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 4));
        right.add(log);
        p.add(left, BorderLayout.CENTER);
        p.add(right, BorderLayout.LINE_END);
        return p;
    }

    // what each light says -------------------------------------------------------------------------

    private String stateLine() {
        long now = System.nanoTime();
        return switch (kind) {
            case CMD -> {
                Driver.State s = Driver.current(now);
                yield s == null ? (LocalApi.isEnabled() ? "Local API on; nobody is driving Studio" : "Local API off")
                        : "Driven by " + s.name() + (s.inFlight() ? ", running " : ", last ") + s.command();
            }
            case CPU -> {
                int n = compute().size();
                yield n == 0 ? "Idle" : n + (n == 1 ? " job" : " jobs") + " computing";
            }
            case DISK -> {
                IoCounters.Sample s = IoCounters.sample();
                yield s == null ? "Disk counters not available on this system" : "This run: " + NetActivity.size(s.read()) + " read, " + NetActivity.size(s.written()) + " written";
            }
            case NET -> {
                int n = transfers().size();
                yield (n == 0 ? "No transfers" : n + (n == 1 ? " transfer" : " transfers")) + "; " + NetActivity.size(Activity.netBytesTotal()) + " downloaded this run";
            }
            case MEM -> {
                MemoryHealth.Reading m = MemoryHealth.latest();
                yield m == null ? "No reading yet" : switch (m.level()) {
                    case OK -> "Healthy";
                    case TIGHT -> "Tight";
                    case CRITICAL -> "Short of memory";
                };
            }
            default -> "";
        };
    }

    private String emptyText() {
        return switch (kind) {
            case CMD -> "No commands yet. Commands from scripts and assistants appear here.";
            case CPU -> "Nothing is computing. Decoding frames and filters show here while they run.";
            case DISK -> "No files touched yet.";
            case NET -> "Nothing is downloading.";
            default -> "";
        };
    }

    private List<JComponent> rows() {
        List<JComponent> out = new ArrayList<>();
        switch (kind) {
            case CMD -> recent(out, Activity.Kind.CMD);
            case CPU -> {
                for (ActivityRows.Row r : compute())
                    out.add(workRow(r));
                for (JobQueue.Job j : JobQueue.INSTANCE.jobs())
                    if (j.state() == JobQueue.State.QUEUED || j.state() == JobQueue.State.RUNNING)
                        out.add(row("#" + j.id + " " + j.label, j.state() == JobQueue.State.RUNNING ? j.message() : "Queued", fraction(j),
                                "Cancel", "Cancel this export job; what it already wrote is kept", () -> JobQueue.INSTANCE.cancel(j)));
            }
            case NET -> {
                if (org.helioviewer.jhv.thread.FetchGate.isPaused())
                    out.add(row("Downloads on hold", "Studio held new downloads after it stopped responding, or a stop was sent",
                            -1, "Resume", "Let held downloads start", org.helioviewer.jhv.activity.Watchdog::resume));
                for (ActivityRows.Row r : transfers())
                    out.add(workRow(r));
            }
            case DISK -> recent(out, Activity.Kind.DISK);
            case MEM -> memory(out);
            default -> {
            }
        }
        return out;
    }

    /** A line of work and, where Studio can stop it, a Stop button; where it cannot, the reason. */
    private JComponent workRow(ActivityRows.Row r) {
        if (r.layer() != null && !r.failed() && !r.detail().startsWith("Fourier"))
            return row(r.name(), r.detail(), r.fraction(), "Stop", "Stop this layer's download; the frames already here stay", () -> r.layer().stopDownloading());
        if ("Recording".equals(r.name()) && ExportMovie.isRecording())
            return row(r.name(), r.detail(), r.fraction(), "Stop", "Stop capturing; the frames so far are encoded into the movie", ExportMovie::shallStop);
        if (r.detail().startsWith("Fourier"))
            return row(r.name(), r.detail() + " (change the layer's filter to stop it)", r.fraction(), null, null, null);
        return row(r.name(), r.detail(), r.fraction(), null, null, null);
    }

    private void memory(List<JComponent> out) {
        MemoryHealth.Reading m = MemoryHealth.latest();
        if (m == null)
            return;
        if (m.level() != MemoryHealth.Level.OK)
            out.add(plain((m.reason().isEmpty() ? "" : capitalize(m.reason()) + ". ") + m.advice(), false));
        else
            out.add(plain("The Java heap and the decoded-frame cache both have room. A full frame cache is normal: it is the cache doing its job.", false));
        out.add(bar("Java heap after collection", m.heapAfterGc(), String.format(Locale.ROOT, "%.0f%% of %s", m.heapAfterGc() * 100, MemoryHealth.gib(m.heapMax()))));
        out.add(bar("Decoded frames", m.decodedMax() == 0 ? 0 : (double) m.decoded() / m.decodedMax(), MemoryHealth.gib(m.decoded()) + " of " + MemoryHealth.gib(m.decodedMax())));
        out.add(bar("Collector time", Math.min(1, m.gcShare() * 4), String.format(Locale.ROOT, "%.0f%% of the last ten seconds", m.gcShare() * 100)));
        if (m.machineTotal() > 0)
            out.add(plain("The computer has " + MemoryHealth.gib(m.machineFree()) + " free of " + MemoryHealth.gib(m.machineTotal())
                    + "; Studio may cache up to " + MemoryBudget.sharePercent() + "% (Memory settings).", true));
    }

    private void recent(List<JComponent> out, Activity.Kind k) {
        List<Activity.Event> all = Activity.since(0, java.util.EnumSet.of(k), Activity.CAPACITY);
        for (int i = Math.max(0, all.size() - RECENT); i < all.size(); i++) {
            Activity.Event e = all.get(i);
            JLabel l = new JLabel("<html>" + escape(Activity.cut(e.source() + "  " + e.text())) + "</html>");
            l.setFont(UIGlobals.uiFontMonoSmall);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setToolTipText(ActivityWindow.line(e));
            l.setMaximumSize(new Dimension(WIDTH - 20, 40));
            out.add(l);
        }
    }

    // buttons: where each kind of work is managed ---------------------------------------------------

    private List<JButton> buttons() {
        List<JButton> out = new ArrayList<>();
        switch (kind) {
            case CMD -> {
                JButton off = link("Turn off local API", "Stop answering scripts and assistants; Settings > General turns it on again", () -> {
                    LocalApi.setEnabled(false);
                    note = "Local API turned off.";
                });
                off.setEnabled(LocalApi.isEnabled());
                out.add(off);
            }
            case CPU, NET -> out.add(link("Task Manager…", "What is queued or running, with progress and cancel", JobQueuePanel::open));
            case DISK -> {
                out.add(link("Cache Manager…", "What is on disk in Studio's cache and the FITS library: reveal, import, verify or delete", () -> action("cacheManager")));
                out.add(link("Exports folder", "Show the folder movies, screenshots and records are exported to", () -> action("openExportsFolder")));
            }
            case MEM -> {
                out.add(link("Free up memory", "Empty the decoded-frame cache and ask for a collection. Nothing is lost: frames decode again when they are next shown.", () -> {
                    long held = Layers.freeDecodedFrames();
                    note = "Released " + MemoryHealth.gib(held) + " of decoded frames. The heap figure moves once the collector has run.";
                }));
                out.add(link("Memory settings…", "How much of the computer's memory Studio may use for cached frames", () -> MenuBar.openSettingsTab("Network")));
            }
            default -> {
            }
        }
        return out;
    }

    private static void action(String id) {
        ActionCatalog.Entry e = ActionCatalog.get(id);
        if (e != null)
            e.action().actionPerformed(new java.awt.event.ActionEvent(ActivityPopover.class, java.awt.event.ActionEvent.ACTION_PERFORMED, id));
    }

    // work lists ------------------------------------------------------------------------------------

    private static boolean isCompute(ActivityRows.Row r) {
        return r.detail().startsWith("Fourier") || "Recording".equals(r.name()) || "Python".equals(r.name())
                || "Export queue".equals(r.name()) || "Background".equals(r.name());
    }

    private static List<ActivityRows.Row> compute() {
        return ActivityRows.rows().stream().filter(r -> !r.failed() && isCompute(r) && !"Export queue".equals(r.name())).toList();
    }

    private static List<ActivityRows.Row> transfers() {
        return ActivityRows.rows().stream().filter(r -> !isCompute(r)).toList();
    }

    private static double fraction(JobQueue.Job j) {
        return j.total() == 0 ? -1 : j.done() / (double) j.total();
    }

    // building blocks -------------------------------------------------------------------------------

    private JComponent row(String name, String detail, double fraction, @Nullable String stop, @Nullable String tip, @Nullable Runnable action) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JPanel text = new JPanel(new BorderLayout());
        JLabel n = new JLabel(name);
        n.setFont(n.getFont().deriveFont(Font.BOLD));
        JLabel d = new JLabel("<html><body style='width:" + (WIDTH - 150) + "px'>" + escape(detail) + "</body></html>");
        d.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        text.add(n, BorderLayout.PAGE_START);
        text.add(d, BorderLayout.CENTER);
        if (fraction >= 0) {
            JProgressBar b = new JProgressBar(0, 1000);
            b.setValue((int) Math.round(Math.min(1, fraction) * 1000));
            b.setPreferredSize(new Dimension(100, 6));
            text.add(b, BorderLayout.PAGE_END);
        }
        p.add(text, BorderLayout.CENTER);
        if (stop != null && action != null) {
            JButton s = link(stop, tip, action);
            p.add(s, BorderLayout.LINE_END);
        }
        p.setMaximumSize(new Dimension(WIDTH - 20, p.getPreferredSize().height));
        return p;
    }

    private JComponent bar(String label, double fraction, String text) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(150, l.getPreferredSize().height));
        JProgressBar b = new JProgressBar(0, 1000);
        b.setValue((int) Math.round(Math.max(0, Math.min(1, fraction)) * 1000));
        b.setString(text);
        b.setStringPainted(true);
        p.add(l, BorderLayout.LINE_START);
        p.add(b, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(WIDTH - 20, p.getPreferredSize().height));
        return p;
    }

    private static JComponent plain(String text, boolean muted) {
        JLabel l = new JLabel("<html><body style='width:" + (WIDTH - 50) + "px'>" + escape(text) + "</body></html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        if (muted)
            l.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    private JButton link(String text, @Nullable String tip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> {
            action.run();
            refresh();
        });
        return b;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The light itself, repeated at the head of its own panel, so the panel reads as that light's. */
    private static final class Dot extends JComponent {
        private final Color color;
        private final boolean lit;

        Dot(Color _color, boolean _lit) {
            color = _color;
            lit = _lit;
            setPreferredSize(new Dimension(14, 14));
        }

        @Override
        protected void paintComponent(Graphics g1) {
            Graphics2D g = (Graphics2D) g1.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(lit ? color : Theme.mix(getBackground(), getForeground(), 0.3));
            g.fillOval(1, 3, 10, 10);
            g.dispose();
        }
    }

}
