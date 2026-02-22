package com.zzf.rikki.idea;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collapsible todo-list panel shown above the chat input box.
 * Updates in real-time when the agent calls todo_write.
 * Hidden automatically when the todo list is empty.
 */
final class TodoPanel extends JPanel {

    // ── Status appearance ────────────────────────────────────────────────
    private static final String ICON_PENDING     = "○";
    private static final String ICON_IN_PROGRESS = "◐";
    private static final String ICON_COMPLETED   = "✓";
    private static final String ICON_CANCELLED   = "✗";

    private static Color colorPending()    { return UIUtil.getLabelForeground(); }
    private static Color colorInProgress() { return new JBColor(new Color(0xD9812A), new Color(0xE09040)); }
    private static Color colorCompleted()  { return new JBColor(new Color(0x36B336), new Color(0x59B85C)); }
    private static Color colorCancelled()  { return JBColor.GRAY; }

    // ── State ────────────────────────────────────────────────────────────
    private final ObjectMapper mapper = new ObjectMapper();
    private List<TodoItem> todos = new ArrayList<>();
    private boolean expanded = true;

    // ── UI components ────────────────────────────────────────────────────
    private final JPanel headerPanel;
    private final JLabel headerLabel;
    private final JLabel toggleLabel;
    private final JPanel listPanel;

    TodoPanel() {
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        // ── Header ──────────────────────────────────────────────────────
        headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(3, 6, 3, 6)
        ));
        headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        headerLabel = new JLabel("Todo");
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(11f)));
        headerLabel.setForeground(UIUtil.getLabelForeground());

        toggleLabel = new JLabel("▲");
        toggleLabel.setFont(toggleLabel.getFont().deriveFont(JBUI.scaleFontSize(9f)));
        toggleLabel.setForeground(JBColor.GRAY);

        headerPanel.add(headerLabel, BorderLayout.CENTER);
        headerPanel.add(toggleLabel, BorderLayout.EAST);

        headerPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });

        // ── List area ────────────────────────────────────────────────────
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        listPanel.setBorder(JBUI.Borders.empty(4, 6, 4, 6));

        add(headerPanel, BorderLayout.NORTH);
        add(listPanel, BorderLayout.CENTER);

        setVisible(false); // hidden until todos arrive
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Parse JSON string from SSE `todo_updated` event and refresh. */
    void updateFromJson(String json) {
        SwingUtilities.invokeLater(() -> {
            try {
                List<Map<String, Object>> raw =
                        mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                List<TodoItem> parsed = new ArrayList<>();
                for (Map<String, Object> item : raw) {
                    String id       = str(item.get("id"), "");
                    String content  = str(item.get("content"), "");
                    String status   = str(item.get("status"), "pending");
                    String priority = str(item.get("priority"), "medium");
                    if (!content.isBlank()) {
                        parsed.add(new TodoItem(id, content, status, priority));
                    }
                }
                setTodos(parsed);
            } catch (Exception ignored) {
                // malformed json — keep current state
            }
        });
    }

    /** Replace todo list and rebuild the panel. */
    void setTodos(List<TodoItem> newTodos) {
        this.todos = newTodos != null ? new ArrayList<>(newTodos) : new ArrayList<>();
        rebuild();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private void toggle() {
        expanded = !expanded;
        listPanel.setVisible(expanded);
        toggleLabel.setText(expanded ? "▲" : "▼");
        revalidate();
        repaint();
    }

    private void rebuild() {
        listPanel.removeAll();

        long total     = todos.size();
        long pending   = todos.stream().filter(t -> "pending".equals(t.status)).count();
        long inProg    = todos.stream().filter(t -> "in_progress".equals(t.status)).count();
        long completed = todos.stream().filter(t -> "completed".equals(t.status)).count();

        // Update header label with summary
        headerLabel.setText(String.format("Todo  (%d total: %d pending, %d in-progress, %d done)",
                total, pending, inProg, completed));

        for (TodoItem todo : todos) {
            listPanel.add(createRow(todo));
            listPanel.add(Box.createVerticalStrut(JBUI.scale(2)));
        }

        setVisible(total > 0);
        listPanel.setVisible(expanded && total > 0);
        revalidate();
        repaint();
    }

    private JPanel createRow(TodoItem todo) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(22)));

        // Status icon
        Color color = colorFor(todo.status);
        JLabel iconLabel;
        if ("in_progress".equals(todo.status)) {
            iconLabel = new JLabel(new InProgressIcon(JBUI.scale(12), color));
        } else {
            iconLabel = new JLabel(iconFor(todo.status));
            iconLabel.setForeground(color);
            iconLabel.setFont(iconLabel.getFont().deriveFont(JBUI.scaleFontSize(12f)));
        }
        iconLabel.setPreferredSize(new Dimension(JBUI.scale(16), JBUI.scale(18)));

        // Content text
        JLabel contentLabel = new JLabel();
        boolean done = "completed".equals(todo.status) || "cancelled".equals(todo.status);
        contentLabel.setForeground(done ? JBColor.GRAY : UIUtil.getLabelForeground());
        if ("completed".equals(todo.status)) {
            contentLabel.setText("<html><strike>" + escapeHtml(todo.content) + "</strike></html>");
        } else {
            contentLabel.setText(todo.content);
        }
        contentLabel.setFont(contentLabel.getFont().deriveFont(JBUI.scaleFontSize(11f)));

        row.add(iconLabel, BorderLayout.WEST);
        row.add(contentLabel, BorderLayout.CENTER);
        return row;
    }

    private static String iconFor(String status) {
        return switch (status) {
            case "in_progress" -> ICON_IN_PROGRESS;
            case "completed"   -> ICON_COMPLETED;
            case "cancelled"   -> ICON_CANCELLED;
            default            -> ICON_PENDING;
        };
    }

    private static Color colorFor(String status) {
        return switch (status) {
            case "in_progress" -> colorInProgress();
            case "completed"   -> colorCompleted();
            case "cancelled"   -> colorCancelled();
            default            -> colorPending();
        };
    }

    private static String str(Object o, String def) {
        return o instanceof String s ? s : def;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Data model ───────────────────────────────────────────────────────

    static final class TodoItem {
        final String id;
        final String content;
        final String status;
        final String priority;

        TodoItem(String id, String content, String status, String priority) {
            this.id       = id;
            this.content  = content;
            this.status   = status != null ? status : "pending";
            this.priority = priority != null ? priority : "medium";
        }
    }

    // ── In-progress spinner icon ──────────────────────────────────────────

    /**
     * Paints a 3/4-circle open arc to represent an item that is currently
     * being worked on.  Uses Java2D so it renders crisply at any DPI.
     */
    private static final class InProgressIcon implements Icon {
        private final int size;
        private final Color color;

        InProgressIcon(int size, Color color) {
            this.size  = size;
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : (c != null ? c.getForeground() : Color.GRAY));
                float pad = 1f;
                float s   = size - 2f * pad;
                // 270° open arc (3/4 ring), gap at bottom-right; starts at top (90°)
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Arc2D.Float(x + pad, y + pad, s, s, 90, 270, Arc2D.OPEN));
            } finally {
                g2.dispose();
            }
        }

        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }
    }
}
