package org.helioviewer.jhv.gui.component;

import java.awt.Font;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JToggleButton;

import org.helioviewer.jhv.gui.UIGlobals;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;

public class Buttons {

    public static Font getMaterialFont(float size) {
        return UIGlobals.uiFontMDI.deriveFont(size);
    }

    /**
     * A flat, borderless button: the JideButton look, drawn by FlatLaf instead of by JIDE.
     *
     * <p>JideButton is painted by BasicJideButtonUI, which reads none of FlatLaf's client
     * properties, so on those buttons the theme's hover, pressed and disabled colours never
     * arrived and the toolbar's rounded button groups were never drawn. Marking an ordinary
     * JButton as a toolbar button gets all of it from the look-and-feel, inside a JToolBar or
     * anywhere else (FlatButtonUI.isToolBarButton reads either the parent or this property).
     */
    public static JButton flat(String text) {
        JButton button = new JButton(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        // JideButton's own setting, and the reason the movie keys kept working: clicking a
        // toolbar button must not take the keyboard focus off whatever had it, or the next
        // Space would press that button instead of reaching the scrubber.
        button.setRequestFocusEnabled(false);
        return button;
    }

    public static JButton flat(Icon icon) {
        JButton button = flat((String) null);
        button.setIcon(icon);
        return button;
    }

    public static JToggleButton flatToggle(String text) {
        return flatToggle(text, false);
    }

    public static JToggleButton flatToggle(String text, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setRequestFocusEnabled(false);
        return button;
    }

    public static JToggleButton flatToggle(Icon icon, boolean selected) {
        JToggleButton button = flatToggle((String) null, selected);
        button.setIcon(icon);
        return button;
    }

    // Three sizes, and only three: the row height a glyph sits in is what decides it. Inline
    // is a button in an options row, chevron is a disclosure or a collapse handle, and toolbar
    // is the big glyph on the top bar. They match what the HTML asked for (font-size:12px,
    // size=4, size=5) closely enough that nothing on screen moves.
    private static final float INLINE = 14;
    private static final float CHEVRON = 14;
    private static final float TOOLBAR = 18;

    // Text, not icons: these two are drawn into table cells by a renderer that sets the icon
    // font itself and sizes the glyph off the row's own font, which an icon cannot follow.
    public static final String close = MaterialDesign.CLOSE.toString();
    public static final String check = MaterialDesign.CHECK.toString();
    // A funnel, in the same family as the check and the close above: these three are drawn into
    // table cells by a renderer that sets the icon font itself and sizes the glyph off the row's
    // own font, which an Icon cannot follow.
    public static final String filtered = MaterialDesign.FILTER.toString();

    public static final GlyphIcon play = icon(MaterialDesign.PLAY, INLINE);
    public static final GlyphIcon pause = icon(MaterialDesign.PAUSE, INLINE);
    public static final GlyphIcon backward = icon(MaterialDesign.STEP_BACKWARD, INLINE);
    public static final GlyphIcon forward = icon(MaterialDesign.STEP_FORWARD, INLINE);
    public static final GlyphIcon record = icon(MaterialDesign.RECORD, INLINE);

    public static final GlyphIcon collapseLeft = icon(MaterialDesign.CHEVRON_LEFT, CHEVRON);
    public static final GlyphIcon collapseRight = icon(MaterialDesign.CHEVRON_RIGHT, CHEVRON);
    public static final GlyphIcon chevronRight = icon(MaterialDesign.CHEVRON_RIGHT, CHEVRON);
    public static final GlyphIcon chevronDown = icon(MaterialDesign.CHEVRON_DOWN, CHEVRON);

    public static final GlyphIcon newLayer = icon(MaterialDesign.PLUS_CIRCLE, INLINE);
    public static final GlyphIcon syncLayers = icon(MaterialDesign.SYNC, INLINE);
    public static final GlyphIcon lock = icon(MaterialDesign.LOCK, INLINE);
    /** The toolbar corner's panel lock, at the size the corner's edit control is drawn. */
    public static final GlyphIcon lockPanels = icon(MaterialDesign.LOCK, INLINE);
    /** The corner badge itself: small, because it qualifies the glyph rather than replacing it. */
    public static final GlyphIcon lockBadge = icon(MaterialDesign.LOCK, 10);
    public static final GlyphIcon unlockPanels = icon(MaterialDesign.LOCK_OPEN, INLINE);
    public static final GlyphIcon unlock = icon(MaterialDesign.LOCK_OPEN, INLINE);

    public static final GlyphIcon sync = icon(MaterialDesign.SYNC, INLINE);
    public static final GlyphIcon runFilter = icon(MaterialDesign.PLAY, INLINE);
    public static final GlyphIcon stopFilter = icon(MaterialDesign.STOP, INLINE);
    public static final GlyphIcon info = icon(MaterialDesign.INFORMATION_VARIANT, INLINE);
    public static final GlyphIcon save = icon(MaterialDesign.CONTENT_SAVE, INLINE);
    public static final GlyphIcon load = icon(MaterialDesign.FOLDER_OPEN, INLINE);
    public static final GlyphIcon newSession = icon(MaterialDesign.PLUS, INLINE);
    public static final GlyphIcon revert = icon(MaterialDesign.BACKUP_RESTORE, INLINE);
    public static final GlyphIcon saveAs = icon(MaterialDesign.CONTENT_SAVE_ALL, INLINE);
    public static final GlyphIcon popOut = icon(MaterialDesign.OPEN_IN_NEW, INLINE);
    /** Bars across a time axis, for the Timeline Layers section. Not CHART_GANTT: that is the toolbar's differential rotation. */
    public static final GlyphIcon timeline = icon(MaterialDesign.CHART_TIMELINE, INLINE);
    /** The same mark at toolbar size, for the button that pops the Timelines pane up and down. */
    public static final GlyphIcon timelineToolbar = icon(MaterialDesign.CHART_TIMELINE, TOOLBAR);
    /** The window with one side marked: the layout toggles, as an editor draws them. Timelines is the bottom one. */
    public static final GlyphIcon sidebarLeft = icon(MaterialDesign.PAGE_LAYOUT_SIDEBAR_LEFT, TOOLBAR);
    public static final GlyphIcon sidebarRight = icon(MaterialDesign.PAGE_LAYOUT_SIDEBAR_RIGHT, TOOLBAR);
    /** A bolt, for the Space Weather Event Knowledgebase. Not a warning triangle: this is a catalogue, not an error. */
    public static final GlyphIcon events = icon(MaterialDesign.FLASH, INLINE);
    /**
     * Pencil beside a gear, for the control that changes what the toolbar holds.
     *
     * <p>Two glyphs rather than one because neither says it alone: a pencil is "edit" and belongs
     * to whatever is under the pointer, a gear is "settings" and every application has six of them.
     * Together they read as editing the settings of this thing, which is what the button does, and
     * they are unlike any single-glyph tool on the bar, which is what a permanent corner control
     * has to be.
     *
     * <p>The gear is first and the pencil second, overlapping it by five pixels. Second means drawn
     * second, so the pencil is the one on top, and the MDI pencil points down and to the left: its
     * graphite lands on the gear rather than beside it. Two glyphs merely adjacent read as two
     * buttons pushed together; overlapped, they read as one mark.
     */
    public static final Icon editToolbarCorner =
            new PairIcon(icon(MaterialDesign.SETTINGS, INLINE), icon(MaterialDesign.PENCIL, INLINE), -5);

    /**
     * A small glyph in the bottom-right corner of another, for a state the button is IN rather
     * than a thing it does. Sized to the base, so badging one does not move it in a row of them.
     */
    public static Icon badged(Icon base, Icon badge) {
        return new BadgedIcon(base, badge);
    }

    private record BadgedIcon(Icon base, Icon badge) implements Icon, FlatLaf.DisabledIconProvider {

        @Override
        public int getIconWidth() {
            return base.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return base.getIconHeight();
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            base.paintIcon(c, g, x, y);
            // Overhanging the corner a little: a badge tucked fully inside reads as part of the
            // glyph rather than as something stuck on it.
            badge.paintIcon(c, g, x + base.getIconWidth() - badge.getIconWidth() + 2,
                    y + base.getIconHeight() - badge.getIconHeight() + 2);
        }

        @Override
        public Icon getDisabledIcon() {
            return new BadgedIcon(disabled(base), disabled(badge));
        }

        static Icon disabled(Icon icon) {
            return icon instanceof FlatLaf.DisabledIconProvider p ? p.getDisabledIcon() : icon;
        }
    }

    /** Two icons side by side, each centred on the taller. A button has one icon slot. */
    private record PairIcon(Icon first, Icon second, int gap) implements Icon, FlatLaf.DisabledIconProvider {

        @Override
        public Icon getDisabledIcon() { // else both halves keep painting in the enabled foreground
            return new PairIcon(BadgedIcon.disabled(first), BadgedIcon.disabled(second), gap);
        }

        @Override
        public int getIconWidth() {
            return first.getIconWidth() + gap + second.getIconWidth(); // a negative gap overlaps them
        }

        @Override
        public int getIconHeight() {
            return Math.max(first.getIconHeight(), second.getIconHeight());
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            int h = getIconHeight();
            first.paintIcon(c, g, x, y + (h - first.getIconHeight()) / 2);
            second.paintIcon(c, g, x + first.getIconWidth() + gap, y + (h - second.getIconHeight()) / 2);
        }

    }
    public static final GlyphIcon collapseAll = icon(MaterialDesign.CHEVRON_UP, INLINE);
    public static final GlyphIcon expandAll = icon(MaterialDesign.CHEVRON_DOWN, INLINE);
    // Arrows, not chevrons. A chevron in this application means disclosure: the one on a section
    // header opens it, the one on the sidebar handle folds the bar away. Reordering a section is a
    // different verb, and giving it the same glyph left two controls a few pixels apart that looked
    // identical and did unrelated things.
    public static final GlyphIcon moveUp = icon(MaterialDesign.ARROW_UP, INLINE);
    public static final GlyphIcon moveDown = icon(MaterialDesign.ARROW_DOWN, INLINE);
    public static final GlyphIcon download = icon(MaterialDesign.DOWNLOAD, INLINE);
    public static final GlyphIcon cache = icon(MaterialDesign.FOLDER_OPEN, INLINE);
    public static final GlyphIcon deleteCache = icon(MaterialDesign.DELETE, INLINE);

    public static final GlyphIcon invert = icon(MaterialDesign.INVERT_COLORS, INLINE);
    public static final GlyphIcon colorbar = icon(MaterialDesign.BORDER_ALL, INLINE);
    public static final GlyphIcon corona = icon(MaterialDesign.WHITE_BALANCE_SUNNY, INLINE);

    public static final GlyphIcon calendar = icon(MaterialDesign.CALENDAR, INLINE);
    public static final GlyphIcon skipBack = icon(MaterialDesign.SKIP_BACKWARD, INLINE);
    public static final GlyphIcon skipFore = icon(MaterialDesign.SKIP_FORWARD, INLINE);

    // toolbar

    public static final GlyphIcon annotate = icon(MaterialDesign.SHAPE_POLYGON_PLUS, TOOLBAR);
    public static final GlyphIcon axis = icon(MaterialDesign.BACKUP_RESTORE, TOOLBAR);
    public static final GlyphIcon diffRotation = icon(MaterialDesign.CHART_GANTT, TOOLBAR);
    public static final GlyphIcon multiview = icon(MaterialDesign.BORDER_ALL, TOOLBAR);
    public static final GlyphIcon offDisk = icon(MaterialDesign.WEATHER_SUNNY, TOOLBAR);
    public static final GlyphIcon pan = icon(MaterialDesign.CURSOR_MOVE, TOOLBAR);
    public static final GlyphIcon projection = icon(MaterialDesign.CUBE_OUTLINE, TOOLBAR);
    public static final GlyphIcon trackCme = icon(MaterialDesign.CROSSHAIRS_GPS, TOOLBAR); // a reticle: pick a front and hold it
    public static final GlyphIcon activityDone = icon(MaterialDesign.CHECK, INLINE); // the footer's "everything has landed"
    public static final GlyphIcon sequenceFilter = icon(MaterialDesign.FILTER, TOOLBAR); // NOT a vector-circle
    public static final GlyphIcon grid = icon(MaterialDesign.GRID, TOOLBAR);
    public static final GlyphIcon camera = icon(MaterialDesign.CAMERA, TOOLBAR);
    public static final GlyphIcon colourSettings = icon(MaterialDesign.IMAGE_FILTER_HDR, TOOLBAR);
    public static final GlyphIcon presentation = icon(MaterialDesign.PROJECTOR_SCREEN, TOOLBAR);
    public static final GlyphIcon overflow = icon(MaterialDesign.CHEVRON_DOWN, TOOLBAR);
    public static final GlyphIcon editToolbar = icon(MaterialDesign.PENCIL, TOOLBAR);
    public static final GlyphIcon dragHandle = icon(MaterialDesign.DRAG_HORIZONTAL, INLINE);
    public static final GlyphIcon refresh = icon(MaterialDesign.REFRESH, TOOLBAR);
    /** A box with an arrow leaving it: the AIA cut-out hands the view off to a service in a browser. */
    public static final GlyphIcon sdoCutout = icon(MaterialDesign.OPEN_IN_NEW, TOOLBAR);
    public static final GlyphIcon resetCamera = icon(MaterialDesign.IMAGE_FILTER_CENTER_FOCUS, TOOLBAR);
    public static final GlyphIcon resetCameraAxis = icon(MaterialDesign.DEBUG_STEP_OUT, TOOLBAR);
    public static final GlyphIcon rotate = icon(MaterialDesign.ROTATE_3D, TOOLBAR);
    public static final GlyphIcon rotate90 = icon(MaterialDesign.ROTATE_90, TOOLBAR);
    public static final GlyphIcon samp = icon(MaterialDesign.SHARE_VARIANT, TOOLBAR);
    public static final GlyphIcon track = icon(MaterialDesign.CROSSHAIRS_GPS, TOOLBAR);
    public static final GlyphIcon zoomFit = icon(MaterialDesign.CROP_LANDSCAPE, TOOLBAR);
    public static final GlyphIcon zoomIn = icon(MaterialDesign.MAGNIFY_PLUS, TOOLBAR);
    public static final GlyphIcon zoomOne = icon(MaterialDesign.PLUS_ONE, TOOLBAR);
    public static final GlyphIcon zoomOut = icon(MaterialDesign.MAGNIFY_MINUS, TOOLBAR);

    private static GlyphIcon icon(MaterialDesign uc, float size) {
        return new GlyphIcon(uc, size);
    }

}
