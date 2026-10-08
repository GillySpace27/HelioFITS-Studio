package org.helioviewer.jhv.gui.dialog;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.gui.time.TimeSelectorPanel;
import org.helioviewer.jhv.io.MlsoClient;
import org.helioviewer.jhv.time.TimeUtils;

import com.jidesoft.dialog.ButtonPanel;
import com.jidesoft.dialog.StandardDialog;

/** Search the HAO MLSO API (KCor, UCoMP) by time range and load the chosen files as one layer. */
@SuppressWarnings("serial")
public class MlsoDialog extends StandardDialog implements MlsoClient.Receiver {

    private static final int MAX_FILES = 5000;
    private static final int CONFIRM_FILES = 200;
    private static final Dimension resultSize = new Dimension(500, 300);
    private static final String ANY_WAVE = "any wavelength";

    private record Cadence(String label, long milli) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Cadence[] Cadences = {
            new Cadence("native cadence", 0),
            new Cadence("every 10 minutes", 10 * TimeUtils.MINUTE_IN_MILLIS),
            new Cadence("every 30 minutes", 30 * TimeUtils.MINUTE_IN_MILLIS),
            new Cadence("every hour", 60 * TimeUtils.MINUTE_IN_MILLIS),
            new Cadence("every 6 hours", 6 * 60 * TimeUtils.MINUTE_IN_MILLIS),
            new Cadence("every day", TimeUtils.DAY_IN_MILLIS)};

    private final JComboBox<MlsoClient.Instrument> instrumentCombo = new JComboBox<>();
    private final JComboBox<MlsoClient.Product> productCombo = new JComboBox<>();
    private final JComboBox<Cadence> cadenceCombo = new JComboBox<>(Cadences);
    private final JComboBox<String> waveCombo = new JComboBox<>();
    private final JTextField usernameField = new JTextField(22);
    private final TimeSelectorPanel timeSelectorPanel = new TimeSelectorPanel();
    private final JList<MlsoClient.DataItem> listPane = new JList<>();
    private final JLabel statusLabel = new JLabel(" ", JLabel.LEFT);
    private final JLabel foundLabel = new JLabel("0 found", JLabel.RIGHT);
    private final JLabel selectedLabel = new JLabel("0 selected", JLabel.RIGHT);

    private boolean instrumentsDownloaded;
    private boolean updatingCombos;
    private boolean signingIn; // the dialog has closed itself; only a sign-in failure brings it back

    private static final class Holder { // built on first use, which is always on the EDT
        static final MlsoDialog INSTANCE = new MlsoDialog(MainFrame.get());
    }

    public static MlsoDialog getInstance() {
        return Holder.INSTANCE;
    }

    private MlsoDialog(JFrame mainFrame) {
        super(mainFrame, true);
        setResizable(false);
        setTitle("New MLSO Layer");
        waveCombo.addItem(ANY_WAVE);
        for (String w : MlsoClient.UCOMP_WAVE_REGIONS)
            waveCombo.addItem(w + " nm");
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction close = ComponentUtils.hideAction(this);
        setDefaultCancelAction(close);

        JButton loadButton = new JButton("Add");
        loadButton.addActionListener(e -> load());
        JButton cancelButton = new JButton(close);
        cancelButton.setText("Cancel");

        ButtonPanel panel = new ButtonPanel();
        panel.add(loadButton, ButtonPanel.AFFIRMATIVE_BUTTON);
        panel.add(cancelButton, ButtonPanel.CANCEL_BUTTON);
        return panel;
    }

