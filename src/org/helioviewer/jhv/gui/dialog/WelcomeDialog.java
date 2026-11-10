package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
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
 * The Welcome window (HS-10), a launcher first: one primary way to start (the Getting Started tour
 * the first time, then Continue, the last session or Open Session), the other ways to start as
 * quiet buttons, recent sessions, and a signpost to the help: four tiles always on screen and the
 * rest behind More help. What each item does in full is its tooltip and its screen-reader
 * description, not a paragraph on screen. What the window offers is WelcomeModel; WelcomeCheck
 * reads it and builds this window's content for each state.
 *
 * <p>Shown on a plain launch only: never headless, in a spawned extra window, on CI, or when the
 * launch had arguments (a script's launch). It takes over the first-launch tour offer, since it
 * carries the tour's button.
 *
 * <p>The tip is not here: Tip of the Day is a window of its own, with its own startup switch and
 * snooze, and at startup it follows this window once this one closes.
 */
public final class WelcomeDialog {

    /** WelcomeModel.ShowMode: "auto" or unset, "true" (always) or "false" (never). Help > Welcome opens it whatever it says. */
    static final String SHOW_KEY = "startup.welcome";
    /** Written the first time this window opens, so the first one can lead with the tours. */
    static final String SEEN_KEY = "startup.welcomeSeen";
    /** The version this window last opened in, so the first window after an update leads with What's New. */
    static final String VERSION_KEY = "startup.welcomeVersion";
    private static final int OFFER_DELAY_MS = 2000; // as the tour's offer: once the palettes are back

    private static final AtomicReference<JDialog> showing = new AtomicReference<>();
    /** Set when this window opens at startup, so its closing brings on the startup tip; once. */
    private static final java.util.concurrent.atomic.AtomicBoolean tipAfterClose = new java.util.concurrent.atomic.AtomicBoolean();

    public static void offerAtStartup(boolean bareLaunch) {
        if (!bareLaunch || GraphicsEnvironment.isHeadless() || Session.isExtraWindow() || System.getenv("CI") != null
                || WelcomeModel.ShowMode.of(Settings.getProperty(SHOW_KEY)) == WelcomeModel.ShowMode.NEVER) {
            Tour.offerOnce(bareLaunch);
            TipOfTheDay.offerAlone(bareLaunch); // skipped while the first-launch tour offer is pending
            return;
        }
        Tour.markOffered(); // the Welcome window has the tour's button; one invitation is enough
        // A click or a key in the main window before the timer fires means the person is already
        // working: the window would only be in the way.
        java.util.concurrent.atomic.AtomicBoolean working = new java.util.concurrent.atomic.AtomicBoolean();
        java.awt.event.AWTEventListener watch = ev -> {
            if (isInput(ev) && inMainWindow(ev.getSource()))
                working.set(true);
        };
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(watch, java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.KEY_EVENT_MASK);
        // Checked when the timer fires, not now: macOS delivers a file opened from Finder after main
        // has started, and that launch has already said what it wants open.
        Timer later = new Timer(OFFER_DELAY_MS, e -> {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(watch);
            if (CommandLine.desktopDocumentRequested() || working.get())
                return;
            WelcomeModel.State state = WelcomeModel.state(Settings.getProperty(SEEN_KEY), Settings.getProperty(VERSION_KEY), AppInfo.version);
            if (!WelcomeModel.showAtStartup(WelcomeModel.ShowMode.of(Settings.getProperty(SHOW_KEY)), state, sceneEmpty())) {
                TipOfTheDay.atStartup(); // the window is skipped, the tip is not: it has its own switch
                return;
            }
            tipAfterClose.set(true);
            show(true);
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
        show(false);
    }

    /**
     * @param atStartup opened by the startup offer: a click in the main window closes it, since that
     *                  person has started work. Opened from the Help menu it stays until closed.
     */
    private static void show(boolean atStartup) {
        JDialog open = showing.get();
        if (open != null) {
            open.toFront();
            return;
        }
        WelcomeModel.State state = WelcomeModel.state(Settings.getProperty(SEEN_KEY), Settings.getProperty(VERSION_KEY), AppInfo.version);
        Settings.setProperty(SEEN_KEY, "true");
        Settings.setProperty(VERSION_KEY, AppInfo.version);

        JDialog dialog = new JDialog(MainFrame.get(), "Welcome to HelioFITS Studio", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        java.awt.event.AWTEventListener clickAway = ev -> {
            if (ev.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED && inMainWindow(ev.getSource()))
                dialog.dispose();
        };
        if (atStartup)
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clickAway, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(clickAway);
                showing.set(null);
                if (tipAfterClose.getAndSet(false))
                    TipOfTheDay.atStartup(); // not over a tour started from here: atStartup checks
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        Built built = build(dialog, state, sceneEmpty(), recentFiles());
        dialog.setContentPane(built.content());
        dialog.getRootPane().setDefaultButton(built.primary()); // Return presses it; the theme draws it as the one accent button
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        showing.set(dialog);
        dialog.setVisible(true);
        built.primary().requestFocusInWindow();
    }

    /** The window's content and its primary button, apart from the window, so WelcomeCheck can build it. */
    record Built(JPanel content, JButton primary) {}

    static Built build(JDialog dialog, WelcomeModel.State state, boolean sceneEmpty, List<File> recents) {
        WelcomeModel.Primary which = WelcomeModel.primary(state, sceneEmpty, !recents.isEmpty());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));

        add(content, header(dialog, state), 14);
        JButton primary = primaryButton(dialog, which, recents);
        add(content, stretch(primary), 8);
        add(content, otherStarts(dialog, which), 16);
        if (state != WelcomeModel.State.FIRST_RUN) {
            JPanel recent = recentList(dialog, which == WelcomeModel.Primary.REOPEN ? recents.subList(1, recents.size()) : recents);
            if (recent != null)
                add(content, recent, 16);
        }
        add(content, sectionLabel("Help"), 6);
        add(content, helpTiles(dialog, WelcomeModel.showCaptions(state)), 6);
        add(content, moreHelp(dialog), 14);
        add(content, footer(), 0);
        return new Built(content, primary);
    }

    private static void add(JPanel column, JComponent c, int gapAfter) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(c);
        if (gapAfter > 0)
            column.add(Box.createVerticalStrut(gapAfter));
    }

    /** The title, the version, and What's New. A release shows its version alone; any other build shows which build it is. */
    private static JPanel header(JDialog dialog, WelcomeModel.State state) {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.PAGE_AXIS));
        JLabel title = new JLabel(state == WelcomeModel.State.FIRST_RUN ? "Welcome to HelioFITS Studio" : "Welcome back to HelioFITS Studio");
        title.putClientProperty("FlatLaf.styleClass", "h2"); // scales with Studio's text size, unlike a fixed HTML size
        left.add(title);
        String build = AppInfo.displayBuild(), built = AppInfo.builtLine();
        String full = build + (built.isEmpty() ? "" : "; " + built);
        JLabel version;
        if ("release".equals(AppInfo.channel())) {
            version = new JLabel("Version " + AppInfo.label());
            version.setToolTipText(full);
            version.getAccessibleContext().setAccessibleDescription(full);
        } else // a debug, CI or hand-made build says exactly which build it is, on screen
            version = new JLabel("<html>Version " + escape(build) + (built.isEmpty() ? "" : "<br>" + escape(built)) + "</html>");
        version.getAccessibleContext().setAccessibleName("Version " + full);
        left.add(version);
        header.add(left, BorderLayout.LINE_START);

        JButton news = new JButton("What's New in " + AppInfo.version);
        news.addActionListener(e -> showWhatsNew(dialog));
        if (state == WelcomeModel.State.UPDATED) {
            news.putClientProperty("FlatLaf.styleClass", "semibold");
            describe(news, "What's New in " + AppInfo.version, "Studio was updated since you last opened it: the changes in this version");
        } else {
            news.putClientProperty("JButton.buttonType", "borderless");
            describe(news, "What's New in " + AppInfo.version, "The changes in this version");
        }
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        right.add(news);
        header.add(right, BorderLayout.CENTER);
        return header;
    }

    private static JButton primaryButton(JDialog dialog, WelcomeModel.Primary which, List<File> recents) {
        JButton b = new JButton();
        b.putClientProperty("FlatLaf.style", "font: +2 bold"); // relative, so it follows Studio's text size
        switch (which) {
            case TOUR -> {
                int n = Tour.stepCount(Tour.GETTING_STARTED);
                String name = "Take the tour: " + Tour.title(Tour.GETTING_STARTED);
                b.setText(name + (n > 0 ? "  (" + n + " steps)" : ""));
                describe(b, name, "A short guided tour that points at each control in turn. Every other tour is under Tours below.");
                b.addActionListener(e -> {
                    dialog.dispose();
                    Tour.start(Tour.GETTING_STARTED);
                });
            }
            case CONTINUE -> {
                String name = "Continue with " + Session.displayName();
                b.setText(name);
                describe(b, name, "Close this window and keep working on the session that is open");
                b.addActionListener(e -> dialog.dispose());
            }
            case REOPEN -> {
                File last = recents.get(0);
                String name = "Reopen " + last.getName();
                b.setText(name);
                describe(b, name, "Open the session you had last, saved " + when(last));
                b.addActionListener(e -> {
                    dialog.dispose();
                    new org.helioviewer.jhv.gui.Actions.OpenRecent(last).actionPerformed(null);
                });
            }
            case OPEN_SESSION -> {
                b.setText("Open Session…");
                describe(b, "Open Session", "Open a saved .jhv session file");
                b.addActionListener(e -> {
                    dialog.dispose();
                    run("openSession");
                });
            }
        }
        return b;
    }

    /** The ways to start that are not the primary one, as plain buttons. */
    private static JPanel otherStarts(JDialog dialog, WelcomeModel.Primary which) {
        JPanel row = new JPanel(new GridLayout(1, 0, 8, 0));
        row.add(start(dialog, "Start Blank", "An empty canvas, the scene a fresh install opens with", "newSession"));
        if (which != WelcomeModel.Primary.OPEN_SESSION)
            row.add(start(dialog, "Open Session…", "Open a saved .jhv session file", "openSession"));
        row.add(start(dialog, "Open Demo", "A new session with a bundled SDO/AIA 171 image and RHEF on; nothing to download", "openDemo"));
        return row;
    }

    private static JButton start(JDialog dialog, String name, String description, String id) {
        JButton b = new JButton(name);
        describe(b, name, description);
        b.addActionListener(e -> {
            dialog.dispose();
            run(id);
        });
        return b;
    }

    /** Up to WelcomeModel.RECENTS_SHOWN recent sessions, each one click; null when there are none. */
    @Nullable
    private static JPanel recentList(JDialog dialog, List<File> recents) {
        if (recents.isEmpty())
            return null;
        JPanel list = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.weightx = 1; // the column hugs the left edge instead of floating in the middle
        c.insets = new Insets(0, 0, 4, 0);
        list.add(sectionLabel("Recent sessions"), c);
        for (File f : recents.subList(0, Math.min(WelcomeModel.RECENTS_SHOWN, recents.size()))) {
            c.gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            JButton b = new JButton(f.getName() + "   " + when(f));
            b.putClientProperty("JButton.buttonType", "borderless");
            b.setHorizontalAlignment(JButton.LEADING);
            describe(b, "Open " + f.getName(), "Saved " + when(f) + ", in " + f.getParent());
            b.addActionListener(e -> {
                dialog.dispose();
                new org.helioviewer.jhv.gui.Actions.OpenRecent(f).actionPerformed(null);
            });
            list.add(b, c);
        }
        return list;
    }

    /** The always-visible help: name, shortcut and, the first time, a caption. */
    private static JPanel helpTiles(JDialog dialog, boolean captions) {
        List<JButton> tiles = new ArrayList<>();
        for (WelcomeModel.Item it : WelcomeModel.ITEMS) {
            if (it.tier() != WelcomeModel.Tier.HELP)
                continue;
            JButton b = itemButton(dialog, it, captions);
            if (b != null)
                tiles.add(b);
        }
        int cols = captions && tiles.size() == 4 ? 2 : Math.max(1, tiles.size());
        JPanel grid = new JPanel(new GridLayout(0, cols, 8, 8));
        tiles.forEach(grid::add);
        return grid;
    }

    /** More help: a disclosure that opens a list of the less common help, each with its caption. */
    private static JPanel moreHelp(JDialog dialog) {
        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 10);
        for (WelcomeModel.Item it : WelcomeModel.ITEMS) {
            if (it.tier() != WelcomeModel.Tier.MORE)
                continue;
            JButton b = itemButton(dialog, it, false);
            if (b == null)
                continue;
            c.gridx = 0;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 0;
            rows.add(b, c);
            c.gridx = 1;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 1; // the rows hug the left edge
            JLabel caption = new JLabel(it.caption());
            caption.setLabelFor(b);
            rows.add(caption, c);
            c.gridy++;
        }
        rows.setVisible(false);
        rows.setBorder(BorderFactory.createEmptyBorder(4, 16, 0, 0));

        JToggleButton toggle = new JToggleButton();
        toggle.putClientProperty("JButton.buttonType", "borderless");
        toggle.setHorizontalAlignment(JButton.LEADING);
        Runnable label = () -> {
            boolean open = toggle.isSelected();
            toggle.setText((open ? "More help ▾" : "More help ▸") + (open ? "" : "   Tip of the Day, Send Feedback, scripting and AI assistants"));
            toggle.getAccessibleContext().setAccessibleName("More help");
            toggle.getAccessibleContext().setAccessibleDescription(open ? "Expanded" : "Collapsed: Tip of the Day, Send Feedback, the MCP server and the Local API");
        };
        label.run();
        toggle.addActionListener(e -> {
            rows.setVisible(toggle.isSelected());
            label.run();
            dialog.pack();
        });

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.PAGE_AXIS));
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(toggle);
        p.add(rows);
        return p;
    }

    /** A help item's button, or null when its command is not in this build (the Keyboard Shortcuts overlay before it merges). */
    @Nullable
    static JButton itemButton(JDialog dialog, WelcomeModel.Item it, boolean caption) {
        String key = "";
        Runnable act;
        if (WelcomeModel.TOURS.equals(it.id())) {
            act = null; // set below: the list opens under the button
        } else if (it.isLink()) {
            String url = it.id().startsWith("/") ? AppInfo.sourceURL + it.id() : it.id();
            act = () -> org.helioviewer.jhv.gui.DesktopIntegration.openURL(url);
        } else {
            ActionCatalog.Entry entry = ActionCatalog.get(it.id());
            if (entry == null)
                return null;
            key = shortcut(entry.action());
            act = () -> entry.action().actionPerformed(new ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, it.id()));
        }
        String name = it.name() + (WelcomeModel.TOURS.equals(it.id()) ? " ▾" : "");
        JButton b = new JButton(caption
                ? "<html><b>" + escape(name) + "</b>" + (key.isEmpty() ? "" : "&nbsp;&nbsp;" + escape(key)) + "<br>" + escape(it.caption()) + "</html>"
                : name + (key.isEmpty() ? "" : "  " + key));
        if (caption)
            b.setHorizontalAlignment(JButton.LEADING);
        describe(b, it.name() + (key.isEmpty() ? "" : ", " + key), it.description());
        if (act == null) {
            JPopupMenu tours = tourMenu(dialog);
            b.addActionListener(e -> tours.show(b, 0, b.getHeight()));
        } else {
            Runnable run = act;
            b.addActionListener(e -> {
                if (it.closes())
                    dialog.dispose();
                run.run();
            });
        }
        return b;
    }

    /** Every tour, with its step count; clicking one starts it. */
    private static JPopupMenu tourMenu(JDialog dialog) {
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
        return menu;
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

    /** The footnote, then the startup choices and Settings. No Close button: Esc, the window's close box, or any way to start closes it. */
    private static JPanel footer() {
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.PAGE_AXIS));
        JLabel note = new JLabel(WelcomeModel.FOOTNOTE);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(note);
        footer.add(Box.createVerticalStrut(10));

        JPanel row = new JPanel(new BorderLayout(12, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        JLabel modeLabel = new JLabel("When Studio starts: ");
        left.add(modeLabel);
        JComboBox<String> mode = new JComboBox<>(new String[] {"Reopen the last session", "Start blank"});
        modeLabel.setLabelFor(mode);
        mode.setToolTipText("What a plain launch opens: the session you had last, or an empty canvas");
        mode.setSelectedIndex(CommandLine.BLANK_MODE.equals(Settings.getProperty("startup.mode")) ? 1 : 0);
        mode.addActionListener(e -> Settings.setProperty("startup.mode", mode.getSelectedIndex() == 1 ? CommandLine.BLANK_MODE : "last"));
        left.add(mode);
        String pinned = Settings.getProperty("startup.loadState");
        if (pinned != null && !"false".equals(pinned) && !"true".equals(pinned))
            left.add(new JLabel("  (your default session opens first; File > Clear Default Session)"));
        left.add(Box.createHorizontalStrut(16));
        JLabel showLabel = new JLabel("Show this window: ");
        left.add(showLabel);
        WelcomeModel.ShowMode[] modes = WelcomeModel.ShowMode.values();
        JComboBox<String> show = new JComboBox<>(java.util.Arrays.stream(modes).map(m -> m.label).toArray(String[]::new));
        showLabel.setLabelFor(show);
        show.setSelectedIndex(WelcomeModel.ShowMode.of(Settings.getProperty(SHOW_KEY)).ordinal());
        show.setToolTipText(WelcomeModel.SHOW_TOOLTIP);
        show.getAccessibleContext().setAccessibleDescription(WelcomeModel.SHOW_TOOLTIP);
        show.addActionListener(e -> Settings.setProperty(SHOW_KEY, modes[show.getSelectedIndex()].value));
        left.add(show);
        row.add(left, BorderLayout.LINE_START);

        // One Settings button: Settings > Appearance has the text size and interface scale (Display Scaling, its own window).
        JButton settings = new JButton("Settings…");
        describe(settings, "Settings", "Everything adjustable, including text size and interface scale (Appearance)");
        settings.addActionListener(e -> org.helioviewer.jhv.gui.component.MenuBar.openSettings());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        right.add(settings);
        row.add(right, BorderLayout.LINE_END);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(row);
        return footer;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.putClientProperty("FlatLaf.styleClass", "semibold");
        return l;
    }

    /** A button as wide as the column, so the primary action reads as the one big thing. */
    private static JComponent stretch(JButton b) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(b, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** The name a screen reader says (plain text, never the HTML on the button) and the long text, also the tooltip. */
    static void describe(AbstractButton b, String name, String description) {
        b.getAccessibleContext().setAccessibleName(name);
        b.getAccessibleContext().setAccessibleDescription(description);
        b.setToolTipText(description);
    }

    private static boolean sceneEmpty() {
        return org.helioviewer.jhv.layers.Layers.getImageLayers().isEmpty();
    }

    private static boolean isInput(java.awt.AWTEvent ev) {
        return ev.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED || ev.getID() == java.awt.event.KeyEvent.KEY_PRESSED;
    }

    /** Whether an event came from the main window (its canvas, panels or menus), not from this one or a dialog. */
    private static boolean inMainWindow(Object source) {
        java.awt.Window main = MainFrame.get();
        if (main == null || !(source instanceof Component c))
            return false;
        return c == main || SwingUtilities.getWindowAncestor(c) == main;
    }

    private static List<File> recentFiles() {
        List<File> files = new ArrayList<>();
        for (String path : Session.recentSessions()) {
            File f = new File(path);
            if (f.isFile())
                files.add(f);
        }
        return files;
    }

    /** "today", "yesterday" or "Nov 6", from the file's modification time. */
    static String when(File f) {
        return when(Instant.ofEpochMilli(f.lastModified()), Instant.now(), ZoneId.systemDefault());
    }

    static String when(Instant then, Instant now, ZoneId zone) {
        java.time.LocalDate d = then.atZone(zone).toLocalDate(), today = now.atZone(zone).toLocalDate();
        if (d.equals(today))
            return "today";
        if (d.equals(today.minusDays(1)))
            return "yesterday";
        return DateTimeFormatter.ofPattern(d.getYear() == today.getYear() ? "MMM d" : "MMM d, yyyy", Locale.ENGLISH).format(d);
    }

    private static String shortcut(Action action) {
        Object key = action.getValue(Action.ACCELERATOR_KEY);
        if (!(key instanceof KeyStroke ks))
            return "";
        String mods = java.awt.event.InputEvent.getModifiersExText(ks.getModifiers());
        return (mods.isEmpty() ? "" : mods + "+") + KeyEvent.getKeyText(ks.getKeyCode());
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
            putValue(Action.SHORT_DESCRIPTION, "The ways to start, where the help is, and what's new in this version");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            show();
        }
    }

    private WelcomeDialog() {}

}
