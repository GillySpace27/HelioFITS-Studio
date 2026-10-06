package org.helioviewer.jhv.gui;

import java.awt.Component;
import java.awt.Container;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenu;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Platform;

/**
 * One Action instance per command, under a stable camelCase id.
 *
 * <p>The menus take their actions from here, and every catalogued menu item and toolbar control
 * carries its id as its component name, so help search, guided tours, undo and VoiceOver can name
 * a command once and find it wherever it is drawn. Ids that are also toolbar ids are the toolbar's
 * persisted ids (ToolbarOrder.DEFAULT_ORDER); ids are API, never renamed.
 *
 * <p>The actions hold no component, so the menu bar can be rebuilt around them. Actions.ShowDialog
 * and Actions.OpenRecent are not catalogued: each instance holds its own dialog or file and is built
 * where it is used ({@link #PER_USE}). Later initiatives append one put line each, at the end of
 * register().
 */
public final class ActionCatalog {

    /**
     * One command. {@code menuPath} is the menu names and the item's label joined by '/' (a label may
     * itself contain '/', as "Play/Pause Movie" does), or "" when the command is in no menu.
     * {@code description} is what search shows; put() fills it with the action's name when given "".
     */
    public record Entry(String id, Action action, String menuPath, String description) {}

    /** Actions classes built where they are used, one instance per dialog or file: never catalogued. */
    public static final Set<String> PER_USE = Set.of("ShowDialog", "OpenRecent");

    /**
     * Toolbar ids that name a control (a toggle, a palette, a split button), not a command. They are
     * found by component name, which ToolBar sets to the id; ActionCatalogCheck holds every
     * DEFAULT_ORDER id to be either one of these or a catalogued command.
     */
    public static final Set<String> TOOLBAR_CONTROLS = Set.of(
            "rotate90", "pan", "rotate", "axis", "track", "diffRotation", "corona", "multiview",
            "sidebarLeft", "timelines", "sidebarRight", "projection", "trackCme", "colour", "sequence",
            "grid", "camera", "annotate", "refresh", "samp");

    private static final Map<String, Entry> entries = new LinkedHashMap<>(); // the map is the only state: no static flag (guards.py ratchet)

