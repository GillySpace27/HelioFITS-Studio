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

public interface TimelineLayer {

    /**
     * The panel is letting go of this layer. A teardown hook, not a user gesture: it fires when
     * the row is deleted AND when a state load replaces the whole stack, so it must release only
     * what the layer itself holds (timers, listeners) and never destroy what the layer is a view
     * of. See {@link #deleted()} for the other half.
     */
    void remove();

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
    default void deleted() {}

    void setEnabled(boolean enabled);

    boolean isEnabled();

    String getName();

    @Nullable
    Color getDataColor();

    boolean isDownloading();

    boolean hasData();

    @Nullable
    JPanel getOptionsPanel();

    boolean isDeletable();

    boolean showYAxis();

    void draw(Graphics2D g, Rectangle graphArea, TimeAxis timeAxis, Point mousePosition);

    YAxis getYAxis();

    void fetchData(TimeAxis selectedAxis);

    default void yaxisChanged() {}

    default void zoomToFitAxis() {}

    default void resetAxis() {}

    default boolean highlightChanged(Point p) {
        return false;
    }

    @Nullable
    default String getStringValue(long ts) {
        return null;
    }

    @Nullable
    default ClickableDrawable getDrawableUnderMouse() {
        return null;
    }

    void serialize(JSONObject jo);

    default boolean isPropagated() {
        return false;
    }

    default long getObservationTime(long ts) {
        return ts;
    }

}
