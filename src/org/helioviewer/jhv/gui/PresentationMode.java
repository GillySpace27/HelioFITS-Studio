package org.helioviewer.jhv.gui;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;

import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;

/**
 * Output-only fullscreen for showing JHV to a room.
 *
 * <p>Single monitor: the chrome is hidden and the frame fills the screen, so nothing but the
 * render output is left. Two monitors: the main window stays on the presenter's screen exactly as
 * it is, every control working, and an {@link AudienceWindow} fills the projector with a mirror of
 * its picture (Gilly, 2026-10-06; it replaced a presenter window of borrowed panels with a small
 * preview).
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

    @Nullable private static AudienceWindow audience;
    private static boolean savedEastVisible;
    @Nullable private static GraphicsDevice fullScreenOn; // the device we put into exclusive full screen
    @Nullable private static java.awt.event.ComponentAdapter settleListener;
    @Nullable private static Rectangle savedBounds;
    private static int savedExtendedState;
    private static boolean savedSidebarCollapsed;
    private static boolean savedRightCollapsed;

    public static boolean isActive() {
        return active;
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

        GraphicsDevice target = resolve(OUTPUT_SCREEN, presentationDevice(deviceOf(frame)));
        GraphicsDevice presenterScreen = resolve(CONTROLS_SCREEN,
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
        // Both on one screen would mean the controls sit on top of the output, so treat that as
        // "no second screen" and just hide the chrome, which is the single-monitor behaviour.
        boolean dual = target != presenterScreen;
        // Frames from here on are drawn at the Recording pixel size and scaled to the screen.
        // Before the audience window, whose first frame is asked for as soon as it is up.
        org.helioviewer.jhv.opengl.PresentationOutput.OUTPUT.setActive(true);

        if (dual) {
            // The main window is the presenter's view and is left exactly as it is; the
            // projector gets the mirror. Drawing at the Recording size (below) still applies, so
            // the presenter's canvas shows the frame the room gets.
            audience = new AudienceWindow(target);
            frame.toFront();
        } else {
            savedBounds = frame.getBounds();
            savedExtendedState = frame.getExtendedState();
            savedSidebarCollapsed = MainFrame.isSidebarCollapsed();
            savedRightCollapsed = org.helioviewer.jhv.gui.component.RightSidebar.getInstance().isCollapsed();
            savedEastVisible = MainFrame.isEastVisible();
            Keep keep = keepFor(false);
            MainFrame.setChromeVisible(false, keep.left(), keep.right(), savedEastVisible);
            if (!keep.palettes())
                org.helioviewer.jhv.gui.component.Palette.setFloatingVisible(false);

            // NORMAL first: a maximized frame ignores setBounds on some platforms.
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(target.getDefaultConfiguration().getBounds());
            // Sizing the window to the screen still leaves the macOS menu bar drawn over the top
            // of it. Only real full-screen mode takes the screen away from the menu bar and the
            // Dock, so ask for it and keep the plain bounds as the fallback.
            if (target.isFullScreenSupported()) {
                try {
                    target.setFullScreenWindow(frame);
                    fullScreenOn = target;
                } catch (RuntimeException e) {
                    org.helioviewer.jhv.app.Log.warn("Full screen refused, showing at screen size instead", e);
                    fullScreenOn = null;
                }
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
        if (audience != null) {
            audience.close();
            audience = null;
        } else {
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
            MainFrame.setChromeVisible(true, false, false, savedEastVisible);
            org.helioviewer.jhv.gui.component.Palette.setFloatingVisible(true);
            MainFrame.setSidebarCollapsed(savedSidebarCollapsed);
            MainFrame.setSidebarHandleVisible(true);
            org.helioviewer.jhv.gui.component.RightSidebar.getInstance().setCollapsed(savedRightCollapsed);
            org.helioviewer.jhv.gui.component.RightSidebar.getInstance().setHandleVisible(true);

            if (savedBounds != null)
                frame.setBounds(savedBounds);
            frame.setExtendedState(savedExtendedState);
            savedBounds = null;
        }

        active = false;
        org.helioviewer.jhv.opengl.PresentationOutput.OUTPUT.setActive(false);
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

    /** What presentation mode leaves up. All three are true on two screens; see {@link #keepFor}. */
    public record Keep(boolean left, boolean right, boolean palettes) {}

    /**
     * What stays on screen, given how many screens are in play.
     *
     * <p>On one screen the picture is the whole display and anything kept is drawn over it, which
     * is a real trade the presenter is making knowingly: a sidebar in the corner of the slide, in
     * exchange for being able to drive the thing without leaving the mode. Those are the settings.
     *
     * <p>With a second display nothing is hidden at all: the main window stays on the presenter's
     * screen as it is and the projector shows a mirror of the picture, so the settings do not
     * apply and everything is kept.
     *
     * <p>Pure, so the check can pin both rules without a projector.
     */
    public static Keep keepFor(boolean dual) {
        return dual ? new Keep(true, true, true)
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
