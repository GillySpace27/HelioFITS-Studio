package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.component.ModelPreview;
import org.helioviewer.jhv.io.ModelAssets;
import org.helioviewer.jhv.io.NetFileCache;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.ModelLayer;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.opengl.model.AssimpModelLoader;
import org.helioviewer.jhv.thread.Task;

import com.jidesoft.dialog.ButtonPanel;
import com.jidesoft.dialog.StandardDialog;

/**
 * The 3D Model Manager: spacecraft models to download, look at and add to the scene.
 *
 * <p>The list is {@code resources/data/assets.json} ({@link ModelAssets}); nothing is bundled, and a
 * model arrives only when asked for, checked against its listed SHA-256. A downloaded model shows in
 * the preview on the right, drawn in software ({@link ModelPreview}), so it can be turned over before
 * it goes into the scene. Add as Layer places it at its spacecraft's real position at the size the
 * list gives, or at Sun centre when Studio has no ephemeris for it; the layer's own options change
 * either afterwards.
 */
@SuppressWarnings("serial")
public final class ModelManager extends StandardDialog implements Interfaces.ShowableDialog {

    private final Rows rows = new Rows();
    private final JTable table = new JTable(rows);
    private final ModelPreview preview = new ModelPreview();
    private final JEditorPane info = new JEditorPane("text/html", "");
    private final JLabel status = new JLabel(" ");
    private final Map<String, ModelPreview.Prepared> prepared = new HashMap<>();
    private final AtomicBoolean cancel = new AtomicBoolean();
    @Nullable
    private SwingWorker<Path, Long> downloading;
    @Nullable
    private String downloadingId;

    private final AbstractAction download = new AbstractAction("Download") {
        @Override
        public void actionPerformed(ActionEvent e) {
            if (downloading != null)
                cancel.set(true);
            else
                download();
        }
    };
    private final AbstractAction add = new AbstractAction("Add as Layer") {
        @Override
        public void actionPerformed(ActionEvent e) {
            addLayer();
        }
    };
    private final AbstractAction reveal = new AbstractAction(System.getProperty("os.name").startsWith("Mac") ? "Reveal in Finder" : "Show in Folder") {
        @Override
        public void actionPerformed(ActionEvent e) {
            ModelAssets.Entry s = selected();
            if (s != null)
                DesktopIntegration.reveal(ModelAssets.isPresent(s) ? ModelAssets.file(s).toFile() : ModelAssets.folder().toFile());
        }
    };
    private final AbstractAction page = new AbstractAction("Source Page") {
        @Override
        public void actionPerformed(ActionEvent e) {
            ModelAssets.Entry s = selected();
            if (s != null)
                DesktopIntegration.openURL(s.page());
        }
    };

    private static final class Holder { // made on first use, on the event thread
        static final ModelManager INSTANCE = new ModelManager();
    }

    public static ModelManager get() {
        return Holder.INSTANCE;
    }

    private ModelManager() {
        super(MainFrame.get(), "3D Model Manager");
        setResizable(true);
        try {
            rows.all = ModelAssets.entries();
        } catch (java.io.IOException | org.json.JSONException e) {
            Log.warn("Could not read the model list", e);
            status.setText("Could not read the model list: " + e.getMessage());
        }
    }

    @Override
    public JComponent createBannerPanel() {
        JLabel lede = new JLabel("<html><div style='width:640px'>Spacecraft models from NASA 3D Resources. Each downloads once, is checked against the list, and stays in "
                + esc(ModelAssets.folder().toString()) + ". Select a downloaded model to turn it over in the preview before adding it.</div></html>");
        lede.setBorder(BorderFactory.createEmptyBorder(8, 9, 4, 9));
        return lede;
    }