    private void load() {
        List<MlsoClient.DataItem> items = listPane.getSelectedValuesList();
        if (items.isEmpty())
            return;
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            statusLabel.setText("Downloads need an email registered at " + MlsoClient.REGISTER_URL);
            usernameField.requestFocusInWindow();
            return;
        }
        if (items.size() > MAX_FILES) {
            statusLabel.setText("Too many files: " + items.size() + ". Select fewer than " + MAX_FILES + " or a sparser cadence.");
            return;
        }
        if (items.size() > CONFIRM_FILES) {
            int choice = JOptionPane.showConfirmDialog(this,
                    String.format("This will download %d FITS files (%s listed).%nProceed?", items.size(), sizeText(items)),
                    "Large MLSO download", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION)
                return;
        }
        Settings.setProperty(MlsoClient.USERNAME_KEY, username);
        statusLabel.setText("Signing in to MLSO...");
        signingIn = true;
        MlsoClient.submitLoad(this, items, username);
        setVisible(false);
    }

    @Override
    public JComponent createContentPanel() {
        JPanel sourcePanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
        sourcePanel.add(instrumentCombo);
        sourcePanel.add(productCombo);
        sourcePanel.add(waveCombo);
        sourcePanel.add(cadenceCombo);
        JButton searchButton = new JButton("Search");
        searchButton.addActionListener(e -> search());
        sourcePanel.add(searchButton);

        waveCombo.setToolTipText("UCoMP wave region; the API filters on it for UCoMP only");
        cadenceCombo.setToolTipText("At most one file per period, chosen by the MLSO API");
        instrumentCombo.addActionListener(e -> {
            if (!updatingCombos && instrumentCombo.getSelectedItem() instanceof MlsoClient.Instrument inst)
                instrumentChanged(inst);
        });
        productCombo.addActionListener(e -> {
            if (!updatingCombos) {
                clearResults();
                if (productCombo.getSelectedItem() instanceof MlsoClient.Product p)
                    productCombo.setToolTipText(p.description().isEmpty() ? null : p.description());
            }
        });

        JPanel userPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
        userPanel.add(new JLabel("Registered email", JLabel.RIGHT));
        usernameField.setToolTipText("MLSO downloads need an email registered at " + MlsoClient.REGISTER_URL + "; searching does not");
        userPanel.add(usernameField);

        JPanel foundPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
        JButton selectAllButton = new JButton("Select All");
        selectAllButton.addActionListener(e -> {
            int n = listPane.getModel().getSize();
            if (n > 0)
                listPane.setSelectionInterval(0, n - 1);
        });
        foundPanel.add(selectAllButton);
        foundPanel.add(foundLabel);
        JPanel selectedPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
        selectedPanel.add(selectedLabel);
        listPane.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                List<MlsoClient.DataItem> sel = listPane.getSelectedValuesList();
                selectedLabel.setText(sel.size() + " selected" + (sel.isEmpty() ? "" : ", " + sizeText(sel)));
            }
        });
        com.jidesoft.swing.SearchableUtils.installSearchable(listPane);
        JScrollPane scrollPane = new JScrollPane(listPane);
        scrollPane.setPreferredSize(resultSize);

        timeSelectorPanel.addListener((start, end) -> clearResults());

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 0));
        statusPanel.add(statusLabel);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        timeSelectorPanel.addUseMovieTimeButton();
        content.add(timeSelectorPanel);
        content.add(sourcePanel);
        content.add(userPanel);
        content.add(statusPanel);
        content.add(foundPanel);
        content.add(scrollPane);
        content.add(selectedPanel);
        content.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        return content;
    }

    private void instrumentChanged(MlsoClient.Instrument inst) {
        clearResults();
        instrumentCombo.setToolTipText(inst.dates().isEmpty() ? inst.id() : inst.id() + ": " + inst.dates());
        waveCombo.setEnabled("ucomp".equals(inst.id()));
        updatingCombos = true;
        try {
            productCombo.setModel(new DefaultComboBoxModel<>());
        } finally {
            updatingCombos = false;
        }
        statusLabel.setText("Listing " + inst.id() + " products...");
        MlsoClient.submitGetProducts(this, inst.id());
    }

    private void search() {
        if (instrumentCombo.getSelectedItem() instanceof MlsoClient.Instrument inst &&
                productCombo.getSelectedItem() instanceof MlsoClient.Product product &&
                cadenceCombo.getSelectedItem() instanceof Cadence cadence) {
            clearResults();
            String wave = waveCombo.isEnabled() && waveCombo.getSelectedItem() instanceof String w && !ANY_WAVE.equals(w)
                    ? w.replace(" nm", "") : null;
            foundLabel.setText("Searching...");
            MlsoClient.submitSearch(this, inst.id(), product.id(), timeSelectorPanel.getStartTime(),
                    timeSelectorPanel.getEndTime(), cadence.milli, wave);
        }
    }

    private void clearResults() {
        listPane.setListData(new MlsoClient.DataItem[0]);
        foundLabel.setText("0 found");
        selectedLabel.setText("0 selected");
    }

    // The API documents that filesize can be 0 for some files, so a total is a lower bound.
    private static String sizeText(List<MlsoClient.DataItem> items) {
        long bytes = 0;
        boolean unknown = false;
        for (MlsoClient.DataItem item : items) {
            bytes += item.size();
            unknown |= item.size() <= 0;
        }
        return (unknown ? "at least " : "") + String.format("%.1f MB", bytes / 1e6);
    }

    @Nullable
    @Override
    public JComponent createBannerPanel() {
        return null;
    }

    public void showDialog() {
        signingIn = false;
        if (usernameField.getText().isBlank()) {
            String saved = Settings.getProperty(MlsoClient.USERNAME_KEY);
            if (saved != null)
                usernameField.setText(saved);
        }
        long start = MoviePanel.getInstance().getStartTime();
        long end = MoviePanel.getInstance().getEndTime();
        if (start > TimeUtils.START.milli && end > start)
            timeSelectorPanel.setTime(start, end);
        if (!instrumentsDownloaded) {
            statusLabel.setText("Listing MLSO instruments...");
            MlsoClient.submitGetInstruments(this);
        }
        pack();
        setLocationRelativeTo(MainFrame.get());
        setVisible(true);
    }

    @Override
    public void setMlsoResponseInstruments(List<MlsoClient.Instrument> list) {
        instrumentsDownloaded = !list.isEmpty();
        updatingCombos = true;
        try {
            instrumentCombo.setModel(new DefaultComboBoxModel<>(list.toArray(MlsoClient.Instrument[]::new)));
        } finally {
            updatingCombos = false;
        }
        if (instrumentCombo.getSelectedItem() instanceof MlsoClient.Instrument inst)
            instrumentChanged(inst);
        else
            statusLabel.setText("The MLSO API listed no instruments");
    }

    @Override
    public void setMlsoResponseProducts(List<MlsoClient.Product> list) {
        updatingCombos = true;
        try {
            productCombo.setModel(new DefaultComboBoxModel<>(list.toArray(MlsoClient.Product[]::new)));
        } finally {
            updatingCombos = false;
        }
        if (productCombo.getSelectedItem() instanceof MlsoClient.Product p)
            productCombo.setToolTipText(p.description().isEmpty() ? null : p.description());
        statusLabel.setText(list.isEmpty() ? "No products for this instrument" : " ");
        pack();
    }

    @Override
    public void setMlsoResponseItems(List<MlsoClient.DataItem> list) {
        listPane.setListData(list.toArray(MlsoClient.DataItem[]::new));
        foundLabel.setText(list.isEmpty() ? "0 found: nothing in this range" : list.size() + " found");
    }

    @Override
    public void setMlsoResponseFailed(String reason) {
        statusLabel.setText(reason);
        if ("Searching...".equals(foundLabel.getText()))
            foundLabel.setText("Search failed");
        // A sign-in refusal arrives after the dialog closed itself; bring it back with the reason.
        if (signingIn && !isVisible()) {
            signingIn = false;
            pack();
            setLocationRelativeTo(MainFrame.get());
            setVisible(true);
        }
    }

}
