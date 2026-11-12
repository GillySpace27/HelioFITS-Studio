package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * Highlight All is quiet until the pointer comes near. Three tiers: an ambient mark is one thin,
 * dim line on a small ring with no halo; the mark under the pointer (hover) is the full glow, and
 * a clicked one (selected) keeps it. A hovered or selected mark replaces its ambient copy rather
 * than being drawn over it. Turning Glow off no longer makes every ring of Highlight All loud.
 * Planets keep every combination of the two symbol switches.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyGlowTierCheck
 */
public final class IdentifyGlowTierCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** 30 point-like marks fanned over the middle of the page. */
    private static final Drawn.Source SOURCE = new Drawn.Source() {
        private final List<Mark> marks = new ArrayList<>();

        {
            Random rnd = new Random(5);
            for (int i = 0; i < 30; i++) {
                Vec3 ray = Identifier.ray(Math.toRadians((rnd.nextDouble() - .5) * 2.4), Math.toRadians((rnd.nextDouble() - .5) * 1.8));
                marks.add(new Mark("dso:" + i, Kind.GALAXY, null, observer -> ray, 0));
            }
        }

        @Override
        public List<Drawn.Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
            return List.of();
        }

        @Override
        public List<Mark> marks(Viewport vp, MapView mv, Identifier.Target first) {
            return marks;
        }
    };

    /** The observer twice as far from the Sun as Mercury, on its line, shifted sideways: Mercury in front of the disk, 0.14 deg from its middle. */
    private static Position observer(JHVTime time, double side) {
        Vec3 w = Bodies.world(Bodies.byName("Mercury"), time);
        Vec3 n = Identifier.unit(Vec3.cross(w, new Vec3(0, 0, 1)));
        Vec3 o = new Vec3(2 * w.x + side * n.x, 2 * w.y + side * n.y, 2 * w.z + side * n.z);
        return ModelPlacement.carringtonPosition(time, o.z, o.x, o.y);
    }

    /** A body a few pixels across is drawn at its minimum ring, a few percent over (the sizing pass aims 5 percent high). */
    private static boolean atMost(double radius, double min) {
        return radius >= min - 1e-9 && radius < 1.15 * min;
    }

    private static double meanRadius(IdentifyGlow.Ring r) {
        double sum = 0;
        int n = 0;
        for (double[] p : r.points())
            if (p != null) {
                sum += Math.hypot(p[0] - r.centre()[0], p[1] - r.centre()[1]);
                n++;
            }
        return n == 0 ? 0 : sum / n;
    }

    private static IdentifyGlow.Ring ringAt(IdentifyGlow.Scene s, double[] at) {
        IdentifyGlow.Ring found = null;
        for (IdentifyGlow.Ring r : s.rings())
            if (Math.hypot(r.centre()[0] - at[0], r.centre()[1] - at[1]) < 1)
                found = r;
        return found;
    }

    private static long near(IdentifyGlow.Scene s, double[] at) {
        return s.rings().stream().filter(r -> Math.hypot(r.centre()[0] - at[0], r.centre()[1] - at[1]) < 1).count();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-glow-tier").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        // The looks, as data
        IdentifyGlow.Style quiet = IdentifyGlow.QUIET, loud = IdentifyGlow.LOUD;
        double quietCore = quiet.widths()[quiet.widths().length - 1], loudCore = loud.widths()[loud.widths().length - 1];
        int quietMax = java.util.Arrays.stream(quiet.alphas()).max().orElse(0), loudMax = java.util.Arrays.stream(loud.alphas()).max().orElse(0);
        expect("an ambient mark is one pass with no halo", quiet.widths().length == 1 && loud.widths().length == 3);
        expect("and its line is thinner than the hovered core (" + quietCore + " < " + loudCore + " px)", quietCore < loudCore);
        expect("and dimmer (alpha " + quietMax + " < " + loudMax + ")", quietMax < loudMax);
        expect("and its ring may be smaller (" + quiet.minRadius() + " < " + loud.minRadius() + " px)", quiet.minRadius() < loud.minRadius());
        IdentifyGlow.Flags glowOn = new IdentifyGlow.Flags(true, false, true, false, false);
        IdentifyGlow.Flags glowOff = new IdentifyGlow.Flags(false, false, true, false, false);
        IdentifyGlow.Flags glowOffNoAll = new IdentifyGlow.Flags(false, false, false, false, false);
        expect("with Glow on, hover and selected are the full glow and ambient stays quiet",
                IdentifyGlow.styleFor(IdentifyGlow.Tier.HOVER, glowOn) == loud && IdentifyGlow.styleFor(IdentifyGlow.Tier.SELECTED, glowOn) == loud
                        && IdentifyGlow.styleFor(IdentifyGlow.Tier.AMBIENT, glowOn) == quiet);
        expect("with Glow off, Highlight All does not turn loud: ambient stays quiet and a reached mark keeps its thin line",
                IdentifyGlow.styleFor(IdentifyGlow.Tier.AMBIENT, glowOff) == quiet && IdentifyGlow.styleFor(IdentifyGlow.Tier.HOVER, glowOff) == quiet
                        && IdentifyGlow.styleFor(IdentifyGlow.Tier.SELECTED, glowOff) == quiet);
        expect("with Glow off and no Highlight All, a reached mark draws no glow at all", !IdentifyGlow.styleFor(IdentifyGlow.Tier.HOVER, glowOffNoAll).visible());

        // A scene: Mercury in front of the Sun, thirty ambient marks about it
        var fr = Display.class.getDeclaredField("fieldRadius");
        fr.setAccessible(true);
        fr.set(null, (DoubleSupplier) () -> 60);
        Display.setHelioradial3D(false);
        Display.setMapMode(MapMode.HPC);
        Drawn.register(SOURCE);
        IdentifySettings.setEnabled(true);
        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
        Position obs = observer(new JHVTime("2026-03-26T12:00:00"), 0.4);
        var reset = org.helioviewer.jhv.display.Camera.class.getDeclaredMethod("reset", Position.class);
        reset.setAccessible(true);
        reset.invoke(Display.getCamera(), obs);
        var f = org.helioviewer.jhv.display.Camera.class.getDeclaredField("cameraWidth");
        f.setAccessible(true);
        f.setDouble(Display.getCamera(), 1);
        MapView mv = MapView.create(Display.getCamera(), obs, MapMode.HPC, GridType.Viewpoint, new MapScale[]{MapScale.hpc(2.5, 2.0)});
        List<Identifier.Target> targets = IdentifyHover.targets(vp, mv);
        boolean light = Identifier.Options.fromSettings().lightTime();
        Vec3 dir = Identifier.bodyDirection(Bodies.byName("Mercury"), obs, light);
        double[] at = dir == null ? null : IdentifyPins.locate(vp, mv, targets.getFirst(), dir);
        expect("Mercury is on the page", at != null);
        if (at == null)
            System.exit(1);

        IdentifyHover.exited();
        IdentifyGlow.Scene calm = IdentifyGlow.scene(vp, mv, targets, glowOn, 1);
        long ambient = calm.rings().stream().filter(r -> r.tier() == IdentifyGlow.Tier.AMBIENT).count();
        IdentifyGlow.Ring mercuryCalm = ringAt(calm, at);
        expect("with nothing under the pointer every ring is ambient (" + ambient + " of " + calm.rings().size() + ")", ambient == calm.rings().size() && ambient > 20);
        expect("Mercury is one of them, on the ambient ring size (about 4 px)", mercuryCalm != null && mercuryCalm.tier() == IdentifyGlow.Tier.AMBIENT
                && atMost(meanRadius(mercuryCalm), quiet.minRadius()));

        IdentifyHover.footer(vp, mv, (int) Math.round(at[0]), (int) Math.round(at[1]));
        expect("the pointer names Mercury", IdentifyHover.lastMatches().stream().anyMatch(m -> m.body().name().equals("Mercury")));
        IdentifyGlow.Scene hot = IdentifyGlow.scene(vp, mv, targets, glowOn, 1);
        IdentifyGlow.Ring mercuryHot = ringAt(hot, at);
        expect("under the pointer Mercury's ring is the hover tier, on the full ring size (about 7 px)", mercuryHot != null && mercuryHot.tier() == IdentifyGlow.Tier.HOVER
                && atMost(meanRadius(mercuryHot), loud.minRadius()));
        expect("it replaces its ambient copy: the same number of rings, one at Mercury", hot.rings().size() == calm.rings().size() && near(hot, at) == 1);
        expect("and only it is above ambient", hot.rings().stream().filter(r -> r.tier() != IdentifyGlow.Tier.AMBIENT).count() == 1);

        IdentifyHover h = IdentifyHover.selectionForCheck();
        expect("a click selects Mercury", h.clickSelect(vp, mv, (int) Math.round(at[0]), (int) Math.round(at[1]), targets) && IdentifyHover.selected());
        IdentifyGlow.Scene sel = IdentifyGlow.scene(vp, mv, targets, glowOn, 1);
        IdentifyGlow.Ring mercurySel = ringAt(sel, at);
        expect("a selected mark is the selected tier, still one ring, still the full-size ring", mercurySel != null && mercurySel.tier() == IdentifyGlow.Tier.SELECTED
                && sel.rings().size() == calm.rings().size() && near(sel, at) == 1 && atMost(meanRadius(mercurySel), loud.minRadius()));
        IdentifyHover.deselect();

        // The four combinations of the two planet-marker boxes, for a reached planet and for an ambient one
        for (boolean reached : new boolean[]{true, false}) {
            IdentifyHover.exited();
            if (reached)
                IdentifyHover.footer(vp, mv, (int) Math.round(at[0]), (int) Math.round(at[1]));
            for (boolean always : new boolean[]{false, true})
                for (boolean instead : new boolean[]{false, true}) {
                    IdentifyGlow.Scene s = IdentifyGlow.scene(vp, mv, targets, new IdentifyGlow.Flags(true, false, true, always, instead), 1);
                    IdentifyGlow.Ring ring = ringAt(s, at);
                    IdentifyGlow.Glyph glyph = s.glyphs().stream().filter(g -> Math.hypot(g.centre()[0] - at[0], g.centre()[1] - at[1]) < 1).findFirst().orElse(null);
                    boolean symbol = always || instead; // Highlight All is on, so a ring is wanted either way
                    boolean wantRing = !instead; // "instead" puts the symbol where the ring would be
                    String tag = (reached ? "reached" : "ambient") + ", always=" + always + " instead=" + instead + ": ";
                    expect(tag + (wantRing ? "a ring" : "no ring (the symbol stands in)"), (ring != null) == wantRing);
                    expect(tag + (symbol ? "a symbol" : "no symbol"), (glyph != null) == symbol);
                    if (glyph != null)
                        expect(tag + "the symbol is the quiet size only when the planet is not reached", glyph.ambient() == !reached && glyph.replaces() == (instead && symbol));
                }
        }
        Drawn.unregister(SOURCE);
        System.out.println(failures == 0 ? "IdentifyGlowTierCheck: all passed" : "IdentifyGlowTierCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private IdentifyGlowTierCheck() {}
}
