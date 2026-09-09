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
import javax.swing.Icon;
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

    // Where this palette lives: a sidebar, or null for a window of its own. The two are exclusive:
    // docked it has no dialog at all, which is what keeps the watchdogs below (keepVisible,
    // repackAll, rebuildAll, all of which skip a null dialog) from resurrecting it as a window
    // behind the user's back.
    @Nullable
    private SectionHost home;
    // The glyph the sidebar shows for it. Normally the toolbar button's own, so the two chromes
    // name a thing the same way; set explicitly for the panes that have no toolbar button.
    @Nullable
    private Icon icon;
    // The sidebar this belongs to when it has nowhere else to be. Only the panes that came from a
    // sidebar have one, and it is what stops a palette with no toolbar button being closed for
    // good: see the close button in create().
    @Nullable
    private SectionHost defaultHome;
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

    /**
     * Bind to the toolbar toggle that opens it. The whole bar is rebuilt whenever its display mode
     * or its contents change, and each rebuild binds again.
     *
     * <p>A floating palette is disposed on the way through, because its dialog is owned by a
     * button that is about to stop existing. It is then put back if it was showing: the rebuild is
     * a fact about the toolbar, and a palette the user had open and was working against should not
     * be a casualty of one. This mattered little when the only rebuild was a display-mode change;
     * it matters now that editing the bar rebuilds it on every drag.
     */
    public void bind(JToggleButton button) {
        toggle = button;
        boolean wasFloating = hasWindow();
        dispose();
        // The new button starts unselected. A palette showing in the sidebar is present, so its
        // button has to say so rather than reading as switched off.
        button.setSelected(wasFloating || isOpen());
        button.addActionListener(e -> {
            // One meaning in both homes: lit is showing, unlit is not. Docked, that shows or hides
            // the sidebar section rather than a window. It deliberately does NOT undock: where a
            // palette lives is the section's pop-out button's question, and answering it here
            // would make throwing the palette back into a window the only way to put it away.
            if (home != null)
                setSidebarShown(button.isSelected());
            else
                setOpen(button.isSelected());
        });
        if (wasFloating)
            setOpen(true); // rebuilt under the new owner, where the user left it
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

    /** Which sidebar it lives in, by {@link SectionHost#hostName()}, or absent for a window. */
    private String sidebarKey() {
        return key() + ".sidebar";
    }

    /**
     * The sidebar a stored name refers to, or null for a window.
     *
     * <p>"true" is read as the right sidebar. It is what the build that had only one wrote, and
     * these settings files are already on disk.
     */
    @Nullable
    static SectionHost hostNamed(@Nullable String name) {
        if (name == null)
            return null;
        return switch (name) {
            case "left" -> LeftSidebar.getInstance();
            case "right", "true" -> RightSidebar.getInstance();
            default -> null;
        };
    }

    /**
     * Whether the sidebar section is showing. Its own key, and ABSENT MEANS SHOWING: settings
     * written before the toolbar button could release a docked palette say open=false for every
     * palette that was docked, so reading the showing state out of that key would have brought
     * Gilly's docked palettes back hidden, once, with no way to tell that from a real release.
     */
    private String shownKey() {
        return key() + ".shown";
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

    /**
     * Put this palette in a sidebar, showing unless it was last released from it. Idempotent, so
     * restore can call it over a home the sidebar registration already applied.
     */
    private void dockInto(SectionHost host) {
        if (home == host && isOpen())
            return;
        home = host;
        if (!"false".equals(Settings.getProperty(shownKey()))) // see shownKey: absent means showing
            setSidebarShown(true);
    }

    /**
     * Apply the stored home at startup, defaulting to the sidebar the pane belongs to.
     *
     * <p>Only the docked cases are settled here, because this runs while the window is still being
     * built and a window-homed palette needs a frame on screen to be owned by. That one is left to
     * {@link #restoreOpen}, which runs later and now handles a palette with no toolbar button.
     */
    void restoreHome(SectionHost fallback) {
        defaultHome = fallback;
        String stored = Settings.getProperty(sidebarKey());
        SectionHost host = stored == null ? fallback : hostNamed(stored);
        if (host != null)
            dockInto(host);
    }

    void setIcon(@Nullable Icon _icon) {
        icon = _icon;
    }

    /** Reopen the palettes that were open when the application last quit. Needs the frame on screen. */
    public static void restoreOpen() {
        for (Palette p : palettes) {
            // Where it lives is asked first and on its own: a palette whose home is a sidebar must
            // not be opened as a window on the way past, which is what the open flag alone would
            // do. Released there, it stays released, and its button with it.
            SectionHost host = hostNamed(Settings.getProperty(p.sidebarKey()));
            if (host != null) {
                p.dockInto(host);
                if (p.toggle != null)
                    p.toggle.setSelected(p.isOpen());
            } else if (!p.isOpen() && "true".equals(Settings.getProperty(p.key()))) {
                if (p.toggle != null)
                    p.toggle();
                else
                    p.setOpen(true); // a sidebar pane with no toolbar button of its own
            }
        }
    }

    /** Toggle exactly as the toolbar button does, so the View menu and the button stay in step. */
    public void toggle() {
        if (toggle != null)
            toggle.doClick();
    }

    /**
     * Showing somewhere, as a window or as a section of the sidebar. This is the question the
     * toolbar and the layer rows are asking, and living in the sidebar is not enough to answer it
     * yes: a docked palette whose button has been released is still docked, just not showing.
     *
     * <p>NOT the question anything doing window geometry is asking. A palette in the sidebar has
     * no window at all, so code that stacks or measures windows must use {@link #hasWindow}: this
     * method started answering "yes" for a windowless palette when docking arrived, and the
     * stacking loop in dock(), which had always been entitled to assume otherwise, dereferenced a
     * null dialog on the next launch that restored one.
     */
    public boolean isOpen() {
        return home != null ? home.hasSection(title) : hasWindow();
    }

    /** Has a window of its own, on screen. The precondition for anything positional. */
    boolean hasWindow() {
        return dialog != null && dialog.isVisible();
    }

    /** Living in a sidebar rather than in a window of its own, whether or not it is showing there. */
    public boolean isDocked() {
        return home != null;
    }

    /** Open, or if already open bring to the front: what a "settings..." button wants, where a toggle would close it. */
    public void open() {
        if (home != null) {
            if (isOpen())
                home.reveal(title);
            else
                setSidebarShown(true); // put back whatever the toolbar button was used to release
            if (toggle != null)
                toggle.setSelected(true);
            return;
        }
        if (!isOpen())
            toggle();
        else if (dialog != null)
            dialog.toFront();
    }

    /**
     * Move this palette to a sidebar, to the other sidebar, or (null) to a window of its own.
     *
     * <p>The content component is the same object wherever it goes, and Swing gives a component
     * exactly one parent, so handing it to the new host is what moves it: the dialog is disposed
     * rather than hidden on the way into a sidebar, and rebuilt on the way out.
     */
    public void setHome(@Nullable SectionHost host) {
        if (home == host)
            return;
        SectionHost was = home;
        home = host;
        Settings.setProperty(sidebarKey(), host == null ? "window" : host.hostName());
        if (was != null)
            was.removeSection(title);
        if (host != null) {
            Settings.setProperty(key(), "false"); // not a floating window now, so do not reopen as one
            dispose();
            setSidebarShown(true);
        } else
            setOpen(true);
        if (toggle != null)
            toggle.setSelected(true); // it changed address, it did not go away
    }

    /**
     * Show or hide the sidebar section, leaving the palette living there either way.
     *
     * <p>Where a palette lives and whether it is showing are separate questions, and separating
     * them is the point. They were one: a docked palette counted as showing by virtue of being
     * docked, so its toolbar button was permanently lit with nothing to click it for, and the only
     * way to make it go away was to pop it back out into a window first. The pop-out button on the
     * section answers the first question; the toolbar button answers this one.
     */
    private void setSidebarShown(boolean shown) {
        if (home == null)
            return;
        Settings.setProperty(shownKey(), Boolean.toString(shown)); // so the next launch shows what was showing
        if (!shown) {
            home.removeSection(title);
            return;
        }
        home.addSection(title,
                icon != null ? icon : toggle == null ? null : toggle.getIcon(), // its toolbar glyph
                contentSupplier.get(), () -> setHome(null));
        onShow.run();
        home.reveal(title);
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
            if (other.pinned && other.hasWindow())
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
            if (p.dialog == null)
                continue; // never opened, or mid-rebuild
            // The record of intent is the toolbar toggle where there is one. A palette without a
            // button (a pane popped out of the left sidebar) has none to consult, and for it the
            // intent is simply that it has a window and no sidebar to be in. Without this branch
            // the platform could hide one of those for good, since nothing would put it back.
            if (p.toggle != null ? !p.toggle.isSelected() : p.home != null)
                continue; // genuinely closed by the user, or docked
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
            if (p.hasWindow()) // a sidebar palette has no window to place
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
        // One chevron per sidebar, pointing at it. A palette can be put in either, so the header
        // has to offer both: with a single "dock" button there was no way to say which.
        JButton toLeft = Buttons.flat(Buttons.collapseLeft);
        toLeft.setToolTipText("Dock into the left sidebar");
        toLeft.addActionListener(e -> setHome(LeftSidebar.getInstance()));
        JButton toRight = Buttons.flat(Buttons.chevronRight);
        toRight.setToolTipText("Dock into the right sidebar");
        toRight.addActionListener(e -> setHome(RightSidebar.getInstance()));
        // What close means depends on whether anything could undo it. A palette with a toolbar
        // button collapses, and the button brings it back. One WITHOUT a button (the panes the left
        // sidebar starts with, popped out) has nothing that would ever reopen it, so closing it
        // would be closing it for good: it goes home to its sidebar instead.
        boolean homeward = toggle == null && defaultHome != null;
        JButton close = Buttons.flat("✕");
        close.setToolTipText(homeward
                ? "Put " + title + " back in the sidebar"
                : "Collapse (the toolbar " + title + " button reopens it)");
        close.addActionListener(e -> {
            if (homeward) {
                setHome(defaultHome);
                return;
            }
            if (toggle != null)
                toggle.setSelected(false);
            palette.setVisible(false);
            dockOpen(); // whatever was stacked below this closes the gap
        });
        headerButtons.add(toLeft);
        headerButtons.add(toRight);
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
