package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;

import javax.annotation.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.component.BusyIndicator;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.View;

@SuppressWarnings("serial")
class CellRenderer {

    /**
     * A row's background, tinted by what the layer's data actually is.
     *
     * <p>A JP2 layer and a FITS layer of the same instrument carry the same name and look
     * identical in this list, while one is an 8-bit browse product and the other the calibrated
     * original. The tint says which without adding a column. It is derived from the table's own
     * selection colour and then pulled most of the way back to the normal background, so it reads
     * as a wash rather than as a second kind of selection.
     */
    private static final float TINT = 0.12f; // how far toward the hue; selection is effectively 1

    @Nullable
    private static java.awt.Color tintFor(Object value) {
        if (!(value instanceof ImageLayer layer))
            return null;
        DataUri.Format format = layer.getView().getFormat();
        if (!(format instanceof DataUri.Format.Image image))
            return null;
        return switch (image) {
            // Calibrated, full depth. Green because it is the one that kept everything.
            case FITS -> new java.awt.Color(0x35, 0xA0, 0x6A);
            // Helioviewer's browse products: streamed, and 8-bit before they ever arrive.
            case JPIP, JP2, JPX -> new java.awt.Color(0x3A, 0x7B, 0xD5);
            // Already-rendered pictures rather than data.
            case PNG, JPEG -> new java.awt.Color(0xC0, 0x7A, 0x22);
            case ZIP -> null;
        };
    }

