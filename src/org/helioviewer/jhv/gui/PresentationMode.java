package org.helioviewer.jhv.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;

import javax.annotation.Nullable;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;

/**
 * Output-only fullscreen for showing JHV to a room.
 *
 * <p>Single monitor: the chrome is hidden and the frame fills the screen, so nothing but the
 * render output is left. Two monitors: the frame fills the external display and the controls
 * move to a separate presenter window on the laptop, so the audience sees only the image while
 * the presenter keeps the toolbar, the transport and the layer list.
 *
 * <p>The render canvas is never reparented and the frame is never disposed. Both would run
 * {@code removeNotify()} on {@link org.helioviewer.jhv.opengl.AngleCanvas}, which destroys the
 * native Metal host and, through {@code AngleRenderer.destroy()}, every static GL object the
 * renderer holds -- shaders, uniform buffers and layer textures alike. Moving a window between
 * screens costs none of that, so presentation mode only ever moves and resizes the frame it
 * already has. That is also why this cannot use {@code setUndecorated()}, which requires a
 * non-displayable window.
 */
public final class PresentationMode {

    private static boolean active;

    @Nullable private static JFrame presenterWindow;
    private static boolean savedEastVisible;
    @Nullable private static GraphicsDevice fullScreenOn; // the device we put into exclusive full screen
    @Nullable private static java.awt.event.ComponentAdapter settleListener;
    @Nullable private static Rectangle savedBounds;
    private static int savedExtendedState;
    private static boolean savedSidebarCollapsed;

    public static boolean isActive() {
        return active;
    }

    /**
     * The window carrying the controls while presenting, or null when there isn't one (not
     * presenting, or single-monitor where the controls are simply hidden). Anything that wants
     * to place itself near the controls rather than over the output should dock to this.
     */
    @Nullable
    public static java.awt.Window chromeWindow() {
        return presenterWindow;
    }

    public static void toggle() {
        if (active)
            exit();
        else
            enter();
    }

