package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.PresentationMode;
import org.helioviewer.jhv.gui.UIGlobals;

/**
 * A persistent floating control palette: the Projection panel's window behaviour, made reusable.
 *
 * <p>Everything here was worked out for that one palette and none of it is about projections. It
 * survives focus loss so its sliders can be worked against the view, docks to the corner of the
 * render canvas and follows it, floats when dragged, and re-owns itself when presentation mode
 * moves the controls to another screen. A second palette wanting those properties should not have
 * to rediscover them, so this holds them once and takes the content as an argument.
 *
 * <p>Two behaviours are subtle enough to be worth restating where they live. A JDialog's owner is
 * fixed at construction and macOS makes an owned window a child of its parent, so the only way to
 * keep a palette on the screen carrying the controls is to build a new one under the new owner:
 * that is what {@link #rebuildAll()} does. And alwaysOnTop is not scoped to one application, so it
 * is lifted whenever no window of ours is active, or a viewer's tool palette would sit above a
 * terminal the viewer is not even looking at.
 *
 * <p>Open palettes stack down the right-hand edge in registration order, so a second one does not
 * land on top of the first.
 *
 * <p>Sizing has three rules, in order. A palette the user has dragged keeps that size, remembered
 * across launches. Otherwise it packs to its content, capped to the render canvas so a wide one
 * cannot open across the sidebar. Either way the content scrolls rather than being cut off.
 */
public final class Palette {

    private static final List<Palette> palettes = new ArrayList<>();
    private static boolean listenersAdded;

    private static final int DOCK_MARGIN = 12; // gap left between a docked palette and the canvas corner
    private static final int MIN_WIDTH = 180; // small enough to be useful, large enough to still be grabbable
    private static final int MIN_HEIGHT = 90;
    private static final int GRIP = 6; // border pixels that resize instead of doing nothing
    private static final int SCROLLBAR_WIDTH = 10; // FlatLaf's own ScrollBar.width default, as the sidebar uses

    private final String title;
    private final Supplier<Component> contentSupplier;
    private final Runnable onShow;
    private final boolean focusable;

    @Nullable
    private JDialog dialog;
    @Nullable
    private JToggleButton toggle;
    private boolean pinned = true; // pinned: docks to the corner and follows; unpinned: free-floating
    @Nullable
    private Dimension userSize; // a size dragged by hand: theirs to keep, and it outranks packing

    // Whether this palette lives in the right sidebar rather than in a window of its own. The two
    // are exclusive: in the sidebar it has no dialog at all, which is what keeps the watchdogs
    // below (keepVisible, repackAll, rebuildAll, all of which skip a null dialog) from
    // resurrecting it as a window behind the user's back.
    private boolean inSidebar;
    @Nullable
    private Dimension autoNatural; // the content size an auto-sized palette was last fitted to

    /**
     * @param title           shown in the header and used as the drag handle
     * @param contentSupplier builds the controls; called again whenever the palette is rebuilt
     * @param onShow          run just before the palette becomes visible, to refresh its state
     */
    public Palette(String title, Supplier<Component> contentSupplier, Runnable onShow) {
        this(title, contentSupplier, onShow, false);
    }

    /**
     * @param focusable whether the window may take keyboard focus. A palette of sliders and
     *                  buttons should not: it works against the view and must not take the
     *                  keyboard away from it. A palette with text fields must, or nothing can be
     *                  typed into them: a non-focusable window's fields can be clicked, and the
     *                  caret can even be placed, and every keystroke goes elsewhere. That is how
     *                  the Fourier palette's speeds came to be uneditable. Focus is still never
     *                  taken on showing, only on a click inside, and the view takes it back when
     *                  the pointer re-enters it.
     */
    public Palette(String title, Supplier<Component> contentSupplier, Runnable onShow, boolean focusable) {
        this.title = title;
        this.contentSupplier = contentSupplier;
        this.onShow = onShow;
        this.focusable = focusable;
        palettes.add(this);
    }

