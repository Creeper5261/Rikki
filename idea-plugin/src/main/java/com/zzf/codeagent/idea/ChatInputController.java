package com.zzf.codeagent.idea;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ChatInputController {
    private final JBTextArea input;
    private final JButton sendButton;
    private final BooleanSupplier runtimeBusySupplier;
    private final BooleanSupplier awaitingApprovalSupplier;
    private final Runnable stopAction;
    private final Consumer<String> sendAction;

    ChatInputController(
            JBTextArea input,
            JButton sendButton,
            BooleanSupplier runtimeBusySupplier,
            BooleanSupplier awaitingApprovalSupplier,
            Runnable stopAction,
            Consumer<String> sendAction
    ) {
        this.input = input;
        this.sendButton = sendButton;
        this.runtimeBusySupplier = runtimeBusySupplier;
        this.awaitingApprovalSupplier = awaitingApprovalSupplier;
        this.stopAction = stopAction;
        this.sendAction = sendAction;
    }

    static JButton createRoundSendButton() {
        JButton button = new JButton("\u2191") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight()) - 1;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                boolean stopMode = Boolean.TRUE.equals(getClientProperty("mode.stop"));
                Color fill = stopMode
                        ? new JBColor(new Color(0xF0D4D4), new Color(0x6A3434))
                        : new JBColor(new Color(0xD9DEE6), new Color(0xD7DCE4));
                Color border = stopMode
                        ? new JBColor(new Color(0xD6A3A3), new Color(0x9A4A4A))
                        : new JBColor(new Color(0xC7D0DD), new Color(0xAAB4C1));
                g2.setColor(fill);
                g2.fillOval(x, y, size, size);
                g2.setColor(border);
                g2.drawOval(x, y, size, size);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setPreferredSize(new Dimension(38, 38));
        button.setMinimumSize(new Dimension(38, 38));
        button.setMaximumSize(new Dimension(38, 38));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusable(false);
        button.setMargin(JBUI.emptyInsets());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(new JBColor(new Color(0x20252E), new Color(0x20252E)));
        button.setFont(button.getFont().deriveFont(16f));
        button.setToolTipText("Send");
        return button;
    }

    void bindActions() {
        sendButton.addActionListener(e -> {
            if (isStopMode()) {
                stopAction.run();
                return;
            }
            String text = consumeInput();
            if (text == null || text.isBlank()) {
                return;
            }
            sendAction.accept(text);
        });
    }

    void updateSendButtonMode(boolean stopMode) {
        sendButton.putClientProperty("mode.stop", stopMode);
        sendButton.setText(stopMode ? "\u25A0" : "\u2191");
        sendButton.setToolTipText(stopMode ? "Stop generation" : "Send");
        sendButton.setForeground(stopMode
                ? new JBColor(new Color(0x7A1E1E), new Color(0xF5DCDC))
                : new JBColor(new Color(0x20252E), new Color(0x20252E)));
        sendButton.repaint();
    }

    private boolean isStopMode() {
        return runtimeBusySupplier.getAsBoolean() || awaitingApprovalSupplier.getAsBoolean();
    }

    private String consumeInput() {
        String raw = input.getText();
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.isBlank()) {
            input.setText("");
        }
        return text;
    }
}
