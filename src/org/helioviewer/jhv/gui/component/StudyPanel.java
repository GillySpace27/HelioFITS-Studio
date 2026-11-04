package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.display.StudyView;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.StudyCells.Col;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.study.Study;
import org.json.JSONObject;

/**
 * The Study panel: the session's parameter study as a table, one row per variant. Selecting a row
 * previews it on the canvas (Commands.setView); clearing the selection or closing the panel puts
 * the scene back (Commands.restoreView) from the copy taken when the preview began.
 */
@SuppressWarnings({"serial", "this-escape"})
public final class StudyPanel extends JDialog {

    /** What Export set calls, with a copy of the study holding only its valid, enabled rows. */
    @FunctionalInterface
    public interface ExportHook {
        void export(Study study);
    }

    /** Settings key: preview a row on the canvas when it is selected. Default on (Settings defaults). */
    public static final String PREVIEW_KEY = "study.previewOnSelect";
    /** Save this view: the screenshot key (Record, menu-R) with Alt, as Save Session As is Save Session with Alt. */
    public static final KeyStroke SAVE_VIEW_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_R, DesktopIntegration.menuShortcutMask | InputEvent.ALT_DOWN_MASK);

    private static final long SAVE_DEBOUNCE_NANOS = 300_000_000L; // the menu and the panel can both see one key press
    private static final AtomicReference<ExportHook> EXPORT = new AtomicReference<>();
    private static final AtomicReference<StudyPanel> PANEL = new AtomicReference<>();
    private static final AtomicLong LAST_SAVE = new AtomicLong();

    /** Connects Export set; until the study runner sets this, the button reports that it is not connected. */
    public static void setExportHook(ExportHook hook) {
        EXPORT.set(hook);
    }

    public static Action openAction() {
        return new AbstractAction("Study...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        };
    }

    public static Action saveViewAction() {
        AbstractAction a = new AbstractAction("Save View to Study") {
            @Override
            public void actionPerformed(ActionEvent e) {
                open(false).saveView();
            }
        };
        a.putValue(Action.ACCELERATOR_KEY, SAVE_VIEW_KEY);
        return a;
    }

    public static StudyPanel open() {
        return open(true);
    }

    /** @param ask offer the template-or-empty choice when there is no study yet (Save View skips it) */
    public static StudyPanel open(boolean ask) {
        StudyPanel p = PANEL.get();
        if (p == null) {
            p = new StudyPanel();
            PANEL.set(p);
        }
        if (!p.isVisible()) {
            Study cur = Study.CURRENT.get();
            if (ask && (cur == null || cur.variants.isEmpty()) && !p.chooseStart())
                return p;
            p.reload();
            p.pack();
            p.setLocationRelativeTo(MainFrame.get());
            p.setVisible(true);
        } else
            p.toFront();
        return p;
    }

    private static boolean previewOn() {
        return Boolean.parseBoolean(Settings.getProperty(PREVIEW_KEY));
    }

    /** The image layers by name, on when drawn: enabled and not hidden by a previewed row. */
    private static Map<String, Boolean> sceneLayers() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (ImageLayer l : Layers.getImageLayers())
            m.put(l.getName(), l.isEnabled() && !StudyView.hides(l));
        return m;
    }

    private final JTable table = new JTable();
    private final JLabel countLine = new JLabel(" ");
    private final JLabel reasonLine = new JLabel(" ");
    private final JButton update = new JButton("Update row from view");
    private final JButton apply = new JButton("Apply row to view");
    private final JButton duplicate = new JButton("Duplicate");
    private final JButton delete = new JButton("Delete");
    private final JButton export = new JButton("Export set");
    private StudyTableModel model;
    private JSONObject sceneCopy;
    private boolean previewing;

    private StudyPanel() {
        super(MainFrame.get(), "Study", false);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        table.setDefaultRenderer(String.class, new StudyRowEditor.Renderer());
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setDragEnabled(true);
        table.setDropMode(DropMode.INSERT_ROWS);
        table.setTransferHandler(new RowMover());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                selectionChanged();
        });

        JButton save = new JButton("Save this view");
        save.setToolTipText("Add a row holding every value of the view on screen (" + KeyEvent.getModifiersExText(SAVE_VIEW_KEY.getModifiers())
                + "+" + KeyEvent.getKeyText(SAVE_VIEW_KEY.getKeyCode()) + ")");
        save.addActionListener(e -> saveView());
        update.setToolTipText("Replace the selected row's view values with the view on screen");
        update.addActionListener(e -> updateRow());
        apply.setToolTipText("Make the selected row the scene's view (undoable)");
        apply.addActionListener(e -> applyRow());
        duplicate.addActionListener(e -> {
            int r = selectedRow();
            if (r >= 0)
                select(model.duplicate(r));
        });
        delete.addActionListener(e -> deleteRows());
        JButton paste = new JButton("Paste rows");
        paste.setToolTipText("Append tab-separated rows from the clipboard; the first line names the columns");
        paste.addActionListener(e -> pasteRows());
        export.setToolTipText("Export every enabled row without an error");
        export.addActionListener(e -> exportSet());
        JCheckBox preview = new JCheckBox("Preview the selected row", previewOn());
        preview.addActionListener(e -> {
            Settings.setProperty(PREVIEW_KEY, Boolean.toString(preview.isSelected()));
            selectionChanged();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton preset = new JButton("Load template");
        preset.setToolTipText("Replace the rows with a ready-made set, such as the lambda ladder");
        preset.addActionListener(e -> loadPreset(preset));
        JButton copy = new JButton("Copy command");
        copy.setToolTipText("Copy the command that runs this study headless");
        copy.addActionListener(e -> copyCommand());
        JButton jobs = new JButton("Task Manager...");
        jobs.setToolTipText("The export queue and everything else running (also: click the footer's spinner)");
        jobs.addActionListener(e -> JobQueuePanel.open());
        for (JComponent c : new JComponent[]{save, update, apply, duplicate, delete, paste, preset, copy, export, jobs})
            buttons.add(c);
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{countLine, reasonLine, buttons, preview}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            south.add(c);
        }
        countLine.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
        reasonLine.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 6));
        reasonLine.setForeground(java.awt.Color.RED);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(980, 260));
        JLabel how = new JLabel("<html>Each row is one picture of this scene. Grey cells inherit the scene's value; type to override, clear to inherit.<br>"
                + "1. Start from a template (Load template) or set up the view and press Save this view. "
                + "2. Click a row to preview it, edit its cells. 3. Export set: the run goes to the Task Manager.</html>");
        how.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        getContentPane().add(how, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> saveView(), SAVE_VIEW_KEY, JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closing();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                closing();
            }
        });
    }

    private void closing() {
        if (table.isEditing())
            table.getCellEditor().stopCellEditing();
        table.clearSelection();
        restorePreview();
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible)
            closing();
        super.setVisible(visible);
    }

    /** A fresh model on the session's study, which may have changed since the panel last closed. */
    private void reload() {
        restorePreview();
        Study s = Study.CURRENT.get();
        sceneCopy = Commands.getView();
        model = new StudyTableModel(s == null ? new Study() : s, sceneCopy, sceneLayers());
        model.addTableModelListener(e -> changed());
        table.setModel(model);
        for (int i = 0; i < model.getColumnCount(); i++) {
            Col c = StudyTableModel.col(i);
            if (c == Col.PROJECTION)
                table.getColumnModel().getColumn(i).setCellEditor(StudyRowEditor.projectionEditor());
            else if (c == Col.LAYERS)
                table.getColumnModel().getColumn(i).setCellEditor(new StudyRowEditor.LayersEditor());
            if (c == Col.LAYERS)
                table.getColumnModel().getColumn(i).setPreferredWidth(200);
        }
        refresh();
    }

    private void changed() {
        Study s = model.study();
        if (Study.CURRENT.get() != s && !s.variants.isEmpty()) // the session takes the study once it has a row
            Study.CURRENT.set(s);
        int r = selectedRow();
        if (previewing && r >= 0)
            Commands.setView(model.resolved(r));
        refresh();
    }

    private void refresh() {
        int r = selectedRow();
        countLine.setText(model.countLine(1));
        if (model.study().sweep() != null)
            countLine.setText(countLine.getText() + "; with the sweep " + org.helioviewer.jhv.study.Sweep.count(model.study()) + " files");
        String why = model.firstReason(r);
        if (why == null)
            why = model.firstReason(-1);
        reasonLine.setText(why == null ? " " : why);
        update.setEnabled(r >= 0);
        apply.setEnabled(r >= 0);
        duplicate.setEnabled(r >= 0);
        delete.setEnabled(r >= 0);
        export.setEnabled(!model.exportable().isEmpty());
    }

    private int selectedRow() {
        int v = table.getSelectedRow();
        return v < 0 || model == null ? -1 : table.convertRowIndexToModel(v);
    }

    private void select(int modelRow) {
        int v = table.convertRowIndexToView(modelRow);
        table.setRowSelectionInterval(v, v);
        table.scrollRectToVisible(table.getCellRect(v, 0, true));
    }

    private void selectionChanged() {
        int r = selectedRow();
        if (r < 0 || !previewOn())
            restorePreview();
        else {
            if (!previewing) { // the scene as it is now, so edits made between previews are kept
                sceneCopy = Commands.getView();
                model.setScene(sceneCopy, sceneLayers());
                previewing = true;
            }
            Commands.setView(model.resolved(r));
        }
        refresh();
    }

    private void restorePreview() {
        if (previewing) {
            previewing = false;
            Commands.restoreView(sceneCopy);
        }
    }

    private void saveView() {
        long now = System.nanoTime();
        if (now - LAST_SAVE.getAndSet(now) < SAVE_DEBOUNCE_NANOS)
            return;
        select(model.addFromView(Commands.getView(), sceneLayers()));
    }

    private void updateRow() {
        int r = selectedRow();
        if (r >= 0)
            model.updateFromView(r, Commands.getView(), sceneLayers());
    }

    /** The row becomes the scene: through the plain setters, so it is one undo step like any edit. */
    private void applyRow() {
        int r = selectedRow();
        if (r < 0)
            return;
        JSONObject view = model.resolved(r);
        JSONObject layers = view.optJSONObject("layers");
        view.remove("layers");
        view.remove("size");
        restorePreview();
        StudyView.set(view);
        if (layers != null)
            for (ImageLayer l : Layers.getImageLayers())
                if (layers.has(l.getName()))
                    l.setEnabled(layers.optBoolean(l.getName(), true));
        sceneCopy = Commands.getView();
        model.setScene(sceneCopy, sceneLayers());
    }

    private void deleteRows() {
        int[] v = table.getSelectedRows();
        if (v.length == 0)
            return;
        int[] m = new int[v.length];
        for (int i = 0; i < v.length; i++)
            m[i] = table.convertRowIndexToModel(v[i]);
        table.clearSelection(); // restores the scene before the previewed row goes
        model.delete(m);
    }

    private void pasteRows() {
        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            reasonLine.setText("The clipboard holds no text to paste.");
            return;
        }
        List<String> problems = model.paste(text);
        if (!problems.isEmpty())
            reasonLine.setText(String.join(" ", problems));
    }

    private void exportSet() {
        ExportHook hook = EXPORT.get();
        restorePreview();
        table.clearSelection();
        if (hook != null)
            hook.export(model.exportStudy());
        else
            enqueue(model.exportStudy());
    }

    /** The default Export set: ask above the run limit, queue the study, show the queue. */
    private static void enqueue(Study study) {
        int count = org.helioviewer.jhv.study.Sweep.count(study);
        int limit = org.helioviewer.jhv.study.Sweep.runLimit();
        boolean confirmed = count <= limit || javax.swing.JOptionPane.showConfirmDialog(MainFrame.get(),
                "This study makes " + count + " files, above the limit of " + limit + ". Run it anyway?", "Large study",
                javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION;
        if (!confirmed)
            return;
        String sha = null;
        java.io.File session = org.helioviewer.jhv.app.Session.currentSessionFile();
        if (session != null && session.isFile()) {
            try {
                sha = org.helioviewer.jhv.study.StudyIO.sha256(session.toPath());
                study.setScene(session.getPath(), sha);
            } catch (IOException e) {
                org.helioviewer.jhv.app.Log.warn("Could not hash the scene", e);
            }
        }
        org.helioviewer.jhv.study.StudyJobs.study(study, null, sha, confirmed);
        JobQueuePanel.open();
    }

    /** The shipped templates: menu label and resource under resources/studies. */
    private static final String[][] PRESETS = {
            {"Lambda ladder (Helioradial 1, 0, -1; Unrolled 0)", "/studies/lambda-ladder.hfstudy.json"}};

    /**
     * Opening with no study: start from a template or from an empty table. False when the person
     * closes the question, and then the panel stays shut.
     */
    private boolean chooseStart() {
        Object[] options = new Object[PRESETS.length + 1];
        for (int i = 0; i < PRESETS.length; i++)
            options[i] = "Template: " + PRESETS[i][0];
        options[PRESETS.length] = "New empty study";
        Object pick = javax.swing.JOptionPane.showInputDialog(MainFrame.get(),
                "Start the study from a template (projections and lambda set, layers inherited from the scene), or from an empty table:",
                "New study", javax.swing.JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (pick == null)
            return false;
        for (int i = 0; i < PRESETS.length; i++)
            if (pick == options[i])
                loadPresetResource(PRESETS[i][1]);
        return true;
    }

    private void loadPreset(JComponent from) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        for (String[] preset : PRESETS) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(preset[0]);
            item.addActionListener(e -> {
                loadPresetResource(preset[1]);
                reload();
            });
            menu.add(item);
        }
        menu.show(from, 0, from.getHeight());
    }

    /** Makes a shipped template the session's study; the scene reference of the old one is kept. */
    private void loadPresetResource(String resource) {
        try (java.io.InputStream in = org.helioviewer.jhv.io.FileUtils.getResource(resource)) {
            Study loaded = Study.fromJson(new JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            Study old = Study.CURRENT.get();
            if (old != null)
                loaded.setScene(old.scene(), old.sceneSha256());
            Study.CURRENT.set(loaded);
        } catch (IOException | RuntimeException ex) {
            reasonLine.setText("The template could not be loaded: " + ex.getMessage());
        }
    }

    private void copyCommand() {
        String cmd = org.helioviewer.jhv.study.StudyRunner.commandLine(model.exportStudy());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(cmd), null);
        reasonLine.setText("Copied: " + cmd);
    }

    /** Drag reorder: a row dropped between two others moves there. */
    private final class RowMover extends TransferHandler {
        private final DataFlavor flavor = new DataFlavor(Integer.class, "Study row");

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int row = selectedRow();
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{flavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return flavor.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (!flavor.equals(f))
                        throw new UnsupportedFlavorException(f);
                    return row;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDrop() && s.isDataFlavorSupported(flavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (!canImport(s))
                return false;
            try {
                int from = (Integer) s.getTransferable().getTransferData(flavor);
                int to = ((JTable.DropLocation) s.getDropLocation()).getRow();
                if (from < 0 || to < 0)
                    return false;
                select(model.move(from, to));
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                return false;
            }
        }
    }
}
