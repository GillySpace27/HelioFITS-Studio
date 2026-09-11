package org.helioviewer.jhv.layers;

import java.util.Arrays;
import java.util.UUID;

public abstract class AbstractLayer implements Layer {

    /**
     * Stable identity for this layer, for anything that has to name it across a save and a
     * reload. Neither of the two obvious substitutes works: getName() returns "Loading..." until
     * the data arrives and is not unique anyway (two AIA 171 layers share a name), and list
     * position is decided by an asynchronous restore that also prunes the layers that failed.
     * Written by State.layer2json and handed back by State on load; a layer restored from a
     * session saved before this existed simply keeps the fresh id generated here.
     */
    private String id = UUID.randomUUID().toString();

    public final String getId() {
        return id;
    }

    /** Called by State immediately after construction, with the id the session recorded. */
    public final void restoreId(String _id) {
        if (_id != null && !_id.isBlank())
            id = _id;
    }

    protected boolean enabled;
    protected final boolean[] isVisible = {false, false, false, false, false, false}; // match max of Display.viewports.length

    @Override
    public void setEnabled(boolean _enabled) {
        enabled = _enabled;
        Arrays.fill(isVisible, enabled);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setVisible(int j) {
        Arrays.fill(isVisible, false);
        if (j >= 0 && j < isVisible.length)
            isVisible[j] = true;
    }

    @Override
    public boolean isVisible(int idx) {
        return isVisible[idx];
    }

    @Override
    public int isVisibleIdx() {
        for (int i = 0; i < isVisible.length; i++) {
            if (isVisible[i])
                return i;
        }
        return -1;
    }

}
