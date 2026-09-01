package org.helioviewer.jhv.layers.selector;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.Arrays;

import javax.swing.BorderFactory;
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
    private long lastReadoutSig = Long.MIN_VALUE; // memoize: skip rebuild when nothing shown changed
    private final JideToggleButton downloadButton = new JideToggleButton(Buttons.download);
    private final com.jidesoft.swing.JideButton cacheButton = new com.jidesoft.swing.JideButton(Buttons.cache);
    private final com.jidesoft.swing.JideButton deleteCacheButton = new com.jidesoft.swing.JideButton(Buttons.deleteCache);
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
        // Deleting a cached dataset by hand is the only way to force a genuine re-download: the
        // persistent cache is keyed by a SHA-256 of the source URI, so the files are hash-named
        // and impossible to pick out of the folder by eye. Reveal the layer's own file, selected.
        cacheButton.setToolTipText("Show this layer's cached file on disk");
        cacheButton.addActionListener(e -> revealCache());

        deleteCacheButton.setToolTipText("Delete this layer's cached files, forcing a fresh download");
        deleteCacheButton.addActionListener(e -> deleteCache());

        metaButton.setToolTipText("Show metadata of selected layer");
        metaButton.addActionListener(e -> {
            metaDialog.setMetaData(layer);
            metaDialog.showDialog();
        });

        // Icons sit inline with the readout on one row, not on a line of their own.
        JPanel icons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 2, 0));
        icons.add(downloadButton);
        icons.add(cacheButton);
        icons.add(deleteCacheButton);
        icons.add(makeRefreshButton());
        icons.add(metaButton);

        readout.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0)); // let the readout breathe
        buttonRow.add(readout, BorderLayout.CENTER);
        buttonRow.add(icons, BorderLayout.LINE_END);

        add(buttonRow);

        // Usually refreshed through ImageLayer activation; initialize here too in case that activation already happened before panel creation.
        refresh(layer);
        updateReadout();
    }

    // Only PUNCH layers carry a remembered query; the button stays hidden otherwise
    private JideButton makeRefreshButton() {
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
        return refreshButton;
    }


    void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        downloadButton.setVisible(!imageLayer.isLocal());
        // A JPIP layer streams from the server and never lands in the file cache, so there would
        // be nothing to show; a local file is already sitting where the user put it.
        boolean hasCache = !imageLayer.isLocal();
        cacheButton.setVisible(hasCache);
        deleteCacheButton.setVisible(hasCache);
    }

    // Everything this layer has put on disk. There are two separate stores and a layer uses one
    // or the other, never both:
    //   - a JPIP layer streams from the server, and the download button saves the whole movie as
    //     ONE .jpx under Downloads/;
    //   - a direct-URI layer (PUNCH FITS and friends) is cached by NetFileCache as one file per
    //     frame URI under FileCache/, hash-named, so a 97-frame movie is 97 files.
    private java.util.List<java.io.File> cachedFiles() {
        java.util.List<java.io.File> found = new java.util.ArrayList<>();
        for (java.net.URI uri : layer.getSourceUris()) {
            java.io.File f = org.helioviewer.jhv.io.NetFileCache.cachedFile(uri);
            if (f.isFile())
                found.add(f);
        }
        String baseName = layer.getView().getBaseName();
        if (baseName != null) {
            java.io.File jpx = new java.io.File(org.helioviewer.jhv.io.Directories.DOWNLOADS.getPath(), baseName);
            if (jpx.isFile())
                found.add(jpx);
        }
        return found;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.);
        if (bytes < 1024L * 1024 * 1024)
            return String.format("%.1f MB", bytes / (1024. * 1024));
        return String.format("%.2f GB", bytes / (1024. * 1024 * 1024));
    }

    private void deleteCache() {
        java.util.List<java.io.File> found = cachedFiles();
        if (found.isEmpty()) {
            Message.warn("Nothing cached on disk", "This layer has no cached files, so there is nothing to delete.");
            return;
        }
        long bytes = 0;
        for (java.io.File f : found)
            bytes += f.length();

        int answer = javax.swing.JOptionPane.showConfirmDialog(org.helioviewer.jhv.gui.MainFrame.get(),
                "Delete " + found.size() + " cached file" + (found.size() == 1 ? "" : "s")
                        + " (" + humanSize(bytes) + ") for \"" + layer.getName() + "\"?\n\n"
                        + "This only removes the local copy: the data downloads again next time it is needed.\n"
                        + "Frames already loaded stay in memory until the layer is reloaded.",
                "Delete Cached Files", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION)
            return;

        int deleted = 0, failed = 0;
        for (java.io.File f : found) {
            if (!org.helioviewer.jhv.io.Directories.isInsideCache(f)) {
                org.helioviewer.jhv.app.Log.warn("Refusing to delete outside the cache: " + f);
                failed++;
            } else if (f.delete())
                deleted++;
            else
                failed++;
        }
        if (failed == 0)
            Message.warn("Cache deleted", "Removed " + deleted + " file" + (deleted == 1 ? "" : "s")
                    + " (" + humanSize(bytes) + "). Reload the layer to fetch it again.");
        else
            Message.err("Cache partly deleted", "Removed " + deleted + " file" + (deleted == 1 ? "" : "s")
                    + ", but " + failed + " could not be deleted. See the log for details.");
    }

    private void revealCache() {
        java.util.List<java.io.File> found = cachedFiles();
        if (found.isEmpty()) {
            Message.warn("Nothing cached on disk",
                    "This layer has no files in the cache, so there is nothing to delete. "
                            + "Opening the cache folder anyway.");
            org.helioviewer.jhv.gui.DesktopIntegration.reveal(org.helioviewer.jhv.io.Directories.FILECACHE.getFile());
            return;
        }
        org.helioviewer.jhv.gui.DesktopIntegration.reveal(found.getFirst());
        // The file cache is content-addressed, so its files are named by a SHA-256 of the source
        // URI with no extension: one selected file gets the user to the right folder, but they
        // cannot pick the rest out by eye. Say how many there are so "did I get them all?" has
        // an answer.
        if (found.size() > 1)
            Message.warn("Cached files",
                    "This layer has " + found.size() + " cached files, all in the folder now open. "
                            + "They are named by a hash of their source URL, so sort by Date Added to see them together.");
    }

    // Force a recompute even if the signature is unchanged — used when the layer's
    // view may have been swapped (layerUpdated) so a same-count/different-range layer refreshes.
    void forceReadoutRefresh() {
        lastReadoutSig = Long.MIN_VALUE;
        lastReadoutText = null;
        lastCacheStat = 0; // a swapped view means different files on disk
        updateReadout();
    }

    void updateReadout() {
        String loadStatus = layer.getLoadStatus();
        if (loadStatus != null) { // frames still on the wire: show the load stage, not "0/0"
            lastReadoutSig = Long.MIN_VALUE; // recompute the real readout once frames land
            readout.setText("<html><i>" + loadStatus + "</i></html>");
            return;
        }
        View view = layer.getView();
        int max = view.getMaximumFrameNumber();
        int total = max + 1;
        boolean downloading = layer.isDownloading();
        int done = downloading ? view.getCompleteFrameCount() : total;

        // timeUpdated fires per displayed frame, and the median spacing is an O(n log n) sort, so
        // that one number is memoized on a signature of what could change it. The rest of the
        // readout is rebuilt every time and compared: the decoded resolution moves with the zoom
        // and the cache grows on its own clock, neither of which the frame counts would report.
        long sig = ((long) total << 21) ^ ((long) done << 1) ^ (downloading ? 1 : 0);
        if (sig != lastReadoutSig) {
            lastReadoutSig = sig;
            cadenceText = total > 1 ? formatSeconds(medianSpacingSec(view, max)) : "—";
        }

        long start = view.getFirstTime().milli;
        long end = view.getLastTime().milli;
        String cadence = cadenceText;
        String frames = downloading
                ? (max == 0 ? "0/0 frames" : done + "/" + total + " frames") // scope not yet known
                : total + (total == 1 ? " frame" : " frames");
        String duration = TimeUtils.formatDurationSig(end - start);
        String text = String.format("<html>%s – %s<br>cadence %s · %s · %s total<br>%s<br>%s</html>",
                TimeUtils.format(start), TimeUtils.format(end), cadence, frames, duration,
                describeData(view), describeCache());
        if (!text.equals(lastReadoutText)) {
            lastReadoutText = text;
            readout.setText(text);
        }
    }

    private String cadenceText = "—";
    private String lastReadoutText;

    /**
     * What this layer's pixels actually are: format, size, sample depth, plate scale.
     *
     * <p>Worth stating rather than leaving to the layer name, because the two things a name cannot
     * tell you are exactly the two that decide what an analysis is allowed to conclude. A JP2
     * browse product and the calibrated FITS of the same instrument carry the same name and are
     * 8-bit and 16-bit respectively.
     */
    private String describeData(View view) {
        StringBuilder sb = new StringBuilder();
        String format = CellRenderer.formatName(layer);
        sb.append(format == null ? "unknown format" : format);

        View.ImageData data = layer.getImageData();
        int[] nativeSize = view.getNativeSize();
        if (nativeSize != null) {
            sb.append(" · ").append(nativeSize[0]).append(" × ").append(nativeSize[1]);
            // A JPEG 2000 view decodes whichever resolution level the current zoom asked for, so
            // what is on screen is regularly smaller than what is on the server. Saying so is the
            // difference between "this instrument is low resolution" and "you are zoomed out".
            if (data != null && (data.imageBuffer().width != nativeSize[0] || data.imageBuffer().height != nativeSize[1]))
                sb.append(" (showing ").append(data.imageBuffer().width).append(" × ")
                        .append(data.imageBuffer().height).append(')');
        } else if (data != null) {
            // No resolution ladder: the format arrives whole, so the decoded frame IS the native.
            sb.append(" · ").append(data.imageBuffer().width).append(" × ").append(data.imageBuffer().height);
        }

        if (data != null) {
            sb.append(" · ").append(depthName(data.imageBuffer().format));
            sb.append(measuredDepth(data.imageBuffer()));
        }

        // Absent for a surface map and for a pixel-based product, which have no plate scale to
        // report; see MetaData.getArcsecPerPixel for why that is a 0 rather than a division here.
        double arcsecPerPixel = layer.getMetaData().getArcsecPerPixel();
        if (arcsecPerPixel > 0)
            sb.append(String.format(arcsecPerPixel < 10 ? " · %.2f\u2033/px" : " · %.1f\u2033/px", arcsecPerPixel));
        return sb.toString();
    }

    /**
     * The depth measured from the pixels, beside the depth claimed by the container.
     *
     * <p>The container is not evidence: a JP2 byte-scaled at ingest and the calibrated FITS of the
     * same instrument both reach the pipeline as Gray16F, and only one carries more than 256
     * levels. This counts the distinct values actually present, so a browse product that was 8-bit
     * upstream says so however wide the buffer holding it.
     *
     * <p>Half float is itself the ceiling near the top of the range (an 11-bit mantissa), so a
     * genuine 16-bit source reads as roughly 12 to 14 bits rather than 16. The number that matters
     * is the one that separates 256 from thousands.
     */
    private static String measuredDepth(org.helioviewer.jhv.image.ImageBuffer buffer) {
        int levels = buffer.measuredLevels();
        if (levels <= 0) // colour, where a single depth would not mean anything
            return "";
        String verdict = levels <= 256 ? ", 8-bit data" : String.format(", ≈%.1f bit", Math.log(levels) / Math.log(2));
        return " (measured " + levels + " levels" + verdict + ")";
    }

    // What the decoder produced, which is not always what the file holds: a JP2 codestream can be
    // 8 or 16 bit and lands in whichever buffer the decoder chose. This reports the buffer, since
    // that is what every later stage of the pipeline actually sees.
    private static String depthName(org.helioviewer.jhv.image.ImageBuffer.Format format) {
        return switch (format) {
            case Gray8 -> "8-bit";
            case Gray16F -> "16-bit";
            case RGBA32 -> "RGBA";
        };
    }

    /**
     * What this layer has on disk, throttled.
     *
     * <p>The count is one filesystem stat per frame URI, and a 262-frame layer is 262 of them. The
     * readout rebuilds on every frame that lands while a download runs, so this must not be part of
     * that rebuild; it is refreshed on its own slow clock and the last answer is reused between.
     */
    private String describeCache() {
        long now = System.currentTimeMillis();
        if (cachedSummary == null || now - lastCacheStat > CACHE_STAT_INTERVAL_MS) {
            lastCacheStat = now;
            java.util.List<java.io.File> found = cachedFiles();
            if (found.isEmpty())
                cachedSummary = "not cached on disk";
            else {
                long bytes = 0;
                for (java.io.File f : found)
                    bytes += f.length();
                cachedSummary = "cached " + found.size() + (found.size() == 1 ? " file · " : " files · ") + humanSize(bytes);
            }
        }
        return cachedSummary;
    }

    private static final long CACHE_STAT_INTERVAL_MS = 5000;
    private String cachedSummary;
    private long lastCacheStat;

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

    private static String formatSeconds(long sec) {
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
