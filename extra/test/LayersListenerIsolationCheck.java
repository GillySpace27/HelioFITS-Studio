package org.helioviewer.jhv.layers;

import java.util.List;

import org.json.JSONObject;

/**
 * One broken layer listener cannot stop a layer being added or removed.
 *
 * <p>The Fourier filter palette kept the live view Layers.getImageLayers() returns and compared the
 * next one against it, which throws ConcurrentModificationException once the layer list has changed.
 * It threw from inside Layers.add's listener loop, so the listeners after it never heard of the new
 * layer, ImageLayer.create never reached load(), and the layer sat at "Loading..." with nothing in the
 * log. New Session stopped after its first removal the same way. Gilly's report, 2026-09-14.
 *
 * <p>The listener here is written exactly that way, so it throws on the second add just as the palette
 * did. The palette itself now keeps a copy; this pins that no other listener can do the same damage.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.LayersListenerIsolationCheck
 */
public final class LayersListenerIsolationCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-layer-listeners").toString());
        // As LayersReorderCheck: the registry's null image layer reaches SPICE through its metadata.
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        Layers.addListener(new Quiet() { // the palette's old pattern, verbatim in spirit
            private List<ImageLayer> last;

            @Override
            public void layerAdded(int index, Layer layer) {
                compare();
            }

            @Override
            public void layerRemoved(int index, Layer layer) {
                compare();
            }

            private void compare() {
                List<ImageLayer> current = Layers.getImageLayers();
                if (last != null && !current.equals(last)) // throws once the list has changed under `last`
                    return;
                last = current;
            }
        });
        int[] added = {0};
        int[] removed = {0};
        Layers.addListener(new Quiet() { // registered after it, as LayerOptionSections and friends are
            @Override
            public void layerAdded(int index, Layer layer) {
                added[0]++;
            }

            @Override
            public void layerRemoved(int index, Layer layer) {
                removed[0]++;
            }
        });

        Layer first = new Dummy("first"), second = new Dummy("second");
        Layers.add(first);
        Layers.add(second); // the stale view throws here

        expect("the layer is added although a listener threw", Layers.getLayers().contains(second));
        expect("and the listener after the broken one still heard about both (" + added[0] + ")", added[0] == 2);

        Layers.remove(first);
        Layers.remove(second);
        expect("removing goes on past the broken listener too (" + removed[0] + ")", removed[0] == 2);
        expect("and both layers are gone, as New Session needs",
                !Layers.getLayers().contains(first) && !Layers.getLayers().contains(second));

        System.out.println(failures == 0 ? "LayersListenerIsolationCheck: PASS" : "LayersListenerIsolationCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private abstract static class Quiet implements Layers.Listener {
        @Override
        public void layerAdded(int index, Layer layer) {}

        @Override
        public void layerRemoved(int index, Layer layer) {}

        @Override
        public void layersCleared() {}

        @Override
        public void nameUpdated(Layer layer) {}

        @Override
        public void layerUpdated(Layer layer) {}

        @Override
        public void timeUpdated(Layer layer) {}
    }

    private record Dummy(String name) implements Layer {
        @Override public void remove() {}
        @Override public String getName() { return name; }
        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean b) {}
        @Override public int isVisibleIdx() { return 0; }
        @Override public boolean isVisible(int idx) { return true; }
        @Override public void setVisible(int idx) {}
        @Override public void init() {}
        @Override public void dispose() {}
        @Override public void serialize(JSONObject jo) {}
        @Override public boolean isDeletable() { return true; }
    }

    private LayersListenerIsolationCheck() {}
}
