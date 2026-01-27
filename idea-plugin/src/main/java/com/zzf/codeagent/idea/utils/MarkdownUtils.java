package com.zzf.codeagent.idea.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownUtils {

    public static String renderToHtml(String markdown) {
        if (markdown == null) return "";
        
        // Escape HTML special characters
        String html = escapeHtml(markdown);

        // Code blocks (```lang ... ```)
        // Use non-greedy match (?s) for dot-all, including newlines
        // Pattern: ```(\w+)?\n?(.*?)```
        // Replacement: <pre><code>$2</code></pre> (ignoring lang for now, or putting it in class)
        
        // We handle code blocks manually to avoid regex complexity with nested structures or greedy matches
        StringBuilder sb = new StringBuilder();
        int length = html.length();
        int pos = 0;
        
        while (pos < length) {
            int codeStart = html.indexOf("```", pos);
            if (codeStart == -1) {
                sb.append(processInline(html.substring(pos)));
                break;
            }
            
            // Text before code block
            sb.append(processInline(html.substring(pos, codeStart)));
            
            int codeEnd = html.indexOf("```", codeStart + 3);
            if (codeEnd == -1) {
                // Unclosed code block, treat as text
                sb.append(processInline(html.substring(codeStart)));
                break;
            }
            
            // Extract content
            String content = html.substring(codeStart + 3, codeEnd);
            // Check for language identifier (first word before newline)
            String language = "";
            int firstNewline = content.indexOf('\n');
            if (firstNewline > -1 && firstNewline < 20) { // arbitrary limit for lang length
                String potentialLang = content.substring(0, firstNewline).trim();
                if (!potentialLang.contains(" ") && !potentialLang.isEmpty()) {
                    language = potentialLang;
                    content = content.substring(firstNewline + 1);
                }
            }
            
            sb.append("<pre><code class='" + language + "'>").append(content).append("</code></pre>");
            
            pos = codeEnd + 3;
        }
        
        return "<html><head><style>body { font-family: sans-serif; } pre { background-color: #f5f5f5; padding: 5px; border: 1px solid #ddd; } code { font-family: monospace; }</style></head><body>" + sb.toString() + "</body></html>";
    }

    private static String processInline(String text) {
        String html = text;

        // Headers (must be processed before newlines are converted)
        html = html.replaceAll("(?m)^### (.*)$", "<h3>$1</h3>");
        html = html.replaceAll("(?m)^## (.*)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^# (.*)$", "<h1>$1</h1>");

        // Lists (simple bullet points)
        html = html.replaceAll("(?m)^\\s*[-*]\\s+(.*)$", "<div>&bull; $1</div>");

        // Inline code (`code`)
        html = html.replaceAll("`([^`]+)`", "<code style='background-color:#f0f0f0;'>$1</code>");
        
        // Bold (**text**)
        html = html.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        
        // Italic (*text*)
        html = html.replaceAll("\\*([^*]+)\\*", "<i>$1</i>");
        
        // Newlines to <br> (only for lines that are NOT headers or list items)
        // This is tricky. simpler to just replace all \n with <br> 
        // But headers/divs are block elements, so <br> after them might add extra space.
        // JEditorPane is lenient.
        html = html.replaceAll("\n", "<br>");
        
        return html;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
}
