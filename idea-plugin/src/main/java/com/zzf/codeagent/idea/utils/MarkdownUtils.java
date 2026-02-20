package com.zzf.codeagent.idea.utils;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import com.intellij.util.ui.UIUtil;
import java.awt.Color;
import javax.swing.UIManager;
import java.util.Arrays;

public class MarkdownUtils {
    private static final Parser parser = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create()))
            .build();
    private static final HtmlRenderer renderer = HtmlRenderer.builder()
            .extensions(Arrays.asList(TablesExtension.create()))
            .escapeHtml(false) 
            .build();

    public static String renderToHtml(String markdown) {
        if (markdown == null) return "";

        String processed = markdown.replaceAll("(?<!!)\\[([a-zA-Z0-9_./-]+\\.(java|xml|yml|yaml|json|md|txt|properties|kt|gradle|sh|bat|cmd|sql|ts|js|css|html))\\](?!\\()", "<a href=\"$1\">$0</a>");

        org.commonmark.node.Node document = parser.parse(processed);
        String html = renderer.render(document);
        html = colorDiffCodeBlocks(html);

        String css = getCss();
        return "<html><head><style>" + css + "</style></head><body>" + html + "</body></html>";
    }

    /**
     * Post-processes rendered HTML to color +/- lines in diff code blocks.
     * Lines starting with '+' (but not '+++') get green, '-' (but not '---') get red.
     */
    private static String colorDiffCodeBlocks(String html) {
        if (html == null) return html;
        String openMarker = "language-diff\">";
        int start = 0;
        StringBuilder result = new StringBuilder();
        while (true) {
            int idx = html.indexOf(openMarker, start);
            if (idx < 0) {
                result.append(html, start, html.length());
                break;
            }
            result.append(html, start, idx + openMarker.length());
            int contentStart = idx + openMarker.length();
            int contentEnd = html.indexOf("</code>", contentStart);
            if (contentEnd < 0) {
                result.append(html, contentStart, html.length());
                break;
            }
            result.append(colorDiffLines(html.substring(contentStart, contentEnd)));
            start = contentEnd;
        }
        return result.toString();
    }

    private static String colorDiffLines(String code) {
        String[] lines = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            String line = lines[i];
            if (line.startsWith("+") && !line.startsWith("+++")) {
                sb.append("<span class=\"diff-add\">").append(line).append("</span>");
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                sb.append("<span class=\"diff-remove\">").append(line).append("</span>");
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String getCss() {
        Color panelBg = UIUtil.getPanelBackground();
        Color labelFg = UIUtil.getLabelForeground();
        Color codeBackground = resolveCodeBackground(panelBg);
        Color border = resolveColor("Component.borderColor", UIUtil.getBoundsColor());
        Color link = resolveLinkColor(labelFg);
        Color text = labelFg;

        String textColor = colorToHex(text);
        String linkColor = colorToHex(link);
        String codeBg = colorToHex(codeBackground);
        String borderColor = colorToHex(border);

        Color diffAdd = resolveColor("Label.successForeground", null);
        if (diffAdd == null) diffAdd = resolveColor("Actions.Green", null);
        if (diffAdd == null) diffAdd = new Color(0x1a7f37);
        Color diffRemove = resolveColor("Label.errorForeground", null);
        if (diffRemove == null) diffRemove = resolveColor("Actions.Red", null);
        if (diffRemove == null) diffRemove = new Color(0xcf222e);
        String diffAddColor = colorToHex(diffAdd);
        String diffRemoveColor = colorToHex(diffRemove);

        return "body { font-family: sans-serif; font-size: 13px; color: " + textColor + "; background: transparent; margin: 0; padding: 0; }" +
               "body, p, li, span, div, h1, h2, h3, h4, h5, h6, td, th { overflow-wrap: anywhere; word-break: break-word; }" +
               "p, li, span, div, h1, h2, h3, h4, h5, h6 { color: " + textColor + "; }" +
               "h1 { font-size: 1.7em; font-weight: bold; margin-top: 12px; margin-bottom: 4px; }" +
               "h2 { font-size: 1.4em; font-weight: bold; margin-top: 10px; margin-bottom: 4px; }" +
               "h3 { font-size: 1.2em; font-weight: bold; margin-top: 8px;  margin-bottom: 3px; }" +
               "h4 { font-size: 1.05em; font-weight: bold; margin-top: 6px; margin-bottom: 2px; }" +
               "h5, h6 { font-size: 1em; font-weight: bold; margin-top: 4px; margin-bottom: 2px; }" +
               "a { color: " + linkColor + "; text-decoration: none; }" +
               "code { background-color: " + codeBg + "; color: " + textColor + "; font-family: monospace; padding: 2px 4px; }" +
               "pre { background-color: " + codeBg + "; color: " + textColor + "; padding: 8px; border: 1px solid " + borderColor + "; border-radius: 6px; white-space: pre-wrap; overflow-x: hidden; max-width: 100%; }" +
               "pre code { background-color: transparent; padding: 0; }" +
               "table { border-collapse: collapse; width: 100%; table-layout: fixed; max-width: 100%; }" +
               "th, td { border: 1px solid " + borderColor + "; padding: 6px; text-align: left; overflow-wrap: anywhere; word-break: break-word; }" +
               "th { background-color: " + codeBg + "; }" +
               "span.diff-add { color: " + diffAddColor + "; }" +
               "span.diff-remove { color: " + diffRemoveColor + "; }";
    }

    private static Color resolveCodeBackground(Color fallback) {
        Color textAreaBg = resolveColor("TextArea.background", null);
        if (textAreaBg == null) {
            textAreaBg = resolveColor("EditorPane.background", null);
        }
        if (textAreaBg == null) {
            textAreaBg = resolveColor("TextField.background", fallback);
        }
        if (textAreaBg == null) {
            textAreaBg = fallback;
        }
        return ColorUtil.mix(textAreaBg, fallback == null ? textAreaBg : fallback, 0.88);
    }

    private static Color resolveLinkColor(Color fallback) {
        Color link = resolveColor("Link.activeForeground", null);
        if (link != null) {
            return link;
        }
        link = resolveColor("link.foreground", null);
        if (link != null) {
            return link;
        }
        return fallback == null ? JBColor.BLUE : fallback;
    }

    private static Color resolveColor(String key, Color fallback) {
        Color color = key == null ? null : UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private static String colorToHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
