package org.helioviewer.jhv.app;

import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;

import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.app.state.UndoStack;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

import org.json.JSONObject;

/**
 * Scene undo and redo: Edit &gt; Undo and Redo (Cmd-Z, Shift-Cmd-Z) and the toolbar's Undo and Redo.
 *
 * <p>Polled, not instrumented. Every setting a user can change is already written by
 * State.snapshot, so a timer hands the live scene to an {@link UndoStack}, which keeps the changes
 * that settled. That reaches every control without touching any of them. The poll pauses while a
 * mouse button is held, so a slider drag is one step, taken on release, and while an image layer is
 * still loading, so a movie arriving frame by frame is not a run of steps. Undo hands a snapshot to
 * State.apply, which keeps every layer whose data did not change.
 *
 * <p>Session load, New Session and Revert to Saved start a new history: Session calls {@link #reset}.
 * Autosave is untouched; an undo is a change like any other and is saved like one.
 */
public final class SceneUndo {

    private static final int POLL_MS = 250;
    private static final int BUTTONS = InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON2_DOWN_MASK | InputEvent.BUTTON3_DOWN_MASK;
    // Every field below is this one object's, so nothing here is a mutable static.
    private static final SceneUndo scene = new SceneUndo();

    private final UndoStack stack = new UndoStack(UndoStack.DEPTH);
    private final Map<AbstractButton, Boolean> buttons = new WeakHashMap<>(); // toolbar Undo/Redo; the bar is rebuilt on a layout change
    @Nullable
    private StepAction undoAction;
    @Nullable
    private StepAction redoAction;
    private boolean pointerDown;
    private boolean installed;
    private boolean warned;

    /** Start watching the scene. On the EDT, once the window exists; never headless. */
    public static void install() {
        if (scene.installed)
            return;
        scene.installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e instanceof MouseEvent me && (me.getID() == MouseEvent.MOUSE_PRESSED || me.getID() == MouseEvent.MOUSE_RELEASED))
                scene.pointerDown = (me.getModifiersEx() & BUTTONS) != 0;
        }, AWTEvent.MOUSE_EVENT_MASK);
        new Timer(POLL_MS, e -> scene.poll()).start();
    }

    /** A session was opened, started afresh or reverted: history starts again from it. */
    public static void reset() {
        scene.stack.clear();
        scene.refresh();
    }

    public static void undo() {
        scene.step(false, 1);
    }

    public static void redo() {
        scene.step(true, 1);
    }

    /** Take {@code count} steps at once, as the toolbar's held Undo or Redo list does: one apply. */
    public static void step(boolean forward, int count) {
        scene.stepBy(forward, count);
    }

    /** The steps Undo (or, forward, Redo) could take, the next one first. */
    public static java.util.List<String> labels(boolean forward) {
        return scene.stack.labels(forward);
    }

    /** Edit &gt; Undo, catalogued as "undo". */
    public static Action undoAction() {
        if (scene.undoAction == null)
            scene.undoAction = new StepAction(false);
        return scene.undoAction;
    }

    /** Edit &gt; Redo, catalogued as "redo". */
    public static Action redoAction() {
        if (scene.redoAction == null)
            scene.redoAction = new StepAction(true);
        return scene.redoAction;
    }

    /** A toolbar Undo or Redo button: enabled, and its tip naming the step, as the stack changes. */
    public static void bind(AbstractButton button, boolean forward) {
        scene.buttons.put(button, forward);
        scene.refresh();
    }

    private void poll() {
        if (pointerDown || loading())
            return;
        JSONObject snapshot = snapshot();
        if (snapshot != null && stack.observe(snapshot, System.currentTimeMillis()))
            refresh();
    }

    private void stepBy(boolean forward, int count) {
        // An edit made a moment ago has not settled yet, and it is the one Undo is expected to take.
        if (!pointerDown && !loading()) {
            JSONObject now = snapshot();
            if (now != null)
                stack.flush(now);
        }
        JSONObject target = null;
        for (int i = 0; i < count; i++) {
            JSONObject next = forward ? stack.redo() : stack.undo();
            if (next == null)
                break;
            target = next;
        }
        if (target != null)
            State.apply(target);
        refresh();
    }

    @Nullable
    private JSONObject snapshot() {
        try {
            return State.snapshot();
        } catch (RuntimeException e) { // a scene caught half-built; the next poll sees it whole
            if (!warned) {
                warned = true;
                Log.warn("Scene undo could not read the scene", e);
            }
            return null;
        }
    }

    // Still arriving, or with nothing a snapshot could restore it from yet (a layer stuck at
    // "Loading...", which State.snapshot would leave out and log about on every poll).
    private static boolean loading() {
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isViewLoadFinished())
                return true;
            JSONObject data = new JSONObject();
            layer.serialize(data);
            if (!State.hasRestorableData(data))
                return true;
        }
        return false;
    }

    private void refresh() {
        String undoLabel = stack.undoLabel();
        String redoLabel = stack.redoLabel();
        if (undoAction != null)
            undoAction.show(undoLabel);
        if (redoAction != null)
            redoAction.show(redoLabel);
        buttons.forEach((button, forward) -> {
            String label = forward ? redoLabel : undoLabel;
            button.setEnabled(label != null);
            button.setToolTipText(label == null ? (forward ? "Nothing to redo" : "Nothing to undo") : (forward ? "Redo " : "Undo ") + label);
        });
    }

    // The menu's Undo and Redo. A text field holding the keyboard keeps Cmd-Z: its own undo runs if it
    // has one, and otherwise nothing does, rather than the scene changing under someone typing.
    @SuppressWarnings("serial")
    private static final class StepAction extends AbstractAction {

        private final boolean forward;

        StepAction(boolean _forward) {
            super(_forward ? "Redo" : "Undo");
            forward = _forward;
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    DesktopIntegration.menuShortcutMask | (forward ? InputEvent.SHIFT_DOWN_MASK : 0)));
            setEnabled(false);
        }

        // "Undo Levels change" when the stack can say what; plain "Undo", greyed, when there is nothing.
        void show(@Nullable String label) {
            String verb = forward ? "Redo" : "Undo";
            putValue(NAME, label == null ? verb : verb + " " + label);
            setEnabled(label != null);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner() instanceof JTextComponent text
                    && text.isEditable()) {
                Action own = text.getActionMap().get(forward ? "redo" : "undo");
                if (own != null)
                    own.actionPerformed(new ActionEvent(text, ActionEvent.ACTION_PERFORMED, null));
                return;
            }
            scene.stepBy(forward, 1);
        }
    }

    private SceneUndo() {}
}
