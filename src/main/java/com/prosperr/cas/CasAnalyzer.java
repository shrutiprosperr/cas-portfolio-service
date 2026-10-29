package com.prosperr.cas;

import com.prosperr.cas.extract.PdfTextExtractor;
import com.prosperr.cas.extract.StructuredDocument;
import com.prosperr.cas.insight.PortfolioInsights;
import com.prosperr.cas.model.Portfolio;
import com.prosperr.cas.parse.CamsKfintechReader;
import com.prosperr.cas.parse.FormatDetector;
import com.prosperr.cas.parse.PortfolioNormalizer;
import com.prosperr.cas.parse.PortfolioValidator;

/** PDF bytes in, portfolio record and insights out. The password is not retained. */
public final class CasAnalyzer {
    private CasAnalyzer() {}

    public static Analysis analyze(byte[] data, String password, String fileName) {
        StructuredDocument document = PdfTextExtractor.extract(data, password);
        String format = FormatDetector.detect(document);
        Portfolio portfolio = switch (format) {
            case FormatDetector.CAMS_KFINTECH -> CamsKfintechReader.read(document, fileName);
            case FormatDetector.NSDL, FormatDetector.CDSL -> throw new CasException(
                    CasException.FORMAT_NOT_IN_PROTOTYPE,
                    "This prototype reads CAMS/KFintech statements. The " + format + " reader is the next piece.");
            default -> throw new CasException(
                    CasException.UNSUPPORTED_FORMAT,
                    "This file is not a supported CAS. Upload a CAMS/KFintech Consolidated Account Statement.");
        };
        Portfolio checked = PortfolioValidator.validate(PortfolioNormalizer.normalize(portfolio));
        return new Analysis(checked, PortfolioInsights.build(checked));
    }

    public record Analysis(Portfolio portfolio, PortfolioInsights.Result insights) {}
}
