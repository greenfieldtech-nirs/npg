package cloud.cloudonix.npg.numberingplan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Ministry of Communications numbering plan CSV (data.gov.il, "israel_numbering_plan.csv"):
 * {@code NDC, E.164 Range Format, Dialing Within Israel, Allocated To, Notes}, one row per range such
 * as {@code +972 2 30XXXXX}. Strict: an unexpected header, or a file where more than a few rows are
 * malformed, is rejected instead of half-imported.
 */
public final class NumberingPlanParser {

    /** One allocated range. {@code nsnPrefix} is the national significant number before the X run. */
    public record PlanRange(String ndc, String e164Pattern, String nationalPattern, String nsnPrefix, int nsnLength,
                            long rangeSize, String allocatedTo, String notes) {
    }

    public record ParseResult(List<PlanRange> ranges, List<String> warnings) {
    }

    public static class InvalidPlanException extends RuntimeException {
        public InvalidPlanException(String message) {
            super(message);
        }
    }

    static final int MAX_ROWS = 20_000;
    private static final double MAX_INVALID_SHARE = 0.05;
    private static final Pattern E164 = Pattern.compile("\\+972 (\\d{1,3}) (\\d*)(X+)");

    private NumberingPlanParser() {
    }

    public static ParseResult parse(String csv) {
        List<List<String>> rows = readCsv(csv.startsWith("﻿") ? csv.substring(1) : csv);
        if (rows.isEmpty()) {
            throw new InvalidPlanException("The file is empty");
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        Integer ndcCol = columns.get("ndc");
        Integer e164Col = columns.get("e.164 range format");
        Integer allocatedCol = columns.get("allocated to");
        Integer nationalCol = columns.get("dialing within israel");
        Integer notesCol = columns.get("notes");
        if (ndcCol == null || e164Col == null || allocatedCol == null) {
            throw new InvalidPlanException("Not the Israel numbering plan: expected columns NDC, E.164 Range Format, "
                + "Allocated To; found " + header);
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new InvalidPlanException("Too many rows (" + (rows.size() - 1) + ")");
        }

        List<PlanRange> ranges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int invalid = 0;
        for (int line = 1; line < rows.size(); line++) {
            List<String> row = rows.get(line);
            if (row.size() == 1 && row.get(0).isBlank()) {
                continue;
            }
            String e164 = cell(row, e164Col);
            String allocatedTo = cell(row, allocatedCol);
            Matcher m = E164.matcher(e164);
            if (!m.matches() || allocatedTo.isEmpty()) {
                invalid++;
                warnings.add("Row " + (line + 1) + " skipped: unrecognised range '" + e164 + "' or no operator");
                continue;
            }
            String ndc = m.group(1);
            if (!ndc.equals(cell(row, ndcCol))) {
                warnings.add("Row " + (line + 1) + ": NDC column '" + cell(row, ndcCol) + "' differs from the range; using " + ndc);
            }
            String nsnPrefix = ndc + m.group(2);
            int xCount = m.group(3).length();
            int nsnLength = nsnPrefix.length() + xCount;
            if (nsnLength < 8 || nsnLength > 10) {
                invalid++;
                warnings.add("Row " + (line + 1) + " skipped: " + nsnLength + "-digit numbers are not Israeli numbers");
                continue;
            }
            if (!seen.add(nsnPrefix + "/" + nsnLength)) {
                warnings.add("Row " + (line + 1) + " skipped: duplicate range " + e164);
                continue;
            }
            String national = nationalCol != null ? cell(row, nationalCol) : "";
            if (national.isEmpty()) {
                // trunk prefix 0, except the 1-prefixed (13 IoT) ranges that are dialled without it
                national = (ndc.startsWith("1") ? "" : "0") + ndc + "-" + m.group(2) + m.group(3);
            }
            String notes = notesCol != null ? cell(row, notesCol) : "";
            ranges.add(new PlanRange(ndc, e164, national, nsnPrefix, nsnLength, pow10(xCount), allocatedTo,
                notes.isEmpty() ? null : notes));
        }
        if (ranges.isEmpty()) {
            throw new InvalidPlanException("No valid ranges in the file");
        }
        if (invalid > Math.max(3, (ranges.size() + invalid) * MAX_INVALID_SHARE)) {
            throw new InvalidPlanException(invalid + " malformed rows — refusing to import a damaged plan");
        }
        return new ParseResult(List.copyOf(ranges), List.copyOf(warnings));
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index).trim() : "";
    }

    private static long pow10(int exponent) {
        long value = 1;
        for (int i = 0; i < exponent; i++) {
            value *= 10;
        }
        return value;
    }

    /** RFC 4180: comma-separated, double-quoted fields may hold commas, quotes ("") and line breaks. */
    static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
