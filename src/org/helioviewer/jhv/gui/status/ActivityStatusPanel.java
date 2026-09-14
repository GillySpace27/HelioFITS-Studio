package org.helioviewer.jhv.gui.status;

import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;

import org.helioviewer.jhv.event.SWEKDownloader;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.BusyIndicator;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.timelines.TimelineLayer;
import org.helioviewer.jhv.timelines.TimelineLayers;
import org.helioviewer.jhv.view.ComputedView;

/**
 * The footer's answer to "has everything landed": a spinner while anything is still downloading,
 * loading or computing, a check when nothing is and nothing failed, a warning when the work is over
 * but some of it did not arrive. The tooltip names what is still running or what went wrong.
 *
 * <p>A check is a promise, so it is conservative. Work that has stopped is not the same as work that
 * succeeded: frames that timed out, or a layer that never got past "Loading...", keep the check away.
 * That second case is the one that prompted this. On 2026-09-14 a listener exception left image
 * layers at "Loading..." with no download running and nothing in the log, and a plain "idle" light
 * would have shown a check over a session that had silently stopped.
 *
 * <p>Polled on UITimer's 10 Hz tick rather than fed by events, because the work it reports lives in
 * half a dozen places (layer loaders, the Fourier filter, timeline bands, event downloads, movie
 * export, the shared worker pool) with no single place that sees them all.
 */
@SuppressWarnings("serial")
public final class ActivityStatusPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    private static final int SIZE = 14;
    private static final String DONE_TIP = "Everything has landed: nothing is downloading, loading or computing.";

    private final BusyIndicator wheel = new BusyIndicator(); // shares the angle UITimer turns for the layer rows
    private final Icon spinning = new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            wheel.setForeground(c.getForeground());
            wheel.setSize(SIZE, SIZE);
            Graphics inner = g.create(x, y, SIZE, SIZE);
            wheel.paint(inner);
            inner.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    };

    private String lastTip;

    public ActivityStatusPanel() {
        UITimer.register(this);
        lazyRepaint();
    }

    @Override
    public void lazyRepaint() {
        List<String> running = running();
        if (!running.isEmpty()) {
            show(spinning, "", tip("Still running:", running));
            repaint(); // the wheel turns every tick
            return;
        }
        List<String> failed = failed();
        if (!failed.isEmpty())
            show(null, "⚠", tip("Finished, but not everything landed:", failed));
        else
            show(Buttons.activityDone, "", DONE_TIP);
    }

    private void show(Icon icon, String text, String tooltip) {
        if (getIcon() != icon)
            setIcon(icon);
        if (!text.equals(getText()))
            setText(text);
        if (!tooltip.equals(lastTip)) {
            lastTip = tooltip;
            setToolTipText(tooltip);
        }
    }

    static List<String> running() {
        List<String> out = new ArrayList<>();
        for (Layer layer : Layers.getLayers())
            if (layer.isDownloading())
                out.add(layer instanceof ImageLayer il && il.getView() instanceof ComputedView cv && cv.isRunning()
                        ? "Fourier filter on " + layer.getName()
                        : "Loading " + layer.getName());
        for (TimelineLayer tl : TimelineLayers.get())
            if (tl.isDownloading())
                out.add("Timeline: " + tl.getName());
        if (ExportMovie.isRecording())
            out.add("Recording a movie");
        int events = SWEKDownloader.pending();
        if (events > 0)
            out.add(events + (events == 1 ? " event download" : " event downloads"));
        int workers = Task.running();
        if (workers > 0) // includes the loaders named above: this is the catch-all for the rest
            out.add(workers + (workers == 1 ? " worker thread busy" : " worker threads busy"));
        return out;
    }

    static List<String> failed() {
        List<String> out = new ArrayList<>();
        for (ImageLayer layer : List.copyOf(Layers.getImageLayers())) {
            int n = layer.getFailedUris().size();
            if (n > 0)
                out.add(layer.getName() + ": " + n + (n == 1 ? " frame" : " frames") + " did not download (retry from the layer)");
            else if (!layer.isDownloading() && "Loading...".equals(layer.getName()))
                out.add("A layer stopped before its first frame arrived");
        }
        return out;
    }

    private static String tip(String heading, List<String> lines) {
        StringBuilder sb = new StringBuilder("<html>").append(heading);
        for (String line : lines)
            sb.append("<br>&nbsp;&nbsp;").append(line.replace("&", "&amp;").replace("<", "&lt;"));
        return sb.append("</html>").toString();
    }

}
