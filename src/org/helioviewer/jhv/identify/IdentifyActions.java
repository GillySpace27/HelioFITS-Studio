package org.helioviewer.jhv.identify;

import java.awt.event.ActionEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;

/**
 * The identifier's three switches as actions, for the View menu (under Show Status Bar), help
 * search and the command registry. Each is a toggle whose check mark follows the setting, however
 * it was changed: the menu, a command, or Settings &gt; Identify.
 */
public final class IdentifyActions {

    public static final String UNDER_CURSOR = "identifyUnderCursor";
    public static final String TOOLTIP = "identifyTooltip";
    public static final String STARS = "identifyStars";

    @SuppressWarnings("serial")
    private static final class Toggle extends AbstractAction {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;

        Toggle(String name, String tip, BooleanSupplier _get, Consumer<Boolean> _set) {
            super(name);
            get = _get;
            set = _set;
            putValue(SHORT_DESCRIPTION, tip);
            putValue(SELECTED_KEY, get.getAsBoolean());
            IdentifySettings.addListener(() -> putValue(SELECTED_KEY, get.getAsBoolean()));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // A menu check box has already changed its own state; anything else (search, a command) toggles
            set.accept(e.getSource() instanceof javax.swing.AbstractButton b ? b.isSelected() : !get.getAsBoolean());
        }
    }

    public static Action underCursor() {
        return new Toggle("Identify Under Cursor",
                "Name the Moon, planet or Sun under the pointer in the footer, as each image layer's observer saw it",
                IdentifySettings::enabled, IdentifySettings::setEnabled);
    }

    public static Action tooltip() {
        return new Toggle("Identify Tooltip",
                "Also show the name beside the pointer when it rests (needs Identify Under Cursor)",
                IdentifySettings::tooltip, IdentifySettings::setTooltip);
    }

    public static Action stars() {
        return new Toggle("Identify Stars",
                "Include stars (needs the star catalog; names and magnitude limit in Settings > Identify)",
                IdentifySettings::stars, IdentifySettings::setStars);
    }

    public static final String FIND_BODY = "identifyFindBody";

    /** View &gt; Find Body...: choose a body, pin it, and say whether the active layer's imagery holds it now. */
    @SuppressWarnings("serial")
    public static Action findBody() {
        AbstractAction a = new AbstractAction("Find Body...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (Bodies.Body b : Bodies.ALL)
                    if (!b.name().equals("Pluto") || IdentifySettings.pluto())
                        names.add(b.name());
                java.awt.Component parent = org.helioviewer.jhv.gui.MainFrame.get();
                Object choice = javax.swing.JOptionPane.showInputDialog(parent, "Body to find and pin:", "Find Body",
                        javax.swing.JOptionPane.QUESTION_MESSAGE, null, names.toArray(), "Moon");
                if (!(choice instanceof String name))
                    return;
                IdentifyPins.pin(name);
                javax.swing.JOptionPane.showMessageDialog(parent, describe(name), "Find Body", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            }
        };
        a.putValue(Action.SHORT_DESCRIPTION, "Pin a Moon, planet or Sun label to the view and say whether the active layer's imagery holds it");
        return a;
    }

    /** Where a body is for the first target (the active layer, else the viewpoint), in one plain sentence. */
    static String describe(String name) {
        Bodies.Body body = Bodies.byName(name);
        java.util.List<Identifier.Target> targets = IdentifyHover.targets(org.helioviewer.jhv.display.Display.getActiveViewport(),
                org.helioviewer.jhv.opengl.GLRenderer.getMapView());
        Identifier.Target t = targets.getFirst();
        org.helioviewer.jhv.math.Vec3 dir = body == null ? null : Identifier.bodyDirection(body, t.observer(), IdentifySettings.lightTime());
        if (dir == null)
            return name + " has no position at " + t.observer().time + " (outside the bundled ephemeris).";
        org.helioviewer.jhv.math.Vec2 hp = Identifier.helioprojective(dir);
        String where = String.format("%s is at Tx %.2f°, Ty %.2f° from %s, %.2f° from the Sun.", name,
                Math.toDegrees(hp.x), Math.toDegrees(hp.y), t.name(), Math.toDegrees(Math.acos(Math.clamp(-dir.z, -1, 1))));
        if (t.wcs() == null || t.region() == null)
            return where + " Its label is pinned.";
        boolean in = new TransitIndex.LayerField(t.wcs(), t.region(), t.innerRadius(), t.outerRadius()).contains(dir, t.observer());
        return where + (in ? " It is on this layer's imagery; its label is pinned there."
                : " It is outside this layer's field; its label is pinned and shows wherever the view reaches it.");
    }

    private IdentifyActions() {}
}