    /** Bind to the toolbar toggle that opens it. The toolbar is recreated on display-mode change. */
    public void bind(JToggleButton button) {
        toggle = button;
        dispose();
        // The toolbar is rebuilt whole (a display-mode change, presentation mode), and the new
        // button starts unselected. A palette sitting in the sidebar is present, so its button
        // has to say so rather than reading as switched off.
        button.setSelected(inSidebar);
        button.addActionListener(e -> {
            // Docked in the sidebar there is no window to open or close, so the toolbar button
            // means "show me this" rather than "toggle it": it opens the sidebar if it is folded
            // away and expands the section. The button stays lit, which is true, since the
            // palette is present either way.
            if (inSidebar) {
                RightSidebar.getInstance().reveal(title);
                button.setSelected(true);
                return;
            }
            setOpen(button.isSelected());
        });
    }

    /**
     * Show the palette with this title, wherever it lives. Used by the layer rows.
     *
     * <p>Goes through the instance method rather than testing isOpen and toggling: a docked
     * palette counts as open, so the old test made this a dead button the moment the palette was
     * moved into the sidebar. "Show it" means reveal the section there, or raise the window here.
     */
    public static void open(String title) {
        for (Palette p : palettes)
            if (p.title.equals(title))
                p.open();
    }

    private String key() {
        return "ui.palette." + title.replace(' ', '_');
    }

    private String sizeKey() {
        return key() + ".size";
    }

    private String sidebarKey() {
        return key() + ".sidebar";
    }

    /** The stored hand-set size, or null for a palette nobody has resized, which keeps packing. */
    @Nullable
    private Dimension readUserSize() {
        String value = Settings.getProperty(sizeKey());
        if (value == null)
            return null;
        try {
            int cross = value.indexOf('x');
            return new Dimension(Math.max(MIN_WIDTH, Integer.parseInt(value.substring(0, cross))),
                    Math.max(MIN_HEIGHT, Integer.parseInt(value.substring(cross + 1))));
        } catch (RuntimeException ignore) { // an unreadable size just means "pack it"
            return null;
        }
    }

    private void setUserSize(int width, int height) {
        userSize = new Dimension(width, height);
        Settings.setProperty(sizeKey(), width + "x" + height); // so the next launch reopens it this big
    }

    /** Reopen the palettes that were open when the application last quit. Needs the frame on screen. */
    public static void restoreOpen() {
        // Sidebar membership first: a palette that belongs there must not be opened as a window on
        // the way past, which is what the second loop would do with the stored open flag.
        for (Palette p : palettes)
            if (p.toggle != null && "true".equals(Settings.getProperty(p.sidebarKey())))
                p.setInSidebar(true);
        for (Palette p : palettes)
            if (p.toggle != null && !p.isOpen() && "true".equals(Settings.getProperty(p.key())))
                p.toggle();
    }

    /** Toggle exactly as the toolbar button does, so the View menu and the button stay in step. */
    public void toggle() {
        if (toggle != null)
            toggle.doClick();
    }

    public boolean isOpen() {
        return inSidebar || (dialog != null && dialog.isVisible());
    }

    public boolean isInSidebar() {
        return inSidebar;
    }

    /** Open, or if already open bring to the front: what a "settings..." button wants, where a toggle would close it. */
    public void open() {
        if (inSidebar) {
            RightSidebar.getInstance().reveal(title);
            return;
        }
        if (!isOpen())
            toggle();
        else if (dialog != null)
            dialog.toFront();
    }

    /**
     * Move this palette between the right sidebar and a window of its own.
     *
     * <p>The content component is the same object either way, and Swing gives a component exactly
     * one parent, so handing it to the other host is what moves it: the dialog is disposed rather
     * than hidden on the way in, and rebuilt on the way out.
     */
    public void setInSidebar(boolean sidebar) {
        if (inSidebar == sidebar)
            return;
        inSidebar = sidebar;
        Settings.setProperty(sidebarKey(), Boolean.toString(sidebar));
        if (sidebar) {
            Settings.setProperty(key(), "false"); // not a floating window now, so do not reopen as one
            dispose();
            RightSidebar.getInstance().addSection(title,
                    toggle == null ? null : toggle.getIcon(), // the same glyph as its toolbar button
                    contentSupplier.get(), () -> setInSidebar(false));
            onShow.run();
            if (toggle != null)
                toggle.setSelected(true);
        } else {
            RightSidebar.getInstance().removeSection(title);
            if (toggle != null)
                toggle.setSelected(true);
            setOpen(true);
        }
    }

