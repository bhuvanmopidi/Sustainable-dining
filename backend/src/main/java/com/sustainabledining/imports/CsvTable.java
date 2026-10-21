package com.sustainabledining.imports;

import com.sustainabledining.common.ApiError;
import com.sustainabledining.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/**
 * A small RFC 4180 CSV reader: comma separated, optional double quotes ("" inside quotes is a literal quote),
 * CRLF or LF line endings, UTF-8 BOM ignored, blank lines skipped. Header names are trimmed and lower-cased.
 * Line numbers refer to the physical line where each record starts, so error messages match a text editor.
 */
final class CsvTable {

    record Row(int line, Map<String, String> values) {
        /** The trimmed cell value, or "" when the column is missing or empty. */
        String get(String column) {
            String v = values.get(column);
            return v == null ? "" : v.trim();
        }
    }

    final List<String> header;
    final List<Row> rows;

    private CsvTable(List<String> header, List<Row> rows) {
        this.header = header;
        this.rows = rows;
    }

    static CsvTable parse(String text, int maxRows) {
        String s = text == null ? "" : text;
        if (s.startsWith("ï»¿")) {
            s = s.substring(1);
        }
        List<List<String>> records = new ArrayList<>();
        List<Integer> startLines = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int line = 1;
        int recordStart = 1;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                }
            } else if (c == '"' && field.length() == 0 && !fieldWasQuoted) {
                inQuotes = true;
                fieldWasQuoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldWasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                fieldWasQuoted = false;
                addRecord(records, startLines, fields, recordStart);
                fields = new ArrayList<>();
                line++;
                recordStart = line;
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw malformed("line " + recordStart, "a quoted value is never closed");
        }
        if (field.length() > 0 || !fields.isEmpty() || fieldWasQuoted) {
            fields.add(field.toString());
            addRecord(records, startLines, fields, recordStart);
        }
        if (records.isEmpty()) {
            throw malformed("file", "the file is empty; the first line must be the header");
        }

        List<String> header = new ArrayList<>();
        for (String h : records.get(0)) {
            String name = h.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw malformed("line " + startLines.get(0), "the header has an empty column name");
            }
            if (header.contains(name)) {
                throw malformed("line " + startLines.get(0), "column '" + name + "' appears twice in the header");
            }
            header.add(name);
        }
        if (records.size() - 1 > maxRows) {
            throw malformed("file", "too many rows (" + (records.size() - 1) + "); the limit is " + maxRows);
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.size() > header.size()) {
                throw malformed("line " + startLines.get(r), "has " + rec.size() + " values but the header has "
                        + header.size() + " columns (is there an unquoted comma?)");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int k = 0; k < header.size(); k++) {
                values.put(header.get(k), k < rec.size() ? rec.get(k) : "");
            }
            rows.add(new Row(startLines.get(r), values));
        }
        return new CsvTable(header, rows);
    }

    /** Throws one error listing every required column that the header lacks. */
    void requireColumns(String... required) {
        List<ApiError.Detail> missing = new ArrayList<>();
        for (String col : required) {
            if (!header.contains(col)) {
                missing.add(new ApiError.Detail("header", "missing column '" + col + "'"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMPORT_INVALID",
                    "the CSV header must contain: " + String.join(", ", required), missing);
        }
    }

    private static void addRecord(List<List<String>> records, List<Integer> startLines, List<String> fields, int start) {
        boolean blank = fields.stream().allMatch(f -> f.trim().isEmpty());
        if (!blank) {
            records.add(fields);
            startLines.add(start);
        }
    }

    private static ApiException malformed(String where, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CSV_MALFORMED", "the file is not valid CSV",
                List.of(new ApiError.Detail(where, message)));
    }
}