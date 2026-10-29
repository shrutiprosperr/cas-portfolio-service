package com.prosperr.cas.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Printed statement numbers and dates. Money reconciliation uses integer paise, not double. */
public final class PrintedValues {
    private static final Pattern NUMBER_TOKEN =
            Pattern.compile("^\\(?-?(?:₹|INR|Rs\\.?)?\\s*-?\\d[\\d,]*(?:\\.\\d+)?\\)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\b(\\d{1,2})[-/ ]([A-Za-z]{3}|\\d{1,2})[-/ ](\\d{4})\\b");

    private PrintedValues() {}

    public static boolean isNumberToken(String raw) {
        return raw != null && NUMBER_TOKEN.matcher(raw.trim()).matches();
    }

    public static BigDecimal parseNumber(String raw) {
        Parsed parsed = parse(raw);
        return parsed == null ? null : parsed.value;
    }

    /** Integer paise from the printed string. Half-up on a third decimal. Null when unparsable. */
    public static Long toPaise(String raw) {
        Parsed parsed = parse(raw);
        if (parsed == null) {
            return null;
        }
        String text = clean(raw).replaceAll("[(),\\-]", "");
        String[] parts = text.split("\\.", -1);
        String fraction = parts.length > 1 ? parts[1] : "";
        String padded = (fraction + "000").substring(0, 3);
        long paise = Long.parseLong(parts[0]) * 100 + Long.parseLong(padded.substring(0, 2));
        if (padded.charAt(2) >= '5') {
            paise += 1;
        }
        return parsed.value.signum() < 0 ? -paise : paise;
    }

    public static String parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher match = DATE_TOKEN.matcher(raw);
        if (!match.find()) {
            return null;
        }
        int day = Integer.parseInt(match.group(1));
        int month = month(match.group(2));
        int year = Integer.parseInt(match.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return String.format("%04d-%02d-%02d", year, month, day);
    }

    private static Parsed parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = clean(raw);
        if (text.isEmpty() || text.matches("(?i)(-|–|NA|N\\.A\\.?)")) {
            return null;
        }
        boolean negative = text.startsWith("(") && text.endsWith(")") || text.startsWith("-");
        String digits = text.replaceAll("[(),\\-]", "");
        if (!digits.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        BigDecimal value = new BigDecimal(digits);
        if (negative) {
            value = value.negate();
        }
        return new Parsed(value);
    }

    private static String clean(String raw) {
        return raw.replaceAll("(?i)₹|INR|Rs\\.?", "").replaceAll("\\s+", "").trim();
    }

    private static int month(String token) {
        if (token.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(token);
        }
        return switch (token.toLowerCase()) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> 0;
        };
    }

    private record Parsed(BigDecimal value) {}
}
