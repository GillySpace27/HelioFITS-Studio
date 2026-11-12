package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
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
    private final List<float[]> labelColours = new ArrayList<>();
    private String lastText;
    private final Timer dwell;
    private boolean labelShown;
    private int mouseX, mouseY;
    private List<Identifier.Match> last = List.of();
    private List<Drawn.Hit> lastDrawn = List.of();
    private Snapshot kept = Snapshot.NONE;
    private boolean frozen;
    /** The selected objects: they are followed through time and view changes, and hover is suspended meanwhile. */
    private List<Mark> selMarks = List.of();
    private Set<String> selBodies = Set.of(), selDrawn = Set.of();
    private final List<Runnable> keptListeners = new CopyOnWriteArrayList<>();

    /** Whether the pointer is on a result now, on the image with nothing under it, or has left the image. */
    public enum State { LIVE, EMPTY, AWAY }

    /**
     * The last non-empty answer, kept for the Identify panel. Moving the pointer from the image to
     * the sidebar clears {@link #lastMatches()}, which would blank a live readout at the moment you
     * reach for it, so this keeps the last answer and says how old it is.
     */
    public record Snapshot(long millis, List<Identifier.Match> matches, List<Drawn.Hit> drawn, State state) {
        static final Snapshot NONE = new Snapshot(0, List.of(), List.of(), State.AWAY);
    }

    private IdentifyHover() {
        dwell = new Timer(IdentifySettings.DEFAULT_DWELL_MS, e -> {
            if (!label.isEmpty() && IdentifySettings.tooltip()) {
                labelShown = true;
                DisplayController.display();
            }
        });
        dwell.setRepeats(false);
        Drawn.register(DeepSky.source());
        Drawn.register(CometSky.source());
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

    /** The pointer in canvas pixels, or null when it is not on the image. */
    @Nullable
    public static double[] pointer() {
        return INSTANCE.last.isEmpty() && INSTANCE.lastDrawn.isEmpty() ? null : new double[]{INSTANCE.mouseX, INSTANCE.mouseY};
    }

    /** The pointer left the canvas. */
    public static void exited() {
        INSTANCE.clear();
    }

    /** The last non-empty answer and whether the pointer is still on it. */
    public static Snapshot snapshot() {
        return INSTANCE.kept;
    }

    /** Hold the current snapshot while the pointer moves about, so it can be read at leisure. */
    public static void setFrozen(boolean b) {
        INSTANCE.frozen = b;
        INSTANCE.fireKept();
    }

    public static boolean frozen() {
        return INSTANCE.frozen;
    }

    /** The instance, so a check can pass its own targets. */
    static IdentifyHover selectionForCheck() {
        return INSTANCE;
    }

    /** Whether an object is selected (clicked), and so followed as time or the view changes. */
    public static boolean selected() {
        return !INSTANCE.selMarks.isEmpty();
    }

    /**
     * A click on the canvas at (x, y): selects the highlighted object under the pointer (the Sun has
     * no highlight, so it does not count); a click on the selected object, on empty space, or while
     * a selection is held deselects it. A click on a different highlighted object selects that one.
     * True when it selected or deselected something.
     */
    public static boolean clickSelect(Viewport vp, MapView mv, int x, int y) {
        if (!IdentifySettings.enabled())
            return false;
        return INSTANCE.clickSelect(vp, mv, x, y, targets(vp, mv));
    }

    boolean clickSelect(Viewport vp, MapView mv, int x, int y, List<Identifier.Target> targets) {
        Identifier.Options options = Identifier.Options.fromSettings();
        List<Identifier.Match> matches = identify(vp, mv, x, y, options, targets);
        List<Drawn.Hit> drawn = Drawn.at(vp, mv, x, y, options.tolerancePixels());
        List<Mark> marks = IdentifyGlow.marks(matches, drawn);
        boolean was = selected();
        if (marks.isEmpty() || (was && bodyNames(matches).equals(selBodies) && drawnKeys(drawn).equals(selDrawn))) {
            deselect();
            return was;
        }
        selMarks = marks;
        selBodies = bodyNames(matches);
        selDrawn = drawnKeys(drawn);
        mouseX = x;
        mouseY = y;
        show(matches, drawn);
        return true;
    }

    /** Lets the selection go; hover takes over again at the next pointer move. */
    public static void deselect() {
        INSTANCE.deselectNow();
    }

    private void deselectNow() {
        if (selMarks.isEmpty())
            return;
        selMarks = List.of();
        selBodies = Set.of();
        selDrawn = Set.of();
        clear();
        fireKept();
    }

    private static Set<String> bodyNames(List<Identifier.Match> matches) {
        Set<String> out = new HashSet<>();
        for (Identifier.Match m : IdentifyGlow.outlined(matches))
            out.add(m.body().name());
        return out;
    }

    private static Set<String> drawnKeys(List<Drawn.Hit> drawn) {
        Set<String> out = new HashSet<>();
        for (Drawn.Hit h : drawn)
            if (h.mark() != null)
                out.add(h.mark().key());
        return out;
    }

    /** Makes (matches, drawn) the current answer: glow, label, footer text and panel readout. */
    private void show(List<Identifier.Match> matches, List<Drawn.Hit> drawn) {
        last = matches;
        lastDrawn = drawn;
        keep(matches, drawn);
        IdentifyGlow.matched(matches, drawn);
        label.clear();
        labelColours.clear();
        lastText = describe(matches, drawn);
        labelShown = IdentifySettings.tooltip() && !label.isEmpty();
        DisplayController.display();
        fireKept();
    }

    /**
     * Once per frame: moves a selected object's answer to where the object is now. Each selected
     * object is looked up again in the sky (so time and view changes are followed, not frozen) and
     * identified at the pixel it lands on, which updates the glow, the label, the footer and the
     * panel together. An object that has left the page keeps its last answer.
     */
    public static void follow(Viewport vp, MapView mv) {
        if (selected())
            INSTANCE.follow(vp, mv, targets(vp, mv));
    }

    void follow(Viewport vp, MapView mv, List<Identifier.Target> targets) {
        double[] p = null;
        for (Mark m : selMarks) {
            Identifier.Target t = null;
            for (Identifier.Target c : targets)
                if (m.layer() == null ? t == null : c.name().equals(m.layer()))
                    t = c;
            Vec3 dir = t == null ? null : m.where().direction(t.observer());
            if (dir == null)
                continue;
            p = IdentifyPins.locate(vp, mv, t, dir, new double[]{mouseX, mouseY});
            if (p == null)
                p = IdentifyPins.locate(vp, mv, t, dir);
            if (p != null)
                break;
        }
        if (p == null)
            return;
        int x = (int) Math.round(p[0]), y = (int) Math.round(p[1]);
        Identifier.Options options = Identifier.Options.fromSettings();
        List<Identifier.Match> matches = new ArrayList<>();
        for (Identifier.Match m : identify(vp, mv, x, y, options, targets))
            if (selBodies.contains(m.body().name()))
                matches.add(m);
        List<Drawn.Hit> drawn = new ArrayList<>();
        for (Drawn.Hit h : Drawn.at(vp, mv, x, y, options.tolerancePixels()))
            if (h.mark() != null && selDrawn.contains(h.mark().key()))
                drawn.add(h);
        if (matches.isEmpty() && drawn.isEmpty())
            return;
        mouseX = x;
        mouseY = y;
        show(matches, drawn);
    }

    /** Called (on the thread that moved the pointer) whenever the snapshot changes; coalesce in the listener. */
    public static void addSnapshotListener(Runnable r) {
        INSTANCE.keptListeners.add(r);
    }

    private void fireKept() {
        for (Runnable r : keptListeners)
            r.run();
    }

    private void keep(List<Identifier.Match> matches, List<Drawn.Hit> drawn) {
        if (frozen)
            return;
        Snapshot was = kept;
        if (!matches.isEmpty() || !drawn.isEmpty())
            kept = new Snapshot(System.currentTimeMillis(), matches, drawn, State.LIVE);
        else if (was.state() != State.EMPTY)
            kept = new Snapshot(was.millis(), was.matches(), was.drawn(), State.EMPTY);
        else
            return;
        fireKept();
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
        if (selected() && lastText != null)
            return lastText; // a selected object owns the readout; follow() keeps it current
        if (frozen && lastText != null && (!last.isEmpty() || !lastDrawn.isEmpty()))
            return lastText; // a frozen result holds the glow, the label and the footer where they are
        Identifier.Options options = Identifier.Options.fromSettings();
        List<Identifier.Match> matches = identify(vp, mv, x, y, options);
        List<Drawn.Hit> drawn = Drawn.at(vp, mv, x, y, options.tolerancePixels());
        last = matches;
        lastDrawn = drawn;
        keep(matches, drawn);
        IdentifyGlow.matched(matches, drawn);
        boolean wasShown = labelShown && !label.isEmpty();
        label.clear();
        labelColours.clear();
        lastText = null;
        labelShown = false;
        mouseX = x;
        mouseY = y;
        if (matches.isEmpty() && drawn.isEmpty()) {
            dwell.stop();
            if (wasShown)
                DisplayController.display();
            return null;
        }
        String text = describe(matches, drawn);
        if (IdentifySettings.tooltip()) {
            dwell.setInitialDelay(IdentifySettings.dwellMillis());
            dwell.restart();
        }
        if (wasShown)
            DisplayController.display();
        lastText = text;
        return lastText;
    }

    /** Fills the label's lines and colours for an answer and returns the footer text. */
    private String describe(List<Identifier.Match> matches, List<Drawn.Hit> drawn) {
        StringBuilder sb = new StringBuilder();
        for (Identifier.Match m : matches) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(m.label());
            label.add(m.label());
            label.add(details(m));
            float[] c = tint(Kind.of(m.body()));
            labelColours.add(c);
            labelColours.add(c);
        }
        for (Drawn.Hit h : drawn) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(h.name());
            if (Drawn.labelledElsewhere(h))
                continue; // the orbit plot and the event popup label their own
            label.add(h.name());
            label.add(h.details());
            float[] c = h.mark() == null ? null : tint(h.mark().kind());
            labelColours.add(c);
            labelColours.add(c);
        }
        return sb.toString();
    }

    /** The label's colour for an object of {@code kind}: its icon's, or null (the usual gray) when that is switched off. */
    @Nullable
    private static float[] tint(Kind kind) {
        if (!IdentifySettings.tooltipByKind())
            return null;
        int[] c = IdentifySettings.iconRgb(kind);
        return new float[]{c[0] / 255f, c[1] / 255f, c[2] / 255f, 1};
    }

    /** One line under each name: how far, and how far from the Sun in the sky. */
    static String details(Identifier.Match m) {
        String dist = m.distanceAu() < 0.01 ? String.format("%.0f km", m.distanceAu() * 149_597_870.7) : String.format("%.3f AU", m.distanceAu());
        return m.body().name().equals("Sun") ? "  " + dist : String.format("  %s, %.2f° from the Sun", dist, m.elongation());
    }

    /** Every body under screen point (x, y): the identification the footer shows. */
    public static List<Identifier.Match> identify(Viewport vp, MapView mv, double x, double y, Identifier.Options options) {
        return identify(vp, mv, x, y, options, targets(vp, mv));
    }

    static List<Identifier.Match> identify(Viewport vp, MapView mv, double x, double y, Identifier.Options options, List<Identifier.Target> targets) {
        Identifier.Probe probe = Probes.at(mv, vp, x, y);
        if (probe == null)
            return List.of();
        Identifier.Probe probe1 = Probes.at(mv, vp, x + 1, y);
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

    /**
     * The visible image layers, each with its own observer, or the display viewpoint when there are
     * none. The active image layer comes first: its observer is the frame stars and the other marks
     * at infinity are placed in (the Stars overlay, the star and deep-sky hover and rings alike).
     */
    public static List<Identifier.Target> targets(Viewport vp, MapView mv) {
        return targets(vp, mv.viewpoint());
    }

    /** The frame stars and the other marks at infinity are placed in: the first of {@link #targets}'s observers. */
    public static Position frameObserver(Viewport vp, Position viewpoint) {
        return targets(vp, viewpoint).getFirst().observer();
    }

    private static List<Identifier.Target> targets(Viewport vp, Position viewpoint) {
        List<Identifier.Target> out = new ArrayList<>();
        ImageLayer active = Layers.getActiveImageLayer();
        for (ImageLayer layer : Layers.getImageLayers()) {
            View.ImageData data = layer.getImageData();
            if (data == null || !layer.isVisible(vp.idx))
                continue;
            if (layer == active)
                out.add(0, target(layer.getName(), data));
            else
                out.add(target(layer.getName(), data));
        }
        if (out.isEmpty())
            out.add(Identifier.Target.viewpoint(viewpoint));
        return out;
    }

    static Identifier.Target target(String name, View.ImageData data) {
        MetaData m = data.metaData();
        return new Identifier.Target(name, m.getViewpoint(), m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius());
    }

    private void clear() {
        if ((frozen || selected()) && IdentifySettings.enabled() && (!last.isEmpty() || !lastDrawn.isEmpty()))
            return; // leaving the canvas does not drop a frozen or selected result
        selMarks = List.of();
        selBodies = Set.of();
        selDrawn = Set.of();
        dwell.stop();
        last = List.of();
        lastDrawn = List.of();
        IdentifyGlow.matched(last, List.of());
        if (!frozen && kept.state() != State.AWAY) {
            kept = new Snapshot(kept.millis(), kept.matches(), kept.drawn(), State.AWAY);
            fireKept();
        }
        if (!label.isEmpty()) {
            label.clear();
            labelColours.clear();
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
        GLText.drawTextFloat(vp, h.label, h.labelColours, h.mouseX - vp.x + OFFSET_X, h.mouseY - vp.yAWT + OFFSET_Y);
    }
}