    private void setOpen(boolean open) {
        Settings.setProperty(key(), Boolean.toString(open)); // so the next launch opens what was open
        if (!open) {
            if (dialog != null)
                dialog.setVisible(false);
            return;
        }
        if (dialog == null)
            dialog = create();
        onShow.run();
        dock();
        dialog.setVisible(true);
        // Everyone re-docks, not just this one: a palette registered later than this one but
        // opened earlier was stacked without knowing this one would arrive above it, and without
        // this the two land on the same spot. Seen with Fourier under Colour.
        dockOpen();
    }

    private void dispose() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    /**
     * Dock to the top-right of the render canvas, below any palette already sitting there.
     *
     * <p>In presenter view the render canvas is on the projector, so docking to it would park a
     * control on top of the output the audience is watching. Palettes belong with the rest of the
     * chrome, on the presenter's screen.
     */
    private void dock() {
        if (!pinned || dialog == null)
            return;
        Rectangle canvas = canvasBounds();
        if (canvas == null)
            return;
        int x = canvas.x + canvas.width - dialog.getWidth() - DOCK_MARGIN;
        int y = canvas.y + DOCK_MARGIN;
        for (Palette other : palettes) {
            if (other == this)
                break;
            if (other.pinned && other.isOpen())
                y += other.dialog.getHeight() + 8;
        }
        dialog.setLocation(x, y);
    }

    /**
     * The rectangle a palette docks against, on screen: the chrome window in presenter view, the
     * render canvas otherwise. One source of truth, because the size cap has to agree with the
     * docking or a palette capped to fit still lands somewhere it does not.
     */
    @Nullable
    private static Rectangle canvasBounds() {
        Window chrome = PresentationMode.chromeWindow();
        if (chrome != null && chrome.isShowing())
            return chrome.getBounds();
        Component rc = MainFrame.getRenderComponent();
        if (rc == null || !rc.isShowing())
            return null;
        Point loc = rc.getLocationOnScreen();
        return new Rectangle(loc.x, loc.y, rc.getWidth(), rc.getHeight());
    }

    /**
     * Trim a packed size to what fits against the render canvas.
     *
     * <p>The Camera palette's content is wider than the canvas, so packed and docked to the canvas
     * corner it reached back across the sidebar and covered it. Capping to the same rectangle
     * docking measures against, less the margin docking leaves at each end, is what keeps a
     * palette inside the picture it belongs to. The floor comes first: a canvas narrower than the
     * minimum would otherwise cap a palette down to nothing.
     */
    static Dimension capToCanvas(Dimension natural, @Nullable Rectangle canvas) {
        if (canvas == null)
            return natural; // nothing to measure against yet, e.g. before the canvas is on screen
        return new Dimension(
                Math.min(natural.width, Math.max(MIN_WIDTH, canvas.width - 2 * DOCK_MARGIN)),
                Math.min(natural.height, Math.max(MIN_HEIGHT, canvas.height - 2 * DOCK_MARGIN)));
    }

    /** Which resize a point in the palette asks for, as a Cursor constant. DEFAULT means none. */
    static int zoneAt(int width, int height, int x, int y) {
        boolean west = x < GRIP, east = x >= width - GRIP;
        boolean north = y < GRIP, south = y >= height - GRIP;
        if (north)
            return west ? Cursor.NW_RESIZE_CURSOR : east ? Cursor.NE_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR;
        if (south)
            return west ? Cursor.SW_RESIZE_CURSOR : east ? Cursor.SE_RESIZE_CURSOR : Cursor.S_RESIZE_CURSOR;
        return west ? Cursor.W_RESIZE_CURSOR : east ? Cursor.E_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR;
    }

