package org.helioviewer.jhv.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Taskbar;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.TransferHandler;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.ImageLayersPane;
import org.helioviewer.jhv.gui.component.MainContentPanel;
import org.helioviewer.jhv.gui.component.MenuBar;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.gui.component.SideContentPane;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.gui.component.ToolBar;
import org.helioviewer.jhv.gui.status.FramerateStatusPanel;
import org.helioviewer.jhv.gui.status.PositionStatusPanel;
import org.helioviewer.jhv.gui.status.ViewpointStatusPanel;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.selector.LayerOptionSections;
import org.helioviewer.jhv.layers.selector.LayersPanel;
import org.helioviewer.jhv.layers.selector.LayersSectionPanel;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.AngleCanvas;
import org.helioviewer.jhv.opengl.angle.AngleRenderer;

import org.helioviewer.jhv.opengl.angle.MacAngleBridge;
import org.helioviewer.jhv.thread.Task;

import com.formdev.flatlaf.FlatClientProperties;

public final class MainFrame {

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

    @SuppressWarnings("serial")
    private static final class RenderStartupHost extends JPanel {
        private final JPanel placeholder = new JPanel();
        private AngleCanvas canvas;

        RenderStartupHost() {
            super(new BorderLayout());
            placeholder.setBackground(Color.BLACK);
            add(placeholder, BorderLayout.CENTER);
        }

