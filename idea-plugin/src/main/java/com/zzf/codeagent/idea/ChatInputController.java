package com.zzf.codeagent.idea;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import javax.swing.UIManager;
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
        JButton button = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight()) - 1;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                boolean stopMode = Boolean.TRUE.equals(getClientProperty("mode.stop"));
                Color baseBackground = UIUtil.getTextFieldBackground();
                Color errorColor = resolveErrorColor();
                Color fill = stopMode
                        ? ColorUtil.mix(baseBackground, errorColor, UIUtil.isUnderDarcula() ? 0.35 : 0.20)
                        : baseBackground;
                Color border = stopMode
                        ? ColorUtil.mix(baseBackground, errorColor, UIUtil.isUnderDarcula() ? 0.55 : 0.35)
                        : UIUtil.getBoundsColor();
                g2.setColor(fill);
                g2.fillOval(x, y, size, size);
                g2.setColor(border);
                g2.drawOval(x, y, size, size);
                g2.setColor(getForeground());
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font iconFont = getFont().deriveFont(Font.PLAIN, 15f);
                String glyph = stopMode ? "\u25A0" : "\u2191";
                GlyphVector gv = iconFont.createGlyphVector(g2.getFontRenderContext(), glyph);
                Rectangle2D vb = gv.getVisualBounds();
                float px = (float) ((getWidth() - vb.getWidth()) / 2.0 - vb.getX());
                float py = (float) ((getHeight() - vb.getHeight()) / 2.0 - vb.getY());
                g2.drawGlyphVector(gv, px, py);
                g2.dispose();
            }

            @Override
            public void paintBorder(Graphics g) {
                // All border rendering is handled in paintComponent
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
        button.setForeground(UIUtil.getLabelForeground());
        button.setFont(button.getFont().deriveFont(16f));
        button.setToolTipText("Send");
        // Suppress IntelliJ L&F extra decorations (focus ring, gradient highlight)
        button.putClientProperty("JButton.buttonType", "text");
        button.putClientProperty("paintFocus", Boolean.FALSE);
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
        sendButton.setToolTipText(stopMode ? "Stop generation" : "Send");
        sendButton.setForeground(stopMode
                ? resolveErrorColor()
                : UIUtil.getLabelForeground());
        sendButton.repaint();
    }

    private static Color resolveErrorColor() {
        Color color = resolveUiColor("Label.errorForeground", null);
        if (color != null) {
            return color;
        }
        color = resolveUiColor("ValidationTooltip.errorBorderColor", null);
        if (color != null) {
            return color;
        }
        return JBColor.RED;
    }

    private static Color resolveUiColor(String key, Color fallback) {
        Color color = key == null ? null : UIManager.getColor(key);
        return color == null ? fallback : color;
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
