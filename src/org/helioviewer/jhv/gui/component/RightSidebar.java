package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.helioviewer.jhv.app.Settings;

/**
 * A second sidebar, on the right, holding the control palettes as sections instead of as floating
 * windows.
 *
 * <p>Same affordances as the left one: a handle that collapses it with a click and resizes it with
 * a drag, and both the width and the collapsed state remembered across launches. It differs in
 * what it is FOR, and so in one behaviour. The left sidebar's contents are fixed and it measures
 * itself to them (see MainFrame.stabilizeLeftPaneWidth, which sizes to the widest the image-layer
 * pane will ever be). This one holds whatever the user has put in it, which changes as palettes
 * are docked and floated, so it has no natural width to measure: it takes the width it is given
 * and its sections scroll.
 *
 * <p>It shows only when it holds something. An empty rail down the side of the window would be a
 * permanent reminder of a feature not in use, and with every palette floating there is nothing for
 * it to say.
 *
 * <p>The mirror image of the left sidebar in two places worth stating, because both are easy to
 * get backwards: the handle sits on the LEFT of the content rather than the right, and a drag to
 * the left WIDENS it, so the width moves against the pointer's x rather than with it.
 */
public final class RightSidebar {

    private static final int MIN_WIDTH = 160;
    private static final int MAX_WIDTH = 900;
    private static final int DEFAULT_WIDTH = 320;
    private static final int HANDLE_WIDTH = 16;
    private static final int SCROLLBAR_WIDTH = 10; // FlatLaf's own default, as both other scrollers use
    private static final int DRAG_THRESHOLD = 3;

    private static final String KEY_WIDTH = "ui.rightSidebarWidth";
    private static final String KEY_COLLAPSED = "ui.rightSidebarCollapsed";
    private static final String KEY_ORDER = "ui.rightSidebarOrder";

    @Nullable
    private static RightSidebar instance;

    public static RightSidebar getInstance() {
        if (instance == null)
            instance = new RightSidebar();
        return instance;
    }

    /** Fixed width, so the sidebar is what decides its size rather than whatever was docked into it. */
    @SuppressWarnings("serial")
    private static final class FixedWidthPanel extends JPanel {
        private int fixedWidth = -1;

        FixedWidthPanel() {
            super(new BorderLayout());
        }

        void setFixedWidth(int width) {
            fixedWidth = width;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (fixedWidth > 0)
                size.width = fixedWidth;
            return size;
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension size = super.getMinimumSize();
            if (fixedWidth > 0)
                size.width = fixedWidth;
            return size;
        }
    }

    private final SideContentPane pane = new SideContentPane();
    private final FixedWidthPanel host = new FixedWidthPanel();
    private final JPanel wrap = new JPanel(new BorderLayout());
    private final JButton handle = Buttons.flat(Buttons.collapseRight);
    /** What a section is made of, kept so the whole stack can be rebuilt in a new order. */
    private record Section(String title, @Nullable Icon icon, Component content, Runnable onFloat, JComponent holder) {}

    private final Map<String, Section> sections = new LinkedHashMap<>();

    private int width = readWidth();
    private boolean collapsed = "true".equals(Settings.getProperty(KEY_COLLAPSED));
    private boolean dragged; // a real drag happened, so the click that follows is not a click

