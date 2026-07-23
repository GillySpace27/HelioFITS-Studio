package org.helioviewer.jhv.gui;

import java.awt.FileDialog;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;

import javax.swing.AbstractAction;
import javax.swing.JOptionPane;
import javax.swing.JFrame;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.ExitHooks;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.dialog.AspiicsDialog;
import org.helioviewer.jhv.gui.dialog.LoadStateDialog;
import org.helioviewer.jhv.gui.dialog.NewVersionDialog;
import org.helioviewer.jhv.gui.dialog.ObservationDialog;
import org.helioviewer.jhv.gui.dialog.PunchDialog;
import org.helioviewer.jhv.gui.dialog.SoarDialog;
import org.helioviewer.jhv.gui.dialog.SynopticDialog;
import org.helioviewer.jhv.io.DataSources;
import org.helioviewer.jhv.io.ExtensionFileFilter;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.timelines.band.BandReaderHapi;

@SuppressWarnings({"serial", "this-escape"})
public final class Actions {

    public static final AbstractAction PLAY_PAUSE = new PlayPauseAction();
    public static final AbstractAction PREVIOUS_FRAME = new PreviousFrameAction();
    public static final AbstractAction NEXT_FRAME = new NextFrameAction();
    public static final AbstractAction TRIM_START = new TrimStartAction();
    public static final AbstractAction TRIM_END = new TrimEndAction();
    public static final AbstractAction TRIM_RESET = new TrimResetAction();

    // Set the movie's in/out trim points to the current frame; shared by the top scrubber and the
    // bottom timeline so you can scrub to a moment (e.g. an instrument coming online in the coverage
    // track) and trim there with the same keys. The I/O keys are bound on the timeline components
    // (focused-only) rather than as window-wide menu accelerators, so they never hijack text typing.
    private static class TrimStartAction extends AbstractAction {
        TrimStartAction() {
            super("Trim Start Here (I)");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.trimStartHere();
        }
    }

    private static class TrimEndAction extends AbstractAction {
        TrimEndAction() {
            super("Trim End Here (O)");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.trimEndHere();
        }
    }

    private static class TrimResetAction extends AbstractAction {
        TrimResetAction() {
            super("Reset Trim");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.trimReset();
        }
    }

    public abstract static class AbstractKeyAction extends AbstractAction {
        public AbstractKeyAction(String name, KeyStroke key) {
            super(name);
            putValue(ACCELERATOR_KEY, key);
        }
    }

    public static class ClearAnnotations extends AbstractAction {
        public ClearAnnotations() {
            super("Clear Annotations");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Annotations.clear();
            DisplayController.display();
        }
    }

    // Opens/closes the projection palette, the same toggle as the toolbar Projection button.
    public static class ShowProjectionPalette extends AbstractAction {
        public ShowProjectionPalette() {
            super("Projection…");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            org.helioviewer.jhv.gui.component.ToolBar.toggleProjectionPalette();
        }
    }

    public static class TrackCME extends AbstractAction {
        public TrackCME() {
            super("Track CME...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            org.helioviewer.jhv.event.info.CactusTrackDialog.open();
        }
    }

    public static class ExitProgram extends AbstractKeyAction {
        public ExitProgram() {
            super("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (ExitHooks.exitProgram())
                System.exit(0);
        }
    }

    public static class LoadState extends AbstractAction {
        public LoadState() {
            super("Load State...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            File state = LoadStateDialog.get();
            if (state != null) {
                Commands.loadState(state.toURI());
                org.helioviewer.jhv.app.Session.setSessionFile(state, true); // this window now IS that project
            }
        }
    }

