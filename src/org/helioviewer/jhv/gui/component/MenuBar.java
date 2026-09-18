package org.helioviewer.jhv.gui.component;

import java.awt.event.KeyEvent;

import javax.annotation.Nullable;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.ButtonGroup;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.HdrGain;
import org.helioviewer.jhv.gui.Actions;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.PresentationMode;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.dialog.AboutDialog;
import org.helioviewer.jhv.gui.dialog.LogDialog;
import org.helioviewer.jhv.gui.dialog.SettingsDialog;
import org.helioviewer.jhv.gui.dialog.TextDialog;
import org.helioviewer.jhv.gui.dialog.ThemeDialog;

// Menu bar of the main window
@SuppressWarnings("serial")
public final class MenuBar extends JMenuBar {

    private JMenu layersMenu;

    public JMenu getLayersMenu() {
        return layersMenu;
    }

    /**
     * Everything the toolbar is not showing, and the way to change what that is.
     *
     * <p>Rebuilt every time it opens, and holding the toolbar's OWN controls rather than stand-ins
     * for them. Both halves matter. The bar is recreated whenever its contents or its display mode
     * change, so a component captured once would soon belong to a toolbar that no longer exists;
     * and a stand-in menu item could not show a toggle's pressed state, which for Track, Corona,
     * Differential and Multiview is most of what the control is.
     *
     * @param settings the Settings item, where this menu is its home. Null on macOS, where it
     *                 belongs in the application menu and is installed as the preferences handler.
     */
    private static JMenu buildToolsMenu(@Nullable Actions.ShowDialog settings) {
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.setMnemonic(KeyEvent.VK_T);
        toolsMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                toolsMenu.removeAll();
                // Every tool, at the top, whether or not it is on the bar: this menu is the
                // inventory. Rebuilt on each open because both the list and the toggles' states
                // move underneath it.
                java.util.Set<String> onBar = ToolBar.shownIds();
                for (ToolBar.Tool tool : ToolBar.allTools())
                    toolsMenu.add(toolItem(tool, onBar.contains(tool.id())));
                toolsMenu.addSeparator();
                // Below the tools: a command that loads the CACTus events and then opens the
                // Track CME palette (the toolbar toggle above only shows the palette), and two
                // dialogs with no toolbar button. They were in View, which is where a thing you
                // look through goes, not a thing you work with.
                toolsMenu.add(new Actions.TrackCME());
                toolsMenu.add(new Actions.ShowDialog("Load from Cache...", new org.helioviewer.jhv.gui.dialog.CacheDialog()));
                toolsMenu.addSeparator();
                JMenuItem edit = new JMenuItem("Edit Toolbar...");
                edit.setIcon(Buttons.editToolbar);
                edit.addActionListener(ev -> ToolbarEditor.open());
                toolsMenu.add(edit);
                if (settings != null)
                    toolsMenu.add(settings);
            }

            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        return toolsMenu;
    }

    /**
     * Show the tool's keyboard shortcut, taken from the very listener that will run.
     *
     * <p>Not a second table of shortcuts. A toolbar button is wired to the same Action the File and
     * View menus carry, and that Action already states its ACCELERATOR_KEY, so the shortcut is read
     * off the button rather than written down again somewhere that could disagree with it. A tool
     * driven by a lambda instead of an Action has no shortcut to show, and shows none.
     *
     * <p>The keystroke is then bound twice, here and on the menu item that owns the Action. Both
     * bindings invoke the same Action, so whichever Swing picks does the same thing; the cost of
     * the duplicate is that it exists, and the alternative is a menu that hides shortcuts the
     * application does have.
     */
    private static void setShortcut(JMenuItem item, javax.swing.AbstractButton button) {
        for (java.awt.event.ActionListener listener : button.getActionListeners())
            if (listener instanceof javax.swing.Action action
                    && action.getValue(javax.swing.Action.ACCELERATOR_KEY) instanceof javax.swing.KeyStroke key) {
                item.setAccelerator(key);
                return;
            }
    }

    /**
     * One tool as an entry in the Tools menu.
     *
     * <p>Swing gives a component one parent, so a tool that is on the toolbar cannot also be in
     * this menu. It appears as an item that clicks the real control instead, and a toggle appears
     * as a checkbox reading the real button, so the menu can never claim a state the bar disagrees
     * with. A tool that is NOT on the bar is handed over as itself: nothing else holds that
     * control, the menu is where it lives, and its pressed state is then simply its own.
     *
     * <p>The exception is the two split buttons, More and Rotate View 90. A SplitButton is a
     * JPanel rather than an AbstractButton, so there is no click to forward, and its whole content
     * is a dropdown anchored to itself: it has to be present somewhere to open one. On the bar,
     * the item opens that dropdown where the button is. Off it, the button comes here bodily, as
     * every hidden tool used to.
     */
    private static java.awt.Component toolItem(ToolBar.Tool tool, boolean onBar) {
        javax.swing.JComponent comp = tool.comp();
        if (comp instanceof javax.swing.AbstractButton button) {
            JMenuItem item = button instanceof javax.swing.JToggleButton
                    ? new javax.swing.JCheckBoxMenuItem(tool.label(), button.isSelected())
                    : new JMenuItem(tool.label());
            item.addActionListener(e -> button.doClick());
            item.setIcon(tool.icon());
            item.setToolTipText(tool.tip());
            setShortcut(item, button);
            return item;
        }
        if (onBar && comp instanceof SplitButton split) {
            JMenuItem item = new JMenuItem(tool.label());
            item.setIcon(tool.icon());
            item.setToolTipText(tool.tip());
            // After this menu has closed, or the two popups fight over who is showing.
            item.addActionListener(e -> javax.swing.SwingUtilities.invokeLater(
                    () -> split.getPopupMenu().show(split, 0, split.getHeight())));
            return item;
        }
        return comp;
    }

    public MenuBar(ToolBar toolBar, StatusPanel statusPanel) {
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        // The layer-creation items upstream keeps here live in the Layers menu below.
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
        editMenu.addSeparator();
        // Rubbing out what you drew is an edit of the scene, not a way of looking at it. It sat
        // in View because Annotation used to live on the toolbar's More menu and this was the
        // only other place it could be reached from.
        editMenu.add(new Actions.ClearAnnotations());
        add(editMenu);

        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);
        viewMenu.add(new Actions.ZoomOneToOne());
        viewMenu.add(new Actions.ZoomFit());
        viewMenu.add(new Actions.ZoomIn());
        viewMenu.add(new Actions.ZoomOut());
        viewMenu.add(new Actions.ResetCameraAxis());
        viewMenu.add(new Actions.ResetCamera());

        JCheckBoxMenuItem autoReset = new JCheckBoxMenuItem("Reset View for New Layers",
                org.helioviewer.jhv.app.DisplaySettings.getAutoResetView());
        autoReset.setToolTipText("Frame the scene the way Reset View does when a layer's first frame arrives, "
                + "instead of leaving it framed for whatever was loaded before");
        autoReset.addItemListener(e -> org.helioviewer.jhv.app.DisplaySettings.setAutoResetView(autoReset.getState()));
        viewMenu.add(autoReset);

        viewMenu.addSeparator();

        JCheckBoxMenuItem separateMultiviewZoom = new JCheckBoxMenuItem(new Actions.SeparateMultiviewZoom());
        separateMultiviewZoom.setState(Display.separateViewportZoom);
        viewMenu.add(separateMultiviewZoom);

        JCheckBoxMenuItem white = new JCheckBoxMenuItem("Use White Background");
        white.addItemListener(e -> {
            Display.whiteBackground = white.getState();
            DisplayController.display();
        });
        viewMenu.add(white);

        viewMenu.addSeparator();

        JCheckBoxMenuItem showToolbar = new JCheckBoxMenuItem("Show Toolbar", toolBar.isVisible());
        showToolbar.addItemListener(e -> toolBar.setToolbarVisible(showToolbar.getState()));
        viewMenu.add(showToolbar);

        JCheckBoxMenuItem showToolbarText = new JCheckBoxMenuItem("Show Toolbar Text", toolBar.isTextVisible());
        showToolbarText.addItemListener(e -> toolBar.setTextVisible(showToolbarText.getState()));
        viewMenu.add(showToolbarText);

        JCheckBoxMenuItem showStatusBar = new JCheckBoxMenuItem("Show Status Bar", statusPanel.isVisible());
        showStatusBar.addItemListener(e -> statusPanel.setStatusBarVisible(showStatusBar.getState()));
        viewMenu.add(showStatusBar);

        viewMenu.addSeparator();

        JCheckBoxMenuItem clipping = new JCheckBoxMenuItem("Show Clipped Pixels", Display.showClipping);
        clipping.setToolTipText("Magenta where the display range is exceeded, green where it bottoms out. Flat regions that stay unflagged were already flat in the data.");
        clipping.addItemListener(e -> {
            Display.setShowClipping(clipping.getState());
            ColourPaletteContent.refresh(); // the Colour palette carries the same switch
            DisplayController.display();
        });
        clippingItem = clipping;
        viewMenu.add(clipping);

        JCheckBoxMenuItem dither = new JCheckBoxMenuItem("Dither Colour Banding", Display.isDitherEnabled());
        dither.setToolTipText("Add one screen level of noise before the colour table, so a smooth gradient is "
                + "not rounded into steps by the 8-bit display. It cannot touch banding that is already in the "
                + "data: a stretched 8-bit browse product has lost that detail before it arrives.");
        dither.addItemListener(e -> {
            Display.setDitherEnabled(dither.getState());
            DisplayController.display();
        });
        viewMenu.add(dither);

        JCheckBoxMenuItem hdrCanvas = new JCheckBoxMenuItem("HDR Canvas", HdrGain.canvasEnabled());
        hdrCanvas.setToolTipText("Render image layers into the display's extended range, so the corona can be "
                + "brighter than the window. Needs an EDR display; takes effect the next time HFStudio starts.");
        hdrCanvas.addItemListener(e -> {
            HdrGain.setCanvasEnabled(hdrCanvas.getState()); // also parks the brightness at 1x, or restores it
            DisplayController.display();
        });
        viewMenu.add(hdrCanvas);

        JMenu hdrBrightness = new JMenu("HDR Brightness");
        hdrBrightness.setToolTipText("How far over the SDR white the brightest data goes, in photographic stops. "
                + "Never more than the display offers at its current brightness; Maximum uses all of it.");
        ButtonGroup gainGroup = new ButtonGroup();
        String[][] stops = {{"Off (1x)", "1"}, {"+1/2 stop (1.4x)", "1.41"}, {"+1 stop (2x)", "2"}, {"+1 1/2 stops (2.8x)", "2.83"},
                {"+2 stops (4x)", "4"}, {"Display maximum", "auto"}};
        for (String[] stop : stops) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(stop[0], stop[1].equals(HdrGain.setting()));
            item.addActionListener(e -> {
                HdrGain.setSetting(stop[1]);
                DisplayController.display();
            });
            gainGroup.add(item);
            hdrBrightness.add(item);
        }
        viewMenu.add(hdrBrightness);

        JMenu hdrMapping = new JMenu("HDR Mapping");
        hdrMapping.setToolTipText("Linear scales the whole image into the headroom. The knee modes leave everything "
                + "below the knee as it is and expand only the highlights; soft rolls into it without a visible break.");
        ButtonGroup modeGroup = new ButtonGroup();
        for (HdrGain.Mode mode : HdrGain.Mode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(mode.label, mode == HdrGain.mode());
            item.addActionListener(e -> {
                HdrGain.setMode(mode);
                DisplayController.display();
            });
            modeGroup.add(item);
            hdrMapping.add(item);
        }
        viewMenu.add(hdrMapping);

        JMenu hdrKnee = new JMenu("HDR Knee");
        hdrKnee.setToolTipText("Where the knee modes start expanding, as a fraction of the data range that feeds the colour table.");
        ButtonGroup kneeGroup = new ButtonGroup();
        for (double k : new double[]{0.5, 0.75, 0.9}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem("top " + Math.round((1 - k) * 100) + "% of the data", Math.abs(k - HdrGain.knee()) < 1e-3);
            item.addActionListener(e -> {
                HdrGain.setKnee(k);
                DisplayController.display();
            });
            kneeGroup.add(item);
            hdrKnee.add(item);
        }
        viewMenu.add(hdrKnee);

        viewMenu.addSeparator();
        viewMenu.add(themeMenu());

        viewMenu.addSeparator();
        // Presentation stays: it is a way of looking at the scene rather than a thing to work on
        // it with. The five palette items that used to follow it are gone, not moved -- the Tools
        // menu lists every toolbar tool, and Projection, HDR, Fourier, Grid and Camera are five of
        // them, so those lines were a second copy that could drift out of step with the first.
        viewMenu.add(new Actions.TogglePresentationMode());
        viewMenu.add(presentationMenu());

        add(viewMenu);

        // Layer/timeline creation, gathered from File into their own menu. EVEPlugin inserts
        // "New Timeline…"/"Open Timeline…" at indices 5/6 (see EVEPlugin.installGUI).
        layersMenu = new JMenu("Layers");
        layersMenu.setMnemonic(KeyEvent.VK_L);
        // Grouped by what the layer actually carries, because that is the choice that decides how
        // much of the measurement survives: a JP2 from Helioviewer is an 8-bit browse product,
        // where a native FITS is the calibrated one. Same missions, different data.
        layersMenu.add(new Actions.NewLayer());
        layersMenu.add(new Actions.NewSynopticLayer());
        layersMenu.addSeparator();
        layersMenu.add(new Actions.NewPunchLayer());
        layersMenu.add(new Actions.NewSoarLayer());
        layersMenu.add(new Actions.NewAspiicsLayer());
        layersMenu.addSeparator();
        layersMenu.add(new Actions.NewPointCloudLayer());
        layersMenu.add(new Actions.OpenLocalFile());
        layersMenu.add(new Actions.OpenModel());
        add(layersMenu);

        // Beside Layers, because it answers the same kind of question: Layers is what is in the
        // scene, Tools is what you have to work on it with.
        Actions.ShowDialog settingsAction = new Actions.ShowDialog("Settings...", new SettingsDialog());
        if (Platform.isMacOS())
            DesktopIntegration.setPreferencesHandler(e -> settingsAction.actionPerformed(null));
        add(buildToolsMenu(Platform.isMacOS() ? null : settingsAction));

        JMenu movieMenu = new JMenu("Movie");
        movieMenu.setMnemonic(KeyEvent.VK_M);
        movieMenu.add(Actions.PLAY_PAUSE);
        movieMenu.add(Actions.PREVIOUS_FRAME);
        movieMenu.add(Actions.NEXT_FRAME);
        movieMenu.add(Actions.RECORD);
        movieMenu.addSeparator();
        movieMenu.add(Actions.TRIM_START);
        movieMenu.add(Actions.TRIM_END);
        movieMenu.add(Actions.TRIM_RESET);
        add(movieMenu);

        if (Platform.isMacOS()) {
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
        }

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);
        helpMenu.add(new Actions.ShowDialog("Interaction Guide...", new TextDialog("Interaction Guide", interactionHelp(), true)));
        helpMenu.add(new Actions.ShowDialog("Timeline Interaction...", new TextDialog("Timeline Interaction", timelineHelp(), true)));

        Actions.ShowDialog aboutAction = new Actions.ShowDialog("About HFStudio...", new AboutDialog());
        if (Platform.isMacOS()) {
            DesktopIntegration.setAboutHandler(e -> aboutAction.actionPerformed(null));
        } else {
            helpMenu.add(aboutAction);
        }

        helpMenu.add(new Actions.OpenURLinBrowser("Open User Manual", AppInfo.documentationURL));
        helpMenu.add(new Actions.OpenURLinBrowser("Open Change Log", "https://github.com/GillySpace27/HFStudio/blob/master/changelog.md"));
        helpMenu.add(new Actions.CheckForUpdates());
        helpMenu.addSeparator();
        helpMenu.add(new Actions.ShowDialog("Show Log...", new LogDialog()));
        JMenuItem probe = new JMenuItem("Report Clipped Controls");
        probe.setToolTipText("List every control on screen that is narrower than it asked to be, into the log");
        probe.addActionListener(e -> org.helioviewer.jhv.gui.LayoutProbe.logReport());
        helpMenu.add(probe);
        helpMenu.add(new Actions.OpenURLinBrowser("Report Bug/Request Feature", AppInfo.bugURL));

        add(helpMenu);
    }

    /**
     * Everything about presentation mode, in one place.
     *
     * <p>The two screen choices were already here as top-level View items; the three below are
     * about the same mode and would have been a second place to look. They apply only on ONE
     * screen, and say so, because with a second display nothing is hidden in the first place: the
     * chrome is lent to a presenter window where both sidebars and every palette already are.
     */
    private static JMenu presentationMenu() {
        JMenu menu = new JMenu("Presentation");
        menu.add(screenMenu("Output Display", PresentationMode.OUTPUT_SCREEN));
        menu.add(screenMenu("Controls Display", PresentationMode.CONTROLS_SCREEN));
        menu.addSeparator();

        JMenuItem heading = new JMenuItem("On a single screen, keep:");
        heading.setEnabled(false);
        menu.add(heading);
        menu.add(keepItem("Left sidebar", PresentationMode.KEEP_LEFT, false,
                "Leave the layer list up over the slide, so the talk can be driven without leaving the mode"));
        menu.add(keepItem("Right sidebar", PresentationMode.KEEP_RIGHT, false,
                "Leave the docked palettes up over the slide"));
        menu.add(keepItem("Floating palettes", PresentationMode.KEEP_PALETTES, true,
                "Windowed palettes stay on screen. Off, they are hidden while presenting and come back afterwards"));
        return menu;
    }

    private static JCheckBoxMenuItem keepItem(String label, String key, boolean fallback, String tip) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, PresentationMode.flag(key, fallback));
        item.setToolTipText(tip);
        item.addActionListener(e -> PresentationMode.setFlag(key, item.isSelected()));
        return item;
    }

    // The built-in themes plus whatever the customizer has saved, rebuilt each time the menu opens.
    // Rebuilt rather than built once because the list itself grows: a theme saved in the customizer
    // has to appear here without a restart, and the radio has to follow a switch made from there.
    private static JMenu themeMenu() {
        JMenu menu = new JMenu("Theme");
        menu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                menu.removeAll();
                ButtonGroup group = new ButtonGroup();
                String current = Theme.current().id();
                for (Theme theme : Theme.all()) {
                    JRadioButtonMenuItem item = new JRadioButtonMenuItem(theme.name(), theme.id().equals(current));
                    item.addActionListener(a -> UIGlobals.switchTheme(theme));
                    group.add(item);
                    menu.add(item);
                }
                menu.addSeparator();
                // The same setting the Settings dialog offers. Here because this menu is where a
                // theme is picked, and a pick leaves Follow system: without a way back, following
                // the desktop would be a one-way door for anyone who never opens Settings.
                javax.swing.JCheckBoxMenuItem follow =
                        new javax.swing.JCheckBoxMenuItem("Follow System Appearance", Theme.mode() == Theme.Mode.System);
                follow.setEnabled(org.helioviewer.jhv.app.SystemAppearance.available());
                follow.setToolTipText(follow.isEnabled()
                        ? "Use the dark theme while the desktop is dark and the light one while it is light. Set the pair in Settings."
                        : "This desktop does not report whether it is set to dark or light.");
                follow.addActionListener(a -> {
                    Theme.setMode(follow.isSelected() ? Theme.Mode.System
                            : Theme.current().dark() ? Theme.Mode.Dark : Theme.Mode.Light);
                    UIGlobals.applyThemeMode();
                });
                menu.add(follow);
                menu.addSeparator();
                javax.swing.JMenuItem customize = new javax.swing.JMenuItem("Customize Themes...");
                customize.addActionListener(a -> new ThemeDialog().showDialog());
                menu.add(customize);
            }

            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        return menu;
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


    // Pick which physical display carries the presentation output and which carries the
    // controls. Rebuilt every time the menu opens: displays get plugged and unplugged mid-talk,
    // and a menu built once at startup would offer a projector that is no longer there (or miss
    // the one that is). "Automatic" keeps the default rule -- output on the non-main display,
    // controls on the main one.
    private static JMenu screenMenu(String title, String key) {
        JMenu menu = new JMenu(title);
        menu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                menu.removeAll();
                ButtonGroup group = new ButtonGroup();
                String current = PresentationMode.preference(key);

                JRadioButtonMenuItem auto = new JRadioButtonMenuItem("Automatic");
                auto.setSelected(current.isEmpty());
                auto.addActionListener(a -> PresentationMode.setPreference(key, ""));
                group.add(auto);
                menu.add(auto);
                menu.addSeparator();

                for (PresentationMode.Screen screen : PresentationMode.screens()) {
                    JRadioButtonMenuItem item = new JRadioButtonMenuItem(screen.label());
                    item.setSelected(screen.id().equals(current));
                    item.addActionListener(a -> PresentationMode.setPreference(key, screen.id()));
                    group.add(item);
                    menu.add(item);
                }
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {}

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        return menu;
    }


    private static JCheckBoxMenuItem clippingItem;

    /** The palette changed Display.showClipping; make the menu say so too. */
    static void syncClippingItem() {
        if (clippingItem != null && clippingItem.getState() != Display.showClipping)
            clippingItem.setState(Display.showClipping);
    }

    private static String interactionHelp() {
        String shortcut = "⌘ / Ctrl";
        return "<div style='width:480px'>" + """
                <b>Image canvas</b>
                Click the canvas to give it keyboard focus.
                <b>Double-click</b>: reset the view. <b>Right-click</b>: copy the displayed time and cursor coordinates.
                <b>Shift + drag</b>: draw an annotation with the selected annotation tool.
                <b>Shift + N / P</b>: select the next / previous annotation.
                <b>Shift + Delete / Backspace</b>: delete the selected annotation.
                <b>Hold X, Y, or Z</b>: choose the axis in Rotate Axis mode. Release to restore the default (normally Y).
                
                <b>Movie slider and layers</b>
                With the movie slider focused: <b>Space</b>: play/pause; <b>Left / Right</b>: previous / next frame.
                <b>Scroll over the movie slider</b>: step through frames.
                On the slider: <b>⌥ / Alt + drag</b>: trim the nearest range boundary; <b>%1$s + drag</b>: move the playback range.
                In the layer list: <b>%1$s + C</b>: copy the selected layer's time.
                <b>Double-click an image layer's name or time</b>: change its dataset. Drag layer rows to reorder them.
                
                <b>Adjustment controls</b>
                <b>Scroll over a slider or numeric spinner</b>: adjust its value.
                <b>Double-click an adjustment slider</b>: reset it. Drag between a range slider's handles to move both together.
                
                <b>Timeline graph</b>
                See <b>Help → Timeline Interaction</b> for graph, axis, and overview controls.
                
                <b>Dataset selection</b>
                <b>%1$s + click</b>: add or remove individual selections.
                <b>Shift + click</b>: select a range. Change Dataset accepts only one dataset.
                
                <b>Type to search</b>
                Focus a dataset tree or list, the colormap drop-down, or the FITS metadata table, then type.
                Search ignores case and matches the beginning of a name. Use <b>*text</b> to find text anywhere; <b>?</b> matches one character.
                Image and timeline dataset trees also search collapsed branches.
                
                <b>While the search popup is open</b>
                <b>Up / Down</b>: previous / next match. <b>Home / End</b>: first / last match.
                <b>Backspace</b>: edit the search. <b>Esc</b>: close the search popup.
                Where multiple selection is allowed:
                <b>%1$s + Up / Down</b>: add the previous / next match to the selection.
                <b>%1$s + A</b>: select all matches for the current search.
                """.formatted(shortcut) + "</div>";
    }

    private static String timelineHelp() {
        return "<div style='width:500px'>" + """
                <b>Plot and movie time</b>
                <b>Click an empty part of the plot</b>: seek the movie to that time.
                <b>Drag the vertical movie-time marker</b>: scrub through movie frames.
                <b>Drag elsewhere</b>: pan time horizontally and value scales vertically.
                Click an interactive event or feature to activate it instead of seeking the movie.
                
                <b>Wheel or trackpad scrolling over the plot</b>
                <b>Scroll</b>: zoom time around the pointer.
                <b>Shift + scroll</b>: pan time.
                <b>⌥ / Alt + scroll</b>: zoom values only.
                <b>Ctrl + scroll</b>: zoom time and values together.
                Over the time-axis labels, scrolling zooms time even with modifiers held.
                Over a value-axis label area, scrolling zooms that value axis without a modifier.
                
                <b>Which value scales are affected?</b>
                In overlaid mode, vertical dragging or value zooming over the plot affects all value axes. Start over a value-axis label area to target that axis.
                In stacked mode, value adjustments affect only the plot under the pointer (or where the drag started). Time is shared by all plots.
                <b>Double-click</b> in an overlaid plot to fit value scales to the visible data; double-click a value-axis label area to restore its default scale.
                In stacked mode, double-click a plot or its value axis to restore that plot's default scale.
                
                <b>Overview strip below the plot</b>
                The highlighted interval is the time range shown in the plot.
                <b>Drag the highlighted interval</b>: move the range without changing its duration.
                <b>Left-click</b>: center the range on the clicked time.
                <b>Right-click left / right of the range center</b>: shift the range to later / earlier times.
                
                <b>Timeline controls and layers</b>
                The range selector chooses a preset duration, the movie interval, or the maximum interval.
                <b>Synchronize movie with time series</b>: apply timeline range changes to the image movie's time range.
                <b>Stack layers vertically</b>: give each layer its own plot and value scale; scroll the panel when plots do not fit.
                Select a layer to show its options. Its visibility icon shows or hides it; its remove icon removes it when allowed.
                For datasets with level colors, click the color swatch to switch between multicolor and single-color rendering.
                """ + "</div>";
    }

}
