package org.helioviewer.jhv.timelines.draw;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.timelines.AutomationTimelineLayer;
import org.helioviewer.jhv.timelines.TimelineLayer;

public final class GraphGeometry {

    private static final int MINIMUM_HEIGHT = 50;
    private static final int MINIMUM_STACKED_LAYER_HEIGHT = 64;
    private static final int STACKED_SEPARATOR = 8;
    private static final int MIN_SHARED_HEIGHT = 30; // what the band leaves the HEK, coverage and curve layers

    private final Rectangle size = new Rectangle();
    private Rectangle area = new Rectangle();
    // The animation lanes' own band, carved off the top of the plot so they never share pixels with
    // the HEK event bands (which fill down from the top of area) or the coverage rows (which fill
    // up from its bottom). Empty when nothing is animated.
    private Rectangle automationArea = new Rectangle();
    private int automationLanes;
    private boolean stacked;
    private final ArrayList<LayerLayout> layerLayouts = new ArrayList<>();
    private final ArrayList<TimelineLayer> propagatedLayers = new ArrayList<>();
    private final List<LayerLayout> exposedLayerLayouts = Collections.unmodifiableList(layerLayouts);
    private final List<TimelineLayer> exposedPropagatedLayers = Collections.unmodifiableList(propagatedLayers);

    public record LayerLayout(TimelineLayer layer, YAxis yAxis, Rectangle area, int axisIndex) {}

    private record AxisLayer(TimelineLayer layer, YAxis yAxis) {}

    public void setSize(int width, int height) {
        size.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
    }

    void setStacked(boolean _stacked) {
        stacked = _stacked;
    }

    public void layout(List<TimelineLayer> layers) {
        layerLayouts.clear();
        propagatedLayers.clear();

        ArrayList<AxisLayer> axisLayers = new ArrayList<>();
        automationLanes = 0;
        for (TimelineLayer layer : layers) {
            if (layer.isEnabled() && layer instanceof AutomationTimelineLayer)
                automationLanes++;
            if (layer.isEnabled() && layer.isPropagated())
                propagatedLayers.add(layer);
            if (layer.isEnabled()) {
                YAxis yAxis = layer.getYAxis();
                if (yAxis != null)
                    axisLayers.add(new AxisLayer(layer, yAxis));
            }
        }
        int yAxisCount = axisLayers.size();

        int height = size.height - (DrawConstants.GRAPH_TOP_SPACE + DrawConstants.GRAPH_BOTTOM_SPACE
                + DrawConstants.GRAPH_BOTTOM_AXIS_SPACE * (propagatedLayers.size() + 1));
        // The band takes what it asks for while the rest of the plot keeps MIN_SHARED_HEIGHT. On a
        // plot shorter than that the lanes thin (AutomationTimelineLayer.strip), but never below
        // the height a thinned lane needs: a lane clipped to nothing looks like "Animate" failed.
        int bandH = automationLanes == 0 ? 0 : Math.min(AutomationTimelineLayer.bandHeight(automationLanes),
                Math.max(height - MIN_SHARED_HEIGHT, AutomationTimelineLayer.minBandHeight(automationLanes)));
        int top = DrawConstants.GRAPH_TOP_SPACE + bandH;
        height -= bandH;

        if (stacked && yAxisCount > 0) {
            int totalSeparatorHeight = STACKED_SEPARATOR * (yAxisCount - 1);
            int availableForStrips = Math.max(yAxisCount, height - totalSeparatorHeight);
            int stripHeight = Math.max(1, availableForStrips / yAxisCount);

            int totalHeight = stripHeight * yAxisCount + totalSeparatorHeight;
            int width = size.width - (DrawConstants.GRAPH_LEFT_SPACE + DrawConstants.GRAPH_RIGHT_SPACE);
            area = new Rectangle(DrawConstants.GRAPH_LEFT_SPACE, top,
                    Math.max(1, width), Math.max(1, totalHeight));

            int y = top;
            int axisIndex = -1;
            for (AxisLayer axisLayer : axisLayers) {
                Rectangle layerArea = new Rectangle(area.x, y, area.width, stripHeight);
                layerLayouts.add(new LayerLayout(axisLayer.layer, axisLayer.yAxis, layerArea, axisIndex));
                y += stripHeight + STACKED_SEPARATOR;
                axisIndex++;
            }
        } else {
            int rightAxisCount = Math.max(0, yAxisCount - 1);
            int width = size.width - (DrawConstants.GRAPH_LEFT_SPACE + DrawConstants.GRAPH_RIGHT_SPACE + rightAxisCount * DrawConstants.RIGHT_AXIS_WIDTH);
            area = new Rectangle(DrawConstants.GRAPH_LEFT_SPACE, top, Math.max(1, width), Math.max(1, height));

            int axisIndex = -1;
            for (AxisLayer axisLayer : axisLayers) {
                layerLayouts.add(new LayerLayout(axisLayer.layer, axisLayer.yAxis, area, axisIndex));
                axisIndex++;
            }
        }
        automationArea = bandH == 0 ? new Rectangle()
                : new Rectangle(area.x, DrawConstants.GRAPH_TOP_SPACE, area.width, bandH);
    }

