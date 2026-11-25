package org.helioviewer.jhv.io;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.thread.EDTQueue;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.timelines.IntervalTimelineLayer;
import org.helioviewer.jhv.timelines.Timelines;
import org.helioviewer.jhv.timelines.band.BandImporter;

import org.json.JSONArray;
import org.json.JSONObject;

class LoadRequest {

    static void submit(@Nonnull URI uri) {
        Task.submitBackground(uri.toString(), new LoadRequestURI(uri), Task::doNothing, "Error getting the data");
    }

    static void submit(@Nonnull String json) {
        Task.submitBackground("request", new LoadRequestString(json), Task::doNothing, "Error getting the data");
    }

    static void submitCDF(@Nonnull List<URI> uriList) {
        Task.submitBackground("cdf", new LoadRequestCDF(uriList), LoadRequest::onSuccessCDF, LoadRequest::onFailureCDF);
        Timelines.dc.setStatus("Loading...");
    }

    private record LoadRequestURI(URI uri) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            if (uri.toString().toLowerCase().endsWith(".cdf")) {
                BandImporter.loadCdf(uri);
            } else
                parseRequest(JSONUtils.get(uri));
            return null;
        }
    }

    private record LoadRequestString(String json) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            parseRequest(new JSONObject(json));
            return null;
        }
    }

    private record LoadRequestCDF(List<URI> uriList) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            List<String> failed = new java.util.concurrent.CopyOnWriteArrayList<>();
            long[] union = {Long.MAX_VALUE, Long.MIN_VALUE};
            uriList.parallelStream().forEach(uri -> {
                try {
                    long[] range = BandImporter.loadCdfRange(uri);
                    synchronized (union) {
                        union[0] = Math.min(union[0], range[0]);
                        union[1] = Math.max(union[1], range[1]);
                    }
                } catch (Exception e) {
                    Log.warn(uri.toString(), e);
                    failed.add(e.getMessage() == null ? uri.getPath() : e.getMessage());
                }
            });
            if (union[0] <= union[1]) // one interval covering every file, not whichever finished last
                java.awt.EventQueue.invokeLater(() -> org.helioviewer.jhv.timelines.draw.DrawController.setSelectedInterval(union[0], union[1]));
            if (!failed.isEmpty()) // onFailureCDF shows it; the files that did load stay loaded
                throw new Exception(String.join("\n", failed));
            return null;
        }
    }

    private static void onSuccessCDF(Void ignoredResult) {
        Timelines.dc.setStatus(null);
    }

    private static void onFailureCDF(String logContext, Throwable t) {
        Timelines.dc.setStatus(null);
        Log.error(logContext, t);
        Message.err("Error getting the data", t.getMessage());
    }

    private static void parseRequest(JSONObject jo) throws Exception {
        JSONArray ji = jo.optJSONArray("org.helioviewer.jhv.request.image");
        if (ji != null) {
            int len = ji.length();
            for (int i = 0; i < len; i++) {
                APIRequest req = APIRequest.fromRequestJson(ji.getJSONObject(i));
                EDTQueue.invokeAndWait(() -> ImageLayer.create(null).load(req));
            }
        }

        JSONArray jv = jo.optJSONArray("org.helioviewer.jhv.request.intervals");
        if (jv != null) {
            List<IntervalTimelineLayer> parsed = new java.util.ArrayList<>();
            for (int i = 0; i < jv.length(); i++) // all of them first: a bad one must not leave the others added
                parsed.add(IntervalTimelineLayer.fromJson(jv.getJSONObject(i)));
            for (IntervalTimelineLayer layer : parsed)
                EDTQueue.invokeAndWait(() -> Timelines.getLayers().add(layer));
        }

        JSONArray jt = jo.optJSONArray("org.helioviewer.jhv.request.timeline");
        if (jt != null) {
            int len = jt.length();
            for (int i = 0; i < len; i++) {
                BandImporter.loadBand(jt.getJSONObject(i));
            }
        }
    }

}