    /**
     * Where a drag of (dx, dy) in the given zone puts the window. Dragging a leading edge moves the
     * origin as well as the size, and the minimum is enforced by pinning that edge rather than by
     * letting it walk past the trailing one.
     */
    static Rectangle resizeBounds(Rectangle start, int zone, int dx, int dy) {
        boolean west = zone == Cursor.NW_RESIZE_CURSOR || zone == Cursor.W_RESIZE_CURSOR || zone == Cursor.SW_RESIZE_CURSOR;
        boolean east = zone == Cursor.NE_RESIZE_CURSOR || zone == Cursor.E_RESIZE_CURSOR || zone == Cursor.SE_RESIZE_CURSOR;
        boolean north = zone == Cursor.NW_RESIZE_CURSOR || zone == Cursor.N_RESIZE_CURSOR || zone == Cursor.NE_RESIZE_CURSOR;
        boolean south = zone == Cursor.SW_RESIZE_CURSOR || zone == Cursor.S_RESIZE_CURSOR || zone == Cursor.SE_RESIZE_CURSOR;
        Rectangle to = new Rectangle(start);
        if (east)
            to.width = Math.max(MIN_WIDTH, start.width + dx);
        if (west) {
            to.width = Math.max(MIN_WIDTH, start.width - dx);
            to.x = start.x + start.width - to.width;
        }
        if (south)
            to.height = Math.max(MIN_HEIGHT, start.height + dy);
        if (north) {
            to.height = Math.max(MIN_HEIGHT, start.height - dy);
            to.y = start.y + start.height - to.height;
        }
        return to;
    }

    /**
     * Undo any hiding the platform did while the toggle still says the user wants it open.
     *
     * <p>Chasing each new way macOS found to hide an owned, non-focusable, always-on-top window
     * was a losing game. This inverts it: the toolbar toggle is the single record of intent, so
     * anything that hides a palette against it is by definition wrong and is simply undone. Worst
     * case it costs a flicker; the alternative was a control that vanished mid-adjustment.
     *
     * <p>One hiding is legitimate and left alone: a minimized or hidden owner takes its owned
     * windows with it, and re-showing then would float a palette over other applications while
     * this one is deliberately out of the way.
     */
    public static void keepVisible() {
        for (Palette p : palettes) {
            if (p.dialog == null || p.toggle == null || !p.toggle.isSelected())
                continue; // never opened, mid-rebuild, or genuinely closed by the user
            Window owner = p.dialog.getOwner();
            if (owner != null) {
                if (!owner.isShowing())
                    continue;
                if (owner instanceof java.awt.Frame frame && (frame.getExtendedState() & java.awt.Frame.ICONIFIED) != 0)
                    continue;
            }
            if (!p.dialog.isVisible()) {
                p.dock();
                p.dialog.setVisible(true);
                p.dialog.toFront(); // non-focusable, so this raises without taking the keyboard
            }
        }
    }

    /**
     * Grow the window when its contents grew.
     *
     * <p>A palette is packed when it is built, and its content is not fixed afterwards: the
     * sequence filter's readout gains and loses lines as the layer and the settings change. Once
     * the readout went from two lines to four, the last line and the button under it were simply
     * outside the window. Nothing in Swing repacks a window on its own, so this is asked for
     * whenever the content is refreshed, and does nothing when the size already fits.
     *
     * <p>A palette the user has resized is left alone: packing it would throw that size away, and
     * it no longer needs the help, because its content scrolls. The others track the shape of
     * their content, which is what is compared rather than the window size: a palette held back by
     * the canvas cap never matches its own preferred size, and would repack on every refresh.
     */
    public static void repackAll() {
        for (Palette p : palettes) {
            if (p.dialog == null || !p.dialog.isVisible() || p.userSize != null)
                continue;
            Dimension natural = p.dialog.getContentPane().getPreferredSize();
            if (natural.equals(p.autoNatural))
                continue;
            p.autoNatural = natural;
            p.dialog.pack();
            p.dialog.setSize(capToCanvas(p.dialog.getSize(), canvasBounds()));
            p.dialog.validate();
            p.dock();
        }
    }

    private static void dockOpen() {
        for (Palette p : palettes)
            if (p.dialog != null && p.isOpen()) // a sidebar palette has no window to place
                p.dock();
    }

