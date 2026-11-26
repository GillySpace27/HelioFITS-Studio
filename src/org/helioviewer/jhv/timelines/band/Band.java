package org.helioviewer.jhv.timelines.band;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.LongUnaryOperator;

import javax.annotation.Nullable;
import javax.swing.JPanel;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.event.GOESLevel;
import org.helioviewer.jhv.thread.AppThread;
import org.helioviewer.jhv.thread.LatestWorker;
import org.helioviewer.jhv.time.Interval;
import org.helioviewer.jhv.time.RequestCache;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.timelines.TimelineLayer;
import org.helioviewer.jhv.timelines.draw.DrawConstants;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.GraphGeometry;
import org.helioviewer.jhv.timelines.draw.TimeAxis;
import org.helioviewer.jhv.timelines.draw.YAxis;

import org.json.JSONArray;
import org.json.JSONObject;

public final class Band extends TimelineLayer {

    private record Polyline(int[] xPoints, int[] yPoints, float[] values) {}

    private record Bar(int x1, int y1, int x2, int y2, Color levelColor) {}

    private sealed interface GraphData {}

    private record EmptyGraph() implements GraphData {}

    private record LineGraph(List<Polyline> polylines) implements GraphData {}

    private record BarGraph(List<Bar> bars) implements GraphData {}

