package org.helioviewer.jhv.opengl;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyBoundsAdapter;
import java.awt.event.HierarchyEvent;
import java.awt.geom.AffineTransform;

import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.opengl.angle.AngleRenderer;
import org.helioviewer.jhv.opengl.angle.MacAngleBridge;
import org.helioviewer.jhv.opengl.angle.WinAngleBridge;
import org.helioviewer.jhv.opengl.angle.X11AngleBridge;

@SuppressWarnings("serial")
public final class AngleCanvas extends Canvas {
    private long macHostHandle;
    private AngleRenderer angleRenderer;
    private boolean displayPending;
    private Position pendingViewpoint;
    private boolean hostUpdatePending;
    private boolean hostRenderPending;
    private int fps;
    private int fpsCount;
    private long fpsTime = System.currentTimeMillis();
    private int lastGlWidth = -1;
    private int lastGlHeight = -1;
    private boolean hostResyncPending; // suppress renders that would draw at a size the drawable lacks
    private boolean hostVisible = true;
    private boolean nativeHostVisible = true;
    private double nativeHostScale = Double.NaN;
    private boolean attachmentFailed;

    public AngleCanvas() {
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setBackground(Color.BLACK);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                scheduleHostUpdate(true);
            }

            @Override
            public void componentResized(ComponentEvent e) {
                // Force a redraw after AWT resize so the GL pixel size is recomputed
                // immediately and the aspect ratio does not lag behind the canvas size.
                invalidateGlSize();
                scheduleHostUpdate(true);
            }
        });
        addHierarchyBoundsListener(new HierarchyBoundsAdapter() {
            @Override
            public void ancestorMoved(HierarchyEvent e) {
                scheduleHostUpdate(false);
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        scheduleHostUpdate(true);
    }

    @Override
    public void removeNotify() {
        try {
            detach();
        } finally {
            super.removeNotify();
        }
    }

    @Override
    public void paint(Graphics g) {
        // Native ANGLE host owns presentation.
    }

    @Override
    public void update(Graphics g) {
        paint(g);
    }

    // Bring the canvas to its current size and re-render in the same event. Needed after a
    // programmatic layout change (e.g. collapsing the sidebar): the ordinary resize path is
    // deferred, so AWT paints the old framebuffer stretched to the new width before the correct
    // frame lands. Doing it inline here removes both the stretch and the flash. The JAWT layer's
    // frame is AWT's to set, so all that is forced here is the pixel scale and the GL reshape.
    public void refreshHost() {
        if (getWidth() <= 0 || getHeight() <= 0)
            return;
        invalidateGlSize(); // force GLRenderer.reshape in renderNow
        refreshPixelScale();
        attachIfNeeded();
        if (angleRenderer == null)
            return;

        renderNow(GLRenderer.getDisplayedViewpoint());
    }

    // Called as soon as a programmatic layout change starts, before Swing repaints: from here until
    // resyncHostDeferred runs, we refuse to draw at a size the drawable has not reached.
    public void beginHostResync() {
        hostResyncPending = true;
    }

    public void resyncHostDeferred() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            hostResyncPending = false;
            return;
        }
        attachIfNeeded();
        if (angleRenderer == null) {
            hostResyncPending = false;
            return;
        }

        refreshPixelScale();
        invalidateGlSize();

        // Give AWT's own resize of the JAWT layer a moment to land, then draw. Until it does,
        // renders stay suppressed, so no frame is drawn at a size the drawable has not reached.
        javax.swing.Timer timer = new javax.swing.Timer(16, e -> { // one frame: long enough for the layer resize, short enough not to be seen
            hostResyncPending = false;
            renderNow(GLRenderer.getDisplayedViewpoint());
        });
        timer.setRepeats(false);
        timer.start();
    }

    public void requestRender() {
        requestRender(GLRenderer.getDisplayedViewpoint());
    }

    public void requestRender(Position viewpoint) {
        pendingViewpoint = viewpoint;
        queueRender();
    }

    private void queueRender() {
        if (displayPending)
            return;

        if (angleRenderer == null) {
            scheduleHostUpdate(true);
            return;
        }

        displayPending = true;
        EventQueue.invokeLater(() -> {
            displayPending = false;
            Position renderViewpoint = pendingViewpoint;
            pendingViewpoint = null;
            renderNow(renderViewpoint);
        });
    }

    public int getFramerate() {
        long now = System.currentTimeMillis();
        long delta = now - fpsTime;

        if (delta > 1000) {
            fps = (int) ((1000L * fpsCount + delta / 2) / delta);
            fpsCount = 0;
            fpsTime = now;
        }
        return fps;
    }

    public void setHostVisible(boolean visible) {
        if (!Platform.isMacOS() || hostVisible == visible)
            return;

        hostVisible = visible;
        if (!visible && macHostHandle != 0L && nativeHostVisible) {
            MacAngleBridge.setVisible(macHostHandle, false);
            nativeHostVisible = false;
        } else if (visible) {
            scheduleHostUpdate(true);
        }
    }

    // Render one frame and keep the shared viewport state in sync with the canvas size.
    private void renderNow(Position viewpoint) {
        if (!hostVisible)
            return;

        refreshPixelScale();
        attachIfNeeded();
        if (angleRenderer == null)
            return;

        syncHostScale();
        int glWidth = (int) (getWidth() * Display.pixelScale[0] + .5);
        int glHeight = (int) (getHeight() * Display.pixelScale[1] + .5);
        if (glWidth != lastGlWidth || glHeight != lastGlHeight) {
            // A layout change is in flight and the native drawable has not been brought to the new
            // size yet. Drawing now would reshape GL to the new size against the old drawable, and
            // that one frame is exactly the stretch the user sees. Skip it; resyncHostDeferred will
            // resize the drawable and render immediately afterwards.
            if (hostResyncPending)
                return;
            GLRenderer.reshape(glWidth, glHeight);
            lastGlWidth = glWidth;
            lastGlHeight = glHeight;
        }
        angleRenderer.render(viewpoint);
        fpsCount++;
    }

    // Create the platform-native host/window handle and ANGLE renderer on first use.
    private void attachIfNeeded() {
        if (angleRenderer != null || attachmentFailed || !isDisplayable() || getWidth() <= 0 || getHeight() <= 0)
            return;

        long newNativeWindowHandle = 0L;
        try {
            if (Platform.isMacOS()) {
                JRootPane rootPane = SwingUtilities.getRootPane(this);
                Point location = rootPane == null ? getLocation() :
                        SwingUtilities.convertPoint(this, 0, 0, rootPane.getContentPane());
                MacAngleBridge.Host host = MacAngleBridge.create(
                        this, location.x, location.y, getWidth(), getHeight());
                if (host == null)
                    return;
                macHostHandle = host.handle();
                newNativeWindowHandle = host.layer();
                if (!hostVisible)
                    MacAngleBridge.setVisible(macHostHandle, false);
            } else if (Platform.isWindows()) {
                newNativeWindowHandle = WinAngleBridge.hwnd(this);
            } else if (Platform.isLinux()) {
                newNativeWindowHandle = X11AngleBridge.drawable(this);
            }
            if (newNativeWindowHandle == 0L)
                return;

            // The factory, not the constructor: it is what drops from EDR to deep colour to 8 bits
            // when a driver refuses the deeper canvas.
            angleRenderer = AngleRenderer.window(newNativeWindowHandle);
            nativeHostVisible = hostVisible;
            if (Platform.isMacOS())
                nativeHostScale = Display.pixelScale[0];
            invalidateGlSize();
        } catch (AngleRenderer.GraphicsUnavailableException e) {
            // The system's graphics would not start. That used to escape to the uncaught-exception
            // handler, whose dialog is a stack trace, for something no user can fix by reading one.
            // Say it once in plain words (attachmentFailed stops the retries) and keep the details
            // in the log. Later, not here: this runs inside layout and painting, where a modal
            // dialog would re-enter them.
            attachmentFailed = true;
            org.helioviewer.jhv.app.Log.error("Graphics could not start; images cannot be shown", e);
            EventQueue.invokeLater(AngleCanvas::sayGraphicsUnsupported);
        } catch (RuntimeException | Error e) {
            // Anything else is a fault in HelioFITS Studio (a missing library, a bug), not the machine's
            // graphics, and keeps the crash report that says so.
            // Keep the macOS host until removeNotify so its JAWT layer is cleared only during Canvas teardown.
            attachmentFailed = true;
            throw e;
        }
    }

    private static void sayGraphicsUnsupported() {
        String api = Platform.isMacOS() ? "Metal" : Platform.isWindows() ? "Direct3D 11" : "OpenGL";
        org.helioviewer.jhv.app.Message.err("Graphics not supported",
                "HelioFITS Studio could not start its graphics on this computer, so it cannot show images.\n\n"
                        + "It draws through " + api + ", and this system's graphics would not start it. That usually "
                        + "means the graphics hardware or its driver is too old, or that this is a virtual machine "
                        + "without full graphics support.\n\n"
                        + "If it happens on a computer you expect to work, please send the log from\n"
                        + org.helioviewer.jhv.io.Directories.LOGS.getPath() + "\n"
                        + "to gilly@nwra.com or https://github.com/GillySpace27/HelioFITS-Studio/issues");
    }

    // Keep native scale and visibility synchronized, then trigger a redraw if needed.
    private void updateHost(boolean renderNeeded) {
        if (getWidth() <= 0 || getHeight() <= 0)
            return;

        boolean pixelScaleChanged = refreshPixelScale();

        if (angleRenderer == null) {
            attachIfNeeded();
            if (angleRenderer == null)
                return;
        }

        if (Platform.isMacOS()) {
            syncHostScale();
            if (hostVisible != nativeHostVisible) {
                MacAngleBridge.setVisible(macHostHandle, hostVisible);
                nativeHostVisible = hostVisible;
            }
        }
        if (hostVisible && (renderNeeded || pixelScaleChanged || lastGlWidth < 0 || lastGlHeight < 0)) {
            if (pendingViewpoint == null)
                pendingViewpoint = GLRenderer.getDisplayedViewpoint();
            queueRender();
        }
    }

    // Coalesce host updates onto the EDT so move/resize bursts become one native update.
    private void scheduleHostUpdate(boolean renderNeeded) {
        hostRenderPending |= renderNeeded;
        if (hostUpdatePending || !isDisplayable())
            return;

        hostUpdatePending = true;
        EventQueue.invokeLater(() -> {
            hostUpdatePending = false;
            boolean render = hostRenderPending;
            hostRenderPending = false;
            updateHost(render);
        });
    }

    // Tear down renderer and native host state, even if part of the shutdown path fails.
    private void detach() {
        try {
            if (angleRenderer != null)
                angleRenderer.destroy();
        } finally {
            angleRenderer = null;
            try {
                if (Platform.isMacOS() && macHostHandle != 0L)
                    MacAngleBridge.destroy(macHostHandle);
            } finally {
                macHostHandle = 0L;
                nativeHostVisible = true;
                nativeHostScale = Double.NaN;
                displayPending = hostUpdatePending = hostRenderPending = false;
                invalidateGlSize();
            }
        }
    }

    private void invalidateGlSize() {
        lastGlWidth = -1;
        lastGlHeight = -1;
    }

    private void syncHostScale() {
        if (!Platform.isMacOS() || nativeHostScale == Display.pixelScale[0])
            return;

        MacAngleBridge.setScale(macHostHandle, Display.pixelScale[0]);
        nativeHostScale = Display.pixelScale[0];
    }

    // Keep the shared pixel scale in sync and invalidate the GL size if a monitor switch
    // changed the backing pixel ratio.
    private boolean refreshPixelScale() {
        GraphicsConfiguration graphicsConfiguration = getGraphicsConfiguration();
        double scaleX = 1;
        double scaleY = 1;
        if (graphicsConfiguration != null) {
            AffineTransform transform = graphicsConfiguration.getDefaultTransform();
            scaleX = transform.getScaleX();
            scaleY = transform.getScaleY();
        }
        boolean changed = Display.pixelScale[0] != scaleX || Display.pixelScale[1] != scaleY;
        if (!changed)
            return false;

        Display.pixelScale[0] = scaleX;
        Display.pixelScale[1] = scaleY;
        invalidateGlSize();
        return true;
    }

}
