package org.helioviewer.jhv.identify;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.layers.CometLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.metadata.XMLMetaDataContainer;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.view.uri.FITSImage;
import org.helioviewer.jhv.view.uri.URIView;

/**
 * A comet layer's rings and dot in Identify (comet3d plan, revision 2, section 4, CometMarksCheck),
 * with SPICE and the STEREO COR2 fixture header.
 *
 * <p>Pins: (1) a ring's family is its comet, though designations contain '/', and so are image
 * layer names sometimes; (2) of two rings half a pixel apart the first in target order is chosen,
 * not the nearer; (3) a selection made on one image's ring is the same selection on another's, so
 * it does not flip between C2 and C3; (4) the pointer on a ring names the comet with that ring,
 * and a target without an image gives no ring, only the dot of the Orthographic view; (5) hovering lights every ring of the comet without
 * building the frame's mark list; (6) Highlight All and CometSky give no second copy of a comet a
 * layer draws; (7) a comet's marks take its layer's colour. A missing fixture is a FAIL.
 */
public final class CometMarksCheck {

    private static int failures;
    private static final long DAY = 86_400_000L;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-marks-check");
        System.setProperty("user.home", home.toString());
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        AppInit.loadSpice();

        checkFamily();
        checkFirst();
        checkSelection();
        checkScene();

        if (failures != 0) {
            System.out.println("CometMarksCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometMarksCheck: PASS");
    }

    // Row 1
    private static void checkFamily() {
        Mark ring = new Mark("comet:C/2025 R3/LASCO C3", Kind.COMET, "LASCO C3", o -> null, 0);
        Mark slashed = new Mark("comet:P/2019 LD2/SECCHI COR2/A", Kind.COMET, "SECCHI COR2/A", o -> null, 0);
        Mark dot = new Mark("comet:C/2025 R3", Kind.COMET, null, o -> null, 0);
        Mark venus = new Mark("Venus/LASCO C3", Kind.PLANET, "LASCO C3", o -> null, 0);
        expect(CometMarks.family(ring).equals("comet:C/2025 R3") && CometMarks.family(dot).equals("comet:C/2025 R3"),
                "a ring and the dot of C/2025 R3 are one family (" + CometMarks.family(ring) + ")");
        expect(CometMarks.family(slashed).equals("comet:P/2019 LD2"), "and a layer name with '/' does not cut the designation (" + CometMarks.family(slashed) + ")");
        expect(CometMarks.family(venus).equals("Venus/LASCO C3"), "a body's mark keeps its own key");
    }

    // Row 2
    private static void checkFirst() {
        List<double[]> centres = new ArrayList<>();
        centres.add(new double[]{100, 100});
        centres.add(null);
        centres.add(new double[]{100.5, 100});
        int i = CometMarks.firstWithin(centres, 100.4, 100, 7);
        expect(i == 0, "of two rings 0.5 px apart, the first in target order is chosen, not the nearer (" + i + ")");
        expect(CometMarks.firstWithin(centres, 200, 200, 7) == -1, "and none when the pointer is off both");
    }

    // Row 3
    @SuppressWarnings("unchecked")
    private static void checkSelection() throws Exception {
        Method keys = IdentifyHover.class.getDeclaredMethod("drawnKeys", List.class);
        keys.setAccessible(true);
        Mark c2 = new Mark("comet:C/2025 R3/LASCO C2", Kind.COMET, "LASCO C2", o -> null, 0);
        Mark c3 = new Mark("comet:C/2025 R3/LASCO C3", Kind.COMET, "LASCO C3", o -> null, 0);
        Set<String> a = (Set<String>) keys.invoke(null, List.of(new Drawn.Hit("C/2025 R3", "comet", 1, 0.1, Kind.COMET, c2)));
        Set<String> b = (Set<String>) keys.invoke(null, List.of(new Drawn.Hit("C/2025 R3", "comet", 1, 0.1, Kind.COMET, c3)));
        expect(a.equals(b), "a selection made on the C2 ring is the same selection on the C3 ring (" + a + ", " + b + ")");
    }

    /** A comet path held at world point {@code at} (frame at {@code t}) for two days either side. */
    private static CometEphemeris still(Vec3 at, JHVTime t) throws Exception {
        double[] carrington = {at.z, at.x, at.y}; // toWorld is (y, z, x) of the Carrington vector
        double[] ecl = Spice.rotate("SOLO_IAU_SUN_2009", "ECLIPJ2000", t.milli, carrington);
        double[] km = new double[6];
        for (int k = 0; k < 3; k++)
            km[k] = km[3 + k] = ecl[k] / Sun.RadiusKMeterInv;
        return CometEphemeris.of("still", new JplHorizons.States(new long[]{t.milli - 2 * DAY, t.milli + 2 * DAY}, km, new double[6]), "", 0);
    }

