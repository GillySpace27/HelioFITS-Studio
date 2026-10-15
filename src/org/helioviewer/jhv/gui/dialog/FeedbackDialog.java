package org.helioviewer.jhv.gui.dialog;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.TransferAccess;
import org.helioviewer.jhv.io.FeedbackReport;
import org.helioviewer.jhv.movie.Provenance;
import org.helioviewer.jhv.thread.Task;

import org.json.JSONObject;

/**
 * Help &gt; Send Feedback..., and what Report this... on an error or warning dialog opens.
 *
 * <p>A report reaches Gilly without the user finding a website or having an account. Each tick box
 * adds one item, and Preview shows every item in full before anything leaves the machine. Not modal,
 * so the user can go on, reproduce the problem and come back, and so the dialog can step out of its
 * own screenshot.
 *
 * <p>Two roads. With an endpoint (compiled in, set, or published on gilly.space and found at this
 * launch), Send POSTs the report and Email Instead... is the second button. Without one, the button is
 * Send by Email...: it saves the report to the outbox, opens a mail draft to Gilly with the key facts,
 * and shows the saved file in the file manager for the user to attach.
 */
@SuppressWarnings("serial")
public final class FeedbackDialog extends JDialog {

    /** The button an error or warning dialog offers, here so its label is written once. */
    public static final String REPORT_THIS = "Report this...";

    private final JComboBox<FeedbackReport.Category> category = new JComboBox<>(FeedbackReport.Category.values());
    private final JTextArea message = new JTextArea(8, 56);
    private final JTextField replyTo = new JTextField();
    private final JCheckBox errorBox = new JCheckBox("The error shown and its stack trace", true);
    private final JCheckBox systemBox = new JCheckBox("System info: version, revision and commit, operating system, Java, graphics", true);
    private final JCheckBox logBox = new JCheckBox("The last " + FeedbackReport.LOG_LINES + " lines of this run's log", true);
    private final JCheckBox sessionBox = new JCheckBox("The current session: layers, times and view, as the autosave writes it", false);
    private final JCheckBox screenshotBox = new JCheckBox("A screenshot of the main window", false);
    private final JButton send = new JButton("Send");
    private final JButton emailInstead = new JButton("Email Instead...");
    private final JLabel destination = new JLabel();
    private boolean sending;
    @Nullable
    private final FeedbackReport.ErrorContext error;
    @Nullable
    private BufferedImage screenshot;

    /** The Help menu's command. */
    public static final class Open extends AbstractAction {
        public Open() {
            super("Send Feedback...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            new FeedbackDialog(null, "").setVisible(true);
        }
    }

    /** About an error the user has just been shown: its text in the message, its stack as an item. */
    public static void report(String title, String text, @Nullable Throwable cause) {
        new FeedbackDialog(FeedbackReport.ErrorContext.of(title, text, cause), title + ": " + text + "\n\n").setVisible(true);
    }

    private FeedbackDialog(@Nullable FeedbackReport.ErrorContext _error, String prefill) {
        super(MainFrame.get(), "Send Feedback", false);
        error = _error;
        if (error != null)
            category.setSelectedItem(FeedbackReport.Category.BUG);
        message.setText(prefill);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = GridBagConstraints.RELATIVE;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);

        JPanel kind = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        kind.add(new JLabel("Kind: "));
        kind.add(category);
        form.add(kind, c);
        form.add(new JLabel("What happened, or what would you like? (required)"), c);
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        form.add(new JScrollPane(message), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        form.add(new JLabel("Your email, if you would like a reply (optional)"), c);
        form.add(replyTo, c);
        c.insets = new Insets(8, 0, 0, 0);
        form.add(new JLabel("Also send:"), c);
        c.insets = new Insets(0, 0, 0, 0);
        if (error != null)
            form.add(errorBox, c);
        form.add(systemBox, c);
        form.add(logBox, c);
        form.add(sessionBox, c);
        form.add(screenshotBox, c);
        c.insets = new Insets(8, 0, 0, 0);
        form.add(destination, c);

        JButton preview = new JButton("Preview");
        preview.setToolTipText("Show everything this report will contain");
        preview.addActionListener(e -> preview());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        send.addActionListener(e -> send());
        emailInstead.setToolTipText("Write to " + AppInfo.emailAddress + " from your mail program, and attach the saved report");
        emailInstead.addActionListener(e -> {
            JSONObject p = payloadOrNull();
            if (p != null)
                sendByEmail(p);
        });
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(preview, BorderLayout.LINE_START);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        right.add(cancel);
        right.add(emailInstead);
        right.add(send);
        buttons.add(right, BorderLayout.LINE_END);
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);
        setContentPane(content);

