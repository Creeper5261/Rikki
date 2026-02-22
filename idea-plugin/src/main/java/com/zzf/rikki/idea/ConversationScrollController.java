package com.zzf.rikki.idea;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.JButton;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.AWTEventListener;

/**
 * Manages auto-scroll behaviour for the conversation panel.
 *
 * <p>Design:
 * <ul>
 *   <li>{@link #scrollToBottom()} – forced scroll; used for explicit user actions
 *       (sending a message, clicking the jump-to-bottom button, opening history).</li>
 *   <li>{@link #scrollToBottomSmart()} – proximity-based scroll; scrolls only when the
 *       view is already within {@code autoScrollBottomThresholdPx} of the bottom at the
 *       moment the method is called (i.e. before the next layout pass). Calling this
 *       method during streaming therefore auto-follows iff the user is near the bottom,
 *       and does nothing if the user has scrolled away.</li>
 * </ul>
 * There is no persistent "follow" flag that can be stale. The decision is made
 * fresh on every {@link #scrollToBottomSmart()} call.
 */
final class ConversationScrollController {

    private final Project project;
    private final JBScrollPane scrollPane;
    private final JButton jumpToBottomButton;
    private final int autoScrollBottomThresholdPx;

    /** Set to true while we are programmatically moving the scrollbar so the
     *  AdjustmentListener does not trigger side-effects. */
    private volatile boolean suppressScrollTracking;
    private volatile boolean wheelBridgeInstalled;

    ConversationScrollController(
            Project project,
            JBScrollPane scrollPane,
            JButton jumpToBottomButton,
            int autoScrollBottomThresholdPx,
            long manualScrollWindowMs   // kept for binary compatibility, unused
    ) {
        this.project = project;
        this.scrollPane = scrollPane;
        this.jumpToBottomButton = jumpToBottomButton;
        this.autoScrollBottomThresholdPx = autoScrollBottomThresholdPx;
    }

    /** No-op kept for call-site compatibility. */
    void enableFollow() {}

    /** No-op kept for call-site compatibility. */
    void disableFollow() {}

    /**
     * Forced scroll – always scrolls to the very bottom regardless of current position.
     * Use for explicit user-initiated actions (send message, jump-to-bottom button, etc.).
     */
    void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            suppressScrollTracking = true;
            try {
                vertical.setValue(vertical.getMaximum());
            } finally {
                suppressScrollTracking = false;
            }
            updateJumpToBottomVisibility(vertical);
        });
    }

    /**
     * Proximity-based scroll – scrolls to the bottom <em>only</em> when the view is
     * currently within the threshold distance from the bottom.
     *
     * <p>The proximity check is captured <em>synchronously</em> (before the next layout
     * pass) so that we know whether the user was near the bottom just before new content
     * was appended. If the user has scrolled away, this is a no-op.
     */
    void scrollToBottomSmart() {
        // Capture proximity NOW, on the calling thread (EDT), before layout changes.
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        boolean wasNearBottom = isAtBottom(vertical);
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            JScrollBar vbar = scrollPane.getVerticalScrollBar();
            if (!wasNearBottom) {
                updateJumpToBottomVisibility(vbar);
                return;
            }
            suppressScrollTracking = true;
            try {
                vbar.setValue(vbar.getMaximum());
            } finally {
                suppressScrollTracking = false;
            }
            updateJumpToBottomVisibility(vbar);
        });
    }

    void installConversationScrollBehavior() {
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        if (vertical == null) return;

        installGlobalWheelBridge();

        // Wheel on the scroll pane / viewport – just update the jump button.
        scrollPane.addMouseWheelListener(e -> updateJumpToBottomVisibility(vertical));
        scrollPane.getViewport().addMouseWheelListener(e -> updateJumpToBottomVisibility(vertical));

        // Scrollbar drag.
        vertical.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                updateJumpToBottomVisibility(vertical);
            }
        });

        // Any scrollbar position change (including programmatic) – update button.
        vertical.addAdjustmentListener(e -> {
            if (project.isDisposed() || suppressScrollTracking) return;
            updateJumpToBottomVisibility(vertical);
        });

        updateJumpToBottomVisibility(vertical);
    }

    private void installGlobalWheelBridge() {
        if (wheelBridgeInstalled) return;
        AWTEventListener listener = event -> {
            if (!(event instanceof MouseWheelEvent wheelEvent)) return;
            if (project.isDisposed()) return;
            Object src = wheelEvent.getSource();
            if (!(src instanceof Component component)) return;
            if (!SwingUtilities.isDescendingFrom(component, scrollPane)) return;
            JScrollBar target = wheelEvent.isShiftDown()
                    ? scrollPane.getHorizontalScrollBar()
                    : scrollPane.getVerticalScrollBar();
            if (target == null) return;
            int rotation = wheelEvent.getWheelRotation();
            if (rotation == 0) return;
            int direction = rotation > 0 ? 1 : -1;
            int baseIncrement = target.getUnitIncrement(direction);
            if (baseIncrement <= 0) baseIncrement = 16;
            int delta = baseIncrement * Math.max(1, Math.abs(wheelEvent.getUnitsToScroll()));
            if (direction < 0) delta = -delta;
            int next = Math.max(target.getMinimum(),
                    Math.min(target.getMaximum(), target.getValue() + delta));
            if (next != target.getValue()) {
                target.setValue(next);
                updateJumpToBottomVisibility(scrollPane.getVerticalScrollBar());
            }
            wheelEvent.consume();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        wheelBridgeInstalled = true;
    }

    private int distanceToBottom(JScrollBar vertical) {
        if (vertical == null) return 0;
        int extent = vertical.getModel().getExtent();
        int max = vertical.getMaximum();
        int value = vertical.getValue();
        return max - (value + extent);
    }

    private boolean isAtBottom(JScrollBar vertical) {
        return distanceToBottom(vertical) <= autoScrollBottomThresholdPx;
    }

    private void updateJumpToBottomVisibility(JScrollBar vertical) {
        if (jumpToBottomButton == null) return;
        boolean show = vertical != null && !isAtBottom(vertical);
        jumpToBottomButton.setVisible(show);
        jumpToBottomButton.revalidate();
        jumpToBottomButton.repaint();
    }
}
