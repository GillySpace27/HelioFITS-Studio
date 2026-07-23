package org.helioviewer.jhv.gui.component;

import java.awt.event.KeyEvent;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.dialog.AboutDialog;
import org.helioviewer.jhv.gui.dialog.LogDialog;
import org.helioviewer.jhv.gui.dialog.SettingsDialog;
import org.helioviewer.jhv.view.uri.FITSSettings;

// Menu bar of the main window
@SuppressWarnings("serial")
public final class MenuBar extends JMenuBar {

    private JMenu layersMenu;

    public JMenu getLayersMenu() {
        return layersMenu;
    }

    public MenuBar() {
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.add(new Actions.NewSession());
        fileMenu.add(new Actions.LoadState());
        fileMenu.add(buildOpenRecentMenu());
        fileMenu.add(new Actions.CloseWindow());
        fileMenu.addSeparator();
        fileMenu.add(new Actions.SaveState());
        fileMenu.add(new Actions.SaveStateAs());
        fileMenu.add(new Actions.RevertToSaved());
        fileMenu.addSeparator();
        fileMenu.add(new Actions.SetDefaultSession());
        fileMenu.add(new Actions.ClearDefaultSession());
        fileMenu.addSeparator();
        fileMenu.add(new Actions.ReloadSources());
        if (!Platform.isMacOS())
            fileMenu.add(new Actions.NewWindow()); // no Window menu off macOS

        Actions.ExitProgram exitAction = new Actions.ExitProgram();
        if (Platform.isMacOS()) {
            // Honor the quit response so a cancelled quit reports back to macOS as refused
            // ("<app> blocked shutdown") instead of hanging or forcing.
            DesktopIntegration.setQuitHandler((e, response) -> {
                if (org.helioviewer.jhv.app.ExitHooks.exitProgram())
                    response.performQuit();
                else
                    response.cancelQuit();
            });
        } else {
            fileMenu.addSeparator();
            fileMenu.add(exitAction);
        }
        add(fileMenu);

        JMenu editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);
        editMenu.add(new Actions.Paste());
        add(editMenu);

        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);
        viewMenu.add(new Actions.ZoomOneToOne());
        viewMenu.add(new Actions.ZoomFit());
        viewMenu.add(new Actions.ZoomIn());
        viewMenu.add(new Actions.ZoomOut());
        viewMenu.addSeparator();

        JCheckBoxMenuItem separateMultiviewZoom = new JCheckBoxMenuItem(new Actions.SeparateMultiviewZoom());
        separateMultiviewZoom.setState(Display.separateViewportZoom);
        viewMenu.add(separateMultiviewZoom);

        viewMenu.addSeparator();
        viewMenu.add(new Actions.ResetCameraAxis());
        viewMenu.add(new Actions.ResetCamera());
        viewMenu.addSeparator();
        viewMenu.add(new Actions.ClearAnnotations());

        JCheckBoxMenuItem white = new JCheckBoxMenuItem("Use White Background");
        white.addItemListener(e -> {
            Display.whiteBackground = white.getState();
            DisplayController.display();
        });
        viewMenu.add(white);

        viewMenu.addSeparator();
        viewMenu.add(new Actions.ShowProjectionPalette());
        viewMenu.add(new Actions.TrackCME());
        viewMenu.add(new Actions.ShowDialog("FITS Settings...", new FITSSettings.SettingsDialog()));

        add(viewMenu);

        // Layer/timeline creation, gathered from File into their own menu. EVEPlugin inserts
        // "New Timeline…"/"Open Timeline…" at indices 5/6 (see EVEPlugin.installGUI).
        layersMenu = new JMenu("Layers");
        layersMenu.setMnemonic(KeyEvent.VK_L);
        layersMenu.add(new Actions.NewLayer());
        layersMenu.add(new Actions.NewSoarLayer());
        layersMenu.add(new Actions.NewSynopticLayer());
        layersMenu.add(new Actions.NewPunchLayer());
        layersMenu.add(new Actions.NewAspiicsLayer());
        layersMenu.add(new Actions.NewPointCloudLayer());
        layersMenu.add(new Actions.OpenLocalFile());
        add(layersMenu);

        JMenu movieMenu = new JMenu("Movie");
        movieMenu.setMnemonic(KeyEvent.VK_M);
        movieMenu.add(Actions.PLAY_PAUSE);
        movieMenu.add(Actions.PREVIOUS_FRAME);
        movieMenu.add(Actions.NEXT_FRAME);
        movieMenu.addSeparator();
        movieMenu.add(Actions.TRIM_START);
        movieMenu.add(Actions.TRIM_END);
        movieMenu.add(Actions.TRIM_RESET);
        add(movieMenu);

        Actions.ShowDialog settingsAction = new Actions.ShowDialog("Settings...", new SettingsDialog());
        if (Platform.isMacOS()) {
            DesktopIntegration.setPreferencesHandler(e -> settingsAction.actionPerformed(null));
            JMenu windowMenu = new JMenu("Window");
            windowMenu.setMnemonic(KeyEvent.VK_W);
            windowMenu.add(new Actions.NewWindow());
            windowMenu.addSeparator();
            windowMenu.add(new Actions.WindowMinimize());
            windowMenu.add(new Actions.WindowZoom());
            windowMenu.addSeparator();
            int fixedCount = windowMenu.getItemCount(); // items above the live window list
            windowMenu.addMenuListener(new javax.swing.event.MenuListener() {
                @Override
                public void menuSelected(javax.swing.event.MenuEvent e) {
                    rebuildWindowList(windowMenu, fixedCount);
                }

                @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
                @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
            });
            add(windowMenu);
        } else {
            JMenu toolsMenu = new JMenu("Tools");
            toolsMenu.setMnemonic(KeyEvent.VK_T);
            toolsMenu.add(settingsAction);
            add(toolsMenu);
        }

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        Actions.ShowDialog aboutAction = new Actions.ShowDialog("About JHelioviewer...", new AboutDialog());
        if (Platform.isMacOS()) {
            DesktopIntegration.setAboutHandler(e -> aboutAction.actionPerformed(null));
        } else {
            helpMenu.add(aboutAction);
        }

        helpMenu.add(new Actions.OpenURLinBrowser("Open User Manual", AppInfo.documentationURL));
        helpMenu.add(new Actions.OpenURLinBrowser("Open Website", "https://www.jhelioviewer.org"));
        helpMenu.add(new Actions.OpenURLinBrowser("Open Change Log", "https://github.com/Helioviewer-Project/JHelioviewer-SWHV/blob/master/changelog.md"));
        helpMenu.add(new Actions.CheckForUpdates());
        helpMenu.addSeparator();
        helpMenu.add(new Actions.ShowDialog("Show Log...", new LogDialog()));
        helpMenu.add(new Actions.OpenURLinBrowser("Report Bug/Request Feature", AppInfo.bugURL));

        add(helpMenu);
    }

    // Open Recent: rebuilt each time it opens, from the recent-sessions list.
    private static JMenu buildOpenRecentMenu() {
        JMenu recent = new JMenu("Open Recent");
        recent.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                recent.removeAll();
                java.util.List<String> recents = org.helioviewer.jhv.app.Session.recentSessions();
                if (recents.isEmpty()) {
                    javax.swing.JMenuItem none = new javax.swing.JMenuItem("No Recent Sessions");
                    none.setEnabled(false);
                    recent.add(none);
                    return;
                }
                for (String path : recents)
                    recent.add(new javax.swing.JMenuItem(new Actions.OpenRecent(new java.io.File(path))));
                recent.addSeparator();
                recent.add(new javax.swing.JMenuItem(new Actions.ClearRecents()));
            }

            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        return recent;
    }

    // Replace the dynamic tail of the Window menu with one checkable item per open window.
    private static void rebuildWindowList(JMenu windowMenu, int fixedCount) {
        while (windowMenu.getItemCount() > fixedCount)
            windowMenu.remove(windowMenu.getItemCount() - 1);

        for (org.helioviewer.jhv.app.Session.WindowInfo w : org.helioviewer.jhv.app.Session.liveWindows()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(w.name(), w.current());
            item.addActionListener(e -> org.helioviewer.jhv.app.Session.raiseWindow(w.pid()));
            windowMenu.add(item);
        }
    }

}
