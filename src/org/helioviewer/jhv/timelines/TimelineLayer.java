package org.helioviewer.jhv.timelines;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

import javax.annotation.Nullable;
import javax.swing.JPanel;

import org.helioviewer.jhv.timelines.draw.ClickableDrawable;
import org.helioviewer.jhv.timelines.draw.TimeAxis;
import org.helioviewer.jhv.timelines.draw.YAxis;

import org.json.JSONObject;

public abstract class TimelineLayer {

    protected boolean enabled = true;
    private Runnable onStateChanged = () -> {};

    void setOnStateChanged(Runnable callback) {
        onStateChanged = callback;
    }

    protected final void notifyStateChanged() {
        onStateChanged.run();
    }

    /**
     * The panel is letting go of this layer. A teardown hook, not a user gesture: it fires when
     * the row is deleted AND when a state load replaces the whole stack, so it must release only
     * what the layer itself holds (timers, listeners) and never destroy what the layer is a view
     * of. See {@link #deleted()} for the other half.
     */
    public abstract void remove();

    /**
     * The user hit the delete column. Only this means "the thing itself should go".
     *
     * <p>Split out because {@link #remove()} cannot tell the two apart: TimelineLayers.remove and
     * TimelineLayers.restore both call it, and the second is a state load replacing the stack. A
     * layer that owns its own data has nothing to do here; one that is a view of something stored
     * elsewhere (an automation track lives in the session's own object, not in "timelines") has to
     * delete that something here rather than in remove(), or reloading a session deletes the
     * animation it has just finished loading.
     */
    public void deleted() {}

    public void setEnabled(boolean _enabled) {
        enabled = _enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public abstract String getName();

    @Nullable
    public abstract Color getDataColor();

    public abstract boolean isDownloading();

    public abstract boolean hasData();

    @Nullable
    public abstract JPanel getOptionsPanel();

    public abstract boolean isDeletable();

    public abstract void draw(Graphics2D g, Rectangle graphArea, TimeAxis timeAxis, Point mousePosition);

    @Nullable
    public YAxis getYAxis() {
        return null;
    }

    public abstract void fetchData(TimeAxis selectedAxis);

    public void graphGeometryChanged() {}

    public void yaxisChanged() {}

    public void zoomToFitAxis() {}

    public void resetAxis() {}

    public boolean highlightChanged(Point p) {
        return false;
    }

    @Nullable
    public String getStringValue(long ts) {
        return null;
    }

    @Nullable
    public ClickableDrawable getDrawableUnderMouse() {
        return null;
    }

    public abstract void serialize(JSONObject jo);

    public boolean isPropagated() {
        return false;
    }

    public long getObservationTime(long ts) {
        return ts;
    }

}
