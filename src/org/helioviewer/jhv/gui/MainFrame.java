package org.helioviewer.jhv.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Taskbar;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.JFrame;
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

import com.jidesoft.swing.JideButton;
import org.helioviewer.jhv.opengl.angle.MacAngleBridge;
import org.helioviewer.jhv.thread.Task;

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
    private static FixedWidthPanel leftPaneHost;

    private static JPanel centerPanel;
    private static JideButton sidebarCollapseHandle;
    private static boolean sidebarCollapsed;

    private static SideContentPane leftPane;

    private static AngleCanvas renderCanvas;
    private static RenderStartupHost renderHost;
    private static AwtInputAdapter awtInputAdapter;
    private static MainContentPanel mainContentPanel;

    private static LayersPanel layersPanel;
    private static LayersSectionPanel layersSectionPanel;
    private static ImageLayersPane imageLayersPane;

    private static MenuBar menuBar;

    public static JFrame prepare() {
        mainFrame = createFrame();

        Message.setHandler(new MessageHandler());

        menuBar = new MenuBar();
        mainFrame.setJMenuBar(menuBar);

        renderCanvas = null;
        renderHost = new RenderStartupHost();

        leftPane = new SideContentPane();
        JPanel layerOptionsWrapper = new JPanel(new BorderLayout());
        JPanel geometryWrapper = new JPanel(new BorderLayout());
        JPanel manageWrapper = new JPanel(new BorderLayout());
        LayerOptionSections sections = new LayerOptionSections(layerOptionsWrapper, geometryWrapper, manageWrapper);
        layersPanel = new LayersPanel(sections);                       // table needs the controller
        layersSectionPanel = new LayersSectionPanel(); // ctor calls MainFrame.getLayersPanel()
        MoviePanel moviePanel = MoviePanel.getInstance();
        imageLayersPane = new ImageLayersPane(moviePanel.getTimeSelectorPanel(), layersSectionPanel, layerOptionsWrapper, geometryWrapper, manageWrapper);
        // The scrubber + playback buttons are always docked at the top (see below); the sidebar keeps
        // the recording/speed settings as their own "Playback options" pane, and the master time range
        // now lives atop Image Layers where it belongs.
        // Added expanded so stabilizeLeftPaneWidth() can measure the real content width; they are
        // collapsed at the end of that method so the sidebar opens wide but with panels closed.
        // Order: pick-what-to-see (Image Layers) before how-to-play (Playback), then the plugin
        // panels (Timeline Layers, SWEK) below — the movie-building workflow top to bottom.
        leftPane.add("Image Layers", imageLayersPane, true);
        leftPane.add("Playback and Recording", moviePanel.getPlaybackOptions(), true);

        leftScrollPane = new JScrollPane(leftPane, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        leftScrollPane.setFocusable(false);
        leftScrollPane.setBorder(null);
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
        sidebarCollapseHandle = new JideButton(Buttons.collapseLeft);
        sidebarCollapseHandle.setToolTipText("Collapse the sidebar");
        sidebarCollapseHandle.setPreferredSize(new Dimension(16, 0));
        sidebarCollapseHandle.addActionListener(e -> setSidebarCollapsed(!sidebarCollapsed));

        JPanel westWrap = new JPanel(new BorderLayout());
        westWrap.add(leftPaneHost, BorderLayout.CENTER);
        westWrap.add(sidebarCollapseHandle, BorderLayout.LINE_END);

        centerPanel.add(MoviePanel.getInstance().getNorthTransport(), BorderLayout.PAGE_START);
        centerPanel.add(westWrap, BorderLayout.WEST);
        centerPanel.add(mainContentPanel, BorderLayout.CENTER);

        ViewpointStatusPanel viewpointStatus = new ViewpointStatusPanel();
        FramerateStatusPanel framerateStatus = new FramerateStatusPanel();
        PositionStatusPanel positionStatus = new PositionStatusPanel();
        InputController.addListener(positionStatus);

        StatusPanel statusPanel = new StatusPanel(5, 5);
        statusPanel.addPlugin(framerateStatus, StatusPanel.Alignment.LEFT);
        statusPanel.addPlugin(positionStatus, StatusPanel.Alignment.RIGHT);
        statusPanel.addPlugin(viewpointStatus, StatusPanel.Alignment.RIGHT);

        ToolBar toolBar = new ToolBar();

        JPanel toolBarPanel = new JPanel(new BorderLayout());
        toolBarPanel.add(toolBar, BorderLayout.CENTER);

        mainFrame.getContentPane().add(toolBarPanel, BorderLayout.NORTH);
        mainFrame.getContentPane().add(centerPanel, BorderLayout.CENTER);
        mainFrame.getContentPane().add(statusPanel, BorderLayout.SOUTH);

        Player.setMaster(Layers.getActiveImageLayer()); //! for nullImageLayer

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

        frame.setIconImage(IconBank.getIcon(IconBank.JHVIcon.HVLOGO_SMALL).getImage());
        setAppIcon();

        return frame;
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
        JideButton collapseAllButton = new JideButton(Buttons.collapseAll);
        collapseAllButton.setToolTipText("Collapse all panels");
        collapseAllButton.addActionListener(e -> { if (leftPane != null) leftPane.collapseAll(); });
        JideButton expandAllButton = new JideButton(Buttons.expandAll);
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
        JideButton newButton = new JideButton(Buttons.newSession);
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
        JideButton loadButton = new JideButton(Buttons.load);
        loadButton.setToolTipText("Open a saved session…");
        JProgressBar loadSpinner = makeSpinner();
        loadButton.addActionListener(e -> {
            java.io.File state = org.helioviewer.jhv.gui.dialog.LoadStateDialog.get();
            if (state != null) {
                startSpinner(loadButton, loadSpinner);
                org.helioviewer.jhv.app.Session.onNextStateLoad(stopSpinner(loadButton, loadSpinner, Buttons.load));
                org.helioviewer.jhv.app.Commands.loadState(state.toURI());
                org.helioviewer.jhv.app.Session.setSessionFile(state, true);
            }
        });

        // Save: quick-save to the current file (silent → spinner); untitled falls back to Save As (dialog).
        JideButton saveButton = new JideButton(Buttons.save);
        saveButton.setToolTipText("Save");
        JProgressBar saveSpinner = makeSpinner();
        saveButton.addActionListener(e -> {
            if (org.helioviewer.jhv.app.Session.isNamedSession()) {
                startSpinner(saveButton, saveSpinner);
                Runnable done = stopSpinner(saveButton, saveSpinner, Buttons.save);
                new Thread(() -> { // off the EDT so the spinner animates during the write
                    org.helioviewer.jhv.app.Session.quickSaveToCurrent();
                    done.run();
                }, "JHV-QuickSave").start();
            } else {
                new Actions.SaveStateAs().actionPerformed(null); // dialog is its own feedback
            }
        });

        JideButton saveAsButton = new JideButton(Buttons.saveAs);
        saveAsButton.setToolTipText("Save As…");
        saveAsButton.addActionListener(e -> new Actions.SaveStateAs().actionPerformed(null));

        // Revert: reload the session from its file (spins until the load completes).
        JideButton revertButton = new JideButton(Buttons.revert);
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
            org.helioviewer.jhv.app.Session.onNextStateLoad(stopSpinner(revertButton, revertSpinner, Buttons.revert));
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

    private static void startSpinner(JideButton button, JProgressBar spinner) {
        button.setEnabled(false);
        button.setText(null);
        button.add(spinner);
        button.revalidate();
        button.repaint();
    }

    private static Runnable stopSpinner(JideButton button, JProgressBar spinner, String glyph) {
        return () -> EventQueue.invokeLater(() -> {
            button.remove(spinner);
            button.setText(glyph);
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

    // Show the current session's name in-app (the macOS title bar is hidden here) and in the
    // window title / Window menu / Mission Control.
    public static void setSessionName(String name) {
        String shown = name == null || name.isBlank() ? "Untitled" : name;
        if (sessionNameLabel != null)
            sessionNameLabel.setText(shown);
        if (mainFrame != null)
            mainFrame.setTitle(shown + " — " + AppInfo.programName);
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
        fixedContentWidth = contentWidth + scrollbarWidth;
        leftPaneHost.setFixedWidth(fixedContentWidth);
        leftPaneHost.revalidate();
        // Dynamic content (CR button, Sync, video/duration labels, per-layer options) can get wider
        // than the startup measurement, and there is no horizontal scrollbar — so grow to fit. Only
        // ever growing keeps the width from oscillating as layers are selected.
        UITimer.register(MainFrame::growLeftPaneToFit);

        leftPane.collapseAll(); // open the sidebar at full width but with every panel collapsed
    }

    private static int fixedContentWidth;

    private static void growLeftPaneToFit() {
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

    public static void setSidebarCollapsed(boolean collapsed) {
        if (collapsed == sidebarCollapsed)
            return;
        sidebarCollapsed = collapsed;

        leftPaneHost.setVisible(!collapsed); // the handle stays; westWrap shrinks to just it
        sidebarCollapseHandle.setText(collapsed ? Buttons.collapseRight : Buttons.collapseLeft);
        sidebarCollapseHandle.setToolTipText(collapsed ? "Show the sidebar" : "Collapse the sidebar");

        // The canvas is nested deep inside a JSplitPane, so validate the whole frame to push its
        // new bounds all the way down, then force the native GL surface to match and re-render.
        // A plain display() only reshapes the GL viewport, not the native surface.
        centerPanel.revalidate();
        mainFrame.validate(); // push the new bounds down to the deeply-nested canvas synchronously
        centerPanel.repaint();
        if (renderCanvas != null)
            renderCanvas.refreshHost(); // synchronously resizes the native surface + renders at-size
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

    public static LayersPanel getLayersPanel() {
        return layersPanel;
    }

    public static LayersSectionPanel getLayersSectionPanel() {
        return layersSectionPanel;
    }

    public static MenuBar getMenuBar() {
        return menuBar;
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