    private static void enter() {
        if (active)
            return;
        JFrame frame = MainFrame.get();
        if (frame == null)
            return;

        savedBounds = frame.getBounds();
        savedExtendedState = frame.getExtendedState();
        savedSidebarCollapsed = MainFrame.isSidebarCollapsed();

        GraphicsDevice target = resolve(OUTPUT_SCREEN, presentationDevice(deviceOf(frame)));
        GraphicsDevice presenterScreen = resolve(CONTROLS_SCREEN,
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
        // Both on one screen would mean the controls sit on top of the output, so treat that as
        // "no second screen" and just hide the chrome, which is the single-monitor behaviour.
        boolean dual = target != presenterScreen;

        // Hide everything first, in both configurations: the presented window must be output
        // only either way. The presenter window then takes back just the panels it wants, which
        // is also what keeps the status bar and the plugins pane off the projector.
        savedEastVisible = MainFrame.isEastVisible();
        Keep keep = keepFor(dual);
        MainFrame.setChromeVisible(false, keep.left(), keep.right(), savedEastVisible);
        if (!keep.palettes())
            org.helioviewer.jhv.gui.component.Palette.setFloatingVisible(false);
        if (dual)
            presenterWindow = buildPresenterWindow(presenterScreen);

        // NORMAL first: a maximized frame ignores setBounds on some platforms.
        frame.setExtendedState(JFrame.NORMAL);
        frame.setBounds(target.getDefaultConfiguration().getBounds());
        // Sizing the window to the screen still leaves the macOS menu bar drawn over the top of
        // it. Only real full-screen mode takes the screen away from the menu bar and the Dock,
        // so ask for it and keep the plain bounds as the fallback where it is not supported.
        if (target.isFullScreenSupported()) {
            try {
                target.setFullScreenWindow(frame);
                fullScreenOn = target;
            } catch (RuntimeException e) {
                org.helioviewer.jhv.app.Log.warn("Full screen refused, showing at screen size instead", e);
                fullScreenOn = null;
            }
        }

        installEscape(frame.getRootPane());
        active = true;

        // Going full screen and moving between screens are both asynchronous on macOS, and the
        // native Metal layer is positioned by hand in content-pane coordinates -- so resyncing
        // once, here, measures the window as it was before the transition and leaves the render
        // surface cropped to the old size. Resync on the resize events the transition actually
        // produces instead, which also covers the backing-scale change between a Retina laptop
        // and a 1x projector.
        settleListener = new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                MainFrame.resyncRenderSurface();
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                MainFrame.resyncRenderSurface();
            }
        };
        frame.addComponentListener(settleListener);
        MainFrame.resyncRenderSurface();
        org.helioviewer.jhv.gui.component.ToolBar.syncPresentationToggle();
        org.helioviewer.jhv.gui.component.ToolBar.redockProjectionPalette();
    }

    private static void exit() {
        if (!active)
            return;
        JFrame frame = MainFrame.get();
        if (frame == null)
            return;

        if (settleListener != null) {
            frame.removeComponentListener(settleListener);
            settleListener = null;
        }
        // Give the screen back before moving anything, or the frame is restored while the
        // device still thinks it owns an exclusive full-screen window.
        if (fullScreenOn != null) {
            try {
                fullScreenOn.setFullScreenWindow(null);
            } catch (RuntimeException e) {
                org.helioviewer.jhv.app.Log.warn("Could not leave full screen cleanly", e);
            }
            fullScreenOn = null;
        }
        if (presenterWindow != null) {
            returnChrome();
            presenterWindow.dispose(); // a plain JFrame of lightweight panels: no GL to lose
            presenterWindow = null;
        }
        MainFrame.setChromeVisible(true, false, false, savedEastVisible);
        org.helioviewer.jhv.gui.component.Palette.setFloatingVisible(true);
        MainFrame.setSidebarCollapsed(savedSidebarCollapsed);

        if (savedBounds != null)
            frame.setBounds(savedBounds);
        frame.setExtendedState(savedExtendedState);
        savedBounds = null;

        active = false;
        MainFrame.resyncRenderSurface();
        // Escape does not go through the toolbar button, so tell it what actually happened.
        org.helioviewer.jhv.gui.component.ToolBar.syncPresentationToggle();
        org.helioviewer.jhv.gui.component.ToolBar.redockProjectionPalette();
    }

    // --- which screen is which -------------------------------------------------------------
    // Remembered across sessions, because the answer is a property of the room's wiring rather
    // than of the document. Empty means "decide automatically", which is the default and what
    // most single-projector setups want.
    public static final String OUTPUT_SCREEN = "presentation.outputScreen";
    public static final String CONTROLS_SCREEN = "presentation.controlsScreen";

    // --- what stays on screen ---------------------------------------------------------------
    public static final String KEEP_LEFT = "presentation.keepLeftSidebar";
    public static final String KEEP_RIGHT = "presentation.keepRightSidebar";
    public static final String KEEP_PALETTES = "presentation.keepFloatingPalettes";

    /** What presentation mode leaves up. All three are false on two screens; see {@link #keepFor}. */
    public record Keep(boolean left, boolean right, boolean palettes) {}

    /**
     * What stays on screen, given how many screens are in play.
     *
     * <p>On one screen the picture is the whole display and anything kept is drawn over it, which
     * is a real trade the presenter is making knowingly: a sidebar in the corner of the slide, in
     * exchange for being able to drive the thing without leaving the mode. Those are the settings.
     *
     * <p>With a second display the sidebar settings are ignored, and not as a limitation worked
     * around: the chrome is not hidden there at all, it is lent to a presenter window on the other
     * screen where both sidebars already are. Keeping one "on screen" would mean drawing it over
     * the projector, which is the one thing the mode exists to prevent.
     *
     * <p>The palettes go the other way, and the difference is the whole reason this is not one
     * flag. A palette is its own window and follows the presenter to the second screen; hiding
     * them there would take away the controls the presenter view exists to provide. So the
     * sidebars are forced off on two screens and the palettes are forced ON.
     *
     * <p>Pure, so the check can pin both rules without a projector.
     */
    public static Keep keepFor(boolean dual) {
        return dual ? new Keep(false, false, true)
                : new Keep(flag(KEEP_LEFT, false), flag(KEEP_RIGHT, false), flag(KEEP_PALETTES, true));
    }

    public static boolean flag(String key, boolean fallback) {
        String value = org.helioviewer.jhv.app.Settings.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    public static void setFlag(String key, boolean value) {
        org.helioviewer.jhv.app.Settings.setProperty(key, Boolean.toString(value));
    }

    /** One attached display: a stable id to persist, and a label to show in the menu. */
    public record Screen(String id, String label) {}

    public static java.util.List<Screen> screens() {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice main = env.getDefaultScreenDevice();
        GraphicsDevice[] all = env.getScreenDevices();
        java.util.List<Screen> out = new java.util.ArrayList<>(all.length);
        for (int i = 0; i < all.length; i++) {
            Rectangle b = all[i].getDefaultConfiguration().getBounds();
            out.add(new Screen(all[i].getIDstring(),
                    "Display " + (i + 1) + ": " + b.width + "\u00d7" + b.height + (all[i] == main ? " (main)" : "")));
        }
        return out;
    }

    /** The saved id for a screen role, or "" when it is on Automatic. */
    public static String preference(String key) {
        String saved = org.helioviewer.jhv.app.Settings.getProperty(key);
        return saved == null ? "" : saved;
    }

    public static void setPreference(String key, String id) {
        org.helioviewer.jhv.app.Settings.setProperty(key, id == null ? "" : id); // setProperty NPEs on null
    }

    // A saved screen that is no longer attached silently falls back to the automatic choice,
    // so unplugging the projector cannot leave presentation mode pointing at nothing.
    private static GraphicsDevice resolve(String key, GraphicsDevice fallback) {
        String want = preference(key);
        if (!want.isEmpty())
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (want.equals(screen.getIDstring()))
                    return screen;
        return fallback;
    }

    // The screen to present on: the first one that is NOT the main display, i.e. the projector.
    // Keyed on which display is main rather than on where the window currently sits -- picking
    // "any screen other than this one" only looked right while JHV happened to be on the laptop,
    // and put the output on the laptop and the controls on the projector whenever it wasn't.
    // With one screen there is nothing to choose and we present on it.
    private static GraphicsDevice presentationDevice(GraphicsDevice here) {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice main = env.getDefaultScreenDevice();
        for (GraphicsDevice screen : env.getScreenDevices())
            if (screen != main)
                return screen;
        return here;
    }

    private static GraphicsDevice deviceOf(JFrame frame) {
        GraphicsConfiguration config = frame.getGraphicsConfiguration();
        return config != null
                ? config.getDevice()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    // Lend the chrome to a window on the presenter's screen. These are ordinary lightweight
    // Swing panels, so moving them between windows is just a reparent -- the expensive
    // component, the canvas, stays exactly where it is.
    private static JFrame buildPresenterWindow(GraphicsDevice on) {
        JFrame window = new JFrame("HelioFITS Studio: Presenter", on.getDefaultConfiguration());
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // closing it would strand the chrome
        // Toolbar and transport stack at the top at their natural height; the sidebar takes
        // everything left over. BorderLayout.NORTH is what enforces "natural height" here -- a
        // BoxLayout hands each child its MAXIMUM height instead, which for a JPanel is
        // unbounded, so the toolbar and scrubber stretched into big empty bands and pushed the
        // layer list to the floor.
        JPanel content = new JPanel(new BorderLayout());
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.PAGE_AXIS));

        java.util.List<Component> fillers = new java.util.ArrayList<>();
        for (MainFrame.ChromeSlot slot : MainFrame.chromeForPresenterView()) {
            Component c = slot.panel();
            // Asked BEFORE it is forced open, because for the right sidebar being invisible is how
            // it says it holds nothing: forcing that one on put an empty rail in the window and
            // gave it half the height of the presenter view.
            boolean carriesSomething = c.isVisible();
            Container parent = c.getParent();
            if (parent != null)
                parent.remove(c);
            if (!slot.fills()) {
                c.setVisible(true);
                if (c instanceof JComponent jc)
                    jc.setAlignmentX(Component.LEFT_ALIGNMENT); // else BoxLayout centres them
                top.add(c);
            } else if (carriesSomething) {
                c.setVisible(true);
                fillers.add(c);
            }
        }
        content.add(top, BorderLayout.NORTH);
        placeFillers(content, fillers);
        MainFrame.setSidebarCollapsed(false); // the layer list is the point of this window
        openEverything(content);

        window.setContentPane(content);
        window.pack();
        Rectangle bounds = on.getDefaultConfiguration().getBounds();
        // A third of the screen: enough for the layer list to be readable, and it leaves the
        // rest of the presenter's screen free for notes, the console or the speaker view. The
        // toolbar no longer drives the width -- it overflows into a menu instead of forcing the
        // window as wide as every button laid end to end.
        int width = Math.max(bounds.width / 3, 360);
        window.setBounds(bounds.x + 40, bounds.y + 40,
                Math.min(width, bounds.width - 80), bounds.height - 120);
        installEscape(window.getRootPane());
        window.setVisible(true);
        return window;
    }

    /**
     * Give each sidebar its own place in the presenter window.
     *
     * <p>They both used to go to BorderLayout.CENTER, which takes one component: the second one
     * added wins the constraint and the first is laid out at zero by zero while remaining a child
     * of the container. With the canvas gone there is nothing between the two sidebars to hide
     * that, so what the presenter got was one bar with everything crammed into it and the other
     * either missing or painting over it.
     *
     * <p>Stacked rather than set side by side, because this window is a third of a screen wide and
     * full height: two sidebars at their natural widths do not fit across it, which is the
     * truncation half of the same complaint. Down the height they both fit, and the divider lets
     * the presenter give the room to whichever of the two the talk needs.
     */
    static void placeFillers(JPanel content, java.util.List<Component> fillers) {
        if (fillers.isEmpty())
            return;
        if (fillers.size() == 1) {
            content.add(fillers.getFirst(), BorderLayout.CENTER);
            return;
        }
        Component stacked = fillers.getFirst();
        for (int i = 1; i < fillers.size(); i++) {
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stacked, fillers.get(i));
            split.setResizeWeight(0.62); // the layer list is the one that grows with the window
            split.setContinuousLayout(true);
            split.setOneTouchExpandable(true);
            split.setBorder(null);
            stacked = split;
        }
        content.add(stacked, BorderLayout.CENTER);
    }

    /**
     * Open every collapsed section and grow the layer list to show all of its rows.
     *
     * <p>Presentation mode is entered at the moment attention moves to the room, which is the
     * worst possible time to be reopening panels or dragging a list taller to find a control.
     * Whatever was folded away while composing the view gets unfolded here instead.
     *
     * <p>Recursive rather than SideContentPane.expandAll(), because that only reaches the
     * top-level sections; the ones that actually get collapsed (Layer Options and the geometry
     * and manage panes under it) are nested inside Image Layers.
     */
    private static void openEverything(Container chrome) {
        // Takes the container the panels are actually in: at this point they have been moved out
        // of the main frame and the presenter window does not exist yet, so neither one would
        // reach them.
        expandRecursively(chrome);
        MainFrame.getLayersPanel().forceShowAllRows(); // override a hand-set height for the talk
        MainFrame.getOverlaysPanel().forceShowAllRows();
        MainFrame.getCameraPanel().forceShowAllRows();
    }

    private static void expandRecursively(Component c) {
        if (c instanceof org.helioviewer.jhv.gui.component.CollapsiblePane pane)
            pane.setExpanded(true);
        if (c instanceof Container container)
            for (Component child : container.getComponents())
                expandRecursively(child);
    }

    // Put every borrowed panel back in the slot it names.
    private static void returnChrome() {
        for (MainFrame.ChromeSlot slot : MainFrame.chromeForPresenterView()) {
            Container parent = slot.panel().getParent();
            if (parent != null)
                parent.remove(slot.panel());
            slot.restore();
        }
    }

    private static void installEscape(JRootPane root) {
        // Escape is the reflex when a projector goes wrong, so it always leaves the mode.
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "jhv.exitPresentation");
        root.getActionMap().put("jhv.exitPresentation", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                exit();
            }
        });
    }

    private PresentationMode() {}
}
