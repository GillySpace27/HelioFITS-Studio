package org.helioviewer.jhv;

import java.awt.EventQueue;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;

import javax.swing.JFrame;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.HeadlessEDT;
import org.helioviewer.jhv.app.JHVUncaughtExceptionHandler;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.io.CommandLine;
import org.helioviewer.jhv.io.DataSources;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.ProxySettings;
import org.helioviewer.jhv.opengl.AnglePbuffer;
import org.helioviewer.jhv.plugins.PluginManager;
import org.helioviewer.jhv.plugins.eve.EVEPlugin;
import org.helioviewer.jhv.plugins.pfss.PfssPlugin;
import org.helioviewer.jhv.plugins.pointcloud.PointCloudPlugin;
import org.helioviewer.jhv.plugins.swek.SWEKPlugin;
import org.helioviewer.jhv.thread.Task;

public class HFStudio {

    static void main(String[] args) throws Exception {
        // Before the theme is peeked at, because the peek reads user.properties in the new home
        // and the migration is what puts it there. Without this, the one launch that carries a
        // JHelioviewer install across, and every launch until it is carried across, would read no
        // theme and hand the window frame the default appearance while the rest of the interface
        // used the saved one. Idempotent: createPersistentDirs calls it again below and it returns
        // as soon as the new folder exists.
        Directories.migrateLegacyHome();
        // The traffic lights, the title bar and the native menus are drawn in the appearance the
        // Cocoa application is given here. It was pinned to dark, so a light theme produced a
        // light application under a dark title bar. macOS reads this exactly once, as NSApp
        // starts (libosxapp, [NSApp setAppearance:]), which is why it is set before anything
        // touches AWT and why a theme switch made later in the session cannot move it: the rest
        // of the interface changes immediately, the window frame follows on the next launch.
        System.setProperty("apple.awt.application.appearance", appearance(Theme.startupIsDark()));
        System.setProperty("apple.awt.application.name", "HelioFITS Studio");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("sun.awt.noerasebackground", "true");
        //System.setProperty("org.lwjgl.util.NoChecks", "true");
        // Save current default system timezone in user.timezone
        System.setProperty("user.timezone", TimeZone.getDefault().getID());
        // Per default all times should be given in GMT
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // Per default, the US locale should be used
        Locale.setDefault(Locale.US);

        boolean headless = isHeadless();
        // Uncaught runtime errors are reported to the GUI or stderr, depending on startup mode.
        JHVUncaughtExceptionHandler.setupHandlerForThread(headless);

        // Set the platform
        Platform.init();
        // Create persistent directories, including Logs.
        Directories.createPersistentDirs();
        // Init log
        Log.init();
        // Create transient cache directories after logging is available. On Windows this may need an ASCII-safe path.
        Directories.createCacheDirs();
        // Information log message
        Log.info("HelioFITS Studio started with command-line options: " + String.join(" ", args));

        // Read the version and revision from the JAR metafile
        AppInfo.loadVersion();

        DataSources.initSources(); // sources must be initialized before settings
        Settings.load();
        // System.setProperty("jsamp.nosystray", "true");

        // Prints the usage message
        if (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
            System.out.println(CommandLine.getUsageMessage());
            return;
        }
        // Save command line arguments
        CommandLine.setArguments(args);

        ProxySettings.init();
        try {
            AppInit.loadSpice();
        } catch (Exception e) {
            Log.error("Failed to setup SPICE", e);
            Message.fatalErr("Failed to setup SPICE:\n" + e.getMessage());
            return;
        }

        PluginManager.setGUIEnabled(!headless);
        if (headless)
            startHeadless();
        else
            startGUI();
    }

    private static void startGUI() {
        EventQueue.invokeLater(() -> {
            Log.info("Start main window");
            UIGlobals.setLaf();
            JFrame frame = MainFrame.prepare();

            loadPlugins(true);

            frame.pack();
            MainFrame.stabilizeLeftPaneWidth();
            if (!MainFrame.boundsRestored())
                frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            MainFrame.restoreChrome();
            UITimer.start();
            // Starts the desktop watch when the mode is Follow system. The theme itself is already
            // right: Theme.current() resolves through the mode, so setLaf() above put on whichever
            // half of the pair applies.
            org.helioviewer.jhv.gui.UIGlobals.applyThemeMode();
            // A way to get the layout report without a hand on the mouse. Same call the Help menu
            // makes, once the window has actually been laid out.
            if (Boolean.getBoolean("jhv.probeLayout"))
                new javax.swing.Timer(4000, e -> {
                    ((javax.swing.Timer) e.getSource()).stop();
                    org.helioviewer.jhv.gui.LayoutProbe.logReport();
                    if (Boolean.getBoolean("jhv.probeExit"))
                        System.exit(0);
                }).start();
            org.helioviewer.jhv.app.Session.init(); // session dirty-tracking + autosave timer

            Task.submit("init", new Init(true), HFStudio::onSuccessInit, HFStudio::onFailureInit);
        });
    }

    private static void startHeadless() throws InterruptedException {
        HeadlessEDT.invokeLater(() -> {
            Log.info("Start headless mode");
            AnglePbuffer renderer = new AnglePbuffer();
            DisplayController.setRenderRequestHandler(renderer::requestRender);

            loadPlugins(false);

            Task.submit("init", new Init(false), HFStudio::onSuccessInit, HFStudio::onFailureInit);
        });
    }

    private static void loadPlugins(boolean loadTimelines) {
        try {
            Log.info("Load enabled plugins");
            if (loadTimelines)
                PluginManager.addPlugin(new EVEPlugin());
            PluginManager.addPlugin(new SWEKPlugin());
            PluginManager.addPlugin(new PfssPlugin());
            PluginManager.addPlugin(new PointCloudPlugin());
        } catch (Exception e) {
            Log.warn("Plugin load error", e);
        }
    }

    private record Init(boolean webProfilePopup) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            AppInit.init(webProfilePopup);
            return null;
        }
    }

    private static void onSuccessInit(Void ignoredResult) {
        DataSources.loadSources(true);
        CommandLine.load();
    }

    private static void onFailureInit(String ignoredLogContext, Throwable t) {
        Log.error(t);
        Message.err("An error occurred during initialization", t.getMessage());
    }

    /**
     * The Cocoa appearance name for a theme's darkness.
     *
     * <p>Its own method because the two names are strings macOS validates and silently ignores
     * when they are wrong: a typo would leave the window frame at the system default with
     * nothing said, which is indistinguishable from this never having been wired up.
     */
    static String appearance(boolean dark) {
        return dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";
    }

    private static boolean isHeadless() {
        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        return screens == null || screens.length == 0;
    }

}
