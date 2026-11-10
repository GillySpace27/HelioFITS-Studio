package org.helioviewer.jhv.identify;

import java.util.List;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Vec3;

import java.awt.Color;

/**
 * The deep-sky table reader and the kind colours. The rows are lines copied unchanged from the
 * OpenNGC table (NGC.csv), so what is checked is the reader, not astronomy: M31 comes out at the
 * file's own RA/Dec, with a radius of half its 177.83 arcmin major axis, named by its common name.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.DeepSkyCheck
 */
public final class DeepSkyCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        String table = String.join("\n", ROWS);
        List<DeepSky.Obj> objs = DeepSky.parse(table);
        expect("five rows in, five out: " + objs.size(), objs.size() == 5);
        DeepSky.Obj m31 = objs.stream().filter(o -> o.id().equals("NGC 224")).findFirst().orElseThrow();
        expect("M31 is named by its common name", m31.name().equals("Andromeda Galaxy"));
        expect("M31 is a galaxy and named", m31.kind() == Kind.GALAXY && m31.named());
        expect("M31 RA 00:42:44.35 is 10.68479 deg", Math.abs(m31.raDeg() - (42 / 60. + 44.35 / 3600) * 15) < 1e-9);
        expect("M31 Dec +41:16:08.6 is 41.26906 deg", Math.abs(m31.decDeg() - (41 + 16 / 60. + 8.6 / 3600)) < 1e-9);
        expect("M31 radius is half of 177.83 arcmin", Math.abs(m31.radiusDeg() - 177.83 / 2 / 60) < 1e-9);
        expect("M31 details give Messier = NGC and the V magnitude", m31.details().equals("galaxy, M 31 = NGC 224, mag 3.4"));
        DeepSky.Obj m13 = objs.stream().filter(o -> o.id().equals("NGC 5272")).findFirst().orElseThrow();
        expect("M3 has no common name, so it is named M 3, a cluster, point-free size", m13.name().equals("M 3") && m13.kind() == Kind.CLUSTER);
        DeepSky.Obj m42 = objs.stream().filter(o -> o.id().equals("NGC 1976")).findFirst().orElseThrow();
        expect("The Orion Nebula (a cluster with nebula) is diffuse", m42.kind() == Kind.DIFFUSE && m42.name().equals("Great Orion Nebula"));
        DeepSky.Obj faint = objs.stream().filter(o -> o.id().equals("IC 2")).findFirst().orElseThrow();
        expect("A faint unnamed galaxy is not named and has its B magnitude", !faint.named() && Math.abs(faint.mag() - 15.46) < 1e-9);
        expect("Catalogue ids lose their zero padding", DeepSky.catalogId("NGC0869").equals("NGC 869") && DeepSky.catalogId("IC0001").equals("IC 1"));

        // A name reads on the panel whichever way it is lit: white stars on a light panel must darken
        for (Kind k : Kind.values()) {
            expect(k + " reads on a light panel", Kind.contrast(k.textOn(new Color(238, 238, 238)), new Color(238, 238, 238)) >= 3);
            expect(k + " reads on a dark panel", Kind.contrast(k.textOn(new Color(40, 40, 40)), new Color(40, 40, 40)) >= 3);
        }
        expect("stars are white, planets green, comets red, diffuse blue on a dark panel",
            Kind.STAR.textOn(Color.BLACK).equals(Color.WHITE) && Kind.PLANET.color().getGreen() > Kind.PLANET.color().getRed()
            && Kind.COMET.color().getRed() > Kind.COMET.color().getBlue() && Kind.DIFFUSE.color().getBlue() > Kind.DIFFUSE.color().getRed());
        System.exit(failures == 0 ? 0 : 1);
    }

    private static final String[] ROWS = {
            "Name;Type;RA;Dec;Const;MajAx;MinAx;PosAng;B-Mag;V-Mag;J-Mag;H-Mag;K-Mag;SurfBr;Hubble;Pax;Pm-RA;Pm-Dec;RadVel;Redshift;Cstar U-Mag;Cstar B-Mag;Cstar V-Mag;M;NGC;IC;Cstar Names;Identifiers;Common names;NED notes;OpenNGC notes;Sources",
            "IC0002;G;00:11:00.88;-12:49:22.3;Cet;0.98;0.32;142;15.46;;12.26;11.48;11.17;23.45;Sb;;;;6775;0.022860;;;;;;;;2MASX J00110081-1249206,IRAS 00084-1306,MCG -02-01-031,PGC 000778;;;;Type:1|RA:1|Dec:1|Const:99|MajAx:3|MinAx:3|PosAng:3|B-Mag:3|J-Mag:2|H-Mag:2|K-Mag:2|SurfBr:3|Hubble:3|RadVel:2|Redshift:2",
            "NGC0224;G;00:42:44.35;+41:16:08.6;And;177.83;69.66;35;4.29;3.44;2.09;1.28;0.98;23.63;Sb;;;;-300;-0.001000;;;;031;;;;2MASX J00424433+4116074,IRAS 00400+4059,MCG +07-02-016,PGC 002557,UGC 00454;Andromeda Galaxy;;;Type:1|RA:1|Dec:1|Const:99|MajAx:3|MinAx:3|PosAng:3|B-Mag:3|V-Mag:2|J-Mag:2|H-Mag:2|K-Mag:2|SurfBr:3|Hubble:3|Pax:2|RadVel:2|Redshift:2",
            "NGC0869;OCl;02:18:58.56;+57:07:02.1;Per;14.40;;;4.30;3.70;;;;;;0.3942;-0.694;-1.083;-39;-0.000130;;;;;;;;MWSC 0175;h Persei Cluster;;Caldwell 14 refers to both NGC869 and NGC884;Type:1|RA:1|Dec:1|Const:99|MajAx:5|B-Mag:2|V-Mag:2|Pax:2|Pm-RA:2|Pm-Dec:2|RadVel:2|Redshift:2",
            "NGC1976;Cl+N;05:35:16.48;-05:23:22.8;Ori;90.00;60.00;;4.00;4.00;;;;;;;1.670;-0.300;28;0.000093;;;;042;;;;LBN 974,MWSC 0582;Great Orion Nebula,Orion Nebula;;;Type:1|RA:1|Dec:1|Const:99|MajAx:9|MinAx:9|B-Mag:3|V-Mag:10|Pm-RA:2|Pm-Dec:2|RadVel:2|Redshift:2",
            "NGC5272;GCl;13:42:11.23;+28:22:31.6;CVn;16.20;;;;6.39;;;4.11;;;0.1100;-0.140;-2.640;-147;-0.000491;;;;003;;;;MWSC 2152;;;;Type:1|RA:1|Dec:1|Const:99|MajAx:5|V-Mag:3|K-Mag:2|Pax:2|Pm-RA:2|Pm-Dec:2|RadVel:2|Redshift:2"
    };
}