    private RightSidebar() {
        // Never sideways: see SqueezeView for why the contents' own minimum widths are not to be
        // believed, and what is traded for it.
        JScrollPane scroller = new JScrollPane(new SqueezeView(pane),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setFocusable(false);
        scroller.setBorder(null);
        scroller.getVerticalScrollBar().setPreferredSize(new Dimension(SCROLLBAR_WIDTH, 0));
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        host.add(scroller, BorderLayout.CENTER);
        host.setFixedWidth(width);

        handle.setPreferredSize(new Dimension(HANDLE_WIDTH, 0));
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
        handle.addActionListener(e -> {
            // Same guard the left sidebar needs, for the same reason: the handle moves with the
            // edge being dragged, slides under a stationary pointer, and the mouseEntered that
            // follows re-arms the button model, so the release arrives as a click. Answer it here,
            // where nothing can re-arm it.
            if (dragged) {
                dragged = false;
                return;
            }
            setCollapsed(!collapsed);
        });
        attachResize();

        wrap.add(handle, BorderLayout.LINE_START);
        wrap.add(host, BorderLayout.CENTER);
        applyCollapsed();
        wrap.setVisible(false); // nothing docked yet
    }

    private static int readWidth() {
        try {
            return Math.clamp(Integer.parseInt(Settings.getProperty(KEY_WIDTH)), MIN_WIDTH, MAX_WIDTH);
        } catch (RuntimeException ignore) {
            return DEFAULT_WIDTH;
        }
    }

    /** The component to place against the east edge of the window. */
    public JComponent component() {
        return wrap;
    }

    /**
     * Put a palette's content in as a section.
     *
     * @param onFloat run when the user asks for this section to become a floating palette again
     */
    public void addSection(String title, @Nullable Icon icon, Component content, Runnable onFloat) {
        if (sections.containsKey(title))
            removeSection(title);
        sections.put(title, buildSection(title, icon, content, onFloat));
        applyStoredOrder();
        rebuild();
    }

    /**
     * The controls that ride above a section's own content: float it out, and move it up or down.
     *
     * <p>Above the content rather than in the section header, because a trailing button inside a
     * CollapsiblePane's header would sit outside the toggle button that paints the header's
     * coloured bar, leaving a notch of window background in it.
     *
     * <p>Buttons rather than dragging the header. Both are ways to say "put this one above that
     * one", and with a handful of sections a pair of arrows says it without the ambiguity of a
     * drop target, in a fraction of the code that reordering a GridBag by drag would take.
     */
    private Section buildSection(String title, @Nullable Icon icon, Component content, Runnable onFloat) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        // LEADING, not trailing. Right-aligned they sat at the far end of a row as wide as the
        // content, which is how they ended up outside the sidebar entirely and unreachable. At the
        // leading edge they are at x = 0 of the section whatever the content does.
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        bar.setOpaque(false);

        JButton up = Buttons.flat(Buttons.collapseAll);
        up.setToolTipText("Move " + title + " up");
        up.addActionListener(e -> move(title, -1));
        JButton down = Buttons.flat(Buttons.expandAll);
        down.setToolTipText("Move " + title + " down");
        down.addActionListener(e -> move(title, 1));
        JButton floatOut = Buttons.flat(Buttons.collapseLeft);
        floatOut.setToolTipText("Float " + title + " free of the sidebar");
        floatOut.addActionListener(e -> onFloat.run());

        bar.add(up);
        bar.add(down);
        bar.add(floatOut);
        holder.add(bar, BorderLayout.PAGE_START);
        holder.add(content, BorderLayout.CENTER);
        return new Section(title, icon, content, onFloat, holder);
    }

    public void removeSection(String title) {
        if (sections.remove(title) == null)
            return;
        rebuild();
    }

    /** Move a section one place up or down, and remember where everything ended up. */
    public void move(String title, int delta) {
        java.util.List<String> titles = reordered(new java.util.ArrayList<>(sections.keySet()), title, delta);
        Map<String, Section> next = new LinkedHashMap<>();
        for (String t : titles)
            next.put(t, sections.get(t));
        sections.clear();
        sections.putAll(next);
        Settings.setProperty(KEY_ORDER, String.join("|", titles));
        rebuild();
    }

    /**
     * The order after moving one entry by delta, clamped at the ends. Pure and package-private so
     * RightSidebarOrderCheck can pin it: the list arithmetic is the part that silently corrupts an
     * order, and it is the part that needs no window to test.
     */
    static java.util.List<String> reordered(java.util.List<String> titles, String title, int delta) {
        int from = titles.indexOf(title);
        if (from < 0)
            return titles;
        int to = Math.clamp(from + delta, 0, titles.size() - 1);
        if (to == from)
            return titles;
        titles.remove(from);
        titles.add(to, title);
        return titles;
    }

