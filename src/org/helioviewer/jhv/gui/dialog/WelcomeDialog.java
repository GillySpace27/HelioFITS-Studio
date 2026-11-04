package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.HTMLPane;
import org.helioviewer.jhv.gui.search.Tour;
import org.helioviewer.jhv.io.CommandLine;

import com.google.common.html.HtmlEscapers;

/**
 * The Welcome window (HS-10): the ways to start (continue, a blank session, a session file, the demo
 * scene, and Take a Tour, a list of every tour), where the help is (search, tooltips, Tip of the Day,
 * the manual, the Help menu), and What's New as a button that opens the changelog section the jar
 * carries. It also holds the startup choice (reopen the last session or start blank) and its own
 * "show at startup" switch.
 *
 * <p>Shown on a plain launch only: never headless, in a spawned extra window, on CI, or when the
 * launch had arguments (a script's launch). It takes over the first-launch tour offer, since it
 * carries the tour's button.
 *
 * <p>The tip is not here: Tip of the Day is a window of its own, with its own startup switch and
 * snooze, and at startup it follows this window once this one closes.
 */
public final class WelcomeDialog {

    /** "false" keeps the window from opening at startup; Help > Welcome still opens it. */
    static final String SHOW_KEY = "startup.welcome";
    /** Written the first time this window opens, so the first one can lead with the tours. */
    static final String SEEN_KEY = "startup.welcomeSeen";
    private static final int OFFER_DELAY_MS = 2000; // as the tour's offer: once the palettes are back

    private static final AtomicReference<JDialog> showing = new AtomicReference<>();
    /** Set when this window opens at startup, so its closing brings on the startup tip; once. */
    private static final java.util.concurrent.atomic.AtomicBoolean tipAfterClose = new java.util.concurrent.atomic.AtomicBoolean();

    public static void offerAtStartup(boolean bareLaunch) {
        if (!bareLaunch || GraphicsEnvironment.isHeadless() || Session.isExtraWindow() || System.getenv("CI") != null
                || "false".equals(Settings.getProperty(SHOW_KEY))) {
            Tour.offerOnce(bareLaunch);
            TipOfTheDay.offerAlone(bareLaunch); // skipped while the first-launch tour offer is pending
            return;
        }
        Tour.markOffered(); // the Welcome window has the tour's button; one invitation is enough
        // Checked when the timer fires, not now: macOS delivers a file opened from Finder after main
        // has started, and that launch has already said what it wants open.
        Timer later = new Timer(OFFER_DELAY_MS, e -> {
            if (!CommandLine.desktopDocumentRequested()) {
                tipAfterClose.set(true);
                show();
            }
        });
        later.setRepeats(false);
        later.start();
    }

    /** Close the window if it is open; a session opened from the desktop takes its place. */
    public static void dismiss() {
        JDialog open = showing.get();
        if (open != null)
            open.dispose();
    }

    public static void show() {
        JDialog open = showing.get();
        if (open != null) {
            open.toFront();
            return;
        }
        JDialog dialog = new JDialog(MainFrame.get(), "Welcome to HelioFITS Studio", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                showing.set(null);
                if (tipAfterClose.getAndSet(false))
                    TipOfTheDay.atStartup(); // not over a tour started from here: atStartup checks
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        content.add(header(dialog), BorderLayout.PAGE_START);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.PAGE_AXIS));

        JPanel starts = new JPanel(new GridLayout(1, 0, 8, 0));
        JButton continueButton = card("Continue", "Keep working on " + Session.displayName(), dialog::dispose);
        starts.add(continueButton);
        starts.add(card("Start Blank", "An empty canvas", () -> {
            dialog.dispose();
            run("newSession");
        }));
        starts.add(card("Open Session...", "A saved .jhv file", () -> {
            dialog.dispose();
            run("openSession");
        }));
        starts.add(card("Open Demo", "AIA 171, no download", () -> {
            dialog.dispose();
            run("openDemo");
        }));
        JButton tour = tourCard(dialog);
        starts.add(tour);
        JPanel startBox = titled("Start", starts);
        center.add(startBox);
        center.add(Box.createVerticalStrut(10));
        center.add(helpPanel(dialog));
        content.add(center, BorderLayout.CENTER);

