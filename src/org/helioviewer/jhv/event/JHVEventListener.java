package org.helioviewer.jhv.event;

public class JHVEventListener {

    public interface Handle {
        void cacheUpdated();
    }

    public interface Highlight {
        void highlightChanged();
    }

}
