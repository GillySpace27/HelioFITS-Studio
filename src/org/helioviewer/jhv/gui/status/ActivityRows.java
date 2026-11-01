package org.helioviewer.jhv.gui.status;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.event.SWEKDownloader;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.timelines.TimelineLayer;
import org.helioviewer.jhv.timelines.TimelineLayers;
import org.helioviewer.jhv.view.ComputedView;
import org.helioviewer.jhv.view.View;

/**
 * The Activity window's rows: the same work the footer spinner watches, with the readout the layer
 * list shows (stage or frames done of total) and a fraction for a progress bar where one is known.
 */
public final class ActivityRows {

    /** @param fraction 0 to 1, or negative when the amount of work is not known yet; @param layer the layer to stop or show, if any */
    public record Row(String name, String detail, double fraction, boolean failed, @Nullable ImageLayer layer) {}

    private ActivityRows() {
    }

    public static List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Layer layer : Layers.getLayers()) {
            if (!layer.isDownloading())
                continue;
            if (layer instanceof ImageLayer il) {
                View view = il.getView();
                if (view instanceof ComputedView cv && cv.isRunning()) {
                    out.add(new Row(layer.getName(), "Fourier filter running", -1, false, il));
                    continue;
                }
                String stage = il.getLoadStatus();
                int max = view.getMaximumFrameNumber();
                int done = view.getCompleteFrameCount();
                if (stage != null || max == 0)
                    out.add(new Row(layer.getName(), stage == null ? "Waiting for the frame list" : stage, -1, false, il));
                else
                    out.add(new Row(layer.getName(), "Downloading " + done + " of " + (max + 1) + " frames", done / (double) (max + 1), false, il));
            } else
                out.add(new Row(layer.getName(), "Loading", -1, false, null));
        }
        for (TimelineLayer tl : TimelineLayers.get())
            if (tl.isDownloading())
                out.add(new Row("Timeline: " + tl.getName(), "Loading", -1, false, null));
        String movie = ExportMovie.progress();
        if (movie != null)
            out.add(new Row("Recording", movie, -1, false, null));
        String python = org.helioviewer.jhv.gui.Actions.SendToPython.progress();
        if (python != null)
            out.add(new Row("Python", python, -1, false, null));
        String job = org.helioviewer.jhv.study.JobQueue.INSTANCE.runningLine();
        if (job != null)
            out.add(new Row("Export queue", job, -1, false, null));
        int events = SWEKDownloader.pending();
        if (events > 0)
            out.add(new Row("Events", events + (events == 1 ? " download" : " downloads"), -1, false, null));
        int workers = Task.running();
        if (workers > 0)
            out.add(new Row("Background", workers + (workers == 1 ? " worker thread busy" : " worker threads busy"), -1, false, null));
        for (ImageLayer layer : List.copyOf(Layers.getImageLayers())) {
            int n = layer.getFailedUris().size();
            if (n > 0)
                out.add(new Row(layer.getName(), n + (n == 1 ? " frame" : " frames") + " did not download: retry from the layer", 0, true, layer));
            else if (!layer.isDownloading() && "Loading...".equals(layer.getName()))
                out.add(new Row(layer.getName(), "Stopped before its first frame arrived", 0, true, layer));
        }
        return out;
    }
}