    // Rows 4 to 7
    private static void checkScene() throws Exception {
        File file = Path.of("extra/test/data/20241224_194245_d4c2A.fts").toFile();
        if (!file.isFile()) {
            expect(false, "fixture " + file + " is readable");
            return;
        }
        URIView.SourceInfo info = FITSImage.readInfo(file, 0);
        FitsMetaData m = new FitsMetaData(new XMLMetaDataContainer(info.xml()), file.toURI());
        Region region = m.roiToRegion(0, 0, info.width(), info.height(), 1, 1);
        Position obs = m.getViewpoint();
        Identifier.Target first = new Identifier.Target("COR2 first", obs, m.getWcsHeader(), region, m.getInnerRadius(), m.getOuterRadius());
        Identifier.Target second = new Identifier.Target("COR2 second", obs, m.getWcsHeader(), region, m.getInnerRadius(), m.getOuterRadius());
        Identifier.Target bare = Identifier.Target.viewpoint(obs);
        List<Identifier.Target> targets = List.of(first, second, bare);

        // The comet 6 Rsun from Sun centre in the plane of sky, as COR2 sees it
        double d = obs.distance;
        Vec3 dir = Probes.plane(-6, 0).direction(obs);
        Quat q = obs.toQuat();
        Vec3 o = ModelPlacement.toWorld(obs), w = q.rotateInverseVector(new Vec3(dir.x * d, dir.y * d, dir.z * d));
        CometLayer layer = new CometLayer(new Comets.Comet("C/2099 Z1", "Test", 0.1, 0), new int[]{12, 200, 34});
        Field path = CometLayer.class.getDeclaredField("path");
        path.setAccessible(true);
        path.set(layer, still(new Vec3(o.x + w.x, o.y + w.y, o.z + w.z), obs.time));
        Layers.add(layer);
        List<CometLayer> layers = List.of(layer);

        // Row 4
        var fr = Display.class.getDeclaredField("fieldRadius");
        fr.setAccessible(true);
        fr.set(null, (DoubleSupplier) () -> 20);
        Display.setMapMode(MapMode.Orthographic);
        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
        var reset = org.helioviewer.jhv.display.Camera.class.getDeclaredMethod("reset", Position.class);
        reset.setAccessible(true);
        reset.invoke(Display.getCamera(), obs);
        MapView mv = MapView.create(Display.getCamera(), obs, MapMode.Orthographic, GridType.Viewpoint, new MapScale[]{MapScale.ortho});
        Vec3 ring = layer.ringDirection(first);
        double[] c = ring == null ? null : SkyScreen.place(mv, vp, obs, ring);
        if (c == null) {
            expect(false, "the comet's ring is on the page (" + ring + ")");
        } else {
            List<Drawn.Hit> hits = CometMarks.at(vp, mv, (int) Math.round(c[0]), (int) Math.round(c[1]), 5, targets, layers);
            Mark mark = hits.size() == 1 ? hits.getFirst().mark() : null;
            expect(mark != null && "COR2 first".equals(mark.layer()) && CometMarks.family(mark).equals("comet:C/2099 Z1"),
                    "the pointer on the ring names the comet with the first image's ring (" + (mark == null ? hits : mark.key()) + ")");
            List<Drawn.Hit> bareHits = CometMarks.at(vp, mv, (int) Math.round(c[0]), (int) Math.round(c[1]), 5, List.of(bare), layers);
            boolean ringless = true, dot = false;
            for (Drawn.Hit h : bareHits) {
                ringless &= h.mark() != null && h.mark().layer() == null;
                dot |= h.mark() != null && h.mark().key().equals("comet:C/2099 Z1");
            }
            expect(ringless && dot, "with only the bare viewpoint, which draws no ring, the comet in the plane of sky is named by its dot ("
                    + bareHits.stream().map(h -> h.mark() == null ? "no mark" : h.mark().key()).toList() + ")");
        }

        // Row 5
        long builds = MarkFrame.BUILDS.sum();
        List<Mark> family = CometMarks.familyMarks("comet:C/2099 Z1", vp, mv, targets);
        expect(family.size() == 2 && MarkFrame.BUILDS.sum() == builds, "hovering lights the comet's ring in both images (" + family.size()
                + ") without building the frame's mark list (" + (MarkFrame.BUILDS.sum() - builds) + " builds)");

        // Row 6
        Drawn.register(CometMarks.source());
        boolean comet = false;
        for (Mark k : Drawn.allMarks(vp, mv, first))
            comet |= k.key().startsWith("comet:C/2099 Z1");
        expect(!comet, "Highlight All gets no ambient copy of a comet its layer draws");
        long t = obs.time.milli;
        Comets.Track track = new Comets.Track(new long[]{t - DAY, t + DAY}, new double[]{6, 6}, new double[]{90, 90});
        CometSky.Tracked owned = new CometSky.Tracked(new Comets.Comet("C/2099 Z1", "", 0.1, 0), track);
        CometSky.Tracked other = new CometSky.Tracked(new Comets.Comet("C/2098 Y1", "", 0.1, 0), track);
        expect(!CometSky.shown(owned, t) && CometSky.shown(other, t), "CometSky leaves a comet with a layer to CometMarks and keeps the others");

        // Row 7
        int[] rgb = CometStyle.rgbFor(CometMarks.ringMark(layer, first));
        expect(rgb != null && rgb[0] == 12 && rgb[1] == 200 && rgb[2] == 34, "a comet's ring lights in its layer's colour (" + (rgb == null ? "none" : CometStyle.hex(rgb)) + ")");
        expect(CometStyle.rgbFor(CometSky.mark(other)) == null && CometStyle.rgbFor(new Mark("Venus/x", Kind.PLANET, "x", k -> null, 0)) == null,
                "another comet and a planet keep their kind colour");
        Drawn.unregister(CometMarks.source());
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }
}