    /** The background a row should paint: selection wins, then the format wash, then plain. */
    static java.awt.Color background(JTable table, Object value, boolean isSelected) {
        if (isSelected)
            return table.getSelectionBackground();
        java.awt.Color base = table.getBackground();
        java.awt.Color tint = tintFor(value);
        if (tint == null)
            return base;
        return new java.awt.Color(
                Math.round(base.getRed() + (tint.getRed() - base.getRed()) * TINT),
                Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * TINT),
                Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * TINT));
    }

    /** Names the format for a tooltip, so the colour is discoverable rather than a private code. */
    @Nullable
    static String formatName(Object value) {
        if (!(value instanceof ImageLayer layer))
            return null;
        DataUri.Format format = layer.getView().getFormat();
        if (!(format instanceof DataUri.Format.Image image))
            return null;
        return switch (image) {
            case FITS -> "FITS";
            case JPIP -> "JPEG 2000 (streamed)";
            case JP2 -> "JP2";
            case JPX -> "JPX";
            case PNG -> "PNG";
            case JPEG -> "JPEG";
            case ZIP -> null;
        };
    }

    static final class Enabled extends DefaultTableCellRenderer {

        private final JCheckBox checkBox = new JCheckBox();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // https://stackoverflow.com/questions/3054775/jtable-strange-behavior-from-getaccessiblechild-method-resulting-in-null-point
            if (value instanceof Layer layer) {
                checkBox.setSelected(layer.isEnabled());
            }
            checkBox.setBackground(background(table, value, isSelected));
            return checkBox;
        }

    }

    static final class Loading extends DefaultTableCellRenderer {

        private final Font font = Buttons.getMaterialFont(getFont().getSize2D());
        private final BusyIndicator over = new BusyIndicator();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setBorder(null); //!
            label.setText(null);
            label.setBackground(background(table, value, isSelected));

            // https://stackoverflow.com/questions/3054775/jtable-strange-behavior-from-getaccessiblechild-method-resulting-in-null-point
            if (value instanceof Layer layer) {
                if (layer.isDownloading()) {
                    // Repaint the whole row (not just the spinner cell) each animation tick so the
                    // finished/total count in the Time column keeps up as frames stream in.
                    Rectangle rect = table.getCellRect(row, column, false);
                    table.repaint(0, rect.y, table.getWidth(), rect.height); // lazy

                    over.setForeground(label.getForeground());
                    over.setBackground(label.getBackground());
                    over.setOpaque(label.isOpaque());
                    return over;
                } else if (layer.isLocal()) {
                    label.setFont(font);
                    label.setText(Buttons.check);
                }
            }
            return label;
        }

    }

    // Which layer drives the movie clock. It used to be set by clicking the layer's NAME, while
    // clicking a few pixels to the side of the name merely selected it -- two very different
    // outcomes from what looks like the same click. It is a radio button now: one per row, next
    // to the visibility box, exclusive by nature, and it says what it does.
    static final class Master extends DefaultTableCellRenderer {

        private final JRadioButton radio = new JRadioButton();
        private final JLabel blank = new JLabel();

        Master() {
            radio.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            java.awt.Color background = background(table, value, isSelected);
            // Image layers can always drive the clock. So can a layer that carries its own time
            // series and says so; a point cloud loaded one file per epoch is the case in mind.
            // Anything else gets an empty cell rather than a radio that would never do anything.
            boolean isSource = value instanceof org.helioviewer.jhv.layers.TimelineSource ts && ts.canDriveTimeline();
            if (!(value instanceof ImageLayer) && !isSource) {
                blank.setOpaque(true);
                blank.setBackground(background);
                return blank;
            }
            radio.setSelected(isSource
                    ? value == Layers.getMasterTimelineSource()
                    : Layers.getMasterTimelineSource() == null && value == Layers.getActiveImageLayer());
            radio.setToolTipText("Use this layer as the master for the movie clock and frame rate");
            radio.setBackground(background);
            return radio;
        }

    }

    static final class Name extends DefaultTableCellRenderer {

        // setValue has no table and no selection state, so the wash has to be applied here.
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setBackground(background(table, value, isSelected));
            return c;
        }

        @Override
        public void setValue(Object value) {
            if (value instanceof Layer layer) {
                String layerName = layer.getName();
                setText(layerName);
                boolean master = layer == Layers.getMasterTimelineSource()
                        || (Layers.getMasterTimelineSource() == null && layer == Layers.getActiveImageLayer());
                // The tint alone is a private code; the tooltip is what makes it readable.
                String format = formatName(value);
                String tip = format == null ? null : layerName + "  \u00b7  " + format;
                if (master) {
                    setToolTipText(format == null ? layerName + " (master)" : tip + "  \u00b7  master");
                    setFont(UIGlobals.uiFontBold);
                } else {
                    setToolTipText(tip);
                    setFont(UIGlobals.uiFont);
                }
            }
        }

    }

    static final class Remove extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setBackground(background(table, value, isSelected));
            return c;
        }

        private final Font font = Buttons.getMaterialFont(getFont().getSize2D());

        @Override
        public void setValue(Object value) {
            setBorder(null); //!
            if (value instanceof Layer layer && layer.isDeletable()) {
                setFont(font);
                setText(Buttons.close);
            } else
                setText(null);
        }

    }

    static final class Time extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setBackground(background(table, value, isSelected));
            return c;
        }

        static final Font font = UIGlobals.sansFont;

        // "2026-07-08T03:43:39.715" -> "03:43:39"
        @Nullable
        private static String shortTime(@Nullable String timeString) {
            if (timeString == null)
                return null;
            int t = timeString.indexOf('T');
            if (t < 0)
                return timeString;
            int dot = timeString.indexOf('.', t);
            return timeString.substring(t + 1, dot > t ? dot : timeString.length());
        }

        @Override
        public void setValue(Object value) {
            if (value instanceof Layer layer) {
                setFont(font);
                // While downloading, append finished/total to the right of the timestamp (still
                // just left of the row's download spinner) rather than replacing the time.
                if (layer.isDownloading() && layer instanceof ImageLayer il) {
                    String loadStatus = il.getLoadStatus();
                    if (loadStatus != null) { // frames still on the wire: narrate the stage instead of "0 / 0"
                        setText(loadStatus);
                        return;
                    }
                    View view = il.getView();
                    int max = view.getMaximumFrameNumber();
                    // Before the download scope is known (max == 0) show 0 / 0 rather than 1 / 1.
                    String count = max == 0 ? "0 / 0" : view.getCompleteFrameCount() + " / " + (max + 1);
                    // Compact time-of-day (drop date + millis) so the count fits without widening
                    // the column; the full timestamp returns once the download completes.
                    String time = shortTime(layer.getTimeString());
                    setText(time == null ? count : time + "   " + count);
                } else {
                    setText(layer.getTimeString());
                }
            }
        }

    }

}