    public static class NewLayer extends AbstractKeyAction {
        public NewLayer() {
            super("New Image Layer...", KeyStroke.getKeyStroke(KeyEvent.VK_N, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            ObservationDialog.getInstance().showDialog(true, null);
        }
    }

    public static class NewSoarLayer extends AbstractAction {
        public NewSoarLayer() {
            super("New SOAR Layer...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            SoarDialog.getInstance().showDialog();
        }
    }

    public static class NewSynopticLayer extends AbstractAction {
        public NewSynopticLayer() {
            super("New Synoptic Layer...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            SynopticDialog.getInstance().showDialog();
        }
    }

    public static class NewPunchLayer extends AbstractAction {
        public NewPunchLayer() {
            super("New PUNCH Layer...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            PunchDialog.getInstance().showDialog();
        }
    }

    public static class NewAspiicsLayer extends AbstractAction {
        public NewAspiicsLayer() {
            super("New ASPIICS Layer...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            AspiicsDialog.getInstance().showDialog();
        }
    }

    public static class NewPointCloudLayer extends AbstractAction {
        public NewPointCloudLayer() {
            super("New Point Cloud Layer...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // Pick the file(s) up front, like the other "New … Layer" actions. Multi-select so a whole
            // folder of per-frame clouds loads into ONE layer as a time series (the layer keys them by
            // timestamp and shows the one nearest the movie time) — select all the files in the folder.
            FileDialog fileDialog = new FileDialog(MainFrame.get(), "Choose point cloud file(s) — select several for a time series", FileDialog.LOAD);
            fileDialog.setMultipleMode(true);
            fileDialog.setFilenameFilter((dir, name) -> {
                String n = name.toLowerCase(java.util.Locale.ROOT);
                return n.endsWith(".json") || n.endsWith(".json.gz");
            });
            fileDialog.setDirectory(Settings.getProperty("path.local"));
            fileDialog.setVisible(true);

            File[] files = fileDialog.getFiles();
            if (files.length > 0) {
                Settings.setProperty("path.local", fileDialog.getDirectory()); // remember the current directory for future
                org.helioviewer.jhv.plugins.pointcloud.PointCloudLayer layer = new org.helioviewer.jhv.plugins.pointcloud.PointCloudLayer(null);
                org.helioviewer.jhv.layers.Layers.add(layer);
                for (File f : files) // all into the one layer -> a time series
                    layer.load(f.toURI());
            }
        }
    }

    public static class OpenLocalFile extends AbstractKeyAction {
        public OpenLocalFile() {
            super("Open Image Layer...", KeyStroke.getKeyStroke(KeyEvent.VK_O, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            FileDialog fileDialog = new FileDialog(MainFrame.get(), "Choose a file", FileDialog.LOAD);
            // does not work on Windows
            fileDialog.setFilenameFilter(ExtensionFileFilter.Image);
            fileDialog.setMultipleMode(true);
            fileDialog.setDirectory(Settings.getProperty("path.local"));
            fileDialog.setVisible(true);

            String directory = fileDialog.getDirectory();
            File[] fileNames = fileDialog.getFiles();
            if (fileNames.length > 0 && directory != null) {
                Settings.setProperty("path.local", directory); // remember the current directory for future
                ArrayList<URI> uris = new ArrayList<>(fileNames.length);
                for (File f : fileNames) {
                    if (f.isFile() && f.canRead()) // cannot select directories anyway
                        uris.add(f.toURI());
                }
                Commands.loadImage(uris);
            }
        }
    }

    public static class OpenURLinBrowser extends AbstractAction {
        private final String urlToOpen;

        public OpenURLinBrowser(String name, String url) {
            super(name);
            urlToOpen = url;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            DesktopIntegration.openURL(urlToOpen);
        }
    }

    public static class Paste extends AbstractKeyAction {
        public Paste() {
            super("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            TransferAccess.readClipboard();
        }
    }

    public static class ResetCamera extends AbstractAction {
        public ResetCamera() {
            super("Reset Camera");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.resetView();
        }
    }

    public static class ReloadSources extends AbstractKeyAction {
        public ReloadSources() {
            super("Reload Datasets Listings", KeyStroke.getKeyStroke(KeyEvent.VK_R, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            DataSources.loadSources(false);
            BandReaderHapi.requestCatalog();
        }
    }

    public static class ResetCameraAxis extends AbstractAction {
        public ResetCameraAxis() {
            super("Reset Camera Axis");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.resetViewAxis();
        }
    }

    public static class SeparateMultiviewZoom extends AbstractAction {
        public SeparateMultiviewZoom() {
            super("Separate Multiview Zoom");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Display.setSeparateViewportZoom(!Display.separateViewportZoom);
        }
    }

    public static class Rotate90Camera extends AbstractAction {
        private final String axis;

        public Rotate90Camera(String name, String _axis) {
            super(name);
            axis = _axis;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.rotateView90(axis);
        }
    }

    private static class PlayPauseAction extends AbstractKeyAction {
        PlayPauseAction() {
            super("Play/Pause Movie", KeyStroke.getKeyStroke(KeyEvent.VK_P, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.togglePlayback();
        }
    }

    private static class PreviousFrameAction extends AbstractKeyAction {
        PreviousFrameAction() {
            super("Step to Previous Frame", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, DesktopIntegration.menuShortcutMask | InputEvent.ALT_DOWN_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (Player.isPlaying())
                Commands.pause();
            Commands.previousFrame();
        }
    }

    private static class NextFrameAction extends AbstractKeyAction {
        NextFrameAction() {
            super("Step to Next Frame", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, DesktopIntegration.menuShortcutMask | InputEvent.ALT_DOWN_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (Player.isPlaying())
                Commands.pause();
            Commands.nextFrame();
        }
    }

    public static class SaveState extends AbstractKeyAction {
        public SaveState() {
            super("Save State", KeyStroke.getKeyStroke(KeyEvent.VK_S, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            State.save(Settings.getProperty("path.state"), "state__" + TimeUtils.formatFilename(System.currentTimeMillis()) + ".jhv");
            org.helioviewer.jhv.app.Session.markSaved();
        }
    }

    public static class SaveStateAs extends AbstractKeyAction {
        public SaveStateAs() {
            super("Save State As...", KeyStroke.getKeyStroke(KeyEvent.VK_S, DesktopIntegration.menuShortcutMask | InputEvent.ALT_DOWN_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            FileDialog fileDialog = new FileDialog(MainFrame.get(), "Save as...", FileDialog.SAVE);
            // does not work on Windows
            fileDialog.setFilenameFilter(ExtensionFileFilter.JHV);
            fileDialog.setMultipleMode(false);
            fileDialog.setDirectory(Settings.getProperty("path.state"));
            fileDialog.setFile(org.helioviewer.jhv.app.Session.suggestedSaveName() + ".jhv");
            fileDialog.setVisible(true);

            String directory = fileDialog.getDirectory();
            String file = fileDialog.getFile();
            if (directory != null && file != null) {
                Settings.setProperty("path.state", directory); // remember the current directory for future
                if (!file.toLowerCase().endsWith(".jhv"))
                    file += ".jhv";
                State.save(directory, file);
                org.helioviewer.jhv.app.Session.setSessionFile(new java.io.File(directory, file), true);
            }
        }
    }

    // Opens another JHelioviewer window as a separate process ("jhv.secondary"), so the user can
    // start a new session without closing the current one — like opening a second Excel window.
    // Extra windows are scratch sessions (no autosave/restore); only the primary keeps continuity.
    // The persistent FileCache is shared across processes, so no dataset is re-downloaded.
    public static class NewWindow extends AbstractKeyAction {
        private static final long GB = 1024L * 1024 * 1024;

        public NewWindow() {
            super("Open New Window", KeyStroke.getKeyStroke(KeyEvent.VK_N, DesktopIntegration.menuShortcutMask | InputEvent.SHIFT_DOWN_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (!confirmMemory())
                return;
            org.helioviewer.jhv.app.Session.spawnWindow(org.helioviewer.jhv.app.Session.newWindowFile());
        }

        // Standard-practice guardrail (cf. Excel warning on low resources): if free physical
        // memory is tight, confirm before adding another process instead of risking an OOM.
        private static boolean confirmMemory() {
            try {
                java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                    long free = os.getFreeMemorySize();
                    if (free < 2 * GB) {
                        int r = JOptionPane.showConfirmDialog(MainFrame.get(),
                                String.format("Only %.1f GB of memory is free. Opening another window may run the machine short. Open it anyway?", free / (double) GB),
                                "Low memory", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                        return r == JOptionPane.OK_OPTION;
                    }
                }
            } catch (Throwable ignore) {} // never block the feature on a metrics hiccup
            return true;
        }
    }

    // Clears all loaded image layers and annotations for a fresh start. The session autosaves
    // the now-empty state, so the previous auto-restore is replaced; named .jhv saves are untouched.
    public static class NewSession extends AbstractAction {
        public NewSession() {
            super("Start New Session");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (!Layers.getImageLayers().isEmpty()) {
                int r = JOptionPane.showConfirmDialog(MainFrame.get(),
                        "Clear all loaded layers and start a new session?",
                        "New Session", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.OK_OPTION)
                    return;
            }
            for (ImageLayer layer : new ArrayList<>(Layers.getImageLayers()))
                Layers.remove(layer);
            Annotations.clear();
            org.helioviewer.jhv.app.Session.resetToUntitled(); // clear the name back to Untitled
        }
    }

    public static class CloseWindow extends AbstractKeyAction {
        public CloseWindow() {
            super("Close Window", KeyStroke.getKeyStroke(KeyEvent.VK_W, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (ExitHooks.exitProgram(true)) // close THIS window (drop it from the reopen set)
                System.exit(0);
        }
    }

    // Reload the current session file, discarding unsaved changes (standard "Revert to Saved").
    public static class RevertToSaved extends AbstractAction {
        public RevertToSaved() {
            super("Revert to Saved");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            java.io.File f = org.helioviewer.jhv.app.Session.currentSessionFile();
            if (f == null || !f.isFile())
                return;
            int r = JOptionPane.showConfirmDialog(MainFrame.get(),
                    "Discard changes and revert to the last saved state?",
                    "Revert to Saved", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.OK_OPTION)
                Commands.loadState(f.toURI());
        }
    }

    // One Open Recent entry.
    public static class OpenRecent extends AbstractAction {
        private final java.io.File file;

        public OpenRecent(java.io.File _file) {
            super(_file.getName());
            file = _file;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.loadState(file.toURI());
            org.helioviewer.jhv.app.Session.setSessionFile(file, true);
        }
    }

    public static class ClearRecents extends AbstractAction {
        public ClearRecents() {
            super("Clear Menu");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            org.helioviewer.jhv.app.Session.clearRecents();
        }
    }

    // Pins the current session as the default that auto-loads on every launch (the user's
    // "set this as the default dataset"). Writes a stable default.jhv and points startup at it,
    // taking precedence over the auto-restored last session.
    public static class SetDefaultSession extends AbstractAction {
        public SetDefaultSession() {
            super("Set Current Session as Default");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            String dir = Settings.getProperty("path.state");
            if (dir == null)
                dir = org.helioviewer.jhv.io.Directories.STATES.getPath();
            String file = "default.jhv";
            State.saveNow(dir, file);
            Settings.setProperty("startup.loadState", java.nio.file.Path.of(dir, file).toString());
            org.helioviewer.jhv.app.Message.warn("Default session", "This session will load automatically on startup.");
        }
    }

    // Clears the pinned default; startup falls back to auto-restoring the last session.
    public static class ClearDefaultSession extends AbstractAction {
        public ClearDefaultSession() {
            super("Clear Default Session");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Settings.setProperty("startup.loadState", "false");
            org.helioviewer.jhv.app.Message.warn("Default session", "Startup will reopen your last session instead.");
        }
    }

    public static class SDOCutOut extends AbstractAction {
        public SDOCutOut() {
            super("SDO Cut-out");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            String baseURL = "https://www.lmsal.com/get_aia_data/?";
            DesktopIntegration.openURL(baseURL + ImageLayers.getSDOCutoutString());
        }
    }

    public static class ShowDialog extends AbstractAction {
        private final Interfaces.ShowableDialog dialog;

        public ShowDialog(String name, Interfaces.ShowableDialog _dialog) {
            super(name);
            dialog = _dialog;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            dialog.showDialog();
        }
    }

    public static class CheckForUpdates extends AbstractAction {
        public CheckForUpdates() {
            super("Check for Updates...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            NewVersionDialog.check();
        }
    }

    public static class WindowMinimize extends AbstractKeyAction {
        public WindowMinimize() {
            super("Minimize", KeyStroke.getKeyStroke(KeyEvent.VK_M, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int state = MainFrame.get().getExtendedState();
            state ^= JFrame.ICONIFIED;
            MainFrame.get().setExtendedState(state);
        }
    }

    public static class WindowZoom extends AbstractAction {
        public WindowZoom() {
            super("Zoom");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int state = MainFrame.get().getExtendedState();
            state ^= JFrame.MAXIMIZED_BOTH;
            MainFrame.get().setExtendedState(state);
        }
    }

    public static class ZoomFit extends AbstractKeyAction {
        public ZoomFit() {
            super("Zoom to Fit", KeyStroke.getKeyStroke(KeyEvent.VK_9, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.zoomFit();
        }
    }

    public static class ZoomFOVAnnotation extends AbstractAction {
        public ZoomFOVAnnotation() {
            super("Fit FOV Annotation");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Annotations.zoom();
            DisplayController.render(1);
        }
    }

    public static class ZoomIn extends AbstractKeyAction {
        public ZoomIn() {
            super("Zoom In", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.zoomIn();
        }
    }

    public static class ZoomOneToOne extends AbstractKeyAction {
        public ZoomOneToOne() {
            super("Actual Size", KeyStroke.getKeyStroke(KeyEvent.VK_0, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.zoomOneToOne();
        }
    }

    public static class ZoomOut extends AbstractKeyAction {
        public ZoomOut() {
            super("Zoom Out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, DesktopIntegration.menuShortcutMask));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Commands.zoomOut();
        }
    }

    private Actions() {}
}
