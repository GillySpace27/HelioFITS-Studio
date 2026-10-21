package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.search.Tour;
import org.helioviewer.jhv.io.CommandLine;

import com.google.common.html.HtmlEscapers;

/**
 * Tip of the Day: one tip from {@link Tips} in a window of its own, apart from the Welcome window,
 * so each can be switched off or snoozed without the other.
 *
 * <p>At startup it opens at most once a day: after the Welcome window closes when that window
 * came up, or on its own a moment after launch when it did not, and never while a tour or the
 * first-launch tour offer is on screen. A tip that names a control has Show Me, which spotlights
 * it as a tour step does; a tip that names a tour has Take the Tour. Every tip shown is remembered
 * as seen (Tips), so the next one is one this person has not read yet.
 *
 * <p>Help > Tip of the Day opens it whatever the startup switch and the snooze say.
 */
public final class TipOfTheDay {

    private static final int OFFER_DELAY_MS = 2500; // the Welcome window's own delay, plus a little
    private static final int TEXT_WIDTH = 420;

    private static final AtomicReference<TipOfTheDay> showing = new AtomicReference<>();

    private final JDialog dialog;
    private final List<Tips.Tip> tips;
    private final List<Integer> history = new ArrayList<>(); // indices shown in this window, for Previous
    private int at = -1; // position in history

    private final JLabel heading = new JLabel();
    private final JLabel body = new JLabel();
    private final JLabel counter = new JLabel();
    private final JButton showMe = new JButton("Show Me");
    private final JButton takeTour = new JButton("Take the Tour");
    private final JButton previous = new JButton("Previous");
    private final JButton nextTip = new JButton("Next Tip");

    /** On a bare launch with no Welcome window: the tip on its own, a moment after the window is up. */
    static void offerAlone(boolean bareLaunch) {
        if (!bareLaunch || GraphicsEnvironment.isHeadless() || Session.isExtraWindow() || System.getenv("CI") != null)
            return;
        Timer later = new Timer(OFFER_DELAY_MS, e -> atStartup());
        later.setRepeats(false);
        later.start();
    }

    /** At startup, when it is due today and nothing else is asking for attention. */
    static void atStartup() {
        if (MainFrame.get() == null || !MainFrame.get().isShowing() || CommandLine.desktopDocumentRequested()
                || Tour.isRunning() || !Tour.offered() || !Tips.dueAtStartup(LocalDate.now()))
            return;
        Settings.setProperty(Tips.LAST_KEY, LocalDate.now().toString());
        show();
    }

    /** Open the window, or bring it forward, with the next tip not yet seen. */
    public static void show() {
        TipOfTheDay open = showing.get();
        if (open != null) {
            open.dialog.toFront();
            return;
        }
        List<Tips.Tip> tips = Tips.load();
        if (tips.isEmpty() || MainFrame.get() == null)
            return;
        TipOfTheDay t = new TipOfTheDay(tips);
        showing.set(t);
        t.go(Tips.next(tips));
        t.dialog.pack();
        t.dialog.setLocationRelativeTo(MainFrame.get());
        t.dialog.setVisible(true);
    }

    private TipOfTheDay(List<Tips.Tip> _tips) {
        tips = _tips;
        dialog = new JDialog(MainFrame.get(), "Tip of the Day", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                showing.compareAndSet(TipOfTheDay.this, null);
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 3));
        content.add(heading, BorderLayout.PAGE_START);
        JPanel middle = new JPanel(new BorderLayout(0, 6));
        middle.add(body, BorderLayout.CENTER);
        counter.setFont(counter.getFont().deriveFont(counter.getFont().getSize2D() - 1));
        middle.add(counter, BorderLayout.PAGE_END);
        content.add(middle, BorderLayout.CENTER);

        showMe.setToolTipText("Point at the control this tip is about");
        showMe.addActionListener(e -> showMe());
        takeTour.setToolTipText("Start the guided tour this tip is about");
        takeTour.addActionListener(e -> takeTour());
        previous.setToolTipText("The tip shown before this one");
        previous.addActionListener(e -> step(-1));
        nextTip.setToolTipText("The next tip you have not seen yet");
        nextTip.addActionListener(e -> step(1));