    private static final GraphData EMPTY_GRAPH_DATA = new EmptyGraph();
    private static final Colors.Data bandColors = new Colors.Data();
    private static final int GRAPH_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, 8);
    private static final ExecutorService graphPool = AppThread.createIdleExecutor("Timeline-Graph", GRAPH_THREADS);

    private static final int MAX_REQUEST_DAYS = 21;

    private final BandType bandType;
    private final boolean fullResolution;
    private final BandOptions optionsPanel = new BandOptions(this);

    private YAxis yAxis; // replaced when the scale flips between linear and log
    private final int[] warnPixels;
    private final LatestWorker<GraphData> graphWorker = new LatestWorker<>(graphPool);

    private RequestCache requestCache;
    private BandCache bandCache;
    private GraphData graphData = EMPTY_GRAPH_DATA;
    private Color graphColor = bandColors.getNextColor();
    private PropagationModel propagationModel = new PropagationModel.Delay(0);
    private boolean multicolor;
    @Nullable
    private String spacecraftKey; // the buoy's spacecraft, picked on the row; null infers it from the name
    private boolean autoFit; // y range follows the visible data
    private boolean logScale;

    public Band(BandType _bandType) {
        this(_bandType, false);
    }

    public Band(BandType _bandType, boolean _fullResolution) {
        bandType = _bandType;
        fullResolution = _fullResolution;
        multicolor = bandType.hasLevels();
        logScale = "logarithmic".equalsIgnoreCase(bandType.getScale());
        yAxis = newAxis(logScale, bandType.getMin(), bandType.getMax());
        warnPixels = new int[bandType.getWarningLevels().length];
        requestCache = new RequestCache();
        bandCache = createBandCache();
        optionsPanel.syncAxis(); // the panel is built in a field initializer, before there is a band to read
    }

    // The type's own range; a log axis cannot start at or below zero, so it gets 1 to 10 until data arrives
    private double[] defaultRange(boolean log) {
        return !log || bandType.getMin() > 0 ? new double[]{bandType.getMin(), bandType.getMax()} : new double[]{1, 10};
    }

    private YAxis newAxis(boolean log, double start, double end) {
        String type = log ? "logarithmic" : "logarithmic".equalsIgnoreCase(bandType.getScale()) ? "linear" : bandType.getScale();
        return new YAxis(start, end, YAxis.generateScale(type, bandType.getUnitLabel()));
    }

    private BandCache createBandCache() {
        return fullResolution ? new BandCacheFull() : new BandCacheMinute();
    }

    public boolean isFullResolution() {
        return fullResolution;
    }

    JSONObject toJson() {
        TimeAxis timeAxis = DrawController.selectedAxis;
        long start = propagationModel.getObservationTime(timeAxis.start());
        long end = propagationModel.getObservationTime(timeAxis.end());
        float[] bounds = bandCache.getBounds(start, end);

        double multiplier = 1;
        if (bounds[0] != 0 && Float.isFinite(bounds[0]) && Float.isFinite(bounds[1])) {
            multiplier = bounds[0];
        }

        JSONObject jo = new JSONObject();
        jo.put("multiplier", multiplier);
        jo.put("fullResolution", fullResolution);
        bandCache.serialize(jo, 1 / multiplier);
        bandType.serialize(jo);
        return new JSONObject().put("org.helioviewer.jhv.request.timeline", new JSONArray().put(jo));
    }

    @Override
    public void serialize(JSONObject jo) {
        bandType.serialize(jo);
        jo.put("fullResolution", fullResolution);
        jo.put("color", new JSONObject().put("r", graphColor.getRed()).put("g", graphColor.getGreen()).put("b", graphColor.getBlue()));
        jo.put("multicolor", multicolor);
        if (spacecraftKey != null)
            jo.put("spacecraft", spacecraftKey);
        jo.put("stackWeight", getStackWeight());
        jo.put("yLog", logScale);
        jo.put("yAuto", autoFit);
        jo.put("yMin", yAxis.start());
        jo.put("yMax", yAxis.end());
    }

    public static TimelineLayer deserialize(JSONObject jo) throws Exception { // has to be implemented for state
        JSONObject jobt = jo.optJSONObject("bandType");
        if (jobt == null)
            throw new Exception("Missing bandType: " + jo);
        Band band = new Band(new BandType(jobt), jo.optBoolean("fullResolution", false));

        JSONObject jcolor = jo.optJSONObject("color");
        if (jcolor != null) {
            int r = Math.clamp(jcolor.optInt("r", 0), 0, 255);
            int g = Math.clamp(jcolor.optInt("g", 0), 0, 255);
            int b = Math.clamp(jcolor.optInt("b", 0), 0, 255);
            band.setDataColor(new Color(r, g, b));
        }
        band.multicolor = band.hasLevelColors() && jo.optBoolean("multicolor", band.bandType.hasLevels());
        band.spacecraftKey = jo.optString("spacecraft", null);
        band.restoreAxis(jo);
        band.setStackWeight((float) jo.optDouble("stackWeight", 1));
        return band;
    }

    public void applyStateFrom(Band restored) {
        enabled = restored.enabled;
        graphColor = restored.graphColor;
        multicolor = restored.multicolor;
        spacecraftKey = restored.spacecraftKey;
        autoFit = restored.autoFit;
        setStackWeight(restored.getStackWeight());
        if (restored.logScale != logScale) {
            logScale = restored.logScale;
            dropData(); // values cached under the other scale's clip are not the same set
        }
        yAxis = restored.yAxis;
        optionsPanel.syncAxis();
    }

    // yLog/yAuto/yMin/yMax are absent from states saved before the controls existed: keep the type's defaults then
    private void restoreAxis(JSONObject jo) {
        boolean log = !bandType.isBarPlot() && jo.optBoolean("yLog", logScale);
        double min = jo.optDouble("yMin", Double.NaN);
        double max = jo.optDouble("yMax", Double.NaN);
        boolean usable = min < max && Double.isFinite(min) && Double.isFinite(max) && (!log || min > 0);
        logScale = log;
        double[] range = defaultRange(log);
        yAxis = usable ? newAxis(log, min, max) : newAxis(log, range[0], range[1]);
        autoFit = jo.optBoolean("yAuto", false);
    }

    @Override
    public void resetAxis() {
        autoFit = false;
        double[] range = defaultRange(yAxis.isLog());
        yAxis.reset(range[0], range[1]);
        updateGraph();
        optionsPanel.syncAxis();
    }

    @Override
    public void zoomToFitAxis() {
        TimeAxis timeAxis = DrawController.selectedAxis;
        long start = propagationModel.getObservationTime(timeAxis.start());
        long end = propagationModel.getObservationTime(timeAxis.end());

        float[] bounds = bandCache.getBounds(start, end, yAxis.isLog());
        if (Float.isFinite(bounds[0]) && Float.isFinite(bounds[1])) {
            fitToVisibleData(); // pads a flat line too, rather than falling back to the type's range
            updateGraph();
            notifyStateChanged();
        }
    }

    public boolean isAutoFit() {
        return autoFit;
    }

    /** While on, every redraw fits the y range to the data in view. */
    public void setAutoFit(boolean _autoFit) {
        autoFit = _autoFit;
        updateGraph();
        optionsPanel.syncAxis();
        notifyStateChanged();
    }

    public boolean isLogScale() {
        return logScale;
    }

    /** Linear or log y axis. The cached data is dropped and fetched again, and the range is fitted to the new data. */
    public void setLogScale(boolean log) {
        if (log == logScale || bandType.isBarPlot()) // bars hang from zero, which a log axis cannot show
            return;
        logScale = log;
        autoFit = true;
        double[] range = defaultRange(log);
        yAxis = newAxis(log, range[0], range[1]);
        dropData();
        DrawController.layoutChanged(); // the layout holds the old axis
        fetchData(DrawController.selectedAxis);
        optionsPanel.syncAxis();
        notifyStateChanged();
    }

    public double yMin() {
        return yAxis.start();
    }

    public double yMax() {
        return yAxis.end();
    }

    /** A range typed by the user. Turns auto-fit off; false when the range cannot be shown on this scale. */
    public boolean setYRange(double min, double max) {
        if (!(min < max) || !Double.isFinite(min) || !Double.isFinite(max) || (yAxis.isLog() && min <= 0))
            return false;
        autoFit = false;
        yAxis.reset(min, max);
        updateGraph();
        optionsPanel.syncAxis();
        notifyStateChanged();
        return true;
    }

    // The data in view fills the axis, with a margin so extremes do not sit on the frame.
    private void fitToVisibleData() {
        TimeAxis timeAxis = DrawController.selectedAxis;
        long start = propagationModel.getObservationTime(timeAxis.start());
        long end = propagationModel.getObservationTime(timeAxis.end());
        float[] b = bandCache.getBounds(start, end, yAxis.isLog());
        if (!Float.isFinite(b[0]) || !Float.isFinite(b[1]))
            return; // nothing in view: keep the range rather than collapse it
        double lo = yAxis.scale(b[0]);
        double hi = yAxis.scale(b[1]);
        if (bandType.isBarPlot()) { // bars hang from zero
            lo = Math.min(lo, 0);
            hi = Math.max(hi, 0);
        }
        double pad = (hi - lo) * 0.05;
        if (pad == 0)
            pad = yAxis.isLog() ? 0.5 : Math.max(Math.abs(lo) * 0.05, 1e-3); // a flat line still needs a span
        yAxis.reset(yAxis.invScale(lo - pad), yAxis.invScale(hi + pad));
        optionsPanel.syncAxis();
    }

    private void dropData() {
        graphWorker.invalidate();
        graphData = EMPTY_GRAPH_DATA;
        BandDownloads.stop(this);
        requestCache = new RequestCache();
        bandCache = createBandCache();
    }

    public BandType getBandType() {
        return bandType;
    }

    @Override
    public void remove() {
        graphWorker.dispose();
        graphData = EMPTY_GRAPH_DATA;
        BandDownloads.stop(this);
        requestCache = new RequestCache();
        bandCache = createBandCache();
    }

    @Override
    public String getName() {
        return bandType.toString();
    }

    @Override
    public Color getDataColor() {
        return graphColor;
    }

    void setDataColor(Color c) {
        graphColor = c;
        DrawController.drawRequest();
        notifyStateChanged();
    }

    public boolean isMulticolor() {
        return multicolor;
    }

    public boolean hasLevelColors() {
        return bandType.hasLevels();
    }

    public boolean hasWarningLevels() {
        return bandType.hasWarningLevels();
    }

    public void setMulticolor(boolean _multicolor) {
        multicolor = hasLevelColors() && _multicolor;
        updateGraph();
        notifyStateChanged();
    }

    @Override
    public boolean isDownloading() {
        return BandDownloads.isActive(this);
    }

    @Override
    public JPanel getOptionsPanel() {
        return optionsPanel;
    }

    @Override
    public boolean hasData() {
        return bandCache.hasData();
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public void draw(Graphics2D g, Rectangle graphArea, TimeAxis timeAxis, Point mousePosition) {
        draw(g, graphArea, true);
    }

    public void draw(Graphics2D g, Rectangle graphArea, boolean drawWarnings) {
        if (!enabled)
            return;

        GraphData data = graphData;
        switch (data) {
            case EmptyGraph ignored -> {}
            case BarGraph bars -> {
                for (Bar bar : bars.bars) {
                    g.setColor(bar.levelColor != null ? bar.levelColor : graphColor);
                    g.fillRect(bar.x1, bar.y1, bar.x2 - bar.x1, bar.y2 - bar.y1);
                }
            }
            case LineGraph lines -> {
                Stroke stroke = g.getStroke();
                AffineTransform transform = g.getTransform();
                double scale = Math.max(transform.getScaleX(), transform.getScaleY());
                g.setStroke(new BasicStroke((float) (1 / scale)));
                if (multicolor) {
                    drawMulticolorPolylines(g, lines.polylines);
                } else {
                    g.setColor(graphColor);
                    lines.polylines.forEach(line -> g.drawPolyline(line.xPoints, line.yPoints, line.xPoints.length));
                }
                g.setStroke(stroke);
            }
        }

        if (drawWarnings) {
            BandType.WarningLevel[] wls = bandType.getWarningLevels();
            for (int i = 0; i < warnPixels.length; i++) {
                Color warningColor = wls[i].color();
                g.setColor(warningColor == null ? graphColor : warningColor);
                g.drawLine(graphArea.x, warnPixels[i], graphArea.x + graphArea.width, warnPixels[i]);
                g.drawString(wls[i].label(), graphArea.x, warnPixels[i] - 2);
            }
        }
    }

    private void drawMulticolorPolylines(Graphics2D g, List<Polyline> polylines) {
        Path2D.Float path = new Path2D.Float();
        for (Polyline line : polylines) {
            int[] xp = line.xPoints;
            int[] yp = line.yPoints;
            float[] vals = line.values;
            Color pathColor = null;
            for (int i = 0; i < xp.length - 1; i++) {
                Color segmentColor = vals != null ? bandType.getLevelColor(vals[i]) : null;
                if (segmentColor == null)
                    segmentColor = graphColor;
                if (!segmentColor.equals(pathColor)) {
                    if (pathColor != null) {
                        g.setColor(pathColor);
                        g.draw(path);
                    }
                    path.reset();
                    path.moveTo(xp[i], yp[i]);
                    pathColor = segmentColor;
                }
                path.lineTo(xp[i + 1], yp[i + 1]);
            }
            if (pathColor != null) {
                g.setColor(pathColor);
                g.draw(path);
            }
        }
    }

    private void updateGraph() {
        if (!enabled) {
            graphWorker.invalidate();
            return;
        }

        GraphGeometry geometry = DrawController.getGeometry();
        Rectangle drawArea = geometry.getLayerArea(this);
        if (drawArea == null) {
            graphWorker.invalidate();
            return;
        }
        if (autoFit)
            fitToVisibleData();
        YAxis.Mapper yMapper = geometry.yMapper(yAxis, drawArea);

        BandType.WarningLevel[] wls = bandType.getWarningLevels();
        for (int i = 0; i < warnPixels.length; i++) {
            warnPixels[i] = yMapper.dataToPixel(wls[i].value());
        }

        TimeAxis timeAxis = DrawController.selectedAxis;
        long start = propagationModel.getObservationTime(timeAxis.start());
        long end = propagationModel.getObservationTime(timeAxis.end());

        LongUnaryOperator viewpointTime = propagationModel.viewpointTimeMapper();
        TimeAxis.Mapper xMapper = geometry.xMapper(timeAxis);
        final boolean isBar = bandType.isBarPlot() && bandType.getBarWidth() > 0;
        final long barWidthMillis = isBar ? bandType.getBarWidth() * 1000 : 0;
        List<List<BandCache.DateValue>> rawData = bandCache.getValues(
                Display.pixelScale[0] * drawArea.width,
                start - barWidthMillis, end + barWidthMillis);
        if (rawData.stream().allMatch(List::isEmpty)) {
            graphWorker.invalidate();
            graphData = EMPTY_GRAPH_DATA;
            DrawController.drawRequest();
            return;
        }
        final boolean useMulticolor = multicolor;

        graphWorker.submit(
                () -> isBar
                        ? buildBars(rawData, xMapper, yMapper, viewpointTime, useMulticolor)
                        : buildPolylines(rawData, xMapper, yMapper, viewpointTime, useMulticolor),
                this::graphUpdated);
    }

    private BarGraph buildBars(List<List<BandCache.DateValue>> rawData, TimeAxis.Mapper xMapper,
                               YAxis.Mapper yMapper, LongUnaryOperator viewpointTime,
                               boolean useMulticolor) throws InterruptedException {
        long barWidthMillis = bandType.getBarWidth() * 1000;
        int baselineY = yMapper.dataToPixel(0);
        List<Bar> bars = new ArrayList<>();
        for (List<BandCache.DateValue> list : rawData) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();

            for (BandCache.DateValue dv : list) {
                int right = xMapper.toPixel(viewpointTime.applyAsLong(dv.milli));
                int mappedLeft = xMapper.toPixel(viewpointTime.applyAsLong(dv.milli) - barWidthMillis);
                int left = barLeftPixel(mappedLeft, right);
                int valueY = yMapper.dataToPixel(dv.value);
                int top = Math.min(valueY, baselineY);
                int bottom = Math.max(valueY, baselineY);
                Color levelColor = useMulticolor ? bandType.getLevelColor(dv.value) : null;
                bars.add(new Bar(left, top, right, bottom, levelColor));
            }
        }
        return new BarGraph(bars);
    }

    private static LineGraph buildPolylines(List<List<BandCache.DateValue>> rawData, TimeAxis.Mapper xMapper,
                                            YAxis.Mapper yMapper, LongUnaryOperator viewpointTime,
                                            boolean useMulticolor) throws InterruptedException {
        List<Polyline> polylines = new ArrayList<>();
        for (List<BandCache.DateValue> list : rawData) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();

            int size = list.size();
            if (size == 0)
                continue;

            int[] dates = new int[size];
            int[] yPixels = new int[size];
            float[] values = useMulticolor ? new float[size] : null;
            for (int i = 0; i < size; i++) {
                BandCache.DateValue dv = list.get(i);
                dates[i] = xMapper.toPixel(viewpointTime.applyAsLong(dv.milli));
                yPixels[i] = yMapper.dataToPixel(dv.value);
                if (values != null)
                    values[i] = dv.value;
            }
            polylines.add(new Polyline(dates, yPixels, values));
        }
        return new LineGraph(polylines);
    }

    private void graphUpdated(GraphData result, boolean fresh) {
        if (!fresh)
            return;

        graphData = result;
        DrawController.drawRequest();
    }

    @Override
    public String getStringValue(long ts) {
        float val = bandCache.getValue(propagationModel.getObservationTime(ts));
        if (val == YAxis.BLANK) {
            return "--";
        }
        if (bandType.isXRSB())
            return GOESLevel.getStringValue(val);
        return DrawConstants.valueFormatter.format(yAxis.scale(val));
    }

    static int barLeftPixel(int mappedLeft, int right) {
        int width = Math.max(1, right - mappedLeft);
        int gap = width / 40;
        return right - width + gap;
    }

    @Override
    public YAxis getYAxis() {
        return yAxis;
    }

    private void updateData(long start, long end) {
        if (!BandDownloads.hasCatalog(this))
            return;

        List<Interval> intervals = reserveDownloadIntervals(start, end);
        if (!intervals.isEmpty())
            BandDownloads.start(this, intervals);
    }

    List<Interval> reserveDownloadIntervals(long start, long end) {
        if (start == end)
            return List.of();

        // Round outward to UTC days so small range changes reuse downloaded or in-flight data.
        long day = TimeUtils.DAY_IN_MILLIS;
        long requestStart = Math.floorDiv(start, day) * day;
        long requestEnd = (Math.floorDiv(end - 1, day) + 1) * day;
        List<Interval> intervals = new ArrayList<>();
        for (Interval missing : requestCache.adaptRequestCache(requestStart, requestEnd))
            intervals.addAll(Interval.splitInterval(missing, MAX_REQUEST_DAYS));
        return intervals;
    }

    void requestFailed(Interval interval) {
        requestCache.removeRequestedInterval(interval.start(), interval.end());
        notifyStateChanged();
    }

    void downloadSucceeded(@Nullable BandData data) {
        if (data != null)
            addToCache(data.values(), data.dates());
        notifyStateChanged();
    }

    void downloadStateChanged() {
        notifyStateChanged();
    }

    @Override
    public void fetchData(TimeAxis timeAxis) {
        long start = propagationModel.getObservationTime(timeAxis.start());
        long end = propagationModel.getObservationTime(timeAxis.end());
        updateData(start, end);
        updateGraph();
    }

    @Override
    public void graphGeometryChanged() {
        updateGraph();
    }

    @Override
    public void yaxisChanged() { // the user dragged or scrolled the axis: they have taken over
        autoFit = false;
        notifyStateChanged(); // the range moved, so a saved state must follow
        updateGraph();
        optionsPanel.syncAxis();
    }

    boolean addToCache(float[] values, long[] dates) {
        boolean hadData = bandCache.hasData();
        bandCache.addToCache(yAxis, values, dates);
        updateGraph();
        return !hadData && bandCache.hasData();
    }

    @Override
    public String toString() {
        return bandType.getName();
    }

    @Override
    public boolean isPropagated() {
        return propagationModel.isPropagated();
    }

    @Override
    public long getObservationTime(long ts) {
        return propagationModel.getObservationTime(ts);
    }

    /** The spacecraft this row's data came from, for its buoy in the 3D scene: the pick, else a guess from the name. */
    @Nullable
    public org.helioviewer.jhv.layers.grid.DataBuoys.Craft getSpacecraft() {
        if (spacecraftKey != null)
            return org.helioviewer.jhv.layers.grid.DataBuoys.byKey(spacecraftKey);
        return org.helioviewer.jhv.layers.grid.DataBuoys.infer(getName() + " " + bandType.getName());
    }

    @Nullable
    String getSpacecraftKey() {
        return spacecraftKey;
    }

    /** Null means automatic; "none" means this row has no buoy. */
    void setSpacecraftKey(@Nullable String key) {
        spacecraftKey = key;
        org.helioviewer.jhv.display.DisplayController.display();
        notifyStateChanged();
    }

    void setPropagationModel(PropagationModel _propagationModel) {
        propagationModel = _propagationModel;
        TimeAxis timeAxis = DrawController.selectedAxis;
        updateData(propagationModel.getObservationTime(timeAxis.start()), propagationModel.getObservationTime(timeAxis.end()));
        DrawController.layoutChanged();
    }

}
