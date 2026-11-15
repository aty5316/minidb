package ru.lab.minidb;

import java.io.PrintStream;
import java.util.*;
public final class TablePrinter {
    private TablePrinter() { }
    public static void print(Engine.Result result, PrintStream out) {
        if (result.columns.isEmpty()) { out.println("OK; изменено строк: " + result.affected); return; }
        int[] widths = new int[result.columns.size()];
        List<List<String>> lines = new ArrayList<>(); lines.add(result.columns);
        for (List<Object> row : result.rows) {
            List<String> values = new ArrayList<>();
            for (Object value : row) values.add(value == null ? "NULL" : value.toString().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
            lines.add(values);
        }
        for (List<String> line : lines) for (int i = 0; i < widths.length; i++) widths[i] = Math.max(widths[i], line.get(i).length());
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) { for (int i = 0; i < width + 2; i++) border.append('-'); border.append('+'); }
        out.println(border);
        for (int r = 0; r < lines.size(); r++) {
            out.print("|");
            for (int i = 0; i < widths.length; i++) out.printf(" %-" + widths[i] + "s |", lines.get(r).get(i));
            out.println(); if (r == 0) out.println(border);
        }
        out.println(border);
        out.println("Строк: " + result.rows.size() + "; план: " + result.plan + "; проверено: " + result.examined);
    }
}
