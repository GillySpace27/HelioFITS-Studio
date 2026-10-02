package org.helioviewer.jhv.gui.search;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A guided tour: one control at a time, spotlit, with a card saying what it is for.
 *
 * <p>The steps are data, in resources/data/tours/&lt;id&gt;.json: the component name to spotlight
 * (a toolbar id, a catalogued menu item's id, or a literal setName), a title, a body, and
 * optionally an ActionCatalog id run first to bring the target on screen. TourCheck holds every
 * step to those rules. A target that is not showing gets its card centred with no cut-out.
 */
public final class Tour {

    public static final String GETTING_STARTED = "getting-started";
    /** Written when the first-launch offer appears, so it is made once. */
    static final String OFFERED_KEY = "ui.tourOffered";
    private static final int SETTLE_MS = 300; // after a reveal action, for the sidebar to lay itself out
    private static final int OFFER_DELAY_MS = 2000; // after start-up, once the palettes are back
    private static final int TEXT_WIDTH = 300;

    record Step(String target, String title, String body, @Nullable String action) {}

    private static final AtomicReference<Tour> running = new AtomicReference<>();

    static List<Step> parse(JSONObject tour) {
        JSONArray array = tour.getJSONArray("steps");
        List<Step> steps = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject s = array.getJSONObject(i);
            steps.add(new Step(s.optString("target"), s.optString("title"), s.optString("body"),
                    s.has("action") ? s.getString("action") : null));
        }
        return steps;
    }

    static List<Step> load(String id) throws IOException {
        try (InputStream is = FileUtils.getResource("/data/tours/" + id + ".json")) {
            return parse(JSONUtils.get(is));
        }
    }

    /** Help > Take the Tour. */
    public static Action action() {
        return new AbstractAction("Take the Tour") {
            @Override
            public void actionPerformed(ActionEvent e) {
                start(GETTING_STARTED);
            }
        };
    }

    public static void start(String id) {
        List<Step> steps;
        try {
            steps = load(id);
        } catch (IOException | RuntimeException e) { // TourCheck keeps the shipped tours readable; never a crash here
            Log.warn("Tour " + id + " could not be read", e);
            return;
        }
        if (steps.isEmpty() || MainFrame.get() == null)
            return;
        Tour old = running.getAndSet(null);
        if (old != null)
            old.end();
        Tour tour = new Tour(steps);
        running.set(tour);
        tour.go(0);
    }

    /**
     * Offer the tour once, on the first launch that could use it: a window started with no
     * arguments (a script or a check passes some), not an extra window, not on CI.
     */
    public static void offerOnce(boolean bareLaunch) {
        if (!bareLaunch || GraphicsEnvironment.isHeadless() || Session.isExtraWindow() || System.getenv("CI") != null
                || Settings.getProperty(OFFERED_KEY) != null)
            return;
        Timer later = new Timer(OFFER_DELAY_MS, e -> offer());
        later.setRepeats(false);
        later.start();
    }

    private static void offer() {
        JFrame frame = MainFrame.get();
        if (running.get() != null || frame == null || !frame.isShowing())
            return;
        Settings.setProperty(OFFERED_KEY, "true"); // offered, whatever the answer: never again
        Spotlight prompt = new Spotlight(false);
        Runnable notNow = prompt::dispose;
        Runnable now = () -> {
            prompt.dispose();
            start(GETTING_STARTED);
        };
        prompt.keys(notNow, null, now);
        JButton startButton = button("Start the Tour", now);
        prompt.show(find("imageCanvas"), card("New to HelioFITS Studio?",
                "A short tour points out the main controls, one at a time. You can also take it later from Help > Take the Tour.",
                "", button("Not now", notNow), startButton), startButton);
    }

    private final List<Step> steps;
    private final Spotlight spotlight = new Spotlight(true);
    private int index;

    private Tour(List<Step> _steps) {
        steps = _steps;
        spotlight.keys(this::end, () -> go(index - 1), () -> go(index + 1));
    }

    private void go(int i) {
        if (running.get() != this || i < 0)
            return;
        if (i >= steps.size()) {
            end();
            return;
        }
        index = i;
        String id = steps.get(i).action();
        ActionCatalog.Entry entry = id == null ? null : ActionCatalog.get(id);
        if (entry == null) {
            show(i);
            return;
        }
        try {
            entry.action().actionPerformed(new ActionEvent(MainFrame.get(), ActionEvent.ACTION_PERFORMED, id));
        } catch (RuntimeException e) { // a reveal that fails leaves the card centred, not the tour stuck
            Log.warn("Tour step action " + id + " failed", e);
        }
        Timer settle = new Timer(SETTLE_MS, e -> show(i));
        settle.setRepeats(false);
        settle.start();
    }

    private void show(int i) {
        if (running.get() != this || index != i) // ended, or stepped on, while the reveal settled
            return;
        Step step = steps.get(i);
        Component target = find(step.target());
        if (target instanceof JComponent c) // into view inside the sidebar's scroller
            c.scrollRectToVisible(new Rectangle(c.getWidth(), Math.min(c.getHeight(), 240)));
        boolean last = i == steps.size() - 1;
        JButton back = button("Back", () -> go(index - 1));
        back.setEnabled(i > 0);
        JButton next = button(last ? "Done" : "Next", () -> go(index + 1));
        JButton skip = button("Skip", this::end);
        skip.setVisible(!last);
        spotlight.show(target, card(step.title(), step.body(), (i + 1) + " of " + steps.size(), skip, back, next), next);
    }

    private void end() {
        running.compareAndSet(this, null);
        spotlight.dispose();
    }

    /** The named component, showing and with a size, in the main window or a floating palette; else null. */
    @Nullable
    static Component find(String name) {
        JFrame frame = MainFrame.get();
        Component hit = frame == null ? null : ActionCatalog.find(frame, name);
        if (usable(hit))
            return hit;
        for (Window w : Window.getWindows()) {
            if (w == frame || !w.isShowing())
                continue;
            hit = ActionCatalog.find(w, name);
            if (usable(hit))
                return hit;
        }
        return null;
    }

    private static boolean usable(@Nullable Component c) {
        return c != null && c.isShowing() && c.getWidth() > 0 && c.getHeight() > 0;
    }

    private static JButton button(String text, Runnable run) {
        JButton b = new JButton(text);
        b.addActionListener(e -> run.run());
        return b;
    }

    /** Built per step, so a theme switch mid-tour shows on the next card. */
    private static JComponent card(String title, String body, String counter, JButton... buttons) {
        Theme theme = Theme.current();
        Color fg = theme.get(Theme.Token.Foreground);
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(theme.get(Theme.Token.Background));
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(theme.get(Theme.Token.Accent), 2),
                BorderFactory.createEmptyBorder(12, 14, 10, 14)));
        card.getAccessibleContext().setAccessibleName(title);

        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 2));
        heading.setForeground(fg);
        JLabel text = new JLabel("<html><div style='width:" + TEXT_WIDTH + "px'>" + escape(body) + "</div></html>");
        text.setForeground(fg);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JLabel count = new JLabel(counter);
        count.setForeground(fg);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        actions.setOpaque(false);
        for (JButton b : buttons)
            actions.add(b);
        row.add(count, BorderLayout.LINE_START);
        row.add(actions, BorderLayout.LINE_END);

        card.add(heading, BorderLayout.PAGE_START);
        card.add(text, BorderLayout.CENTER);
        card.add(row, BorderLayout.PAGE_END);
        return card;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
