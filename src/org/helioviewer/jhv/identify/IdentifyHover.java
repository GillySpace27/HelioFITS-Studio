package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.view.View;

/**
 * The identifier at the pointer: the text the footer appends and the label drawn beside the
 * pointer after it rests. Both come from one identification per pointer move.
 *
 * <p>Every visible image layer is asked with its own observer and frame time; with no image layer
 * the display's viewpoint is asked instead, so the footer still names the Sun and the planets in
 * an empty scene. Only the footer and the label read this; nothing here touches the imagery.
 */
public final class IdentifyHover {

    private static final IdentifyHover INSTANCE = new IdentifyHover();
    private static final int OFFSET_X = 25;
    private static final int OFFSET_Y = 25;

    private final List<String> label = new ArrayList<>();
    private final Timer dwell;
    private boolean labelShown;
    private int mouseX, mouseY;
    private List<Identifier.Match> last = List.of();
    private List<Drawn.Hit> lastDrawn = List.of();

    private IdentifyHover() {
        dwell = new Timer(IdentifySettings.DEFAULT_DWELL_MS, e -> {
            if (!label.isEmpty() && IdentifySettings.tooltip()) {
                labelShown = true;
                DisplayController.display();
            }
        });
        dwell.setRepeats(false);
        IdentifySettings.addListener(() -> {
            if (!IdentifySettings.enabled() || !IdentifySettings.tooltip())
                INSTANCE.clear();
        });
    }

    /**
     * The footer's text for the pointer at (x, y), or null when the identifier is off or names
     * nothing. Also arms the label beside the pointer.
     */
    @Nullable
    public static String footer(Viewport vp, MapView mv, int x, int y) {
        return INSTANCE.update(vp, mv, x, y);
    }

    /** The drawn 3D objects named at the last pointer position. */
    public static List<Drawn.Hit> lastDrawn() {
        return INSTANCE.lastDrawn;
    }

    /** The pointer left the canvas. */
    public static void exited() {
        INSTANCE.clear();
    }

    /** The bodies named at the last pointer position, for a command or a check. */
    public static List<Identifier.Match> lastMatches() {
        return INSTANCE.last;
    }

    @Nullable
    private String update(Viewport vp, MapView mv, int x, int y) {
        if (!IdentifySettings.enabled()) {
            clear();
            return null;
        }
        Identifier.Options options = Identifier.Options.fromSettings();
        List<Identifier.Match> matches = identify(vp, mv, x, y, options);
        List<Drawn.Hit> drawn = Drawn.at(vp, mv, x, y, options.tolerancePixels());
        last = matches;
        lastDrawn = drawn;
        boolean wasShown = labelShown && !label.isEmpty();
        label.clear();
        labelShown = false;
        mouseX = x;
        mouseY = y;
        if (matches.isEmpty() && drawn.isEmpty()) {
            dwell.stop();
            if (wasShown)
                DisplayController.display();
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Identifier.Match m : matches) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(m.label());
            label.add(m.label());
            label.add(details(m));
        }
        for (Drawn.Hit h : drawn) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(h.name());
            if (Drawn.labelledElsewhere(h))
                continue; // the orbit plot and the event popup label their own
            label.add(h.name());
            label.add(h.details());
        }
        if (IdentifySettings.tooltip()) {
            dwell.setInitialDelay(IdentifySettings.dwellMillis());
            dwell.restart();
        }
        if (wasShown)
            DisplayController.display();
        return sb.toString();
    }

    /** One line under each name: how far, and how far from the Sun in the sky. */
    static String details(Identifier.Match m) {
        String dist = m.distanceAu() < 0.01 ? String.format("%.0f km", m.distanceAu() * 149_597_870.7) : String.format("%.3f AU", m.distanceAu());
        return m.body().name().equals("Sun") ? "  " + dist : String.format("  %s, %.2f° from the Sun", dist, m.elongation());
    }

    /** Every body under screen point (x, y): the identification the footer shows. */
    public static List<Identifier.Match> identify(Viewport vp, MapView mv, double x, double y, Identifier.Options options) {
        Identifier.Probe probe = Probes.at(mv, vp, x, y);
        if (probe == null)
            return List.of();
        Identifier.Probe probe1 = Probes.at(mv, vp, x + 1, y);
        List<Identifier.Target> targets = targets(vp, mv);
        List<Identifier.Match> matches = Identifier.identify(targets, probe, probe1, options);
        return mv.isOrthographic() ? drawnOnly(matches, targets, mv.viewRotation()) : matches;
    }

    /**
     * In the orthographic view turned away from a layer's line of sight, that layer names only
     * what it draws: a body on its imagery, not one behind the Sun, the occulter or off the field,
     * and nothing at all from a layer with no imagery (the bare viewpoint), since the sky behind
     * the pointer is not that observer's sky any more.
     */
    static List<Identifier.Match> drawnOnly(List<Identifier.Match> matches, List<Identifier.Target> targets, Quat viewRotation) {
        Set<String> orbited = new HashSet<>();
        Set<String> imageless = new HashSet<>();
        for (Identifier.Target t : targets) {
            if (Drawn.orbited(viewRotation, t.observer().toQuat()))
                orbited.add(t.name());
            if (t.wcs() == null)
                imageless.add(t.name());
        }
        if (orbited.isEmpty())
            return matches;
        List<Identifier.Match> out = new ArrayList<>();
        for (Identifier.Match m : matches)
            if (!orbited.contains(m.layer()) || (m.qualifier() == Identifier.Qualifier.NONE && !imageless.contains(m.layer())))
                out.add(m);
        return out;
    }

    /** The visible image layers, each with its own observer, or the display viewpoint when there are none. */
    public static List<Identifier.Target> targets(Viewport vp, MapView mv) {
        List<Identifier.Target> out = new ArrayList<>();
        for (ImageLayer layer : Layers.getImageLayers()) {
            View.ImageData data = layer.getImageData();
            if (data == null || !layer.isVisible(vp.idx))
                continue;
            out.add(target(layer.getName(), data));
        }
        if (out.isEmpty())
            out.add(Identifier.Target.viewpoint(mv.viewpoint()));
        return out;
    }

    static Identifier.Target target(String name, View.ImageData data) {
        MetaData m = data.metaData();
        return new Identifier.Target(name, m.getViewpoint(), m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius());
    }

    private void clear() {
        dwell.stop();
        last = List.of();
        lastDrawn = List.of();
        if (!label.isEmpty()) {
            label.clear();
            if (labelShown)
                DisplayController.display();
        }
        labelShown = false;
    }

    /** The label beside the pointer, in the screen pass that recordings never capture. */
    public static void renderLabel(Viewport vp) {
        IdentifyHover h = INSTANCE;
        if (!h.labelShown || h.label.isEmpty() || !IdentifySettings.tooltip() || ExportMovie.isRecording())
            return;
        // The pointer is in canvas pixels; the text is placed in the viewport, which a fixed output aspect insets
        GLText.drawTextFloat(vp, h.label, h.mouseX - vp.x + OFFSET_X, h.mouseY - vp.yAWT + OFFSET_Y);
    }
}
