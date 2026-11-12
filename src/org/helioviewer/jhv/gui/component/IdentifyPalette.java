package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.gui.dialog.IdentifyTab;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.IdentifyHover;
import org.helioviewer.jhv.identify.IdentifySettings;
import org.helioviewer.jhv.opengl.GLRenderer;

/**
 * The Identify panel: every identifier setting, and what the identifier last matched and why, side
 * by side so a setting can be changed and its effect read without a dialog in the way.
 *
 * <p>Docks in the right sidebar unless the user moved it, like Track CME. The settings form is the
 * one the Settings dialog used to hold ({@link IdentifyTab}); there is no second copy.
 *
 * <p>The readout keeps the last non-empty answer (IdentifyHover.Snapshot): moving the pointer from
 * the image to this panel clears the live result, which would blank the table as you reached for
 * it. It says plainly when it is showing an old answer.
 */
public final class IdentifyPalette {

    /** The one palette and its readout, in a final holder (guards.py ratchet: no non-final statics). */
    private static final class Holder {
        Palette palette;
        Readout readout;
    }

    private static final Holder HOLDER = new Holder();

    private IdentifyPalette() {}

    /** The palette, built on first call and docked where it lives (the right sidebar unless moved). */
    public static Palette palette() {
        if (HOLDER.palette == null) {
            HOLDER.palette = new Palette("Identify", IdentifyPalette::build, () -> {
                if (HOLDER.readout != null)
                    HOLDER.readout.refresh();
            }, true); // spinners take keystrokes
            HOLDER.palette.restoreHome(RightSidebar.getInstance());
        }
        return HOLDER.palette;
    }

    /** Show the palette wherever it lives. */
    public static void open() {
        palette().open();
    }

    /**
     * The switch and a line saying whether Identify works in this view, then four sections: what is
     * under the pointer, what to name, how it looks, and the advanced numbers (closed).
     */
    static JComponent build() {
        HOLDER.readout = new Readout();
        IdentifyTab.Groups groups = IdentifyTab.build();
        JPanel root = new JPanel(new BorderLayout());
        JPanel head = new JPanel(new BorderLayout(0, 2));
        head.setBorder(BorderFactory.createEmptyBorder(6, 9, 0, 9));
        head.add(IdentifyTab.masterSwitch(), BorderLayout.NORTH);
        head.add(HOLDER.readout.modeLine, BorderLayout.CENTER);
        JPanel body = new JPanel();
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        body.add(section("Under the pointer", HOLDER.readout, true));
        body.add(section("What to name", groups.whatToName(), true));
        body.add(section("How it looks", groups.look(), true));
        body.add(section("Advanced", groups.advanced(), false));
        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    /** A subheading: a nested collapsible section, remembered under an Identify name so no other panel's section of the same title shares it. */
    private static CollapsiblePane section(String title, JComponent content, boolean open) {
        CollapsiblePane p = new CollapsiblePane(title, content, open, true, null, "Identify " + title);
        p.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return p;
    }

    /** The "what did it match, and why" half. All text, so it copies cleanly into a bug report. */
    static final class Readout extends JPanel {

        private final JLabel status = new JLabel();
        /** Whether Identify works in the current view, under the switch at the top. */
        final JLabel modeLine = new JLabel();
        private final javax.swing.JTextPane matches = pane();
        private final JTextArea layers = area();
        private final JCheckBox freeze = new JCheckBox("Freeze this result");
        private final Timer coalesce = new Timer(120, e -> refresh());

        Readout() {
            super(new BorderLayout(0, 4));
            setBorder(BorderFactory.createEmptyBorder(9, 9, 0, 9));
            coalesce.setRepeats(false);
            freeze.setToolTipText("Hold this result at its place on the screen while you move the pointer, so it can be read or copied. (Clicking an object selects it and follows it instead.)");
            freeze.addActionListener(e -> IdentifyHover.setFrozen(freeze.isSelected()));
            JButton copy = new JButton("Copy");
            copy.setToolTipText("Copy the settings, the result and the observers used as plain text, for a bug report");
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report()), null));

