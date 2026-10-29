package com.prosperr.cas.parse;

import com.prosperr.cas.extract.StructuredDocument;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Investor name, accepted only when the text reads like a person's name. */
public final class InvestorName {
    private static final Pattern NAME = Pattern.compile(
            "^(?:(?:MRS|MR|MS|MISS|DR|SHRI|SMT|KUMARI|KUM)\\.?\\s+)?[A-Za-z][A-Za-z .'-]{1,80}$");
    private static final Pattern NOT_A_NAME = Pattern.compile(
            "\\b(statement|account|consolidated|mutual|fund|funds|limited|ltd|depository|services|period|page|email|e-mail|mobile|phone|address|nominee|registrar|summary|total|india|private|pvt|investor|holder|kyc|pan|folio|transaction|date|portfolio|value|cost|units|nav|dear|welcome|cas|nsdl|cdsl|cams|kfintech|demat|opening|closing|balance)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL = Pattern.compile(
            "^(?:investor\\s*name|name\\s*of\\s*(?:the\\s*)?(?:investor|account\\s*holder|first\\s*holder|holder)|(?:sole\\s*/?\\s*)?first\\s*holder(?:\\s*name)?|holder\\s*name|account\\s*holder(?:\\s*name)?|name)\\s*[:\\-]?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final double LEFT_COLUMN_MAX_X = 280;

    private InvestorName() {}

    public static boolean looksLikeName(String text) {
        if (text == null) {
            return false;
        }
        String value = text.replaceAll("\\s+", " ").trim();
        if (value.length() < 3 || !NAME.matcher(value).matches() || NOT_A_NAME.matcher(value).find()) {
            return false;
        }
        int words = value.split(" ").length;
        return words >= 1 && words <= 6;
    }

    public static String findLabelled(List<StructuredDocument.Row> rows) {
        int limit = Math.min(rows.size(), 150);
        for (int r = 0; r < limit; r++) {
            List<StructuredDocument.Cell> cells = rows.get(r).cells;
            for (int i = 0; i < cells.size(); i++) {
                String cell = cells.get(i).str.trim();
                Matcher match = LABEL.matcher(cell);
                if (!match.find()) {
                    continue;
                }
                String sameCell = cell.substring(match.end()).trim();
                if (!sameCell.isEmpty() && looksLikeName(sameCell)) {
                    return sameCell;
                }
                if (sameCell.isEmpty() && cell.matches("(?i).*[:\\-]\\s*$|.*name$|.*holder$") && i + 1 < cells.size()) {
                    String next = cells.get(i + 1).str.trim();
                    if (looksLikeName(next)) {
                        return next;
                    }
                }
            }
        }
        return null;
    }

    public static Found nameNearAnchor(List<StructuredDocument.Row> rows, int anchorIndex) {
        for (int i = anchorIndex + 1; i <= Math.min(rows.size() - 1, anchorIndex + 30); i++) {
            String name = leftName(rows.get(i));
            if (name != null) {
                return new Found(name, i);
            }
        }
        for (int i = anchorIndex - 1; i >= Math.max(0, anchorIndex - 3); i--) {
            String name = leftName(rows.get(i));
            if (name != null) {
                return new Found(name, i);
            }
        }
        return null;
    }

    public static String nameUnderFolios(List<StructuredDocument.Row> rows, Pattern folioPattern) {
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            if (!folioPattern.matcher(rows.get(index).text).find()) {
                continue;
            }
            for (int i = index + 1; i <= Math.min(rows.size() - 1, index + 2); i++) {
                String name = leftName(rows.get(i));
                if (name != null) {
                    counts.merge(name, 1, Integer::sum);
                    break;
                }
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static String leftName(StructuredDocument.Row row) {
        for (StructuredDocument.Cell cell : row.cells) {
            if (cell.x < LEFT_COLUMN_MAX_X && looksLikeName(cell.str)) {
                return cell.str.trim();
            }
        }
        return null;
    }

    public record Found(String name, int index) {}
}
