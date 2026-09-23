package org.helioviewer.jhv.layers.filters;

import java.awt.Component;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.image.ImageProcessingSettings;

/**
 * Which image inside the file this layer shows, beside the colour table that paints it.
 *
 * <p>A FITS file can hold more than one image. PUNCH's polarized products are cubes of three,
 * B / pB / pBp, named in the file's own OBSLAYR keywords; most other files hold exactly one. The
 * row is shown either way and the single image is numbered like any other, because "1. Primary"
 * answers the question a layer of unknown provenance raises ("is there more in here?") while an
 * absent row leaves it open.
 *
 * <p>In Display rather than in the FITS rows under Intensity: choosing the plane is choosing what
 * is on screen, which is the same kind of decision as the colour table, not a detail of how the
 * numbers are stretched.
 */
public final class PlanePanel implements FilterDetails, ImageProcessingSettings.FITSListener {

    /**
     * One image, as both this row and the chooser dialog name it.
     *
     * <p>The index rides along rather than being looked up from the label, so a file that names
     * two of its images the same still selects the one that was clicked.
     */
    public record PlaneOption(int index, String label) {
        @Override
        public String toString() {
            return (index + 1) + ".  " + label;
        }
    }

    private final ImageProcessingSettings state;
    private final Runnable onPlaneChanged;
    private final JLabel title = new JLabel("Image ", JLabel.RIGHT);
    private final JComboBox<PlaneOption> combo = new JComboBox<>();
    private final JPanel filler = new JPanel();
    private boolean syncing;
    private List<String> shown = List.of();

    public PlanePanel(ImageProcessingSettings _state, Runnable _onPlaneChanged) {
        state = _state;
        onPlaneChanged = _onPlaneChanged;
        combo.setToolTipText("Which image inside the file this layer shows; changing it reads the frames again");
        combo.addActionListener(e -> {
            if (syncing || !(combo.getSelectedItem() instanceof PlaneOption option)
                    || option.index() == state.fitsParameters().plane())
                return;
            state.setPlane(option.index());
            onPlaneChanged.run(); // the clip set is sampled per image, so the frames must be re-read
        });
        filler.setOpaque(false);
        state.addFITSListener(this);
        fitsParametersChanged();
    }

    /**
     * Follow the settings, including whether this row exists at all.
     *
     * <p>Visibility is decided here rather than by the panel that owns the row, because the
     * answer arrives late: a layer is selected before its load has said what the file holds, so a
     * row shown or hidden at selection time is a row showing the previous layer's answer. Set
     * once from outside, it filled its combo when the planes landed and stayed invisible.
     */
    @Override
    public void fitsParametersChanged() {
        syncing = true;
        try {
            List<String> planes = state.planes();
            setVisible(!planes.isEmpty());
            if (!planes.equals(shown)) {
                shown = planes;
                combo.removeAllItems();
                for (int i = 0; i < planes.size(); i++)
                    combo.addItem(new PlaneOption(i, planes.get(i)));
            }
            // A file with one image still offers it, so the row reads the same everywhere; there
            // is simply nothing else in the list to pick.
            combo.setEnabled(planes.size() > 1);
            int plane = state.fitsParameters().plane();
            if (plane < combo.getItemCount())
                combo.setSelectedIndex(plane);
        } finally {
            syncing = false;
        }
    }

    /** Only a FITS layer has images inside a file; everything else has no such question. */
    public boolean hasPlanes() {
        return !state.planes().isEmpty();
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return combo;
    }

    @Override
    public Component getThird() {
        return filler;
    }

}