        JPanel actions = new JPanel(new BorderLayout());
        JPanel doIt = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        doIt.add(showMe);
        doIt.add(takeTour);
        JPanel browse = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        browse.add(previous);
        browse.add(nextTip);
        actions.add(doIt, BorderLayout.LINE_START);
        actions.add(browse, BorderLayout.LINE_END);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.add(actions, BorderLayout.PAGE_START);
        JPanel row = new JPanel(new BorderLayout());
        JCheckBox atStartup = new JCheckBox("Show a tip at startup", !"false".equals(Settings.getProperty(Tips.SHOW_KEY)));
        atStartup.setToolTipText("At most one a day. Help > Tip of the Day opens this window whenever you like.");
        atStartup.addActionListener(e -> Settings.setProperty(Tips.SHOW_KEY, Boolean.toString(atStartup.isSelected())));
        row.add(atStartup, BorderLayout.LINE_START);
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        JButton snooze = new JButton("Snooze");
        snooze.setToolTipText("No tip at startup for a while; the Welcome window is not affected");
        JPopupMenu snoozeMenu = new JPopupMenu();
        for (Tips.Snooze s : Tips.SNOOZES) {
            JMenuItem item = new JMenuItem(s.label());
            item.addActionListener(e -> {
                Settings.setProperty(Tips.SNOOZE_KEY, LocalDate.now().plusDays(s.days()).toString());
                dialog.dispose();
            });
            snoozeMenu.add(item);
        }
        snooze.addActionListener(e -> snoozeMenu.show(snooze, 0, snooze.getHeight()));
        closeRow.add(snooze);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        closeRow.add(close);
        row.add(closeRow, BorderLayout.LINE_END);
        footer.add(row, BorderLayout.PAGE_END);
        content.add(footer, BorderLayout.PAGE_END);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
    }

    /** Show the tip at {@code index} as a new entry in this window's history, and remember it as seen. */
    private void go(int index) {
        while (history.size() > at + 1) // stepping forward from the middle of the history drops what was ahead
            history.removeLast();
        history.add(index);
        at = history.size() - 1;
        display();
    }

    private void step(int delta) {
        if (delta < 0) {
            if (at > 0) {
                at--;
                display();
            }
        } else if (at < history.size() - 1) {
            at++;
            display();
        } else
            go(Tips.next(tips));
    }

    private void display() {
        Tips.Tip tip = tips.get(history.get(at));
        Tips.markSeen(tip);
        heading.setText(tip.title());
        body.setText("<html><div style='width:" + TEXT_WIDTH + "px'>" + escape(tip.text()) + "</div></html>");
        int unseen = Tips.unseenCount(tips, Tips.seen());
        counter.setText(unseen == 0 ? "You have seen all " + tips.size() + " tips; Next Tip starts them again."
                : unseen + " of " + tips.size() + " tips you have not seen yet");
        showMe.setVisible(tip.target() != null);
        takeTour.setVisible(tip.tour() != null);
        previous.setEnabled(at > 0);
        dialog.pack();
    }

    private void showMe() {
        Tips.Tip tip = tips.get(history.get(at));
        if (tip.target() == null)
            return;
        dialog.dispose(); // the spotlight's card says the same, beside the control
        Tour.spotlight(tip.target(), tip.reveal(), tip.title(), tip.text());
    }

    private void takeTour() {
        Tips.Tip tip = tips.get(history.get(at));
        if (tip.tour() == null)
            return;
        dialog.dispose();
        Tour.start(tip.tour());
    }

    private static String escape(String s) {
        return HtmlEscapers.htmlEscaper().escape(s);
    }

    /** Help > Tip of the Day. */
    @SuppressWarnings("serial")
    public static final class Open extends AbstractAction {
        public Open() {
            super("Tip of the Day...");
            putValue(Action.SHORT_DESCRIPTION, "A short tip about something HelioFITS Studio can do, one you have not seen yet");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            show();
        }
    }

}
