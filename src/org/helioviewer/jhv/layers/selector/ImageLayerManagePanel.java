package org.helioviewer.jhv.layers.selector;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Arrays;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.CompletionNotifications;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.CircularProgressUI;
import org.helioviewer.jhv.gui.dialog.MetaDataDialog;
import org.helioviewer.jhv.io.DownloadLayer;
import org.helioviewer.jhv.io.PunchClient;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.view.View;

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideToggleButton;

// Download, metadata, and PUNCH-refresh controls for the selected image layer.
// Shown in the "Manage" wrapper of the Layers section.
@SuppressWarnings("serial")
final class ImageLayerManagePanel extends JPanel {

    private final ImageLayer layer;
    private final JLabel readout = new JLabel();
    private final JideToggleButton downloadButton = new JideToggleButton(Buttons.download);
    private final JProgressBar progressBar = new JProgressBar();
    private DownloadProgress downloadProgress;

    ImageLayerManagePanel(ImageLayer layer) {
        this.layer = layer;
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

        JPanel buttonRow = new JPanel(new BorderLayout());

        downloadButton.setToolTipText("Download selected layer");
        downloadButton.addActionListener(e -> {
            if (downloadButton.isSelected()) {
                Insets margin = downloadButton.getMargin();
                if (margin == null) // satisfy coverity
                    margin = new Insets(0, 0, 0, 0);
                Dimension size = downloadButton.getSize(null);
                progressBar.setPreferredSize(new Dimension(size.width - margin.left - margin.right, size.height - margin.top - margin.bottom));

                downloadButton.setText(null);
                downloadButton.add(progressBar);
                downloadButton.setToolTipText("Stop download");

                downloadProgress = new DownloadProgress();
                layer.startDownload(downloadProgress);
            } else {
                layer.cancelDownloadTask();
                if (downloadProgress != null)
                    downloadProgress.done();
            }
        });

        progressBar.setUI(new CircularProgressUI());
        progressBar.setForeground(downloadButton.getForeground());

        MetaDataDialog metaDialog = new MetaDataDialog();
        JideButton metaButton = new JideButton(Buttons.info);
        metaButton.setToolTipText("Show metadata of selected layer");
        metaButton.addActionListener(e -> {
            metaDialog.setMetaData(layer);
            metaDialog.showDialog();
        });

        // Only PUNCH layers carry a remembered query; the button stays hidden otherwise
        JideButton refreshButton = new JideButton(Buttons.refresh);
        refreshButton.setToolTipText("Check the PUNCH archive for new frames in this layer's time range");
        refreshButton.setVisible(PunchClient.hasRememberedQuery(layer));
        JProgressBar refreshSpinner = new JProgressBar();
        refreshSpinner.setUI(new CircularProgressUI());
        refreshSpinner.setIndeterminate(true);
        refreshSpinner.setVisible(false);
        refreshSpinner.setPreferredSize(new Dimension(20, 20));
        refreshButton.addActionListener(e -> {
            refreshButton.setEnabled(false);
            refreshButton.setText(null);
            refreshButton.add(refreshSpinner);
            refreshSpinner.setVisible(true);
            PunchClient.submitRefresh(layer, result -> {
                refreshSpinner.setVisible(false);
                refreshButton.remove(refreshSpinner);
                refreshButton.setText(Buttons.refresh);
                refreshButton.setEnabled(true);
                Message.warn("PUNCH refresh", result.newCount() == 0
                        ? "No new frames in the archive for this layer."
                        : String.format("Loaded %d new frame%s as a new layer.", result.newCount(), result.newCount() == 1 ? "" : "s"));
            });
        });

        JPanel rightCluster = new JPanel(new BorderLayout());
        rightCluster.add(refreshButton, BorderLayout.LINE_START);
        rightCluster.add(metaButton, BorderLayout.LINE_END);

        buttonRow.add(downloadButton, BorderLayout.LINE_START);
        buttonRow.add(rightCluster, BorderLayout.LINE_END);

        add(readout);
        add(buttonRow);

        // Usually refreshed through ImageLayer activation; initialize here too in case that activation already happened before panel creation.
        refresh(layer);
        updateReadout();
    }

    void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        downloadButton.setVisible(!imageLayer.isLocal());
    }

    void updateReadout() {
        View view = layer.getView();
        int max = view.getMaximumFrameNumber();
        int frames = max + 1;
        long start = view.getFirstTime().milli;
        long end = view.getLastTime().milli;
        String cadence = frames > 1
                ? formatCadence(medianSpacingSec(view, max))
                : "—";
        readout.setText(String.format("<html>%s – %s<br>cadence %s · %d frame%s</html>",
                TimeUtils.format(start),
                TimeUtils.format(end),
                cadence, frames, frames == 1 ? "" : "s"));
    }

    private static long medianSpacingSec(View view, int max) {
        long[] gaps = new long[max];
        long prev = view.getFrameTime(0).milli;
        for (int i = 1; i <= max; i++) {
            long t = view.getFrameTime(i).milli;
            gaps[i - 1] = (t - prev) / 1000;
            prev = t;
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    private static String formatCadence(long sec) {
        if (sec >= 86400) return (sec / 86400) + " d";
        if (sec >= 3600) return (sec / 3600) + " h";
        if (sec >= 60) return (sec / 60) + " min";
        return sec + " s";
    }

    private void downloadProgress(int value) {
        if (value < 0) {
            progressBar.setIndeterminate(true);
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setValue(value);
        }
    }

    private void downloadDone() {
        downloadButton.remove(progressBar);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        downloadButton.setToolTipText("Download selected layer");
        downloadButton.setText(Buttons.download);
        downloadButton.setSelected(false);
    }

    private final class DownloadProgress implements DownloadLayer.Progress {
        @Override
        public void progress(int percent) {
            if (downloadProgress == this)
                downloadProgress(percent);
        }

        @Override
        public void success(String result) {
            CompletionNotifications.fileReady(result);
        }

        @Override
        public void done() {
            if (downloadProgress != this)
                return;
            downloadProgress = null;
            downloadDone();
        }
    }

}
