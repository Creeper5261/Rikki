package com.zzf.codeagent.idea;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.JButton;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class ConversationScrollController {

    private final Project project;
    private final JBScrollPane scrollPane;
    private final JButton jumpToBottomButton;
    private final int autoScrollBottomThresholdPx;
    private final long manualScrollWindowMs;

    private volatile boolean followStreamingOutput = true;
    private volatile boolean suppressScrollTracking;
    private volatile long lastManualScrollAtMs;

    ConversationScrollController(
            Project project,
            JBScrollPane scrollPane,
            JButton jumpToBottomButton,
            int autoScrollBottomThresholdPx,
            long manualScrollWindowMs
    ) {
        this.project = project;
        this.scrollPane = scrollPane;
        this.jumpToBottomButton = jumpToBottomButton;
        this.autoScrollBottomThresholdPx = autoScrollBottomThresholdPx;
        this.manualScrollWindowMs = manualScrollWindowMs;
    }

    void enableFollow() {
        followStreamingOutput = true;
    }

    void disableFollow() {
        followStreamingOutput = false;
    }

    void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            if (!followStreamingOutput) {
                updateJumpToBottomVisibility(vertical);
                return;
            }
            suppressScrollTracking = true;
            try {
                vertical.setValue(vertical.getMaximum());
            } finally {
                suppressScrollTracking = false;
            }
            updateJumpToBottomVisibility(vertical);
        });
    }

    void scrollToBottomSmart() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            if (!followStreamingOutput) {
                updateJumpToBottomVisibility(vertical);
                return;
            }
            suppressScrollTracking = true;
            try {
                vertical.setValue(vertical.getMaximum());
            } finally {
                suppressScrollTracking = false;
            }
            updateJumpToBottomVisibility(vertical);
        });
    }

    void installConversationScrollBehavior() {
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        if (vertical == null) {
            return;
        }
        scrollPane.addMouseWheelListener(e -> {
            markManualScroll();
            followStreamingOutput = false;
            updateJumpToBottomVisibility(vertical);
        });
        scrollPane.getViewport().addMouseWheelListener(e -> {
            markManualScroll();
            followStreamingOutput = false;
            updateJumpToBottomVisibility(vertical);
        });
        vertical.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                markManualScroll();
                if (!isAtBottom(vertical)) {
                    followStreamingOutput = false;
                }
                updateJumpToBottomVisibility(vertical);
            }
        });
        vertical.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                markManualScroll();
                followStreamingOutput = false;
                updateJumpToBottomVisibility(vertical);
            }
        });
        vertical.addAdjustmentListener(e -> {
            if (project.isDisposed() || suppressScrollTracking) {
                return;
            }
            if (isManualScrollRecent() || e.getValueIsAdjusting()) {
                followStreamingOutput = false;
            } else if (isAtBottom(vertical)) {
                followStreamingOutput = true;
            }
            updateJumpToBottomVisibility(vertical);
        });
        updateJumpToBottomVisibility(vertical);
    }

    private void markManualScroll() {
        lastManualScrollAtMs = System.currentTimeMillis();
    }

    private boolean isManualScrollRecent() {
        return (System.currentTimeMillis() - lastManualScrollAtMs) <= manualScrollWindowMs;
    }

    private int distanceToBottom(JScrollBar vertical) {
        if (vertical == null) {
            return 0;
        }
        int extent = vertical.getModel().getExtent();
        int max = vertical.getMaximum();
        int value = vertical.getValue();
        return max - (value + extent);
    }

    private boolean isAtBottom(JScrollBar vertical) {
        return distanceToBottom(vertical) <= autoScrollBottomThresholdPx;
    }

    private void updateJumpToBottomVisibility(JScrollBar vertical) {
        if (jumpToBottomButton == null) {
            return;
        }
        boolean show = vertical != null && !isAtBottom(vertical);
        jumpToBottomButton.setVisible(show);
        jumpToBottomButton.revalidate();
        jumpToBottomButton.repaint();
    }
}