    @Override
    public JComponent createContentPanel() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                selectionChanged();
        });
        JScrollPane list = new JScrollPane(table);
        list.setPreferredSize(new Dimension(440, 380));

        preview.setPreferredSize(new Dimension(380, 300));
        UIGlobals.themed(preview, c -> c.setBackground(UIGlobals.backColor));
        info.setEditable(false);
        info.addHyperlinkListener(DesktopIntegration.hyperOpenURL);
        JScrollPane infoScroll = new JScrollPane(info);
        infoScroll.setPreferredSize(new Dimension(380, 150));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, preview, infoScroll);
        right.setResizeWeight(0.7);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, right);
        split.setResizeWeight(0.5);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
        panel.add(split, BorderLayout.CENTER);
        panel.add(status, BorderLayout.PAGE_END);
        enableActions();
        return panel;
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction close = new AbstractAction("Close") {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        };
        setDefaultCancelAction(close);
        ButtonPanel panel = new ButtonPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(6, 9, 9, 9));
        panel.setButtonOrder("AC");
        panel.setOppositeButtonOrder("O");
        JButton addButton = new JButton(add);
        panel.add(addButton, ButtonPanel.AFFIRMATIVE_BUTTON);
        panel.add(new JButton(download), ButtonPanel.OTHER_BUTTON);
        panel.add(new JButton(reveal), ButtonPanel.OTHER_BUTTON);
        panel.add(new JButton(page), ButtonPanel.OTHER_BUTTON);
        panel.add(new JButton(close), ButtonPanel.CANCEL_BUTTON);
        getRootPane().setDefaultButton(addButton);
        return panel;
    }

    @Override
    public void showDialog() {
        if (!isVisible()) {
            pack();
            setLocationRelativeTo(MainFrame.get());
        }
        int keep = table.getSelectedRow();
        rows.fireTableDataChanged(); // a download elsewhere may have changed a status
        if (rows.getRowCount() > 0)
            table.setRowSelectionInterval(Math.max(keep, 0), Math.max(keep, 0));
        selectionChanged();
        setVisible(true);
    }

    @Nullable
    private ModelAssets.Entry selected() {
        int r = table.getSelectedRow();
        return r < 0 ? null : rows.all.get(table.convertRowIndexToModel(r));
    }

    private void enableActions() {
        ModelAssets.Entry s = selected();
        boolean present = s != null && ModelAssets.isPresent(s);
        add.setEnabled(present && downloading == null);
        download.setEnabled(downloading != null || (s != null && !present));
        download.putValue(AbstractAction.NAME, downloading != null ? "Cancel Download" : "Download");
        reveal.setEnabled(s != null);
        page.setEnabled(s != null);
    }

    private void selectionChanged() {
        ModelAssets.Entry s = selected();
        enableActions();
        if (s == null) {
            info.setText("");
            preview.show(null, "");
            return;
        }
        info.setText(describe(s));
        info.setCaretPosition(0);
        if (!ModelAssets.isPresent(s)) {
            preview.show(null, "Download to preview (" + megabytes(s.bytes()) + ")");
            return;
        }
        ModelPreview.Prepared done = prepared.get(s.id());
        if (done != null) {
            preview.show(done, "");
            return;
        }
        preview.show(null, "Loading the preview...");
        Path file = ModelAssets.file(s);
        new SwingWorker<ModelPreview.Prepared, Void>() {
            @Override
            protected ModelPreview.Prepared doInBackground() throws Exception {
                return ModelPreview.prepare(AssimpModelLoader.load(NetFileCache.get(file.toUri())));
            }

            @Override
            protected void done() {
                try {
                    ModelPreview.Prepared p = get();
                    prepared.put(s.id(), p);
                    if (s.equals(selected()))
                        preview.show(p, "");
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    Log.warn("Could not preview " + file, cause);
                    if (s.equals(selected()))
                        preview.show(null, "Could not load: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void download() {
        ModelAssets.Entry s = selected();
        if (s == null)
            return;
        cancel.set(false);
        downloadingId = s.id();
        status.setText("Downloading " + s.name() + "...");
        downloading = new SwingWorker<>() {
            @Override
            protected Path doInBackground() throws Exception {
                return ModelAssets.download(s, got -> publish(got), cancel::get);
            }

            @Override
            protected void process(List<Long> got) {
                status.setText("Downloading " + s.name() + ": " + megabytes(got.getLast()) + " of " + megabytes(s.bytes()));
            }

            @Override
            protected void done() {
                downloading = null;
                downloadingId = null;
                try {
                    get();
                    status.setText(s.name() + " downloaded and checked.");
                } catch (InterruptedException | ExecutionException | java.util.concurrent.CancellationException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof java.util.concurrent.CancellationException)
                        status.setText("Download of " + s.name() + " cancelled.");
                    else {
                        Log.warn("Could not download " + s.url(), cause);
                        status.setText("Could not download " + s.name() + ": " + cause.getMessage());
                    }
                }
                rows.fireTableDataChanged();
                int i = rows.all.indexOf(s);
                if (i >= 0) {
                    int view = table.convertRowIndexToView(i);
                    table.setRowSelectionInterval(view, view);
                }
                selectionChanged();
            }
        };
        enableActions();
        rows.fireTableRowsUpdated(rows.all.indexOf(s), rows.all.indexOf(s));
        downloading.execute();
    }

    private void addLayer() {
        ModelAssets.Entry s = selected();
        if (s == null || !ModelAssets.isPresent(s))
            return;
        URI uri = ModelAssets.file(s).toUri();
        Task.submitBackground(uri.toString(), () -> new ModelLayer(uri), layer -> {
            configure(layer, s);
            Layers.add(layer);
            status.setText(s.name() + " added" + (s.target() == null ? " at Sun centre." : " at the position of " + s.target() + "."));
        }, "Error loading model");
    }

    /** The placement the list gives: the spacecraft's position and size, or Sun centre when Studio has no ephemeris for it. */
    public static void configure(ModelLayer layer, ModelAssets.Entry s) {
        if (s.metersPerUnit() != null)
            layer.setMetersPerUnit(s.metersPerUnit());
        if (s.target() != null) {
            layer.setTarget(s.target());
            layer.setPlacementMode(ModelPlacement.Mode.EPHEMERIS);
        }
    }

    private static String describe(ModelAssets.Entry s) {
        StringBuilder b = new StringBuilder("<html><body style='font-family:sans-serif'>");
        b.append("<b>").append(esc(s.name())).append("</b><br>");
        b.append(s.target() == null ? "Added at Sun centre." : "Placed at the position of " + esc(s.target()) + ".");
        if (s.targetNote() != null)
            b.append(' ').append(esc(s.targetNote()));
        b.append("<br>");
        if (s.size().meters() != null) {
            b.append("Scale: ").append(String.format("%.2f m", s.size().meters())).append(" from <a href='").append(esc(s.size().source())).append("'>")
                    .append(esc(host(s.size().source()))).append("</a>: “").append(esc(s.size().quote())).append("”. ");
        }
        b.append(esc(s.size().note())).append("<br>");
        b.append(esc(s.credit())).append(", <a href='").append(esc(s.page())).append("'>source</a>. ").append(esc(s.terms())).append("<br>");
        b.append("Download: ").append(megabytes(s.bytes())).append(", SHA-256 ").append(s.sha256(), 0, 12).append("...");
        return b.append("</body></html>").toString();
    }

    private static String host(@Nullable String url) {
        try {
            return url == null ? "" : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String esc(@Nullable String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    static String megabytes(long bytes) {
        return String.format("%.1f MB", bytes / 1e6);
    }

    private final class Rows extends AbstractTableModel {
        List<ModelAssets.Entry> all = List.of();

        @Override
        public int getRowCount() {
            return all.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "Model";
                case 1 -> "Placed at";
                case 2 -> "Size";
                default -> "Status";
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            ModelAssets.Entry e = all.get(r);
            return switch (c) {
                case 0 -> e.name();
                case 1 -> e.target() == null ? "Sun centre" : e.target();
                case 2 -> e.size().meters() == null ? "unchecked" : String.format("%.2f m", e.size().meters());
                default -> e.id().equals(downloadingId) ? "Downloading" : ModelAssets.isPresent(e) ? "Downloaded" : megabytes(e.bytes());
            };
        }
    }

}
