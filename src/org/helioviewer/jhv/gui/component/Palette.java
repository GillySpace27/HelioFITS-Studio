package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
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
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.PresentationMode;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideToggleButton;

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
 */
public final class Palette {

    private static final List<Palette> palettes = new ArrayList<>();
    private static boolean listenersAdded;

    private final String title;
    private final Supplier<Component> contentSupplier;
    private final Runnable onShow;

    @Nullable
    private JDialog dialog;
    @Nullable
    private JideToggleButton toggle;
    private boolean pinned = true; // pinned: docks to the corner and follows; unpinned: free-floating

    /**
     * @param title           shown in the header and used as the drag handle
     * @param contentSupplier builds the controls; called again whenever the palette is rebuilt
     * @param onShow          run just before the palette becomes visible, to refresh its state
     */
    public Palette(String title, Supplier<Component> contentSupplier, Runnable onShow) {
        this.title = title;
        this.contentSupplier = contentSupplier;
        this.onShow = onShow;
        palettes.add(this);
    }

    /** Bind to the toolbar toggle that opens it. The toolbar is recreated on display-mode change. */
    public void bind(JideToggleButton button) {
        toggle = button;
        dispose();
        button.addActionListener(e -> setOpen(button.isSelected()));
    }

    /** Open the palette with this title if it is not already open. Used by the layer row. */
    public static void open(String title) {
        for (Palette p : palettes)
            if (p.title.equals(title) && !p.isOpen())
                p.toggle();
    }

    /** Toggle exactly as the toolbar button does, so the View menu and the button stay in step. */
    public void toggle() {
        if (toggle != null)
            toggle.doClick();
    }

    public boolean isOpen() {
        return dialog != null && dialog.isVisible();
    }

    private void setOpen(boolean open) {
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
        int x, y;
        Window chrome = PresentationMode.chromeWindow();
        if (chrome != null && chrome.isShowing()) {
            x = chrome.getX() + chrome.getWidth() - dialog.getWidth() - 12;
            y = chrome.getY() + 12;
        } else {
            Component rc = MainFrame.getRenderComponent();
            if (rc == null || !rc.isShowing())
                return;
            Point loc = rc.getLocationOnScreen();
            x = loc.x + rc.getWidth() - dialog.getWidth() - 12;
            y = loc.y + 12;
        }
        for (Palette other : palettes) {
            if (other == this)
                break;
            if (other.pinned && other.isOpen())
                y += other.dialog.getHeight() + 8;
        }
        dialog.setLocation(x, y);
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
     */
    public static void repackAll() {
        for (Palette p : palettes) {
            if (p.dialog == null || !p.dialog.isVisible())
                continue;
            java.awt.Container content = p.dialog.getContentPane();
            if (!content.getPreferredSize().equals(content.getSize())) {
                p.dialog.pack();
                p.dock();
            }
        }
    }

    private static void dockOpen() {
        for (Palette p : palettes)
            if (p.isOpen())
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
        palette.setFocusableWindowState(false); // don't steal keyboard focus from the view
        palette.setAutoRequestFocus(false);
        // Being owned by the main frame is supposed to keep a dialog above it, but a non-focusable
        // owned window does not hold its place in the stacking order here: click anywhere in the
        // view and the frame comes up over the palette, which is a control you are meant to be
        // working WHILE watching that view. Stated outright rather than derived from window
        // events, which is what kept failing. See the activeWindow listener for the scoping.
        palette.setAlwaysOnTop(true);

        JPanel content = new JPanel();
        content.setCursor(Cursor.getDefaultCursor()); // always restore a visible arrow
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(content.getBackground().brighter()),
                BorderFactory.createEmptyBorder(4, 8, 6, 8)));

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
        JideToggleButton pin = new JideToggleButton("◱"); // dock-to-corner glyph
        pin.setSelected(pinned);
        pin.setToolTipText("Dock to the top-right corner (unpin to float freely)");
        pin.addActionListener(e -> {
            pinned = pin.isSelected();
            if (pinned)
                dockOpen();
        });
        JideButton close = new JideButton("✕");
        close.setToolTipText("Collapse (the toolbar " + title + " button reopens it)");
        close.addActionListener(e -> {
            if (toggle != null)
                toggle.setSelected(false);
            palette.setVisible(false);
            dockOpen(); // whatever was stacked below this closes the gap
        });
        headerButtons.add(pin);
        headerButtons.add(close);
        header.add(headerButtons, BorderLayout.LINE_END);
        content.add(header);
        content.add(new JSeparator());
        content.add(contentSupplier.get());

        palette.setContentPane(content);
        palette.pack();
        addGlobalListeners();
        return palette;
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
