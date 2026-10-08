package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
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
 * The Welcome window (HS-10): the build, What's New from the changelog the jar carries, and the
 * ways to start: continue, a blank session, a session file, or the tour. It also holds the startup
 * choice (reopen the last session or start blank) and its own "show at startup" switch.
 *
 * <p>Shown on a plain launch only: never headless, in a spawned extra window, on CI, or when the
 * launch had arguments (a script's launch). It takes over the first-launch tour offer, since it
 * carries the tour's button.
 */
public final class WelcomeDialog {

    /** "false" keeps the window from opening at startup; Help > Welcome still opens it. */
    static final String SHOW_KEY = "startup.welcome";
    private static final int OFFER_DELAY_MS = 2000; // as the tour's offer: once the palettes are back

    private static final AtomicReference<JDialog> showing = new AtomicReference<>();

    public static void offerAtStartup(boolean bareLaunch) {
        if (!bareLaunch || GraphicsEnvironment.isHeadless() || Session.isExtraWindow() || System.getenv("CI") != null
                || "false".equals(Settings.getProperty(SHOW_KEY))) {
            Tour.offerOnce(bareLaunch);
            return;
        }
        Tour.markOffered(); // the Welcome window has the tour's button; one invitation is enough
        // Checked when the timer fires, not now: macOS delivers a file opened from Finder after main
        // has started, and that launch has already said what it wants open.
        Timer later = new Timer(OFFER_DELAY_MS, e -> {
            if (!CommandLine.desktopDocumentRequested())
                show();
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
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));

        JLabel title = new JLabel("<html><span style='font-size:150%'><b>HelioFITS Studio " + escape(AppInfo.version)
                + "</b></span><br>Build " + escape(AppInfo.buildId()) + "</html>");
        content.add(title, BorderLayout.PAGE_START);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        JPanel starts = new JPanel(new GridLayout(1, 0, 8, 0));
        JButton continueButton = card("Continue", "Keep working on " + Session.displayName(), dialog::dispose);
        starts.add(continueButton);
        starts.add(card("Start Blank", "A new session on an empty canvas", () -> {
            dialog.dispose();
            run("newSession");
        }));
        starts.add(card("Open Session...", "A saved .jhv session file", () -> {
            dialog.dispose();
            run("openSession");
        }));
        starts.add(card("Take the Tour", "The main controls, one at a time", () -> {
            dialog.dispose();
            Tour.start(Tour.GETTING_STARTED);
        }));
        center.add(starts, BorderLayout.PAGE_START);

        HTMLPane news = new HTMLPane();
        news.setText(whatsNewHtml(AppInfo.whatsNew()));
        news.setCaretPosition(0);
        JScrollPane newsScroll = new JScrollPane(news);
        newsScroll.setPreferredSize(new Dimension(620, 260));
        newsScroll.setBorder(BorderFactory.createTitledBorder("What's New in " + AppInfo.version));
        center.add(newsScroll, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        content.add(footer(dialog), BorderLayout.PAGE_END);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(continueButton);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        showing.set(dialog);
        dialog.setVisible(true);
    }

    private static JPanel footer(JDialog dialog) {
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.PAGE_AXIS));

        JPanel startup = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        startup.add(new JLabel("When HelioFITS Studio starts: "));
        JComboBox<String> mode = new JComboBox<>(new String[] {"Reopen the last session", "Start blank"});
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
        atStartup.addActionListener(e -> Settings.setProperty(SHOW_KEY, Boolean.toString(atStartup.isSelected())));
        row.add(atStartup, BorderLayout.LINE_START);
        JPanel links = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        ActionCatalog.Entry changeLog = ActionCatalog.get("openChangeLog");
        if (changeLog != null)
            links.add(new JButton(changeLog.action()));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        links.add(close);
        row.add(links, BorderLayout.LINE_END);
        row.setAlignmentX(0);
        footer.add(row);
        return footer;
    }

    private static JButton card(String name, String line, Runnable onClick) {
        JButton b = new JButton("<html><center><b>" + escape(name) + "</b><br><small>" + escape(line) + "</small></center></html>");
        b.addActionListener(e -> onClick.run());
        return b;
    }

    private static void run(String id) {
        ActionCatalog.Entry entry = ActionCatalog.get(id);
        if (entry != null)
            entry.action().actionPerformed(new ActionEvent(MainFrame.get(), ActionEvent.ACTION_PERFORMED, id));
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
