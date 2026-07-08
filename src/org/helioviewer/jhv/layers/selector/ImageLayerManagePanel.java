package org.helioviewer.jhv.layers.selector;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;

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

import com.jidesoft.swing.JideButton;
import com.jidesoft.swing.JideToggleButton;

// Download, metadata, and PUNCH-refresh controls for the selected image layer.
// Shown in the "Manage" wrapper of the Layers section.
@SuppressWarnings("serial")
final class ImageLayerManagePanel extends JPanel {

    private final JideToggleButton downloadButton = new JideToggleButton(Buttons.download);
    private final JProgressBar progressBar = new JProgressBar();
    private DownloadProgress downloadProgress;

    ImageLayerManagePanel(ImageLayer layer) {
        setLayout(new BorderLayout());

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

        add(downloadButton, BorderLayout.LINE_START);
        add(rightCluster, BorderLayout.LINE_END);

        // Usually refreshed through ImageLayer activation; initialize here too in case that activation already happened before panel creation.
        refresh(layer);
    }

    void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        downloadButton.setVisible(!imageLayer.isLocal());
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
