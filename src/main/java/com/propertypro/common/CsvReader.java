package com.propertypro.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small CSV reader for imports (units, tenants). Handles quoted fields,
 * commas and doubled quotes inside quotes, a UTF-8 BOM, and Excel's ";" when
 * a file uses it. Header names are matched case-insensitively, ignoring spaces.
 * Excel users save as "CSV UTF-8" before uploading.
 */
public final class CsvReader {

    public static final int MAX_ROWS = 500;

    private CsvReader() {}

    /** Each row as a map of normalised header -> trimmed value. */
    public static List<Map<String, String>> read(InputStream in) throws IOException {
        List<List<String>> rows = readRows(in, MAX_ROWS + 1);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> headers = rows.getFirst().stream().map(CsvReader::normalise).toList();
        List<Map<String, String>> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            if (values.isEmpty()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                row.put(headers.get(c), c < values.size() ? values.get(c).trim() : "");
            }
            out.add(row);
        }
        return out;
    }

    /**
     * Every line as its fields, header included, for files whose header is not on the first line (bank
     * statements start with the account name and period). A blank line is an empty list, so row index + 1 is
     * always the line number in the file.
     */
    public static List<List<String>> readRows(InputStream in, int maxRows) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null) {
                return List.of();
            }
            if (first.startsWith("﻿")) {
                first = first.substring(1);
            }
            char sep = count(first, ';') > count(first, ',') ? ';' : ',';
            rows.add(parseLine(first, sep));
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(line.isBlank() ? List.of() : parseLine(line, sep));
                if (rows.size() > maxRows) {
                    throw ApiException.badRequest("TOO_MANY_ROWS", "A file can have at most " + (maxRows - 1) + " rows");
                }
            }
        }
        return rows;
    }

    public static String normalise(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static List<String> parseLine(String line, char sep) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
