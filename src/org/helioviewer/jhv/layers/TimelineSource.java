package org.helioviewer.jhv.layers;

import java.util.Collection;

import org.helioviewer.jhv.time.JHVTime;

/**
 * A layer that carries its own timestamps and can therefore drive the movie clock.
 *
 * <p>Image layers already do this, through the frames of their own view. This interface is for
 * the layers that also have a real time series but are not imagery: a point cloud loaded as one
 * file per epoch is the case that motivated it.
 *
 * <p>Before this existed, such a layer could only drive the clock when nothing else was loaded
 * (see {@code Layers.setPlaceholderMasterTimes}, which is a no-op the moment any image layer
 * appears). That is the right default, since imagery usually carries the authoritative cadence,
 * but it left no way to say otherwise. The result was a cloud series silently pinned to whichever
 * of its frames happened to sit nearest the imagery's epoch, which looks exactly like a layer
 * that failed to animate.
 *
 * <p>Implementing this makes the layer eligible for the master-transport control in the layer
 * table. It does not make it the master; the user still has to pick it.
 */
public interface TimelineSource {

    /**
     * The timestamps this layer would drive the clock with.
     *
     * <p>Return fewer than two and the layer is not offered as a timeline source: a single
     * instant is not a sequence, and a master with one frame would freeze playback.
     */
    Collection<JHVTime> getTimelineTimes();

    /** Whether this layer currently has enough of a series to be worth offering. */
    default boolean canDriveTimeline() {
        return getTimelineTimes().size() > 1;
    }
}