        screenshotBox.addActionListener(e -> {
            if (screenshotBox.isSelected())
                capture();
            else
                screenshot = null;
        });
        DocumentListener validate = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSend();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSend();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSend();
            }
        };
        message.getDocument().addDocumentListener(validate);
        replyTo.getDocument().addDocumentListener(validate);
        updateSend();
        updateRoad();
        // The endpoint may be published on gilly.space; this launch's lookup runs off the EDT and, when it
        // ends, the buttons and the words follow what it found.
        FeedbackReport.lookUpEndpoint().thenRun(() -> EventQueue.invokeLater(() -> {
            if (isDisplayable() && !sending)
                updateRoad();
        }));
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        pack();
        setLocationRelativeTo(MainFrame.get());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) { // not before: a window not yet shown cannot take focus
                message.requestFocusInWindow();
                message.setCaretPosition(message.getDocument().getLength());
            }
        });
    }

    /** The road a report takes now: the button's name, the second button, and the words saying where it goes. */
    private void updateRoad() {
        boolean email = FeedbackReport.endpoint().isBlank();
        send.setText(email ? "Send by Email..." : "Send");
        emailInstead.setVisible(!email);
        String where = email
                ? "Send by Email opens an email to the developer (" + AppInfo.emailAddress + ") in your mail program, with your note and the main details,"
                  + " and shows the full report file so you can attach it. The report is also kept in ~/HFStudio/Outbox."
                : "Send delivers the report to the developer of " + AppInfo.programName + ".";
        destination.setText("<html><div style='width:420px'>" + where + " Your home folder is written as ~ in everything sent. Preview shows all of it.</div>");
        revalidate();
    }

    private void updateSend() {
        String email = replyTo.getText().strip();
        boolean emailOk = email.isEmpty() || email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
        boolean described = !message.getText().isBlank();
        send.setEnabled(described && emailOk);
        send.setToolTipText(!described ? "Describe the problem or the request first" : emailOk ? null : "That email address looks incomplete");
    }

    /**
     * The main window as it is on screen. This dialog steps aside first, so it is not in the picture,
     * and comes back when the capture is taken.
     */
    private void capture() {
        Window main = MainFrame.get();
        if (main == null || !main.isShowing()) {
            screenshotBox.setSelected(false);
            return;
        }
        setVisible(false);
        Timer later = new Timer(400, e -> {
            try {
                screenshot = new Robot().createScreenCapture(main.getBounds());
                screenshotBox.setText("A screenshot of the main window (" + screenshot.getWidth() + " x " + screenshot.getHeight() + ", see Preview)");
            } catch (AWTException | SecurityException ex) {
                Log.warn("No screenshot for feedback: " + ex);
                screenshot = null;
                screenshotBox.setSelected(false);
                screenshotBox.setText("A screenshot of the main window (not available here)");
            }
            setVisible(true);
        });
        later.setRepeats(false);
        later.start();
    }

    /** Exactly what is ticked, built now. On the EDT, which the session snapshot needs. */
    private JSONObject payload() throws IOException {
        JSONObject session = null;
        if (sessionBox.isSelected()) {
            try {
                session = State.snapshot();
            } catch (RuntimeException e) { // a report about a broken session must still go out
                session = new JSONObject().put("unavailable", String.valueOf(e));
            }
        }
        JSONObject p = FeedbackReport.build((FeedbackReport.Category) category.getSelectedItem(), message.getText(), replyTo.getText(),
                errorBox.isSelected() ? error : null,
                systemBox.isSelected() ? FeedbackReport.systemInfo() : null,
                logBox.isSelected() ? FeedbackReport.logTail(FeedbackReport.LOG_LINES) : null,
                session);
        if (screenshotBox.isSelected() && screenshot != null)
            FeedbackReport.attachScreenshot(p, screenshot);
        return p;
    }

    private void preview() {
        JSONObject p;
        try {
            p = payload();
        } catch (IOException e) {
            Log.warn("Feedback preview failed: " + e);
            return;
        }
        JTextArea text = new JTextArea(FeedbackReport.asText(p));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(640, 420));
        List<Object> parts = new ArrayList<>();
        parts.add(new JLabel("This is the whole report, sent as JSON. Untick an item to leave it out."));
        parts.add(scroll);
        if (p.has("screenshot") && screenshot != null)
            parts.add(new JLabel(new ImageIcon(screenshot.getScaledInstance(320, -1, Image.SCALE_SMOOTH))));
        JOptionPane pane = new JOptionPane(parts.toArray(), JOptionPane.PLAIN_MESSAGE);
        String[] close = {"Close"};
        pane.setOptions(close);
        pane.setInitialValue(close[0]);
        pane.createDialog(this, "What will be sent").setVisible(true);
    }

    @Nullable
    private JSONObject payloadOrNull() {
        try {
            return payload();
        } catch (IOException e) {
            Log.warn("Feedback not built: " + e);
            return null;
        }
    }

    private void send() {
        JSONObject p = payloadOrNull();
        if (p == null)
            return;
        String endpoint = FeedbackReport.endpoint();
        if (endpoint.isBlank()) {
            sendByEmail(p);
            return;
        }
        sending = true;
        send.setEnabled(false);
        emailInstead.setEnabled(false);
        send.setText("Sending...");
        Task.submitBackground(() -> FeedbackReport.submit(p, endpoint), d -> {
            dispose();
            if (d.sent())
                JOptionPane.showMessageDialog(MainFrame.get(), "Thank you. Your report was sent.", "Send Feedback", JOptionPane.INFORMATION_MESSAGE);
            else
                afterward(p, d.file(), "Report saved", "The report could not be sent just now. It is saved as\n" + shown(d.file())
                        + "\nand will be sent automatically the next time " + AppInfo.programName + " starts. You can also email it now.", false);
        }, t -> {
            dispose();
            Log.warn("Feedback not saved: " + t);
            afterward(p, null, "Report not saved", "The report could not be saved: " + t.getMessage() + "\nCopy it to the clipboard to keep it, or email it.", false);
        });
    }

    /**
     * The email road: save the report (and its screenshot beside it) off the EDT, then open a mail draft to
     * Gilly and show the saved file, so the user only has to attach it and press Send in the mail program.
     */
    private void sendByEmail(JSONObject p) {
        sending = true;
        send.setEnabled(false);
        emailInstead.setEnabled(false);
        send.setText("Saving...");
        Task.submitBackground(() -> FeedbackReport.saveForEmail(p), file -> {
            dispose();
            DesktopIntegration.openURL(FeedbackReport.mailto(p, file));
            DesktopIntegration.reveal(file.toFile());
            Path png = FeedbackReport.screenshotFile(file);
            String manager = Platform.isMacOS() ? "Finder" : "your file manager";
            afterward(p, file, "Attach the report to your email", "An email to " + AppInfo.emailAddress
                    + " with your note is open in your mail program. Please attach the report file, now shown in " + manager
                    + ", before you send it:\n" + shown(file)
                    + (Files.isRegularFile(png) ? "\nand the screenshot beside it:\n" + shown(png) : "")
                    + "\n\nIf no email opened, press Copy to Clipboard and paste the report into an email to " + AppInfo.emailAddress + '.', true);
        }, t -> {
            dispose();
            Log.warn("Feedback not saved: " + t);
            DesktopIntegration.openURL(FeedbackReport.mailto(p, null));
            afterward(p, null, "Report not saved", "The report could not be saved: " + t.getMessage()
                    + "\nAn email to " + AppInfo.emailAddress + " is open in your mail program: press Copy to Clipboard and paste the report into it.", true);
        });
    }

    /** A path as the user's dialogs show it, with the home folder as ~. */
    private static String shown(Path file) {
        return Provenance.stripHome(file.toString(), System.getProperty("user.home", ""));
    }

    /**
     * What the user can still do with a report: copy it, write the email (again, when it was already
     * opened), and show the saved file to attach.
     */
    private static void afterward(JSONObject p, @Nullable Path file, String title, String text, boolean emailed) {
        JTextArea words = new JTextArea(text);
        words.setEditable(false);
        words.setOpaque(false);
        words.setFocusable(false);
        JButton copy = new JButton("Copy to Clipboard");
        copy.addActionListener(e -> {
            TransferAccess.writeClipboard(FeedbackReport.asText(p));
            copy.setText("Copied");
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        actions.add(copy);
        JButton email = new JButton(emailed ? "Open the Email Again" : "Email Instead");
        email.setToolTipText("Write to " + AppInfo.emailAddress + (file == null ? "" : "; attach the saved report to the email"));
        email.addActionListener(e -> {
            DesktopIntegration.openURL(FeedbackReport.mailto(p, file));
            if (file != null && !emailed)
                DesktopIntegration.reveal(file.toFile());
        });
        actions.add(email);
        if (file != null) {
            JButton show = new JButton("Show the Report File");
            show.setToolTipText("Show " + shown(file) + " in " + (Platform.isMacOS() ? "Finder" : "the file manager"));
            show.addActionListener(e -> DesktopIntegration.reveal(file.toFile()));
            actions.add(show);
        }
        JOptionPane pane = new JOptionPane(new Object[]{words, actions}, JOptionPane.INFORMATION_MESSAGE);
        String[] ok = {"OK"};
        pane.setOptions(ok);
        pane.setInitialValue(ok[0]); // Return dismisses; it never opens the mail client
        pane.createDialog(MainFrame.get(), title).setVisible(true);
    }

}