    /** Builds every catalogued action once. Idempotent; get() and all() call it. */
    public static synchronized void register() {
        if (!entries.isEmpty())
            return;
        boolean mac = Platform.isMacOS();
        // File
        put("newSession", new Actions.NewSession(), "File/Start New Session", "");
        put("openSession", new Actions.LoadState(), "File/Open Session...", "");
        put("clearRecents", new Actions.ClearRecents(), "File/Open Recent/Clear Menu", "Forget the recent sessions list");
        put("closeWindow", new Actions.CloseWindow(), "File/Close Window", "");
        put("saveSession", new Actions.SaveState(), "File/Save Session", "");
        put("saveSessionAs", new Actions.SaveStateAs(), "File/Save Session As...", "");
        put("revertToSaved", new Actions.RevertToSaved(), "File/Revert to Saved", "");
        put("setDefaultSession", new Actions.SetDefaultSession(), "File/Set Current Session as Default", "");
        put("clearDefaultSession", new Actions.ClearDefaultSession(), "File/Clear Default Session", "");
        put("reloadSources", new Actions.ReloadSources(), "File/Reload Datasets Listings", "");
        put("newWindow", new Actions.NewWindow(), mac ? "Window/Open New Window" : "File/Open New Window", "");
        put("quit", new Actions.ExitProgram(), mac ? "" : "File/Quit", ""); // macOS: the application menu's own Quit
        // Edit
        put("paste", new Actions.Paste(), "Edit/Paste", "");
        put("clearAnnotations", new Actions.ClearAnnotations(), "Edit/Clear Annotations", "");
        // View
        put("zoomOne", new Actions.ZoomOneToOne(), "View/Actual Size", "Zoom to native resolution");
        put("zoomFit", new Actions.ZoomFit(), "View/Zoom to Fit", "");
        put("zoomIn", new Actions.ZoomIn(), "View/Zoom In", "");
        put("zoomOut", new Actions.ZoomOut(), "View/Zoom Out", "");
        put("resetAxis", new Actions.ResetCameraAxis(), "View/Reset View Axis", "");
        put("resetCamera", new Actions.ResetCamera(), "View/Reset View", "");
        put("separateMultiviewZoom", new Actions.SeparateMultiviewZoom(), "View/Separate Multiview Zoom", "");
        put("present", new Actions.TogglePresentationMode(), "View/Present", "Presentation mode: output only, fullscreen (Esc to leave)");
        // Layers
        put("newJp2Layer", new Actions.NewLayer(), "Layers/New JP2 Image Layer…", "");
        put("newSynopticLayer", new Actions.NewSynopticLayer(), "Layers/New Synoptic Layer...", "");
        put("newPunchLayer", new Actions.NewPunchLayer(), "Layers/New PUNCH Layer…", "");
        put("newSoarLayer", new Actions.NewSoarLayer(), "Layers/New SOAR Layer…", "");
        put("newAspiicsLayer", new Actions.NewAspiicsLayer(), "Layers/New ASPIICS Layer...", "");
        put("newPointCloudLayer", new Actions.NewPointCloudLayer(), "Layers/New Point Cloud Layer...", "");
        put("openImageLayer", new Actions.OpenLocalFile(), "Layers/Open Image Layer...", "");
        put("openModel", new Actions.OpenModel(), "Layers/Open Model Layer...", "");
        // Tools
        put("findCmes", new Actions.TrackCME(), "Tools/Find CMEs to Track...", "");
        put("findComets", new Actions.TrackComet(), "Tools/Find Comets to Track...", "");
        // Movie: the shared instances the movie panel and the time slider already use
        put("playPause", Actions.PLAY_PAUSE, "Movie/Play/Pause Movie", "");
        put("previousFrame", Actions.PREVIOUS_FRAME, "Movie/Step to Previous Frame", "");
        put("nextFrame", Actions.NEXT_FRAME, "Movie/Step to Next Frame", "");
        put("record", Actions.RECORD, "Movie/Start/Stop Recording", "");
        put("trimStart", Actions.TRIM_START, "Movie/Trim Start Here (I)", "");
        put("trimEnd", Actions.TRIM_END, "Movie/Trim End Here (O)", "");
        put("trimReset", Actions.TRIM_RESET, "Movie/Reset Trim", "");
        // Window (macOS only as a menu)
        put("windowMinimize", new Actions.WindowMinimize(), mac ? "Window/Minimize" : "", "");
        put("windowZoom", new Actions.WindowZoom(), mac ? "Window/Zoom" : "", "");
        // Help
        put("openUserManual", new Actions.OpenURLinBrowser("Open User Manual", AppInfo.documentationURL), "Help/Open User Manual", "");
        put("openChangeLog", new Actions.OpenURLinBrowser("Open Change Log", "https://github.com/GillySpace27/HelioFITS-Studio/blob/master/changelog.md"), "Help/Open Change Log", "");
        put("checkForUpdates", new Actions.CheckForUpdates(), "Help/Check for Updates...", "");
        put("reportBug", new Actions.OpenURLinBrowser("Report Bug/Request Feature", AppInfo.bugURL), "Help/Report Bug/Request Feature", "");
        // Not in a menu: the toolbar's More, its Rotate View 90 dropdown, the Annotation palette
        put("sdoCutout", new Actions.SDOCutOut(), "", "Open LMSAL's AIA cut-out service for the enabled AIA layers and the current view");
        put("rotate90X", new Actions.Rotate90Camera("X Axis", "X"), "", "Rotate the view 90° about X");
        put("rotate90Y", new Actions.Rotate90Camera("Y Axis", "Y"), "", "Rotate the view 90° about Y");
        put("rotate90Z", new Actions.Rotate90Camera("Z Axis", "Z"), "", "Rotate the view 90° about Z");
        put("zoomFovAnnotation", new Actions.ZoomFOVAnnotation(), "", "");
        put("copyProvenance", new Actions.CopyProvenance(), "File/Copy Provenance", "");
        // Guided tour (HS-15): the menu item, and the reveal its steps run before spotlighting
        put("takeTour", org.helioviewer.jhv.gui.search.Tour.action(), "Help/Take the Tour", "Step through the main controls, one at a time");
        put("showImageLayers", new AbstractAction("Show Image Layers") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                org.helioviewer.jhv.gui.component.Palette.open("Image Layers"); // wherever it is docked or floating
            }
        }, "", "Open the Image Layers section, wherever it is docked");
        put("sendFeedback", new org.helioviewer.jhv.gui.dialog.FeedbackDialog.Open(), "Help/Send Feedback...", "Send a problem, a request or a question to the developer; no account needed");
        put("undo", org.helioviewer.jhv.app.SceneUndo.undoAction(), "Edit/Undo", "Undo the last change to the scene");
        put("redo", org.helioviewer.jhv.app.SceneUndo.redoAction(), "Edit/Redo", "Redo the last undone change to the scene");
        put("openExportsFolder", ExportsFolder.action(), "File/Open Exports Folder", "Show the folder movies, screenshots and metadata are exported to");
    }

    /**
     * Only inside register(). An empty description reads the action's own name; a duplicate id is a
     * programming error and stops the catalog from building.
     */
    static void put(String id, Action action, String menuPath, String description) {
        if (entries.containsKey(id))
            throw new IllegalStateException("duplicate action id " + id);
        String text = description.isEmpty() ? String.valueOf(action.getValue(Action.NAME)) : description;
        entries.put(id, new Entry(id, action, menuPath, text));
    }

    @Nullable
    public static synchronized Entry get(String id) {
        register();
        return entries.get(id);
    }

    /** Every entry, in registration order. */
    public static synchronized Collection<Entry> all() {
        register();
        return Collections.unmodifiableCollection(entries.values());
    }

    /** The first component under {@code root}, depth first, whose name is {@code id}; menus are searched through their items. */
    @Nullable
    public static Component find(Container root, String id) {
        if (id.equals(root.getName()))
            return root;
        Component[] children = root instanceof JMenu menu ? menu.getMenuComponents() : root.getComponents();
        for (Component child : children) {
            if (child instanceof Container container) {
                Component hit = find(container, id);
                if (hit != null)
                    return hit;
            } else if (id.equals(child.getName()))
                return child;
        }
        return null;
    }

    /**
     * Writes the catalog as a Markdown table to the file named by the one argument (docs/actions.md).
     * A file rather than standard output: guards.py ratchets System.out prints in src/. Needs a
     * display, because the actions read the platform's menu shortcut mask.
     */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length != 1)
            throw new IllegalArgumentException("usage: ActionCatalog <output.md>");
        Platform.init();
        StringBuilder md = new StringBuilder("# Action ids\n\nGenerated by `ActionCatalog.main` (HS-8). Ids are API: never rename one. "
                + "Toolbar ids that name a control rather than a command: "
                + String.join(", ", new java.util.TreeSet<>(TOOLBAR_CONTROLS)) + ".\n\n| id | menu | description |\n|---|---|---|\n");
        for (Entry e : all())
            md.append("| `").append(e.id()).append("` | ")
                    .append(e.menuPath().isEmpty() ? "(none)" : e.menuPath().replace("|", "\\|"))
                    .append(" | ").append(e.description().replace("|", "\\|")).append(" |\n");
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), md.toString());
    }

    private ActionCatalog() {}
}
