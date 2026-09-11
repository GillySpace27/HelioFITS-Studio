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
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;
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
    /** Carries the band's fill behind whatever sits beside the title, so there is no notch in it. */
    /** Ground under a top-level section, between its last control and the next section's band. */
    private static final int SECTION_GAP = 5;

    private final JPanel header = new JPanel(new BorderLayout());
    @Nullable
    private JComponent accessory;
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
        header.setOpaque(true);
        UIGlobals.themed(header, c -> c.setBackground(
                Theme.current().get(child ? Theme.Token.ChildHeaderFill : Theme.Token.HeaderFill)));
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
        if (!child) {
            // Where one panel ends and the next begins. Expanded, a section is a coloured band
            // followed by an undifferentiated stretch of contents, and the next band sits straight
            // on the end of it, so two open panels read as one long one with a stripe through the
            // middle. A rule and a few pixels of ground under each closes the block: the band
            // opens it, this ends it, and the gap says the next band belongs to something else.
            UIGlobals.themed(this, c -> c.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UIGlobals.separator()),
                    BorderFactory.createEmptyBorder(0, 0, SECTION_GAP, 0))));
        }
        if (child) {
            setBorder(BorderFactory.createEmptyBorder(0, CHILD_INDENT, 0, 0));
            // And the ground under its contents is stepped down from the panel's, so the nested
            // block reads as recessed rather than as another band on the same surface. The band
            // itself cannot carry this: a section header has to clear 3:1 against the panel, and
            // in a dark theme the parent band is already within a tenth of that floor, so there is
            // no darker band available. A body has no such rule, only that its text stays legible
            // on it, which Theme.nestedSurface() is what enforces.
            setOpaque(true);
            UIGlobals.themed(this, c -> c.setBackground(Theme.nestedSurface()));
            UIGlobals.themed(managed, c -> {
                c.setOpaque(true);
                c.setBackground(Theme.nestedSurface());
            });
        }
        header.add(toggleButton, BorderLayout.CENTER);
        add(header, BorderLayout.PAGE_START);
        add(managed, BorderLayout.CENTER);
    }

    /**
     * Controls that ride at the trailing end of the header, in line with the title.
     *
     * <p>These used to be a row of their own under the header, because a button placed here sits
     * outside the toggle that paints the header's coloured band and left a notch of window
     * background in it. The notch is the thing to fix, not the placement: the strip carries the
     * same fill, so the band is continuous and the controls are on it. A row of their own cost a
     * whole line of sidebar height per section and read as content rather than as chrome.
     */
    public void setAccessory(@Nullable JComponent accessory) {
        if (this.accessory != null)
            header.remove(this.accessory);
        this.accessory = accessory;
        if (accessory != null) {
            accessory.setOpaque(false);
            header.add(accessory, BorderLayout.LINE_END);
        }
        header.revalidate();
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

    /**
     * Blink the header a few times: "it is here".
     *
     * <p>For the panel lock. Locked, a palette's toolbar button cannot show or hide the panel, but
     * that is exactly when you most want to know WHERE it went, and unlocking, hunting, and
     * locking again to find out is a worse answer than the question deserves. So a locked button
     * reveals the section and blinks it instead of moving anything.
     *
     * <p>Three blinks at 180 ms, which is long enough to catch out of the corner of an eye and
     * short enough to be over before it becomes something happening AT you. The band's own colour
     * is put back at the end rather than assumed, because a theme switch may have changed it while
     * the timer was running.
     */
    public void flash() {
        // The whole band, which means the button too. The header panel's own background shows
        // only where nothing is drawn over it, and that is the strip the docking icons sit in:
        // the accessory is non-opaque, the toggle button paints its own fill across everything
        // else. Colouring the header alone therefore blinked the three icons and left the title
        // and its band sitting there unmoved, which reads as a glitch rather than as an answer.
        java.awt.Color wasHeader = header.getBackground();
        java.awt.Color wasButton = toggleButton.getBackground();
        boolean wasOpaque = toggleButton.isOpaque();
        java.awt.Color hit = UIGlobals.separator();
        javax.swing.Timer timer = new javax.swing.Timer(180, null);
        int[] left = {6}; // three on, three off
        timer.addActionListener(e -> {
            boolean on = left[0] % 2 == 0;
            header.setBackground(on ? hit : wasHeader);
            toggleButton.setOpaque(on || wasOpaque);
            toggleButton.setBackground(on ? hit : wasButton);
            header.repaint();
            if (--left[0] <= 0) {
                timer.stop();
                header.setBackground(wasHeader);
                toggleButton.setOpaque(wasOpaque);
                toggleButton.setBackground(wasButton);
                header.repaint();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    public boolean isExpanded() {
        return toggleButton.isSelected();
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