    /** Put the sections back in the order the user last left them in. */
    private void applyStoredOrder() {
        String stored = Settings.getProperty(KEY_ORDER);
        if (stored == null || stored.isBlank())
            return;
        Map<String, Section> next = new LinkedHashMap<>();
        for (String t : stored.split("\\|"))
            if (sections.containsKey(t))
                next.put(t, sections.get(t));
        for (Map.Entry<String, Section> e : sections.entrySet()) // anything the stored order never heard of
            next.putIfAbsent(e.getKey(), e.getValue());
        sections.clear();
        sections.putAll(next);
    }

    // SideContentPane appends, so a new order means adding them all again. Cheap at this many
    // sections, and it keeps one code path for adding, removing and reordering. Expansion is not
    // lost: a CollapsiblePane reads its own remembered state by title when it is built.
    private void rebuild() {
        for (Section s : sections.values())
            pane.remove(s.holder());
        for (Section s : sections.values())
            // Its own preference key, because CollapsiblePane otherwise remembers expansion under
            // the section's title and the left sidebar already has a section called Camera: the
            // two were collapsing each other through one shared setting.
            pane.add(s.title(), s.holder(), true, s.icon(), "rightSidebar." + s.title());
        wrap.setVisible(!sections.isEmpty());
        revalidate();
    }

    public boolean hasSection(String title) {
        return sections.containsKey(title);
    }

    /** The sections top to bottom, for a check and for anything that wants to report the state. */
    public java.util.List<String> sectionTitles() {
        return java.util.List.copyOf(sections.keySet());
    }

    /** Make a section visible: open the sidebar if it is folded away, and expand the section. */
    public void reveal(String title) {
        Section section = sections.get(title);
        if (section == null)
            return;
        if (collapsed)
            setCollapsed(false);
        pane.setExpanded(section.holder(), true);
        revalidate();
        section.holder().scrollRectToVisible(new java.awt.Rectangle(0, 0, section.holder().getWidth(), section.holder().getHeight()));
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean _collapsed) {
        if (collapsed == _collapsed)
            return;
        collapsed = _collapsed;
        Settings.setProperty(KEY_COLLAPSED, Boolean.toString(collapsed));
        applyCollapsed();
        revalidate();
    }

    private void applyCollapsed() {
        host.setVisible(!collapsed); // the handle stays, so there is something left to click
        handle.setIcon(collapsed ? Buttons.collapseLeft : Buttons.collapseRight);
        handle.setToolTipText(collapsed ? "Show the right sidebar" : "Drag to resize, click to collapse the right sidebar");
        // Collapsed there is nothing to resize and the drag is ignored, so the resize cursor would
        // be an offer the handle does not honour.
        handle.setCursor(Cursor.getPredefinedCursor(collapsed ? Cursor.DEFAULT_CURSOR : Cursor.W_RESIZE_CURSOR));
    }

    public void setWidth(int _width) {
        int clamped = Math.clamp(_width, MIN_WIDTH, MAX_WIDTH);
        if (clamped == width)
            return;
        width = clamped;
        host.setFixedWidth(width);
        revalidate();
        // Settings rewrites the whole file per call and a drag fires this every few pixels, so the
        // final width is written once the pointer settles. Same debounce the left sidebar uses.
        if (settle == null) {
            settle = new javax.swing.Timer(400, e -> Settings.setProperty(KEY_WIDTH, String.valueOf(width)));
            settle.setRepeats(false);
        }
        settle.restart();
    }

    @Nullable
    private javax.swing.Timer settle;

    private void attachResize() {
        int[] startX = new int[1];
        int[] startWidth = new int[1];
        handle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startX[0] = e.getXOnScreen();
                startWidth[0] = width;
                dragged = false;
            }
        });
        handle.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (collapsed)
                    return;
                int dx = e.getXOnScreen() - startX[0];
                if (!dragged && Math.abs(dx) < DRAG_THRESHOLD)
                    return;
                dragged = true;
                setWidth(startWidth[0] - dx); // against the pointer: dragging left widens it
            }
        });
    }

    // The canvas is nested deep in the layout and carries a native GL surface, so a size change
    // has to be pushed all the way down rather than left to the next repaint. MainFrame owns that
    // knowledge; this asks for it by the same route the left sidebar's own resize does.
    private void revalidate() {
        org.helioviewer.jhv.gui.MainFrame.reflowChrome();
    }

}