        content.add(footer(dialog), BorderLayout.PAGE_END);
        dialog.setContentPane(content);
        // The first time this window opens, Return opens the tour list (Getting started first), not
        // Continue on an empty Untitled session; after that it is Continue.
        boolean first = Settings.getProperty(SEEN_KEY) == null;
        Settings.setProperty(SEEN_KEY, "true");
        dialog.getRootPane().setDefaultButton(first ? tour : continueButton);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        showing.set(dialog);
        dialog.setVisible(true);
    }

    /** The build, and What's New as a button: the changelog is one click away, not the first thing seen. */
    private static JPanel header(JDialog dialog) {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        JLabel title = new JLabel("<html><span style='font-size:150%'><b>Welcome to HelioFITS Studio</b></span><br>Version "
                + escape(AppInfo.version) + ", build " + escape(AppInfo.buildId()) + "</html>");
        header.add(title, BorderLayout.LINE_START);
        JButton news = new JButton("What's New in " + AppInfo.version);
        news.setToolTipText("The changes in this version");
        news.addActionListener(e -> showWhatsNew(dialog));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        right.add(news);
        header.add(right, BorderLayout.CENTER);
        return header;
    }

    /** What's New in its own small window, over the Welcome window, from the changelog the jar carries. */
    private static void showWhatsNew(JDialog owner) {
        JDialog d = new JDialog(owner, "What's New in HelioFITS Studio " + AppInfo.version, false);
        HTMLPane news = new HTMLPane();
        news.setText(whatsNewHtml(AppInfo.whatsNew()));
        news.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(news);
        scroll.setPreferredSize(new Dimension(620, 380));
        JButton changeLog = null;
        ActionCatalog.Entry entry = ActionCatalog.get("openChangeLog");
        if (entry != null) {
            changeLog = new JButton("Full Change Log");
            changeLog.setToolTipText("Every version's changes, on GitHub");
            changeLog.addActionListener(entry.action());
        }
        JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        if (changeLog != null)
            buttons.add(changeLog);
        buttons.add(close);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);
        d.setContentPane(content);
        d.getRootPane().setDefaultButton(close);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /** "Take a Tour": one button that opens the list of every tour; clicking one starts it. */
    private static JButton tourCard(JDialog dialog) {
        JPopupMenu menu = new JPopupMenu();
        for (String id : Tour.IDS) {
            int n = Tour.stepCount(id);
            String name = Tour.title(id) + (n > 0 ? " (" + n + " steps)" : "");
            JMenuItem item = new JMenuItem(Tour.GETTING_STARTED.equals(id) ? name + ", start here" : name);
            item.addActionListener(e -> {
                dialog.dispose();
                Tour.start(id);
            });
            menu.add(item);
        }
        JButton b = cardButton("Take a Tour \u25BE", "Pick one of " + Tour.IDS.size() + " tours");
        b.addActionListener(e -> menu.show(b, 0, b.getHeight()));
        return b;
    }

    private static JPanel titled(String title, JComponent body) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(2, 6, 6, 6)));
        p.add(body, BorderLayout.CENTER);
        p.setAlignmentX(0);
        return p;
    }

    /** Every way the app helps: search, hover text, the Help menu and what is in it. */
    private static JPanel helpPanel(JDialog dialog) {
        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(3, 0, 3, 10);
        c.anchor = GridBagConstraints.LINE_START;

        ActionCatalog.Entry search = ActionCatalog.get("searchControls");
        String key = search == null ? "" : shortcut(search.action());
        helpRow(rows, c, search == null ? null : search.action(), true, "Search Controls\u2026" + (key.isEmpty() ? "" : "  " + key), dialog,
                "Type what you want to do or the name of any control. It runs the command, or points at the control and shows where it is.");
        helpRow(rows, c, null, false, "Hover any control", dialog,
                "Every button, slider and field has a tooltip saying what it is for.");
        ActionCatalog.Entry tip = ActionCatalog.get("tipOfTheDay");
        helpRow(rows, c, tip == null ? null : tip.action(), true, "Tip of the Day", dialog,
                "One short tip at a time, each with Show Me to point at the control it is about.");
        ActionCatalog.Entry manual = ActionCatalog.get("openUserManual");
        helpRow(rows, c, manual == null ? null : manual.action(), false, "User Manual", dialog,
                "The full guide to every part of the app, as a PDF.");
        helpRow(rows, c, null, false, "Help menu", dialog,
                "Everything here, plus Interaction Guide and Timeline Interaction (mouse and keyboard controls), "
                        + "the Change Log, Check for Updates and Report Bug.");
        ActionCatalog.Entry feedback = ActionCatalog.get("sendFeedback");
        helpRow(rows, c, feedback == null ? null : feedback.action(), false, "Send Feedback\u2026", dialog,
                "A problem, a request or a question for the developer; no account needed.");
        return titled("Find your way around", rows);
    }

    /**
     * One row: a button and what it does, or, for help with nothing to press, the text alone with its
     * name in bold, in the text column so the buttons stay one column.
     *
     * @param closes whether the button closes this window first: yes for what needs the main window
     *               (the search, the tip), no for what opens elsewhere (a browser, a form)
     */
    private static void helpRow(JPanel rows, GridBagConstraints c, @javax.annotation.Nullable Action action, boolean closes,
            String name, JDialog dialog, String text) {
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        if (action != null) {
            JButton b = new JButton(name);
            b.addActionListener(e -> {
                if (closes)
                    dialog.dispose();
                action.actionPerformed(e);
            });
            rows.add(b, c);
        }
        c.gridx = 1;
        c.weightx = 1;
        String lead = action == null ? "<b>" + escape(name) + ".</b> " : "";
        JLabel desc = new JLabel("<html><div style='width:430px'>" + lead + escape(text) + "</div></html>");
        rows.add(desc, c);
        c.gridy++;
    }

    private static String shortcut(Action action) {
        Object key = action.getValue(Action.ACCELERATOR_KEY);
        if (!(key instanceof KeyStroke ks))
            return "";
        String mods = java.awt.event.InputEvent.getModifiersExText(ks.getModifiers());
        return "(" + (mods.isEmpty() ? "" : mods + "+") + KeyEvent.getKeyText(ks.getKeyCode()) + ")";
    }

    private static JPanel footer(JDialog dialog) {
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.PAGE_AXIS));

        JPanel startup = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        startup.add(new JLabel("When HelioFITS Studio starts: "));
        JComboBox<String> mode = new JComboBox<>(new String[] {"Reopen the last session", "Start blank"});
        mode.setToolTipText("What a plain launch opens: the session you had last, or an empty canvas");
        mode.setSelectedIndex(CommandLine.BLANK_MODE.equals(Settings.getProperty("startup.mode")) ? 1 : 0);
        mode.addActionListener(e -> Settings.setProperty("startup.mode", mode.getSelectedIndex() == 1 ? CommandLine.BLANK_MODE : "last"));
        startup.add(mode);
        String pinned = Settings.getProperty("startup.loadState");
        if (pinned != null && !"false".equals(pinned) && !"true".equals(pinned))
            startup.add(new JLabel("  (your default session opens first; File > Clear Default Session)"));
        startup.setAlignmentX(0);
        footer.add(startup);
        footer.add(Box.createVerticalStrut(6));

        JPanel row = new JPanel(new BorderLayout());
        JCheckBox atStartup = new JCheckBox("Show this window at startup", !"false".equals(Settings.getProperty(SHOW_KEY)));
        atStartup.setToolTipText("This window only; Tip of the Day has its own switch. Help > Welcome opens it again");
        atStartup.addActionListener(e -> Settings.setProperty(SHOW_KEY, Boolean.toString(atStartup.isSelected())));
        row.add(atStartup, BorderLayout.LINE_START);
        JPanel links = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        // One Settings button: Settings > Appearance has the text size and interface scale (Display Scaling, its own window).
        JButton settings = new JButton("Settings\u2026");
        settings.setToolTipText("Everything adjustable, including text size and interface scale (Appearance)");
        settings.addActionListener(e -> org.helioviewer.jhv.gui.component.MenuBar.openSettings());
        links.add(settings);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        links.add(close);
        row.add(links, BorderLayout.LINE_END);
        row.setAlignmentX(0);
        footer.add(row);
        return footer;
    }

    private static JButton card(String name, String line, Runnable onClick) {
        JButton b = cardButton(name, line);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    private static JButton cardButton(String name, String line) {
        JButton b = new JButton("<html><center><b>" + escape(name) + "</b><br><small>" + escape(line) + "</small></center></html>");
        b.setToolTipText(line);
        return b;
    }

    private static void run(String id) {
        org.helioviewer.jhv.gui.registry.Dispatch.invoke(id, null); // the one way to run a command by id
    }

    /**
     * The changelog section as HTML: "## " lines (an unreleased version folded in) become version
     * headings, "### " lines headings, "- " lines list items.
     */
    static String whatsNewHtml(String section) {
        if (section.isEmpty())
            return "<p>No notes for this version. Help &gt; Open Change Log has the full history.</p>";
        StringBuilder html = new StringBuilder();
        boolean inList = false;
        for (String line : section.split("\n")) {
            String t = line.strip();
            boolean item = t.startsWith("- ");
            if (inList && !item) {
                html.append("</ul>");
                inList = false;
            }
            if (item) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                html.append("<li>").append(escape(t.substring(2))).append("</li>");
            } else if (t.startsWith("## "))
                html.append("<h3>").append(escape(t.substring(3))).append("</h3>");
            else if (t.startsWith("### "))
                html.append("<p><b>").append(escape(t.substring(4))).append("</b></p>");
            else if (!t.isEmpty())
                html.append("<p>").append(escape(t)).append("</p>");
        }
        if (inList)
            html.append("</ul>");
        return html.toString();
    }

    private static String escape(String s) {
        return HtmlEscapers.htmlEscaper().escape(s);
    }

    /** Help > Welcome to HelioFITS Studio. */
    @SuppressWarnings("serial")
    public static final class Open extends AbstractAction {
        public Open() {
            super("Welcome to HelioFITS Studio...");
            putValue(Action.SHORT_DESCRIPTION, "What's new in this version, and the ways to start");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            show();
        }
    }

    private WelcomeDialog() {}

}
