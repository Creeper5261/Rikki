package com.zzf.codeagent.idea.utils;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.JBColor;
import java.awt.Color;
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
        
        String css = getCss();
        return "<html><head><style>" + css + "</style></head><body>" + html + "</body></html>";
    }

    private static String getCss() {
        boolean dark = UIUtil.isUnderDarcula();
        String textColor = colorToHex(UIUtil.getLabelForeground());
        String linkColor = colorToHex(JBColor.BLUE); 
        String codeBg = dark ? "#2f343a" : "#f5f5f5";
        String borderColor = dark ? "#5e6060" : "#e0e0e0";
        
        return "body { font-family: sans-serif; font-size: 13px; color: " + textColor + "; background: transparent; margin: 0; padding: 0; }" +
               "a { color: " + linkColor + "; text-decoration: none; }" +
               "code { background-color: " + codeBg + "; font-family: monospace; padding: 2px 4px; }" +
               "pre { background-color: " + codeBg + "; padding: 8px; border: 1px solid " + borderColor + "; border-radius: 6px; overflow-x: auto; }" +
               "table { border-collapse: collapse; width: 100%; }" +
               "th, td { border: 1px solid " + borderColor + "; padding: 6px; text-align: left; }" +
               "th { background-color: " + codeBg + "; }";
    }

    private static String colorToHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}