    /** Rebuild every open palette under the current owner. Presentation mode moves that owner. */
    public static void rebuildAll() {
        for (Palette p : palettes) {
            if (p.dialog == null)
                continue;
            boolean wasVisible = p.dialog.isVisible();
            p.dispose();
            if (wasVisible) {
                p.dialog = p.create();
                p.onShow.run();
                p.dock();
                p.dialog.setVisible(true);
            }
        }
        dockOpen();
    }

    private static Window owner() {
        Window chrome = PresentationMode.chromeWindow();
        return chrome != null ? chrome : MainFrame.get();
    }

    private JDialog create() {
        JDialog palette = new JDialog(owner(), Dialog.ModalityType.MODELESS);
        palette.setUndecorated(true); // no OS chrome: a docked tool palette, not a window
        palette.setFocusableWindowState(focusable); // see the constructor: only a palette with text fields
        palette.setAutoRequestFocus(false);
        // Being owned by the main frame is supposed to keep a dialog above it, but a non-focusable
        // owned window does not hold its place in the stacking order here: click anywhere in the
        // view and the frame comes up over the palette, which is a control you are meant to be
        // working WHILE watching that view. Stated outright rather than derived from window
        // events, which is what kept failing. See the activeWindow listener for the scoping.
        palette.setAlwaysOnTop(true);

        // BorderLayout, not the BoxLayout this used to be: the header has to keep its height while
        // the scroller below it takes up every pixel the user adds or removes. Under a BoxLayout
        // the two shrink together and the title bar is the first thing to go.
        JPanel content = new JPanel(new BorderLayout());
        content.setCursor(Cursor.getDefaultCursor()); // always restore a visible arrow
        UIGlobals.themed(content, c -> c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIGlobals.separator()),
                BorderFactory.createEmptyBorder(4, 8, 6, 8))));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setToolTipText("Drag to move (undocks); use the pin to re-dock to the corner");
        header.add(label, BorderLayout.CENTER);

        MouseAdapter dragger = new MouseAdapter() { // dragging the header floats the palette
            private Point grab;

            @Override
            public void mousePressed(MouseEvent e) {
                grab = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                pinned = false;
                Point on = e.getLocationOnScreen();
                palette.setLocation(on.x - grab.x, on.y - grab.y);
            }
        };
        label.addMouseListener(dragger);
        label.addMouseMotionListener(dragger);

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        headerButtons.setOpaque(false);
        JToggleButton pin = Buttons.flatToggle("◱"); // dock-to-corner glyph
        pin.setSelected(pinned);
        pin.setToolTipText("Dock to the top-right corner (unpin to float freely)");
        pin.addActionListener(e -> {
            pinned = pin.isSelected();
            if (pinned)
                dockOpen();
        });
        JButton toSidebar = Buttons.flat(Buttons.chevronRight);
        toSidebar.setToolTipText("Dock into the right sidebar");
        toSidebar.addActionListener(e -> setInSidebar(true));
        JButton close = Buttons.flat("✕");
        close.setToolTipText("Collapse (the toolbar " + title + " button reopens it)");
        close.addActionListener(e -> {
            if (toggle != null)
                toggle.setSelected(false);
            palette.setVisible(false);
            dockOpen(); // whatever was stacked below this closes the gap
        });
        headerButtons.add(toSidebar);
        headerButtons.add(pin);
        headerButtons.add(close);
        header.add(headerButtons, BorderLayout.LINE_END);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.PAGE_AXIS));
        top.add(header);
        top.add(new JSeparator());
        content.add(top, BorderLayout.PAGE_START);

        // The palette can be made smaller than its content wants to be, so the content has to be
        // able to scroll instead of being cut off at the window edge. Same treatment as the
        // sidebar's scroller: bars only when they are needed, no border of its own over the
        // palette's line border, and a thin bar when one does appear.
        JScrollPane scroller = new JScrollPane(contentSupplier.get(),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroller.setBorder(null);
        scroller.setFocusable(false);
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setPreferredSize(new Dimension(SCROLLBAR_WIDTH, 0));
        scroller.getHorizontalScrollBar().setPreferredSize(new Dimension(0, SCROLLBAR_WIDTH));
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        scroller.getHorizontalScrollBar().setUnitIncrement(16);
        content.add(scroller, BorderLayout.CENTER);

        attachResize(palette, content);

        palette.setContentPane(content);
        palette.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT)); // a drag cannot take it to nothing
        palette.pack();
        if (userSize == null)
            userSize = readUserSize();
        autoNatural = content.getPreferredSize();
        // Packing gives the content's natural size; the cap is what stops a palette wider than the
        // canvas (Camera) from opening across the sidebar. A hand-set size beats both.
        palette.setSize(userSize != null ? userSize : capToCanvas(palette.getSize(), canvasBounds()));
        palette.validate();
        addGlobalListeners();
        return palette;
    }

    /**
     * Resize the palette by dragging its edges and corners, as any window resizes.
     *
     * <p>setUndecorated is deliberate, so there is no OS grip to inherit and setResizable alone
     * offers nothing to grab. All eight zones rather than a corner grip only: dragging an edge is
     * what people already do to windows, and the border insets the palette already draws are wide
     * enough to catch the press without taking a single click away from the controls inside them.
     * Nothing here touches pinned, so a docked palette stays docked and simply re-docks at its new
     * size when the drag ends.
     */
    private void attachResize(JDialog palette, JPanel content) {
        MouseAdapter resizer = new MouseAdapter() {
            private int zone = Cursor.DEFAULT_CURSOR;
            @Nullable
            private Point press;
            @Nullable
            private Rectangle start;

            @Override
            public void mouseMoved(MouseEvent e) {
                content.setCursor(Cursor.getPredefinedCursor(zoneAt(content.getWidth(), content.getHeight(), e.getX(), e.getY())));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (start == null)
                    content.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                zone = zoneAt(content.getWidth(), content.getHeight(), e.getX(), e.getY());
                if (zone == Cursor.DEFAULT_CURSOR)
                    return;
                press = e.getLocationOnScreen();
                start = palette.getBounds();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (start == null || press == null)
                    return;
                Point on = e.getLocationOnScreen();
                palette.setBounds(resizeBounds(start, zone, on.x - press.x, on.y - press.y));
                palette.validate();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Rectangle from = start;
                start = null;
                press = null;
                // A click on the border is not a resize, and must not freeze a palette that is
                // still happily sizing itself to its content.
                if (from == null || (from.width == palette.getWidth() && from.height == palette.getHeight()))
                    return;
                setUserSize(palette.getWidth(), palette.getHeight());
                dock();
            }
        };
        content.addMouseListener(resizer);
        content.addMouseMotionListener(resizer);
    }

    // Registered once for the lifetime of the app, and acting on the live palettes rather than on
    // any one dialog: create() runs many times, so a listener holding an instance would go on
    // nudging a disposed window while the live one sat unmanaged.
    private static void addGlobalListeners() {
        if (listenersAdded)
            return;
        listenersAdded = true;
        // Scope alwaysOnTop to this application. The focus manager reports a null active window
        // exactly when no window of ours is active. Cheaper and more reliable than window
        // listeners on every frame we might own, and it covers the palettes for free: they are
        // non-focusable, so one never becomes active and never mistakes itself for the app.
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("activeWindow", event -> {
                    boolean appActive = event.getNewValue() != null;
                    for (Palette p : palettes)
                        if (p.dialog != null && p.dialog.isAlwaysOnTop() != appActive)
                            p.dialog.setAlwaysOnTop(appActive);
                });
        ComponentAdapter follow = new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                dockOpen();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                dockOpen();
            }
        };
        MainFrame.get().addComponentListener(follow);
        // Backstop only, for ordering that alwaysOnTop does not settle on its own. The toggle
        // button is the record of whether the user wants a palette open, so restore from that
        // rather than from isVisible, which the platform may have set to false behind our back.
        MainFrame.get().addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                for (Palette p : palettes) {
                    if (p.dialog == null)
                        continue;
                    if (p.toggle != null && p.toggle.isSelected() && !p.dialog.isVisible())
                        p.dialog.setVisible(true);
                    if (p.dialog.isVisible())
                        p.dialog.toFront();
                }
            }
        });
    }

}