    public boolean isStacked() {
        return stacked;
    }

    public int minimumHeight() {
        int band = automationLanes == 0 ? 0 : AutomationTimelineLayer.bandHeight(automationLanes);
        if (!stacked || layerLayouts.isEmpty())
            return band == 0 ? MINIMUM_HEIGHT : Math.max(MINIMUM_HEIGHT, DrawConstants.GRAPH_TOP_SPACE
                    + DrawConstants.GRAPH_BOTTOM_SPACE + DrawConstants.GRAPH_BOTTOM_AXIS_SPACE * (propagatedLayers.size() + 1)
                    + band + MIN_SHARED_HEIGHT);

        return DrawConstants.GRAPH_TOP_SPACE + DrawConstants.GRAPH_BOTTOM_SPACE
                + DrawConstants.GRAPH_BOTTOM_AXIS_SPACE * (propagatedLayers.size() + 1)
                + MINIMUM_STACKED_LAYER_HEIGHT * layerLayouts.size()
                + STACKED_SEPARATOR * (layerLayouts.size() - 1) + band;
    }

    public List<LayerLayout> getLayerLayouts() {
        return exposedLayerLayouts;
    }

    public List<TimelineLayer> getPropagatedLayers() {
        return exposedPropagatedLayers;
    }

    @Nullable
    public Rectangle getLayerArea(TimelineLayer layer) {
        for (LayerLayout layout : layerLayouts) {
            if (layout.layer == layer)
                return layout.area;
        }
        return null;
    }

    public Rectangle size() {
        return size;
    }

    public Rectangle area() {
        return area;
    }

    /** The animation lanes' band above {@link #area()}; zero height when nothing is animated. */
    public Rectangle automationArea() {
        return automationArea;
    }

    /** The shared plot and the animation band together: everything under the time axis's x range. */
    public Rectangle plotArea() {
        return automationArea.isEmpty() ? area : area.union(automationArea);
    }

    /** In the plot or in the animation band, for gestures (seeking) that mean the same in both. */
    public boolean inPlot(Point p) {
        return inGraph(p) || (!automationArea.isEmpty() && p.x >= automationArea.x && p.x <= graphRight()
                && p.y >= automationArea.y && p.y < automationArea.y + automationArea.height);
    }

    public int graphWidth() {
        return area.width;
    }

    public int graphHeight() {
        return area.height;
    }

    public int rightEdge() {
        return size.width - DrawConstants.GRAPH_RIGHT_SPACE;
    }

    public int graphRight() {
        return area.x + area.width;
    }

    public int graphBottom() {
        return area.y + area.height;
    }

    public boolean inGraph(Point p) {
        return p.x >= area.x && p.x <= graphRight() && p.y > area.y && p.y <= graphBottom();
    }

    public boolean inXAxisOrAboveGraph(Point p) {
        return p.x >= area.x && p.x <= graphRight() && (p.y <= area.y || p.y >= graphBottom());
    }

    public TimeAxis.Mapper xMapper(TimeAxis axis) {
        return axis.mapper(area.x, area.width);
    }

    public YAxis.Mapper yMapper(YAxis axis) {
        return axis.mapper(area.y, area.height);
    }

    public YAxis.Mapper yMapper(YAxis axis, Rectangle subArea) {
        return axis.mapper(subArea.y, subArea.height);
    }

    public int axisZoomY(Point p) {
        return graphBottom() - p.y;
    }

    @Nullable
    public LayerLayout getLayerLayout(Point p) {
        if (!stacked)
            return null;
        for (LayerLayout layout : layerLayouts) {
            Rectangle r = layout.area;
            if (p.x <= r.x + r.width && p.y >= r.y && p.y <= r.y + r.height)
                return layout;
        }
        return null;
    }

    public YAxisHit yAxisHit(Point p) {
        if (stacked) {
            return yAxisHitStacked(p);
        }
        boolean inYRange = p.y > area.y && p.y <= graphBottom();
        boolean rightAxes = inYRange && p.x > graphRight();
        boolean leftAxis = inYRange && p.x < area.x;
        int rightAxisNumber = (p.x - graphRight()) / DrawConstants.RIGHT_AXIS_WIDTH;
        if (leftAxis)
            return new YAxisHit(true, -1);
        if (rightAxes)
            return new YAxisHit(true, rightAxisNumber);
        return new YAxisHit(false, -1);
    }

    private YAxisHit yAxisHitStacked(Point p) {
        for (LayerLayout layout : layerLayouts) {
            Rectangle r = layout.area;
            if (p.y >= r.y && p.y <= r.y + r.height) {
                boolean leftAxis = p.x < r.x;
                return new YAxisHit(leftAxis, layout.axisIndex);
            }
        }
        return new YAxisHit(false, -1);
    }

    public record YAxisHit(boolean onAxis, int axisIndex) {
        public boolean outsideAxes() {
            return !onAxis;
        }

        public boolean targets(int axisIndex) {
            return onAxis && this.axisIndex == axisIndex;
        }
    }

}
