package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.UIGlobals;

// This panel consists of a toggle button and one arbitrary component. Clicking
// the toggle button will toggle the visibility of the component.
@SuppressWarnings({"serial", "this-escape"})
public class CollapsiblePane extends JComponent implements ActionListener {

    // A section title is a heading, not one more line of the body text underneath it, so it is
    // stepped up from the UI font instead of down from it. The step is added to whatever size the
    // UI font currently has rather than written as a point size, so a larger system font or a
    // different look-and-feel carries the headers up with it.
    private static final float PARENT_STEP = 2;
    private static final float CHILD_STEP = 1; // above the body text it heads, below its parent
    private static final int CHILD_INDENT = 12; // how far a nested section steps in from its parent

    final CollapsiblePaneButton toggleButton;
    private final JComponent managed;
    private final float headerSize;
    private String title;
    @Nullable
    private Icon sectionIcon;

    public CollapsiblePane(String _title, JComponent _managed, boolean startExpanded) {
        this(_title, _managed, startExpanded, false);
    }

    // child=true renders a subordinate (nested) section: regular weight instead of bold,
    // so it reads as a child of the bold parent header it sits indented beneath.
    public CollapsiblePane(String _title, JComponent _managed, boolean startExpanded, boolean child) {
        this(_title, _managed, startExpanded, child, null);
    }

    /** @param _sectionIcon a glyph for what the section holds, drawn between chevron and title; may be null */
    public CollapsiblePane(String _title, JComponent _managed, boolean startExpanded, boolean child, @Nullable Icon _sectionIcon) {
        this(_title, _managed, startExpanded, child, _sectionIcon, null);
    }

    /**
     * @param _prefKey what to remember this section's expansion under, when its title is not
     *                 unique across the window. Two sidebars can both hold a section called
     *                 Camera, and sharing one setting made each collapse the other.
     */
    public CollapsiblePane(String _title, JComponent _managed, boolean startExpanded, boolean child,
                           @Nullable Icon _sectionIcon, @Nullable String _prefKey) {
        prefKey = _prefKey;
        setLayout(new BorderLayout());

        managed = _managed;
        title = _title;
        // A section opens the way it was last left. Every launch used to open with every section
        // collapsed, so Layer options had to be clicked open every single time.
        boolean expanded = remembered(startExpanded);
        ComponentUtils.setVisible(managed, expanded);

        toggleButton = new CollapsiblePaneButton(child);
        toggleButton.setSelected(expanded);
        // UIGlobals fills its fonts from the look and feel, which a headless check (and any code
        // that builds a section before the LAF is installed) never runs; fall back to the button's
        // own font rather than dying on a null. The look is unchanged wherever the app itself is
        // concerned, since by then uiFont is set.
        Font base = UIGlobals.uiFont != null ? UIGlobals.uiFont : toggleButton.getFont();
        headerSize = base.getSize2D() + (child ? CHILD_STEP : PARENT_STEP);
        toggleButton.setFont(base.deriveFont(child ? Font.PLAIN : Font.BOLD, headerSize));
        toggleButton.addActionListener(this);
        setSectionIcon(_sectionIcon); // sets the title too

        // Inset, so what reads as top level is exactly what runs the full width of the sidebar.
        // Weight and fill alone were not enough: a nested band is a different colour from the
        // parent band but the same shape in the same place, and shape is what the eye groups by.
        // The border is on the whole pane rather than the header, so the section's contents step
        // in with its title instead of hanging off the edge under an indented heading.
        if (child)
            setBorder(BorderFactory.createEmptyBorder(0, CHILD_INDENT, 0, 0));
        add(toggleButton, BorderLayout.PAGE_START);
        add(managed, BorderLayout.CENTER);
    }

    /** The section's own glyph, or null for none. The chevron keeps its place in front of it. */
    public void setSectionIcon(@Nullable Icon icon) {
        // Sized to this header's font, not left at whatever the constant was built for: the
        // Buttons toolbar glyphs are 18pt, which beside a title is a picture rather than a bullet.
        sectionIcon = icon instanceof GlyphIcon glyph ? glyph.derive(headerSize) : icon;
        setTitle(title);
    }

    public void setTitle(String _title) {
        title = _title;
        // Icon and title, not one string: concatenating them put the chevron's HTML in front of
        // the text and left the gap between them spelled as a non-breaking space.
        toggleButton.setIcons(toggleButton.isSelected() ? Buttons.chevronDown : Buttons.chevronRight, sectionIcon);
        toggleButton.setText(title);
    }

    public void setExpanded(boolean expanded) {
        ComponentUtils.setVisible(managed, expanded);
        toggleButton.setSelected(expanded);
        setTitle(title);
    }

    /** How this section was last left by a click, or the fallback when it never was. */
    public boolean remembered(boolean fallback) {
        String stored = Settings.getProperty(key());
        return stored == null ? fallback : Boolean.parseBoolean(stored);
    }

    @Nullable
    private final String prefKey;

    private String key() {
        return "ui.section." + (prefKey != null ? prefKey : title).replace(' ', '_');
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        boolean expanded = !managed.isVisible();
        setExpanded(expanded);
        Settings.setProperty(key(), Boolean.toString(expanded)); // a click is a preference; setExpanded from code is not
        // A palette is packed to its contents and nothing in Swing repacks a window by itself, so
        // a section expanded inside one was simply cut off at the window edge. Only a pane living
        // in a dialog asks: the sidebar's panes are in the main frame's JScrollPane, which takes
        // up the change itself, and a section click must never resize the application window.
        // Palette.repackAll rather than pack() on the ancestor because it also re-docks, and a
        // docked palette is aligned to the top-right corner: growing it without re-docking walks
        // it off that corner. It ignores dialogs that are not palettes, and is a no-op when the
        // window already fits.
        if (SwingUtilities.getWindowAncestor(this) instanceof JDialog)
            Palette.repackAll();
    }

}
