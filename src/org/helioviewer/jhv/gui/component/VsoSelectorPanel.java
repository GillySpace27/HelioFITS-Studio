package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeSelectionModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.helioviewer.jhv.io.FitsRequest;
import org.helioviewer.jhv.layers.ImageLayer;

/**
 * Staging for native FITS out of the VSO, which is the federated route and covers most missions
 * through one query rather than one client each.
 *
 * <p>A tree rather than combo boxes, for two reasons. It matches the JP2 selector this sits beside,
 * and a JComboBox cannot live here at all: this panel is the content of a JideSplitButton popup,
 * and opening a combo's own popup inside one dismisses the outer menu, so the panel vanished on
 * the first click. A JTree opens no nested popup.
 *
 * <p>Every instrument and detector below was checked against the live service rather than
 * remembered, by querying one hour of 2012-01-01 and counting records: lasco 10, eit 1, aia 2402,
 * hmi 243, secchi 160, xrt 50, eis 13, and SECCHI's COR1/COR2/EUVI/HI1/HI2 all non-empty. Missions
 * outside that date (MDI, TRACE, SXT) are left out rather than listed untested: absence of
 * records for one hour is not evidence the name is wrong, and it is not evidence it is right.
 *
 * <p>SUVI was checked the same way against one hour of 2026-08-30: "suvi" answers 720 records,
 * native L1b FITS served by NOAA, six channels times two spacecraft (G18 and G19; e.g. Fe195 at
 * 105 each, Fe093 at 90 each). VSO has no wavelength filter in this client, but the fileids carry
 * channel and satellite in the filename ("SUVI-L1b-Fe195_G19"), so each leaf below names its
 * channel token and VsoClient.filterRecords narrows the answer to that channel on one spacecraft.
 * Note the 9.4 nm channel is "Fe093" in the filename, not Fe094.
 *
 * <p>The layer carries the query, not the file list, so it follows the master time range from then
 * on the way a JP2 layer does.
 */
@SuppressWarnings("serial")
public final class VsoSelectorPanel extends JPanel {

    /**
     * A selectable leaf: what VSO calls the instrument, the detector within it if any, and a
     * fileid token for what VSO cannot filter itself (SUVI channels; see the class comment).
     */
    private record Source(String label, String instrument, String detector, String fileidToken) {
        private Source(String label, String instrument, String detector) {
            this(label, instrument, detector, "");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final JTree tree;

    public VsoSelectorPanel(LongSupplier startTime, LongSupplier endTime) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("VSO");
        root.add(observatory("SOHO",
                new Source("LASCO C2", "lasco", "C2"),
                new Source("LASCO C3", "lasco", "C3"),
                new Source("EIT", "eit", "")));
        root.add(observatory("SDO",
                new Source("AIA", "aia", ""),
                new Source("HMI", "hmi", "")));
        root.add(observatory("STEREO",
                new Source("SECCHI COR1", "secchi", "COR1"),
                new Source("SECCHI COR2", "secchi", "COR2"),
                new Source("SECCHI EUVI", "secchi", "EUVI"),
                new Source("SECCHI HI1", "secchi", "HI1"),
                new Source("SECCHI HI2", "secchi", "HI2")));
        root.add(observatory("Hinode",
                new Source("XRT", "xrt", ""),
                new Source("EIS", "eis", "")));
        root.add(observatory("GOES",
                new Source("SUVI 94", "suvi", "", "Fe093"),
                new Source("SUVI 131", "suvi", "", "Fe131"),
                new Source("SUVI 171", "suvi", "", "Fe171"),
                new Source("SUVI 195", "suvi", "", "Fe195"),
                new Source("SUVI 284", "suvi", "", "Fe284"),
                new Source("SUVI 304", "suvi", "", "He303")));

        tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setSelectionModel(new LeafOnlySelectionModel());
        for (int i = 0; i < tree.getRowCount(); i++)
            tree.expandRow(i);
        tree.setToolTipText("Calibrated FITS at full bit depth. Larger and slower to load than the JP2 of the same frame.");

        JButton add = new JButton("Add FITS layer");
        add.setToolTipText("Query the VSO over the master time range and load what it returns");
        add.setEnabled(false);
        add.addActionListener(e -> {
            Source source = getSelected();
            if (source == null)
                return;
            // The request goes onto the layer before any file is resolved, which is what lets the
            // time-range sync re-issue it later. Detector rides in the level field, the fileid
            // token in the version field; see FitsRequest and VsoClient.filterRecords.
            ImageLayer.create(null).load(new FitsRequest(FitsRequest.Archive.VSO,
                    source.detector, source.instrument, source.fileidToken, 0, startTime.getAsLong(), endTime.getAsLong()));
        });
        tree.addTreeSelectionListener(e -> add.setEnabled(getSelected() != null));

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(240, 190));
        scroll.setBorder(BorderFactory.createEmptyBorder());

        add(scroll, BorderLayout.CENTER);
        add(add, BorderLayout.PAGE_END);
    }

    private static DefaultMutableTreeNode observatory(String name, Source... sources) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(name);
        for (Source s : sources)
            node.add(new DefaultMutableTreeNode(s));
        return node;
    }

    @Nullable
    private Source getSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null)
            return null;
        Object obj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return obj instanceof Source s ? s : null;
    }

    /** Observatory rows group, they do not select: clicking one should expand, not arm the button. */
    private static class LeafOnlySelectionModel extends DefaultTreeSelectionModel {

        LeafOnlySelectionModel() {
            setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        }

        @Override
        public void setSelectionPaths(TreePath[] paths) {
            if (paths != null && paths.length > 0
                    && ((DefaultMutableTreeNode) paths[0].getLastPathComponent()).getUserObject() instanceof Source)
                super.setSelectionPaths(paths);
        }

    }

}
