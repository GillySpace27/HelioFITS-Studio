package org.helioviewer.jhv.display;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.json.JSONObject;

/**
 * The sun pizza's cut (HS-25): which slot owns which cell, how far a seamless spin turns, the spin
 * recording's steps, and what a session saves.
 *
 * <p>The shader decides which layer a fragment belongs to (imageCommon.frag, pizzaSlotOf) and the
 * labels and the processing record ask the same question in Java (Pizza.slotOfCell). The two must
 * be the same formula, so the shader text is pinned here as well as the Java behaviour. Every
 * repeat must hold every slot exactly once, shuffled or not, or a channel would go missing from a
 * repeat. A spin's last frame must stop one step short of the first, or the looped movie stutters
 * on a repeated frame.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.PizzaCheck
 */
public final class PizzaCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // In order: cell c is slot c mod n.
        boolean inOrder = true;
        for (int c = 0; c < 40; c++)
            inOrder &= Pizza.slotOfCell(c, 10, 0) == c % 10;
        expect("unshuffled, cell c belongs to slot c mod n", inOrder);

        // Every repeat holds every slot once, for every seed, and for negative cells (squares left of centre).
        boolean permutation = true;
        for (int n = 1; n <= 12; n++)
            for (int seed = 0; seed < 15; seed++)
                for (int r = -3; r < 4; r++) {
                    boolean[] seen = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        int s = Pizza.slotOfCell(r * n + i, n, seed);
                        if (s < 0 || s >= n || seen[s])
                            permutation = false;
                        else
                            seen[s] = true;
                    }
                }
        expect("each repeat holds every slot exactly once, any seed, any repeat (negative too)", permutation);
        expect("a seed turns repeat r by r times the seed: cell 10 of 10 slots with seed 3 is slot 3",
                Pizza.slotOfCell(10, 10, 3) == 3);
        expect("no slots never divides by zero", Pizza.slotOfCell(7, 0, 2) == 0);

        String frag = Files.readString(Path.of("resources/glsl/imageCommon.frag"));
        expect("the shader's pizzaSlotOf is slotOfCell's formula", frag.replace(" ", "")
                .contains("returnmod(c+floor(c/n)*display.pizzaD.z,n);"));
        expect("clipPlanarMasks runs the pizza cut first, so every projection takes it",
                frag.replaceAll("\\s+", "").contains("voidclipPlanarMasks(constvec2point){clipPizza(point);"));

        // Seamless loops.
        expect("one ring of 3 repeats loops in 120 degrees, got " + Pizza.loopDegrees(3, 5, false, 0),
                Pizza.loopDegrees(3, 5, false, 0) == 120);
        expect("rings of 4 and 6 repeats loop together in 180 degrees, got " + Pizza.loopDegrees(4, 6, true, 0),
                Pizza.loopDegrees(4, 6, true, 0) == 180);
        expect("rings of 2 and 3 repeats need the full turn", Pizza.loopDegrees(2, 3, true, 0) == 360);
        expect("a shuffled pizza needs the full turn", Pizza.loopDegrees(6, 1, false, 4) == 360);
        expect("repeats below 1 count as 1", Pizza.loopDegrees(0, 0, true, 0) == 360);

        // Spin steps: N frames over the loop, the last one step short of the first.
        double step = Pizza.spinTurn(10, 1, 4, 120, 1) - Pizza.spinTurn(10, 0, 4, 120, 1);
        expect("4 frames over 120 degrees step by 30, got " + step, step == 30);
        expect("the last frame stops one step short, at 100 degrees past the start, got " + (Pizza.spinTurn(10, 3, 4, 120, 1) - 10),
                Pizza.spinTurn(10, 3, 4, 120, 1) == 100);
        expect("a negative speed spins the other way", Pizza.spinTurn(0, 1, 4, 120, -1) == -30);

        // Persistence: nothing while off; everything back by value; unknown names fall back.
        Pizza.fromJson(null, List.of());
        expect("the pizza off writes nothing into a session", Pizza.toJson() == null);
        JSONObject in = new JSONObject().put("shape", "Tiles").put("space", "Screen").put("twoRings", true)
                .put("boundary", 2.5).put("outerRadius", 3).put("gap", 4).put("jitter", .3).put("seed", 7)
                .put("tile", .25).put("rows", 9).put("shift", 2).put("turn", 400).put("spin", "Movie")
                .put("degPerFrame", 3).put("labels", "First").put("angstrom", true)
                .put("rings", new org.json.JSONArray().put(new JSONObject().put("repeats", 3).put("angle", 15))
                        .put(new JSONObject().put("repeats", 5).put("counter", true)));
        Pizza.fromJson(in, List.of());
        expect("a saved pizza restores its shape, space and rings", Pizza.getShape() == Pizza.Shape.Tiles
                && Pizza.getSpace() == Pizza.Space.Screen && Pizza.isTwoRings() && Pizza.getBoundary() == 2.5
                && Pizza.getRepeats(Pizza.INNER) == 3 && Pizza.getRepeats(Pizza.OUTER) == 5
                && Pizza.isCounterRotating(Pizza.OUTER) && !Pizza.isCounterRotating(Pizza.INNER));
        expect("a saved pizza restores its cut and labels", Pizza.getGap() == 4 && Pizza.getJitter() == .3
                && Pizza.getSeed() == 7 && Pizza.getTile() == .25 && Pizza.getRows() == 9 && Pizza.getShift() == 2
                && Pizza.getLabels() == Pizza.Labels.First && Pizza.isAngstrom());
        expect("a turn past 360 is wrapped, got " + Pizza.getTurn(), Pizza.getTurn() == 40);
        JSONObject out = Pizza.toJson();
        Pizza.fromJson(out, List.of());
        expect("toJson then fromJson is the same pizza", out != null && out.similar(Pizza.toJson()));
        Pizza.fromJson(new JSONObject().put("shape", "Calzone").put("rings", new org.json.JSONArray().put(new JSONObject().put("repeats", 999))), List.of());
        expect("a shape from a newer build restores as Off, and repeats are held to the maximum",
                Pizza.getShape() == Pizza.Shape.Off && Pizza.getRepeats(Pizza.INNER) == Pizza.MAX_REPEATS);
        Pizza.aimTurn(-30);
        expect("the automation setter wraps a negative turn to 330, got " + Pizza.getTurn(), Pizza.getTurn() == 330);
        Pizza.fromJson(null, List.of());
        expect("a session without the key leaves the pizza off", !Pizza.isOn());

        expect("the pizza is drawn in Orthographic, HPC, Helioradial and Helioradial Unrolled only",
                Pizza.supports(MapMode.Orthographic) && Pizza.supports(MapMode.HPC) && Pizza.supports(MapMode.Helioradial)
                        && Pizza.supports(MapMode.HelioradialUnrolled) && !Pizza.supports(MapMode.Latitudinal)
                        && !Pizza.supports(MapMode.ObserverSky));

        System.out.println(failures == 0 ? "PizzaCheck: PASS" : "PizzaCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PizzaCheck() {}

}
