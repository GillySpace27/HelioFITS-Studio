package org.helioviewer.jhv.layers;

/**
 * Stop every image load and download, from any thread, without the event thread's help.
 *
 * <p>This is what the watchdog and the local API's /stop call when the window is not answering, so
 * it must not touch Swing or the layer list. The layers are left as they are: a cancelled layer
 * shows what it had, and Refresh on it asks again.
 */
public final class LoadStop {

    private LoadStop() {}

    /** The number of loads stopped. */
    public static int cancelAll() {
        return ImageLayerLoader.cancelAllActive();
    }
}
