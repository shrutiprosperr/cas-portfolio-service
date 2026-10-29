package com.prosperr.cas.parse;

import com.prosperr.cas.extract.StructuredDocument;

import java.util.regex.Pattern;

/** Ordered format rules. Keyword matching is avoided because an NSDL file names CAMS and CDSL. */
public final class FormatDetector {
    public static final String CAMS_KFINTECH = "CAMS_KFINTECH";
    public static final String NSDL = "NSDL";
    public static final String CDSL = "CDSL";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    private static final Pattern CLOSING_UNITS = Pattern.compile("Closing\\s+Unit\\s+Balance", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLIO = Pattern.compile("Folio\\s+No", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPOSITORY = Pattern.compile("\\bDP\\s*ID\\b|\\bClient\\s*ID\\b|\\bBO\\s*ID\\b|\\bDemat\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NSDL_NAME = Pattern.compile("National\\s+Securities\\s+Depository|\\bNSDL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CDSL_NAME = Pattern.compile("Central\\s+Depository\\s+Services|\\bCDSL\\b", Pattern.CASE_INSENSITIVE);

    private FormatDetector() {}

    public static String detect(StructuredDocument document) {
        StringBuilder text = new StringBuilder();
        int pages = Math.min(3, document.pages.size());
        for (int i = 0; i < pages; i++) {
            for (StructuredDocument.Row row : document.pages.get(i).rows) {
                text.append(row.text).append('\n');
            }
        }
        String scanned = text.toString();
        if (CLOSING_UNITS.matcher(scanned).find() && FOLIO.matcher(scanned).find()) {
            return CAMS_KFINTECH;
        }
        if (DEPOSITORY.matcher(scanned).find()) {
            int nsdl = indexOf(NSDL_NAME, scanned);
            int cdsl = indexOf(CDSL_NAME, scanned);
            if (nsdl >= 0 && (cdsl < 0 || nsdl <= cdsl)) {
                return NSDL;
            }
            if (cdsl >= 0) {
                return CDSL;
            }
        }
        return UNSUPPORTED;
    }

    private static int indexOf(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? matcher.start() : -1;
    }
}