            JPanel top = new JPanel(new BorderLayout(0, 4));
            top.add(status, BorderLayout.NORTH);
            top.add(matches, BorderLayout.CENTER);
            // The observers are for finding out why something was not named: behind a disclosure, closed
            JPanel mid = new JPanel(new BorderLayout());
            mid.setOpaque(false);
            mid.add(layers, BorderLayout.CENTER);
            CollapsiblePane observers = new CollapsiblePane("Observers used", mid, false, true, null, "Identify Observers used");
            JPanel buttons = new JPanel(new java.awt.GridLayout(1, 0, 4, 0));
            buttons.setOpaque(false);
            buttons.add(freeze);
            buttons.add(copy);

            JPanel column = new JPanel();
            column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
            for (JComponent c : new JComponent[]{top, buttons, observers}) {
                c.setAlignmentX(LEFT_ALIGNMENT);
                column.add(c);
                column.add(javax.swing.Box.createVerticalStrut(6));
            }
            add(column, BorderLayout.CENTER);

            // The pointer moves often: coalesce so the table redraws a few times a second, not per move.
            IdentifyHover.addSnapshotListener(() -> javax.swing.SwingUtilities.invokeLater(() -> {
                if (!coalesce.isRunning())
                    coalesce.start();
            }));
            IdentifySettings.addListener(() -> javax.swing.SwingUtilities.invokeLater(this::refresh));
            refresh();
        }

        private static javax.swing.JTextPane pane() {
            javax.swing.JTextPane p = new javax.swing.JTextPane();
            p.setEditable(false);
            p.setOpaque(false);
            p.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, p.getFont().getSize() - 1));
            p.setBorder(BorderFactory.createEmptyBorder());
            return p;
        }

        /** Fills the readout: plain text as {@link #matchText}, with each object's name in bold and its kind's colour. */
        void showMatches(IdentifyHover.Snapshot s) {
            javax.swing.text.StyledDocument doc = matches.getStyledDocument();
            java.awt.Color bg = javax.swing.UIManager.getColor("Panel.background");
            if (bg == null)
                bg = java.awt.Color.WHITE;
            try {
                doc.remove(0, doc.getLength());
                java.util.List<String> names = new java.util.ArrayList<>();
                java.util.List<org.helioviewer.jhv.identify.Kind> kinds = new java.util.ArrayList<>();
                for (Identifier.Match m : s.matches()) {
                    names.add(m.body().name());
                    kinds.add(org.helioviewer.jhv.identify.Kind.of(m.body()));
                }
                for (org.helioviewer.jhv.identify.Drawn.Hit h : s.drawn()) {
                    names.add(h.name());
                    kinds.add(h.tone());
                }
                javax.swing.text.SimpleAttributeSet plain = new javax.swing.text.SimpleAttributeSet();
                int next = 0;
                for (String line : matchText(s).split("\n", -1)) {
                    boolean head = next < names.size() && line.startsWith(names.get(next));
                    if (head) {
                        javax.swing.text.SimpleAttributeSet bold = new javax.swing.text.SimpleAttributeSet();
                        javax.swing.text.StyleConstants.setBold(bold, true);
                        javax.swing.text.StyleConstants.setForeground(bold, kinds.get(next).textOn(bg));
                        doc.insertString(doc.getLength(), names.get(next), bold);
                        doc.insertString(doc.getLength(), line.substring(names.get(next).length()) + "\n", plain);
                        next++;
                    } else
                        doc.insertString(doc.getLength(), line + "\n", plain);
                }
            } catch (javax.swing.text.BadLocationException e) {
                matches.setText(matchText(s));
            }
        }

        private static JTextArea area() {
            JTextArea a = new JTextArea();
            a.setEditable(false);
            a.setOpaque(false);
            a.setLineWrap(true);
            a.setColumns(30); // else the preferred width is the longest unwrapped line
            a.setWrapStyleWord(true);
            a.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, a.getFont().getSize() - 1));
            a.setBorder(BorderFactory.createEmptyBorder());
            return a;
        }

        void refresh() {
            IdentifyHover.Snapshot s = IdentifyHover.snapshot();
            freeze.setSelected(IdentifyHover.frozen());
            if (!IdentifySettings.enabled())
                status.setText("<html><body style='width:250px'><b>Off.</b> Switch on Identify Under Cursor (View menu, or the box above).");
            else if (s.millis() == 0)
                status.setText("<html><body style='width:250px'><b>Nothing matched yet.</b> Move the pointer over the image.");
            else
                status.setText("<html><body style='width:250px'><b>" + switch (s.state()) {
                    case LIVE -> "Under the pointer now";
                    case EMPTY -> "Nothing under the pointer; last match " + age(s.millis());
                    case AWAY -> "Pointer left the image; last match " + age(s.millis());
                } + (IdentifyHover.selected() ? " (selected, followed)" : "") + (IdentifyHover.frozen() ? " (frozen)" : "") + "</b>");
            modeLine.setText(modeText());
            showMatches(s);
            layers.setText(layerText());
            revalidate();
            repaint();
        }

        /** One line: whether the view being drawn is one Identify can read. */
        static String modeText() {
            String text;
            try {
                org.helioviewer.jhv.display.MapView mv = GLRenderer.getMapView();
                text = mv.isLatitudinal() ? "Not available in Latitudinal; switch the projection."
                        : "Reads the active viewport (" + mv.mode() + ").";
            } catch (RuntimeException | LinkageError e) { // no display yet
                text = "Works in every projection except Latitudinal.";
            }
            return "<html><body style='width:250px'><small>" + text;
        }

        private static String age(long millis) {
            long sec = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
            return sec < 90 ? sec + " s ago" : sec / 60 + " min ago";
        }

        /**
         * One block per body: how far the pointer is from the body's centre against how close it had
         * to be (the body's angular radius plus the pointer tolerance, in degrees), so a near miss
         * reads off directly, then where the body is and why it may not be visible.
         */
        static String matchText(IdentifyHover.Snapshot s) {
            StringBuilder sb = new StringBuilder();
            for (Identifier.Match m : s.matches()) {
                double dist = m.distanceAu();
                sb.append(String.format("%s  (layer %s)%n", m.body().name(), m.layer()));
                sb.append(String.format("  separation %.3f° of %.3f° allowed (radius %.3f° + pointer %.3f°)%n",
                        m.separation(), m.angularRadius() + m.tolerance(), m.angularRadius(), m.tolerance()));
                sb.append(String.format("  %s, %.2f° from the Sun%s%n",
                        dist < 0.01 ? String.format("%.0f km", dist * 149_597_870.7) : String.format("%.3f AU", dist),
                        m.elongation(), m.qualifier() == Identifier.Qualifier.NONE ? "" : "; " + m.qualifier().text));
            }
            for (org.helioviewer.jhv.identify.Drawn.Hit h : s.drawn())
                sb.append(String.format("%s  (%s, drawn)  %.1f px away%n", h.name(), h.kind(), h.pixels()));
            return sb.length() == 0 ? "(none)" : sb.toString().stripTrailing();
        }

        /** Each visible layer's own observer and frame time: what the identification is computed from. */
        static String layerText() {
            List<Identifier.Target> targets;
            try {
                targets = IdentifyHover.targets(Display.getActiveViewport(), GLRenderer.getMapView());
            } catch (RuntimeException | LinkageError e) { // no display yet, or no ephemeris natives (Linux)
                return "(no view)";
            }
            StringBuilder sb = new StringBuilder();
            for (Identifier.Target t : targets) {
                Position p = t.observer();
                sb.append(String.format("%s%n  %s  lon %.3f° lat %.3f° r %.4f AU%s%n", t.name(), p.time,
                        Math.toDegrees(p.lon), Math.toDegrees(p.lat), p.distance / Sun.MeanEarthDistance,
                        t.wcs() == null ? "  (display viewpoint, no imagery)" : ""));
            }
            if (sb.length() > 0)
                sb.append(String.format("Observers within 1,000,000 km of Earth get +%.0f km of position uncertainty.", IdentifySettings.earthUncertaintyKm()));
            return sb.length() == 0 ? "(none)" : sb.toString().stripTrailing();
        }

        /** Settings, result and observers as plain text. */
        String report() {
            return "Identify diagnostics\n" + IdentifySettings.toJson().toString(2) + "\n\nResult: " + status.getText().replaceAll("<[^>]+>", "").replaceAll("^\\s+", "")
                    + "\n" + matches.getText() + "\n\nObservers:\n" + layers.getText() + "\n";
        }
    }
}
