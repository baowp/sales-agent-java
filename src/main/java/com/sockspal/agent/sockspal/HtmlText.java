package com.sockspal.agent.sockspal;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Product descriptions are HTML with attribute tables; this turns them into short plain-text lines. */
public final class HtmlText {

    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)</(p|li|h\\d)>|<br\\s*/?>");

    private HtmlText() {}

    public static String toText(String html) {
        if (html == null) return "";
        String text = TABLE.matcher(html).replaceAll(m -> Matcher.quoteReplacement(table(m.group(1))));
        return Arrays.stream(LINE_BREAK.matcher(text).replaceAll("\n").split("\n"))
                .map(HtmlText::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private static String table(String table) {
        List<List<String>> rows = ROW.matcher(table).results()
                .map(r -> CELL.matcher(r.group(1)).results().map(c -> strip(c.group(1))).toList())
                .toList();
        // "Attribute | Value | Attribute | Value" tables hold two name/value pairs per row.
        if (!rows.isEmpty() && !rows.getFirst().isEmpty() && rows.getFirst().getFirst().equals("Attribute")) {
            StringBuilder out = new StringBuilder();
            for (List<String> cells : rows.subList(1, rows.size())) {
                for (int i = 0; i < cells.size(); i += 2) {
                    if (cells.get(i).isEmpty()) continue;
                    out.append(cells.get(i)).append(": ").append(i + 1 < cells.size() ? cells.get(i + 1) : "").append('\n');
                }
            }
            return out.toString();
        }
        return rows.stream().map(cells -> String.join(" | ", cells) + "\n").collect(Collectors.joining());
    }

    private static String strip(String s) {
        return s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
