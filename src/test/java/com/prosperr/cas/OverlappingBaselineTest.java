package com.prosperr.cas;

import com.prosperr.cas.extract.PdfTextExtractor;
import com.prosperr.cas.extract.StructuredDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlappingBaselineTest {
    @Test
    void isinStaysIntactWhenAColumnOverlapsTheSchemeLine() throws Exception {
        byte[] pdf = overlappingSchemeLine();
        StructuredDocument document = PdfTextExtractor.extract(pdf, null);
        String joined = document.pages.stream()
                .flatMap(page -> page.rows.stream())
                .map(row -> row.text)
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(joined.contains("INF879O01027"), joined);
        assertFalse(joined.contains("ISINO"), joined);
    }

    private static byte[] overlappingSchemeLine() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 9);
                stream.newLineAtOffset(40, 700);
                for (int line = 0; line < 25; line++) {
                    stream.showText("Filler line " + line + " so the page has a text layer");
                    stream.newLineAtOffset(0, -12);
                }
                stream.endText();

                stream.beginText();
                stream.setFont(font, 9);
                stream.newLineAtOffset(30, 360);
                stream.showText("Parag Parikh Flexi Cap Fund - Direct Plan Growth ISIN: INF879O01027");
                stream.endText();

                stream.beginText();
                stream.setFont(font, 9);
                stream.newLineAtOffset(220, 360.05f);
                stream.showText("Opening Unit Balance: 540.909");
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
