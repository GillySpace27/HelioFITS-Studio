package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Standalone self-check (no test framework in this repo -- see extra/test/LUTLabelsCheck.java for
// the pattern). Guards the fan-out rule that makes multi-select mean anything: a control edited
// with several layers selected has to reach every selected layer, and a control edited on a layer
// that is NOT part of the selection has to stay local.
//
// This checks the decision, not the GLImage plumbing. Layers.applyToSelected needs real
// ImageLayers (each of which needs a View, a decoder and a GL context), so the membership rule is
// reproduced here against the same selection list and asserted directly. If the rule in
// Layers.applyToSelectedLayers changes, change it here too -- the two are deliberately parallel
// and this file exists to make a silent divergence loud.
public final class LayerSelectionFanoutCheck {

    // Mirrors Layers.applyToSelectedLayers, over plain Layers so it can run headless.
    private static void applyToSelected(Layer origin, List<Layer> selection, Consumer<Layer> edit) {
        edit.accept(origin);
        if (!selection.contains(origin))
            return;
        for (Layer layer : selection)
            if (layer != origin)
                edit.accept(layer);
    }

    private static int failures;

    public static void main(String[] args) {
        Layer a = named("a"), b = named("b"), c = named("c");

        // One selected layer: only that layer is touched.
        check(List.of(a), a, List.of(a), "single selection touches only itself");

        // Three selected: editing any one of them reaches all three, exactly once each.
        check(List.of(a, b, c), a, List.of(a, b, c), "edit on the lead reaches the whole selection");
        check(List.of(a, b, c), b, List.of(b, a, c), "edit on a non-lead member still reaches all");

        // The origin must never be applied twice, which would double-apply a relative edit.
        List<Layer> hits = collect(List.of(a, b, c), a);
        if (hits.stream().filter(l -> l == a).count() != 1) {
            System.out.println("FAIL: origin applied " + hits.stream().filter(l -> l == a).count() + " times, want 1");
            failures++;
        }

        // Editing a layer that is not in the selection stays local. This is the case that keeps a
        // programmatic refresh, or a panel driven while its layer is deselected, from leaking an
        // edit onto layers the user did select.
        check(List.of(b, c), a, List.of(a), "edit on an unselected layer stays local");

        // An empty selection behaves the same way.
        check(List.of(), a, List.of(a), "empty selection stays local");

        System.out.println(failures == 0 ? "LayerSelectionFanoutCheck: PASS" : "LayerSelectionFanoutCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static List<Layer> collect(List<Layer> selection, Layer origin) {
        List<Layer> hit = new ArrayList<>();
        applyToSelected(origin, selection, hit::add);
        return hit;
    }

    private static void check(List<Layer> selection, Layer origin, List<Layer> want, String what) {
        List<Layer> got = collect(selection, origin);
        if (!got.equals(want)) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static Layer named(String name) {
        return new AbstractLayer() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void init() {}

            @Override
            public void dispose() {}

            @Override
            public void remove() {}

            @Override
            public void serialize(org.json.JSONObject json) {}

            @Override
            public String toString() {
                return name;
            }
        };
    }

    private LayerSelectionFanoutCheck() {}
}