        void attachCanvas(AngleCanvas _canvas) {
            if (canvas != null)
                return;
            canvas = _canvas;
            remove(placeholder);
            add(canvas, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }

    private static JFrame mainFrame;
    private static JScrollPane leftScrollPane;
    private static final int SCROLLBAR_WIDTH = 10; // FlatLaf's own ScrollBar.width default
    private static FixedWidthPanel leftPaneHost;

    private static JPanel centerPanel;
    private static JButton sidebarCollapseHandle;
    private static boolean sidebarCollapsed;

    // A user-dragged width overrides the measure-and-grow behaviour below entirely: once someone
    // has resized the sidebar by hand, its width is theirs to keep, the same way the collapsed
    // state is. -1 means "no explicit width yet", not "zero pixels wide".
    private static final int MIN_SIDEBAR_WIDTH = 160;
    private static final int MAX_SIDEBAR_WIDTH = 900;
    private static int explicitSidebarWidth = readSidebarWidth();

    private static int readSidebarWidth() {
        try {
            return Integer.parseInt(org.helioviewer.jhv.app.Settings.getProperty("ui.sidebarWidth"));
        } catch (RuntimeException ignore) {
            return -1;
        }
    }

    // Presentation mode needs to take these away from the frame and give them back. They were
    // locals in prepare(), so there was no handle on the chrome at all.
    private static JPanel toolBarPanel;
    private static StatusPanel statusPanel;
    private static JPanel westWrap;
    private static JComponent eastWrap;
    private static Component northTransport;

    private static SideContentPane leftPane;

    private static AngleCanvas renderCanvas;
    private static RenderStartupHost renderHost;
    private static AwtInputAdapter awtInputAdapter;
    private static MainContentPanel mainContentPanel;

    private static LayersPanel layersPanel;
    private static LayersPanel overlaysPanel;
    private static LayersPanel cameraPanel;
    private static LayersSectionPanel layersSectionPanel;
    private static ImageLayersPane imageLayersPane;

    private static MenuBar menuBar;

    public static JFrame prepare() {
        mainFrame = createFrame();

        Message.setHandler(new MessageHandler());

        menuBar = new MenuBar();
        mainFrame.setJMenuBar(menuBar);
        // J, K and L scrub from anywhere in the window; the scrubber's own keys need its focus.
        Shuttle.install(mainFrame.getRootPane());

        renderCanvas = null;
        renderHost = new RenderStartupHost();

        leftPane = new SideContentPane();
        JPanel layerOptionsWrapper = new JPanel(new BorderLayout());
        JPanel geometryWrapper = new JPanel(new BorderLayout());
        JPanel manageWrapper = new JPanel(new BorderLayout());
        LayerOptionSections sections = new LayerOptionSections(layerOptionsWrapper, geometryWrapper, manageWrapper);
        layersPanel = new LayersPanel(sections, Layer.Kind.IMAGE);     // table needs the controller
        layersSectionPanel = new LayersSectionPanel(); // ctor calls MainFrame.getLayersPanel()

        // Everything drawn over the observation rather than being one: the grid, the timestamps,
        // the field-of-view boxes, the miniview. And, separately, the two that decide where the
        // observation is seen from, which are a cause rather than an effect and were sitting among
        // their own consequences. Each list gets its own options section, so selecting the grid
        // does not retitle the section belonging to the pictures.
        JPanel overlayOptionsWrapper = new JPanel(new BorderLayout());
        overlaysPanel = new LayersPanel(new LayerOptionSections(
                overlayOptionsWrapper, new JPanel(new BorderLayout()), new JPanel(new BorderLayout())), Layer.Kind.OVERLAY);
        JPanel overlaysPane = sidePane(overlaysPanel, "Overlay options", overlayOptionsWrapper);

        JPanel cameraOptionsWrapper = new JPanel(new BorderLayout());
        cameraPanel = new LayersPanel(new LayerOptionSections(
                cameraOptionsWrapper, new JPanel(new BorderLayout()), new JPanel(new BorderLayout())), Layer.Kind.VIEWPOINT);
        JPanel cameraPane = sidePane(cameraPanel, "Camera options", cameraOptionsWrapper);
        MoviePanel moviePanel = MoviePanel.getInstance();
        imageLayersPane = new ImageLayersPane(moviePanel.getTimeSelectorPanel(), layersSectionPanel, layerOptionsWrapper, geometryWrapper, manageWrapper);
        // The scrubber + playback buttons are always docked at the top (see below); the sidebar keeps
        // the recording/speed settings as their own "Playback options" pane, and the master time range
        // now lives atop Image Layers where it belongs.
        // Added expanded so stabilizeLeftPaneWidth() can measure the real content width; they are
        // collapsed at the end of that method so the sidebar opens wide but with panels closed.
        // Order: Playback and Recording sits at the top, then Image Layers, then the plugin panels
        // (Timeline Layers, SWEK) below.
        // A glyph per section, from the toolbar's own set so the two chromes name a thing the same
        // way. Buttons.colourSettings is the icon font's picture glyph, which is what an image
        // layer is; the set has nothing that means a stack of layers.
        leftPane.add("Playback and Recording", moviePanel.getPlaybackOptions(), true, Buttons.play);
        leftPane.add("Image Layers", imageLayersPane, true, Buttons.colourSettings);
        leftPane.add("Overlays", overlaysPane, true, Buttons.annotate);
        leftPane.add("Camera", cameraPane, true, Buttons.camera);

        // As-needed, not always: a permanent empty scrollbar down the side of the sidebar is the
        // most dated thing on the window, and the width it used to guard is reserved by the
        // frozen sidebar width below (stabilizeLeftPaneWidth adds the scrollbar's width whether
        // or not it is showing), so nothing overlaps when it appears.
        // Wrapped rather than held directly: the panes inside claim minimum widths that are
        // really just their longest labels, and honouring those is what stopped the sidebar
        // compressing when it was dragged narrower. See SqueezeView.
        leftScrollPane = new JScrollPane(new org.helioviewer.jhv.gui.component.SqueezeView(leftPane),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        leftScrollPane.setFocusable(false);
        leftScrollPane.setBorder(null);
        // A thin bar, and a fixed one: this width is what the frozen sidebar width reserves, so
        // it has to be the same number in both places, which an explicit preferred size gives.
        leftScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(SCROLLBAR_WIDTH, 0));
        leftScrollPane.getVerticalScrollBar().setUnitIncrement(layersPanel.getGridRowHeight());
        leftPaneHost = new FixedWidthPanel();
        leftPaneHost.add(buildSessionBar(), BorderLayout.NORTH); // document name + save/load, above Playback and Recording
        leftPaneHost.add(leftScrollPane, BorderLayout.CENTER);

        awtInputAdapter = new AwtInputAdapter();

        mainContentPanel = new MainContentPanel(renderHost);
        centerPanel = new JPanel(new BorderLayout());

        // The scrubber + playback controls are always docked at the top, so playback stays
        // reachable whether or not the sidebar is open. Collapsing the sidebar (thin handle on its
        // right edge) just folds away the layers/settings and lets the canvas reflow to full width.
        // The same handle drags to resize: see the mouse listener below for how a drag and a click
        // are told apart on one component.
        sidebarCollapseHandle = Buttons.flat(Buttons.collapseLeft);
        sidebarCollapseHandle.setToolTipText("Drag to resize, click to collapse the sidebar");
        sidebarCollapseHandle.setPreferredSize(new Dimension(16, 0));
        sidebarCollapseHandle.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        sidebarCollapseHandle.addActionListener(e -> {
            // A drag that ended over this button still delivers a click. Disarming the model
            // during the drag was supposed to swallow it and does not: the handle MOVES with the
            // edge being dragged, so it slides under the stationary pointer and the resulting
            // mouseEntered re-arms the model before the release. Answer it where the answer
            // cannot be undone, at the action itself, using a flag set during the drag and
            // cleared on the next press.
            if (sidebarDragged) {
                sidebarDragged = false;
                return;
            }
            setSidebarCollapsed(!sidebarCollapsed);
        });
        attachSidebarResize(sidebarCollapseHandle);

        westWrap = new JPanel(new BorderLayout());
        westWrap.add(leftPaneHost, BorderLayout.CENTER);
        westWrap.add(sidebarCollapseHandle, BorderLayout.LINE_END);

        northTransport = MoviePanel.getInstance().getNorthTransport();
        centerPanel.add(northTransport, BorderLayout.PAGE_START);
        centerPanel.add(westWrap, BorderLayout.WEST);
        centerPanel.add(mainContentPanel, BorderLayout.CENTER);
        // The right sidebar, which shows only once something has been docked into it.
        eastWrap = org.helioviewer.jhv.gui.component.RightSidebar.getInstance().component();
        centerPanel.add(eastWrap, BorderLayout.EAST);

        ViewpointStatusPanel viewpointStatus = new ViewpointStatusPanel();
        FramerateStatusPanel framerateStatus = new FramerateStatusPanel();
        PositionStatusPanel positionStatus = new PositionStatusPanel();
        InputController.addListener(positionStatus);

        statusPanel = new StatusPanel(5, 5);
        statusPanel.addPlugin(framerateStatus, StatusPanel.Alignment.LEFT);
        statusPanel.addPlugin(positionStatus, StatusPanel.Alignment.RIGHT);
        statusPanel.addPlugin(viewpointStatus, StatusPanel.Alignment.RIGHT);

        ToolBar toolBar = new ToolBar();

        toolBarPanel = new JPanel(new BorderLayout());
        toolBarPanel.add(toolBar, BorderLayout.CENTER);

        mainFrame.getContentPane().add(toolBarPanel, BorderLayout.NORTH);
        mainFrame.getContentPane().add(centerPanel, BorderLayout.CENTER);
        mainFrame.getContentPane().add(statusPanel, BorderLayout.SOUTH);

        Player.setMaster(Layers.getActiveImageLayer()); //! for nullImageLayer

        FileDropHandler.attach(mainFrame.getContentPane());

        // Prewarm ANGLE off the EDT, then return here via attachAndRender() to attach the real render canvas.
        startAngleWarmup();
        return mainFrame;
    }

    private static void startAngleWarmup() {
        Task.submit("angle-warmup", () -> {
            if (Platform.isMacOS())
                MacAngleBridge.prewarm();
            AngleRenderer.prewarm();
            return null;
        }, ignored -> EventQueue.invokeLater(MainFrame::attachAndRender), (context, error) -> {
            Log.warn("ANGLE warmup failed", error);
            EventQueue.invokeLater(MainFrame::attachAndRender);
        });
    }

    private static void attachAndRender() {
        if (renderCanvas != null) // impossible
            return;

        renderCanvas = new AngleCanvas();
        renderCanvas.setMinimumSize(new Dimension(1, 1)); // allow resize
        renderCanvas.addMouseListener(awtInputAdapter);
        renderCanvas.addMouseMotionListener(awtInputAdapter);
        renderCanvas.addMouseWheelListener(awtInputAdapter);
        renderCanvas.addKeyListener(awtInputAdapter);
        // The canvas is a heavyweight AWT child, so the frame's drop target does not cover it;
        // it gets its own.
        FileDropHandler.attach(renderCanvas);
        renderHost.attachCanvas(renderCanvas);
        // Force ANGLE surface/context creation immediately instead of waiting for the next UI event.
        renderCanvas.requestRender();
        DisplayController.setRenderRequestHandler(renderCanvas::requestRender);
    }

    private static JFrame createFrame() {
        JFrame frame = new JFrame(AppInfo.programName);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && TransferAccess.canImport(support.getTransferable());
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support))
                    return false;
                return TransferAccess.importTransferable(support.getTransferable());
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Red close button = dismiss THIS window (drop it from the reopen set), unlike
                // Cmd-Q which keeps every window for next launch.
                if (org.helioviewer.jhv.app.ExitHooks.exitProgram(true))
                    System.exit(0);
            }
        });

        if (Platform.isMacOS()) {
            frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
            frame.getRootPane().putClientProperty(com.formdev.flatlaf.FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING,
                    com.formdev.flatlaf.FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_MEDIUM);
        }

        Dimension maxSize = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().getSize();
        Dimension minSize = new Dimension(800, 600);
        minSize.width = Math.min(minSize.width, maxSize.width);
        minSize.height = Math.min(minSize.height, maxSize.height);

        frame.setMinimumSize(minSize);

        int preferredWidth = readSizeEnv("JHV_PREFERRED_WIDTH", maxSize.width - 100);
        int preferredHeight = readSizeEnv("JHV_PREFERRED_HEIGHT", maxSize.height - 100);
        preferredWidth = Math.min(preferredWidth, maxSize.width);
        preferredHeight = Math.min(preferredHeight, maxSize.height);
        frame.setPreferredSize(new Dimension(preferredWidth, preferredHeight));
        restoreBounds(frame, maxSize);
        rememberBounds(frame);

        frame.setIconImage(IconBank.getIcon(IconBank.JHVIcon.HVLOGO_SMALL).getImage());
        setAppIcon();

        return frame;
    }

    private static final String KEY_BOUNDS = "ui.windowBounds";

    // The window opens where and as large as it was closed. Only when the remembered rectangle
    // still fits the screen, so a window last seen on a monitor that is gone falls back to the
    // default; startGUI's setLocationRelativeTo(null) is skipped when this succeeds.
    private static boolean boundsRestored;

    private static void restoreBounds(JFrame frame, Dimension maxSize) {
        String stored = org.helioviewer.jhv.app.Settings.getProperty(KEY_BOUNDS);
        if (stored == null)
            return;
        try {
            String[] p = stored.split(",");
            java.awt.Rectangle r = new java.awt.Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            java.awt.Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (r.width < 200 || r.height < 200 || !screen.intersects(r))
                return;
            r.width = Math.min(r.width, maxSize.width);
            r.height = Math.min(r.height, maxSize.height);
            frame.setPreferredSize(r.getSize());
            frame.setLocation(r.getLocation());
            boundsRestored = true;
        } catch (RuntimeException e) {
            Log.warn("Ignoring stored window bounds " + stored);
        }
    }

    public static boolean boundsRestored() {
        return boundsRestored;
    }

    private static void rememberBounds(JFrame frame) {
        javax.swing.Timer settle = new javax.swing.Timer(500, e -> { // a drag fires dozens of events a second; Settings writes a file
            if (!frame.isShowing() || (frame.getExtendedState() & JFrame.ICONIFIED) != 0)
                return;
            java.awt.Rectangle r = frame.getBounds();
            org.helioviewer.jhv.app.Settings.setProperty(KEY_BOUNDS, r.x + "," + r.y + "," + r.width + "," + r.height);
        });
        settle.setRepeats(false);
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                settle.restart();
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                settle.restart();
            }
        });
    }

    /** Chrome that is only restorable once the frame is on screen: the folded sidebar and the open palettes. */
    public static void restoreChrome() {
        if ("true".equals(org.helioviewer.jhv.app.Settings.getProperty("ui.sidebarCollapsed")))
            setSidebarCollapsed(true);
        EventQueue.invokeLater(org.helioviewer.jhv.gui.component.Palette::restoreOpen); // after this pass of layout, so docking sees the canvas
    }

    private static int readSizeEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank())
            return fallback;

        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0)
                return value;
        } catch (NumberFormatException ignore) {}
        return fallback;
    }

    public static JFrame get() {
        return mainFrame;
    }

    private static JLabel sessionNameLabel;
    private static JTextField sessionNameField;
    private static java.awt.CardLayout sessionNameCards;
    private static JPanel sessionNamePanel;

    // The document-name bar: a name that double-clicks into an editable field (inline rename +
    // save), flanked by native Save and Load icons.
    private static JComponent buildSessionBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        // Collapse-all / expand-all, left of the name: open or close every sidebar panel at once.
        JButton collapseAllButton = Buttons.flat(Buttons.collapseAll);
        collapseAllButton.setToolTipText("Collapse all panels");
        collapseAllButton.addActionListener(e -> { if (leftPane != null) leftPane.collapseAll(); });
        JButton expandAllButton = Buttons.flat(Buttons.expandAll);
        expandAllButton.setToolTipText("Expand all panels");
        expandAllButton.addActionListener(e -> { if (leftPane != null) leftPane.expandAll(); });
        JPanel leftIcons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 0));
        leftIcons.setOpaque(false);
        leftIcons.add(collapseAllButton);
        leftIcons.add(expandAllButton);
        bar.add(leftIcons, BorderLayout.LINE_START);

        sessionNameCards = new java.awt.CardLayout();
        sessionNamePanel = new JPanel(sessionNameCards);
        sessionNamePanel.setOpaque(false);

        sessionNameLabel = new JLabel("Untitled", SwingConstants.CENTER);
        sessionNameLabel.setFont(sessionNameLabel.getFont().deriveFont(Font.BOLD));
        sessionNameLabel.setToolTipText("Double-click to rename this session");
        sessionNameLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2)
                    beginRenameSession();
            }
        });

        sessionNameField = new JTextField();
        sessionNameField.setHorizontalAlignment(SwingConstants.CENTER);
        // The field is opened by double-clicking the name, and it opens empty for an untitled
        // session, where it otherwise says nothing about what it wants.
        sessionNameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Session name");
        sessionNameField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        // Clearing is the start of typing a new name, not a request to rename the session to
        // nothing: the focus stays in the field, and the empty name is refused on commit as ever.
        sessionNameField.putClientProperty(FlatClientProperties.TEXT_FIELD_CLEAR_CALLBACK, (Runnable) () -> {
            sessionNameField.setText("");
            sessionNameField.requestFocusInWindow();
        });
        sessionNameField.addActionListener(e -> commitRenameSession()); // Enter
        sessionNameField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                commitRenameSession();
            }
        });
        sessionNameField.registerKeyboardAction(e -> endRenameSession(), // Escape cancels
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_FOCUSED);

        sessionNamePanel.add(sessionNameLabel, "view");
        sessionNamePanel.add(sessionNameField, "edit");
        bar.add(sessionNamePanel, BorderLayout.CENTER);

        // New (plain click = new session, instant; ⌘-click = new window, which takes a moment → spinner).
        JButton newButton = Buttons.flat(Buttons.newSession);
        newButton.setToolTipText("New session (⌘-click for a new window)");
        JProgressBar newSpinner = makeSpinner();
        newButton.addActionListener(e -> {
            if ((e.getModifiers() & java.awt.event.InputEvent.META_MASK) != 0) {
                int before = org.helioviewer.jhv.app.Session.liveWindowCount();
                startSpinner(newButton, newSpinner);
                Runnable done = stopSpinner(newButton, newSpinner, Buttons.newSession);
                new Actions.NewWindow().actionPerformed(null);
                pollUntil(() -> org.helioviewer.jhv.app.Session.liveWindowCount() > before, 15000, done);
            } else {
                new Actions.NewSession().actionPerformed(null); // instant
            }
        });

        // Open (dialog is instant; the reload after choosing a file spins until it lands).
        JButton loadButton = Buttons.flat(Buttons.load);
        loadButton.setToolTipText("Open a saved session…");
        JProgressBar loadSpinner = makeSpinner();
        loadButton.addActionListener(e -> {
            java.io.File state = org.helioviewer.jhv.gui.dialog.LoadStateDialog.get();
            if (state != null) {
                startSpinner(loadButton, loadSpinner);
                java.io.File previous = org.helioviewer.jhv.app.Session.currentSessionFile();
                boolean previousNamed = org.helioviewer.jhv.app.Session.isNamedSession();
                Runnable stop = stopSpinner(loadButton, loadSpinner, Buttons.load);
                org.helioviewer.jhv.app.Session.onNextStateLoad(success -> {
                    // Repointing the window at a file the load never populated would let the next
                    // autosave write the *previous* scene over the user's project.
                    if (!success && previous != null)
                        org.helioviewer.jhv.app.Session.setSessionFile(previous, previousNamed);
                    stop.run();
                });
                org.helioviewer.jhv.app.Commands.loadState(state.toURI());
                org.helioviewer.jhv.app.Session.setSessionFile(state, true);
            }
        });

        // Save: quick-save to the current file (silent → spinner); untitled falls back to Save As (dialog).
        JButton saveButton = Buttons.flat(Buttons.save);
        saveButton.setToolTipText("Save");
        JProgressBar saveSpinner = makeSpinner();
        saveButton.addActionListener(e -> {
            if (org.helioviewer.jhv.app.Session.isNamedSession()) {
                startSpinner(saveButton, saveSpinner);
                Runnable done = stopSpinner(saveButton, saveSpinner, Buttons.save);
                // Snapshot on the EDT: the scene is EDT-owned, and reading it from a worker
                // thread raced layer changes and could throw the save away.
                org.helioviewer.jhv.app.Session.quickSaveToCurrent();
                done.run();
            } else {
                new Actions.SaveStateAs().actionPerformed(null); // dialog is its own feedback
            }
        });

        JButton saveAsButton = Buttons.flat(Buttons.saveAs);
        saveAsButton.setToolTipText("Save As…");
        saveAsButton.addActionListener(e -> new Actions.SaveStateAs().actionPerformed(null));

        // Revert: reload the session from its file (spins until the load completes).
        JButton revertButton = Buttons.flat(Buttons.revert);
        revertButton.setToolTipText("Revert to saved (reload this session from its file)");
        JProgressBar revertSpinner = makeSpinner();
        revertButton.addActionListener(e -> {
            java.io.File f = org.helioviewer.jhv.app.Session.currentSessionFile();
            if (f == null || !f.isFile())
                return;
            int r = javax.swing.JOptionPane.showConfirmDialog(mainFrame,
                    "Discard changes and revert to the last saved state?",
                    "Revert to Saved", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
            if (r != javax.swing.JOptionPane.OK_OPTION)
                return;
            startSpinner(revertButton, revertSpinner);
            Runnable stopRevert = stopSpinner(revertButton, revertSpinner, Buttons.revert);
            org.helioviewer.jhv.app.Session.onNextStateLoad(success -> stopRevert.run());
            org.helioviewer.jhv.app.Commands.loadState(f.toURI());
        });

        // Standard-practice order: New, Open, Save, Save As, Revert.
        JPanel icons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 0, 0));
        icons.setOpaque(false);
        icons.add(newButton);
        icons.add(loadButton);
        icons.add(saveButton);
        icons.add(saveAsButton);
        icons.add(revertButton);
        bar.add(icons, BorderLayout.LINE_END);
        return bar;
    }

    // ---- session-bar spinner helpers: show a spinner in place of a glyph until an action lands ---

    private static JProgressBar makeSpinner() {
        JProgressBar spinner = new JProgressBar();
        spinner.setUI(new org.helioviewer.jhv.gui.component.CircularProgressUI());
        spinner.setIndeterminate(true);
        spinner.setPreferredSize(new Dimension(16, 16));
        return spinner;
    }

    private static void startSpinner(JButton button, JProgressBar spinner) {
        button.setEnabled(false);
        button.setIcon(null); // the glyph is an icon now, so clearing the text would leave it on screen
        button.add(spinner);
        button.revalidate();
        button.repaint();
    }

    private static Runnable stopSpinner(JButton button, JProgressBar spinner, Icon glyph) {
        return () -> EventQueue.invokeLater(() -> {
            button.remove(spinner);
            button.setIcon(glyph);
            button.setEnabled(true);
            button.revalidate();
            button.repaint();
        });
    }

    // Poll `condition` on the EDT every 200 ms; run `then` when it holds or after timeoutMs (safety).
    private static void pollUntil(java.util.function.BooleanSupplier condition, int timeoutMs, Runnable then) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        javax.swing.Timer timer = new javax.swing.Timer(200, null);
        timer.addActionListener(ev -> {
            if (condition.getAsBoolean() || System.nanoTime() > deadline) {
                timer.stop();
                then.run();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private static void beginRenameSession() {
        sessionNameField.setText(org.helioviewer.jhv.app.Session.displayName().equals("Untitled") ? "" : org.helioviewer.jhv.app.Session.displayName());
        sessionNameCards.show(sessionNamePanel, "edit");
        sessionNameField.requestFocusInWindow();
        sessionNameField.selectAll();
    }

    private static boolean renaming;

    private static void commitRenameSession() {
        if (renaming)
            return;
        renaming = true;
        try {
            String name = sessionNameField.getText().trim();
            if (!name.isEmpty())
                org.helioviewer.jhv.app.Session.renameCurrentSession(name);
            endRenameSession();
        } finally {
            renaming = false;
        }
    }

    private static void endRenameSession() {
        sessionNameCards.show(sessionNamePanel, "view");
    }

    private static String sessionBaseName = "Untitled";
    private static boolean sessionMarkShown;

    // Show the current session's name in-app (the macOS title bar is hidden here) and in the
    // window title / Window menu / Mission Control.
    public static void setSessionName(String name) {
        sessionBaseName = name == null || name.isBlank() ? "Untitled" : name;
        renderSessionName();
    }

    /**
     * The name as displayed, with a leading asterisk while the scene differs from what is on disk.
     *
     * <p>The name itself never carries the mark: Session.displayName stays the plain name, which is
     * what the rename field seeds from and what suggestedSaveName turns into a filename. An
     * asterisk that reached either of those would be a rename to "*Untitled" or a file called
     * "*whatever.jhv".
     */
    private static void renderSessionName() {
        sessionMarkShown = org.helioviewer.jhv.app.Session.isDirty();
        String shown = displayedSessionName(sessionBaseName, sessionMarkShown);
        if (sessionNameLabel != null) {
            sessionNameLabel.setText(shown);
            sessionNameLabel.setToolTipText(sessionMarkShown
                    ? "Unsaved changes since the last save. Double-click to rename this session."
                    : "Double-click to rename this session");
        }
        if (mainFrame != null)
            mainFrame.setTitle(shown + " : " + AppInfo.programName);
    }

    /** The composition, kept pure so SessionDirtyMarkCheck can pin it without a live window. */
    static String displayedSessionName(String base, boolean dirty) {
        return (dirty ? "*" : "") + (base == null || base.isBlank() ? "Untitled" : base);
    }

    // Polled, not pushed. Session.markDirty is called from wherever a change happens and on
    // whatever thread, so a mirror that has to be notified is a mirror that will one day not be,
    // and Swing wants the write on the EDT anyway. The UITimer is already the place this window
    // keeps such readouts honest (see the zoom slider, which polls for the same reason). One
    // boolean compare per tick, and a setText only when the state actually turns over.
    private static void syncSessionDirtyMark() {
        if (sessionMarkShown != org.helioviewer.jhv.app.Session.isDirty())
            renderSessionName();
    }

    public static void toFront() {
        if (mainFrame != null) {
            mainFrame.setState(java.awt.Frame.NORMAL); // de-minimize if needed
            mainFrame.toFront();
            mainFrame.requestFocus();
        }
    }

    public static SideContentPane getLeftContentPane() {
        return leftPane;
    }

    public static void stabilizeLeftPaneWidth() {
        // Freeze the left pane to the widest startup state so the scrollbar never overlaps options panels.
        int contentWidth = measureImageLayersPaneWidth(null);
        contentWidth = Math.max(contentWidth, measureImageLayersPaneWidth(Layers.getViewpointLayer()));
        contentWidth = Math.max(contentWidth, measureImageLayersPaneWidth(Layers.getConnectionLayer()));
        // The Playback options pane is its own top-level section, so fold its width in explicitly.
        JComponent playbackOptions = MoviePanel.getInstance().getPlaybackOptions();
        playbackOptions.revalidate();
        playbackOptions.doLayout();
        contentWidth = Math.max(contentWidth, playbackOptions.getPreferredSize().width);

        layersPanel.setSelectedLayer(null);
        leftPane.revalidate();

        // The fixed host width stretches every top-level pane (via SideContentPane's fill) to match.
        int scrollbarWidth = leftScrollPane.getVerticalScrollBar().getPreferredSize().width;
        fixedContentWidth = explicitSidebarWidth > 0
                ? Math.clamp(explicitSidebarWidth, MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH)
                : contentWidth + scrollbarWidth;
        leftPaneHost.setFixedWidth(fixedContentWidth);
        leftPaneHost.revalidate();
        // Dynamic content (CR button, Sync, video/duration labels, per-layer options) can get wider
        // than the startup measurement, and there is no horizontal scrollbar — so grow to fit. Only
        // ever growing keeps the width from oscillating as layers are selected.
        UITimer.register(MainFrame::growLeftPaneToFit);
        UITimer.register(MainFrame::syncSessionDirtyMark);

        leftPane.restoreExpansion(); // the sidebar at full width, each section as it was last left
    }

    private static int fixedContentWidth;

    private static void growLeftPaneToFit() {
        if (explicitSidebarWidth > 0) // the user's own width is not something dynamic content grows past
            return;
        int needed = Math.max(imageLayersPane.getPreferredSize().width, MoviePanel.getInstance().getPlaybackOptions().getPreferredSize().width)
                + leftScrollPane.getVerticalScrollBar().getPreferredSize().width;
        if (needed > fixedContentWidth) {
            fixedContentWidth = needed;
            leftPaneHost.setFixedWidth(fixedContentWidth);
            leftPaneHost.revalidate();
        }
    }

    private static int measureImageLayersPaneWidth(Layer optionsLayer) {
        layersPanel.setSelectedLayer(optionsLayer);
        imageLayersPane.revalidate();
        imageLayersPane.doLayout();
        return imageLayersPane.getPreferredSize().width;
    }

    /**
     * Push a chrome layout change all the way down to the render canvas.
     *
     * <p>The canvas is nested deep inside the layout and carries a native GL surface, so a plain
     * repaint leaves it at its old size: the whole frame is validated so the new bounds reach it
     * synchronously, and then the surface is told to match. Anything that changes how much room
     * the canvas has (either sidebar collapsing or resizing) goes through here.
     */
    public static void reflowChrome() {
        if (centerPanel == null || mainFrame == null)
            return;
        centerPanel.revalidate();
        mainFrame.validate();
        centerPanel.repaint();
        if (renderCanvas != null)
            renderCanvas.refreshHost();
    }

    public static void setSidebarCollapsed(boolean collapsed) {
        if (collapsed == sidebarCollapsed)
            return;
        sidebarCollapsed = collapsed;
        org.helioviewer.jhv.app.Settings.setProperty("ui.sidebarCollapsed", Boolean.toString(collapsed));

        leftPaneHost.setVisible(!collapsed); // the handle stays; westWrap shrinks to just it
        sidebarCollapseHandle.setIcon(collapsed ? Buttons.collapseRight : Buttons.collapseLeft);
        sidebarCollapseHandle.setToolTipText(collapsed ? "Show the sidebar" : "Drag to resize, click to collapse the sidebar");

        // The canvas is nested deep inside a JSplitPane, so validate the whole frame to push its
        // new bounds all the way down, then force the native GL surface to match and re-render.
        // A plain display() only reshapes the GL viewport, not the native surface.
        centerPanel.revalidate();
        mainFrame.validate(); // push the new bounds down to the deeply-nested canvas synchronously
        centerPanel.repaint();
        if (renderCanvas != null)
            renderCanvas.refreshHost(); // synchronously resizes the native surface + renders at-size
    }

    /**
     * One component that is both a click-to-collapse button and a drag-to-resize handle.
     *
     * <p>A JButton fires its click on mouse release provided it is still "armed", which
     * {@code DefaultButtonModel} decides at release time by what {@code setArmed} last recorded --
     * so disarming the model as soon as a drag is detected (moved further than a few pixels from
     * where the mouse went down) is what keeps that same release from also toggling the sidebar
     * closed. Below the threshold, or while the sidebar is collapsed and there is nothing to
     * resize, it behaves exactly like the plain button it always was.
     */
    private static boolean sidebarDragged; // a real drag happened, so the click that follows is not a click

    private static void attachSidebarResize(JButton handle) {
        final int threshold = 3;
        final int[] startX = new int[1];
        final int[] startWidth = new int[1];
        handle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startX[0] = e.getXOnScreen();
                startWidth[0] = fixedContentWidth;
                sidebarDragged = false;
            }
        });
        handle.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (sidebarCollapsed)
                    return;
                int dx = e.getXOnScreen() - startX[0];
                if (!sidebarDragged && Math.abs(dx) < threshold)
                    return;
                sidebarDragged = true;
                setSidebarWidth(startWidth[0] + dx);
            }
        });
    }

    private static javax.swing.Timer sidebarWidthSettle;

    /** Resize the sidebar to width pixels, clamped to a sane range, and remember the choice. */
    public static void setSidebarWidth(int width) {
        int clamped = Math.clamp(width, MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH);
        if (clamped == explicitSidebarWidth && clamped == fixedContentWidth)
            return;
        explicitSidebarWidth = clamped;
        fixedContentWidth = clamped;
        leftPaneHost.setFixedWidth(clamped);

        // Live, like any other splitter: the same reflow setSidebarCollapsed forces, so the canvas
        // and its native surface track the handle instead of catching up once the drag ends.
        centerPanel.revalidate();
        mainFrame.validate();
        centerPanel.repaint();
        if (renderCanvas != null)
            renderCanvas.refreshHost();

        // Settings.setProperty rewrites the whole properties file, and a drag fires this every few
        // pixels; write the final width once things stop moving, the same debounce the window's
        // own remembered bounds use.
        if (sidebarWidthSettle == null) {
            sidebarWidthSettle = new javax.swing.Timer(400, e ->
                    org.helioviewer.jhv.app.Settings.setProperty("ui.sidebarWidth", String.valueOf(explicitSidebarWidth)));
            sidebarWidthSettle.setRepeats(false);
        }
        sidebarWidthSettle.restart();
    }

    public static Component getRenderComponent() {
        return renderCanvas != null ? renderCanvas : renderHost;
    }

    public static int getFramerate() {
        return renderCanvas != null ? renderCanvas.getFramerate() : 0;
    }

    // A programmatic layout change resizes the canvas, and the native GL surface has to follow it.
    //
    // The canvas can settle over more than one layout pass -- collapsing the timelines panel takes it
    // 602 -> 736 -> 806 -- so the surface has to be matched against the size Swing finally settles on,
    // not an intermediate one. Rendering is suppressed until then, or GL reshapes to the new size and
    // draws into a drawable that is still the old one, which is what shows up as a stretched frame.
    //
    // The handover is asynchronous by necessity: dispatching synchronously to the main thread from
    // here deadlocks against AppKit.
    public static void resyncRenderSurface() {
        if (mainFrame == null)
            return;
        if (renderCanvas != null)
            renderCanvas.beginHostResync();
        mainFrame.validate();
        EventQueue.invokeLater(() -> EventQueue.invokeLater(() -> {
            if (renderCanvas != null)
                renderCanvas.resyncHostDeferred();
        }));
    }

    public static MainContentPanel getMainContentPanel() {
        return mainContentPanel;
    }

    // A layer list with its options underneath, indented so it reads as nested under the section
    // header, which is what ImageLayersPane assembles by hand for the images.
    private static JPanel sidePane(LayersPanel list, String optionsTitle, JPanel optionsWrapper) {
        JPanel pane = new JPanel();
        pane.setLayout(new javax.swing.BoxLayout(pane, javax.swing.BoxLayout.PAGE_AXIS));
        pane.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        pane.add(list);
        pane.add(new org.helioviewer.jhv.gui.component.CollapsiblePane(optionsTitle, optionsWrapper, true, true));
        return pane;
    }

    public static LayersPanel getOverlaysPanel() {
        return overlaysPanel;
    }

    public static LayersPanel getCameraPanel() {
        return cameraPanel;
    }

    public static LayersPanel getLayersPanel() {
        return layersPanel;
    }

    public static LayersSectionPanel getLayersSectionPanel() {
        return layersSectionPanel;
    }

    public static MenuBar getMenuBar() {
        return menuBar;
    }

    // Show or hide everything that is not the render output. Presentation mode owns this; the
    // sidebar's own collapsed state is left alone so exiting restores what the user had.
    // The canvas is never removed from its container: detaching it would run removeNotify(),
    // which tears down the native Metal host and every static GL object with it.
    static void setChromeVisible(boolean visible) {
        toolBarPanel.setVisible(visible);
        statusPanel.setVisible(visible);
        westWrap.setVisible(visible);
        northTransport.setVisible(visible);
        mainContentPanel.setPluginsVisible(visible);
        centerPanel.revalidate();
        mainFrame.validate(); // push the new bounds down to the canvas synchronously
        centerPanel.repaint();
        if (renderCanvas != null)
            renderCanvas.refreshHost(); // a plain display() only reshapes the GL viewport
    }

    /**
     * A panel presentation mode can lend out, carrying the slot it has to go back into.
     *
     * <p>{@code fills} says how it should be laid out while on loan: false means it wants only
     * its natural height and stacks at the top (the toolbar, the transport), true means it
     * should take all the height left over (the sidebar). Stacking everything in a BoxLayout
     * instead gave each panel its <em>maximum</em> height, which for a JPanel is unbounded, so
     * the toolbar and scrubber ballooned and the layer list was squeezed to the bottom.
     */
    record ChromeSlot(Component panel, Container parent, String constraint, boolean fills) {
        void restore() {
            parent.add(panel, constraint);
        }
    }

    // One list, in display order, so what gets borrowed and where it returns to cannot drift
    // apart: a panel added here is automatically restored to the slot named right beside it.
    static java.util.List<ChromeSlot> chromeForPresenterView() {
        return java.util.List.of(
                new ChromeSlot(toolBarPanel, mainFrame.getContentPane(), BorderLayout.NORTH, false),
                new ChromeSlot(northTransport, centerPanel, BorderLayout.PAGE_START, false),
                new ChromeSlot(westWrap, centerPanel, BorderLayout.WEST, true),
                new ChromeSlot(eastWrap, centerPanel, BorderLayout.EAST, true));
    }

    public static boolean isSidebarCollapsed() {
        return sidebarCollapsed;
    }

    private MainFrame() {}

    // The window icon above does not reach the macOS Dock or the Windows taskbar: without this the
    // running app is represented by the generic Java icon. The usual remedy, -Xdock:icon, only
    // applies when the app happens to be launched through a script that passes it, so set the icon
    // from inside the app, where it always holds.
    private static void setAppIcon() {
        try {
            if (!Taskbar.isTaskbarSupported())
                return;
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE))
                taskbar.setIconImage(IconBank.getIcon(IconBank.JHVIcon.HVLOGO_APP).getImage());
        } catch (Exception e) { // the icon is cosmetic and must never stop startup
            Log.warn("Could not set the application icon", e);
        }
    }
}
