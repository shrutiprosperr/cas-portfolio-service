package com.prosperr.cas.extract;

import com.prosperr.cas.CasException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDFBox text with positions, grouped into rows and cells.
 * Row and cell gaps follow the JavaScript engine.
 *
 * Glyphs stay in content-stream order and are cut into runs when the pen jumps.
 * A CAMS line can draw the scheme name and a value column 0.05pt apart, with
 * the two strings overlapping in x. Sorting those glyphs together interleaves
 * the ISIN, so the scheme is never recognised.
 */
public final class PdfTextExtractor {
    static final double ROW_TOLERANCE_RATIO = 0.5;
    static final double CELL_MERGE_GAP_RATIO = 0.35;
    static final double BASELINE_EPSILON = 0.03;
    static final double RUN_GAP = 8;
    static final int MIN_TEXT_ITEMS = 20;

    private PdfTextExtractor() {}

    public static StructuredDocument extract(byte[] data, String password) {
        try (PDDocument document = open(data, password)) {
            StructuredDocument structured = new StructuredDocument();
            structured.pageCount = document.getNumberOfPages();
            PositionalStripper stripper = new PositionalStripper();
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                stripper.clear();
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                stripper.getText(document);
                List<Item> items = cluster(stripper.glyphs);
                StructuredDocument.Page page = new StructuredDocument.Page();
                page.pageNumber = pageNumber;
                page.rows = groupIntoRows(items);
                structured.textItemCount += items.size();
                structured.pages.add(page);
            }
            if (structured.textItemCount < MIN_TEXT_ITEMS) {
                throw new CasException(
                        CasException.NO_TEXT_LAYER,
                        "This PDF has no readable text layer (it may be a scanned image). Please use the original CAS PDF from the issuer.");
            }
            return structured;
        } catch (CasException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CasException(CasException.UNREADABLE_PDF, "The file could not be read as a PDF.");
        }
    }

    private static PDDocument open(byte[] data, String password) throws IOException {
        try {
            if (password == null || password.isBlank()) {
                return Loader.loadPDF(data);
            }
            return Loader.loadPDF(data, password);
        } catch (InvalidPasswordException exception) {
            boolean supplied = password != null && !password.isBlank();
            throw new CasException(
                    supplied ? CasException.PASSWORD_INCORRECT : CasException.PASSWORD_REQUIRED,
                    supplied
                            ? "The password is incorrect. Please check and try again."
                            : "This CAS is password-protected. Please enter the password.");
        }
    }

    private static List<StructuredDocument.Row> groupIntoRows(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((Item item) -> item.y).thenComparingDouble(item -> item.x));
        List<List<Item>> groups = new ArrayList<>();
        for (Item item : sorted) {
            List<Item> row = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            double tolerance = Math.max(item.fontHeight * ROW_TOLERANCE_RATIO, 1);
            double rowY = row == null ? 0 : row.get(0).y;
            if (row != null && Math.abs(rowY - item.y) <= tolerance) {
                row.add(item);
            } else {
                List<Item> next = new ArrayList<>();
                next.add(item);
                groups.add(next);
            }
        }
        List<StructuredDocument.Row> rows = new ArrayList<>();
        for (List<Item> group : groups) {
            group.sort(Comparator.comparingDouble(item -> item.x));
            List<StructuredDocument.Cell> cells = mergeIntoCells(group);
            StructuredDocument.Row row = new StructuredDocument.Row();
            row.y = group.get(0).y;
            row.height = group.stream().mapToDouble(item -> item.fontHeight).max().orElse(1);
            row.cells = cells;
            row.text = cells.stream().map(cell -> cell.str).reduce((a, b) -> a + " " + b).orElse("");
            rows.add(row);
        }
        return rows;
    }

    private static List<StructuredDocument.Cell> mergeIntoCells(List<Item> items) {
        List<StructuredDocument.Cell> cells = new ArrayList<>();
        for (Item item : items) {
            StructuredDocument.Cell last = cells.isEmpty() ? null : cells.get(cells.size() - 1);
            double gap = last == null ? Double.POSITIVE_INFINITY : item.x - last.right;
            if (last != null && gap <= item.fontHeight * CELL_MERGE_GAP_RATIO) {
                boolean needsSpace = gap > item.fontHeight * 0.1 && !last.str.endsWith(" ") && !item.str.startsWith(" ");
                last.str = last.str + (needsSpace ? " " : "") + item.str;
                last.right = Math.max(last.right, item.x + item.width);
            } else {
                cells.add(new StructuredDocument.Cell(item.x, item.x + item.width, item.str));
            }
        }
        List<StructuredDocument.Cell> cleaned = new ArrayList<>();
        for (StructuredDocument.Cell cell : cells) {
            String str = cell.str.replaceAll("\\s+", " ").trim();
            if (!str.isEmpty()) {
                cleaned.add(new StructuredDocument.Cell(cell.x, cell.right, str));
            }
        }
        return cleaned;
    }

    private static List<Item> cluster(List<Glyph> glyphs) {
        List<Item> items = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double x = 0;
        double y = 0;
        double right = 0;
        double fontHeight = 1;
        boolean open = false;
        for (Glyph glyph : glyphs) {
            // A space that starts before the previous glyph ends is a duplicate. Keeping it
            // turns the single space before an ISIN into a column break and the value
            // column then swallows the ISIN.
            if (open && glyph.unicode.isBlank() && glyph.x < right - 0.5) {
                continue;
            }
            // A following glyph can start slightly before the previous one ends (kerning).
            // A real column jump moves much further left, so that starts a new run.
            boolean continues = open
                    && Math.abs(glyph.y - y) <= BASELINE_EPSILON
                    && glyph.x >= right - 3
                    && glyph.x - right <= RUN_GAP;
            if (open && !continues) {
                items.addAll(splitOnWideSpaces(text.toString(), x, y, Math.max(right - x, 0.1), fontHeight));
                text = new StringBuilder();
                open = false;
            }
            if (!open) {
                x = glyph.x;
                y = glyph.y;
                fontHeight = glyph.fontHeight;
                right = glyph.x;
                open = true;
            }
            text.append(glyph.unicode);
            right = Math.max(right, glyph.x + glyph.width);
        }
        if (open && !text.isEmpty()) {
            items.addAll(splitOnWideSpaces(text.toString(), x, y, Math.max(right - x, 0.1), fontHeight));
        }
        return items;
    }

    private static final class Glyph {
        final String unicode;
        final double x;
        final double y;
        final double width;
        final double fontHeight;

        Glyph(String unicode, double x, double y, double width, double fontHeight) {
            this.unicode = unicode;
            this.x = x;
            this.y = y;
            this.width = width;
            this.fontHeight = fontHeight;
        }
    }

    private static final class Item {
        final String str;
        final double x;
        final double y;
        final double width;
        final double fontHeight;

        Item(String str, double x, double y, double width, double fontHeight) {
            this.str = str;
            this.x = x;
            this.y = y;
            this.width = width;
            this.fontHeight = fontHeight;
        }
    }

    private static final Pattern WIDE_SPACE = Pattern.compile("\\S+(?:\\s\\S+)*");

    private static List<Item> splitOnWideSpaces(String text, double x, double y, double width, double fontHeight) {
        if (!text.matches("(?s).*\\S\\s{2,}\\S.*")) {
            return List.of(new Item(text, x, y, width, fontHeight));
        }
        double charWidth = width / Math.max(text.length(), 1);
        List<Item> parts = new ArrayList<>();
        Matcher matcher = WIDE_SPACE.matcher(text);
        while (matcher.find()) {
            String part = matcher.group();
            double partX = x + matcher.start() * charWidth;
            double partWidth = part.length() * charWidth;
            Item last = parts.isEmpty() ? null : parts.get(parts.size() - 1);
            // Two spaces before an ISIN are not a column gap. Only a wide gap splits columns.
            if (last != null && partX - (last.x + last.width) < 12) {
                String joined = last.str + " " + part;
                parts.set(parts.size() - 1, new Item(joined, last.x, y, partX + partWidth - last.x, fontHeight));
            } else {
                parts.add(new Item(part, partX, y, partWidth, fontHeight));
            }
        }
        return parts;
    }

    private static final class PositionalStripper extends PDFTextStripper {
        private final List<Glyph> glyphs = new ArrayList<>();

        PositionalStripper() throws IOException {
            setSortByPosition(false);
        }

        void clear() {
            glyphs.clear();
        }

        @Override
        protected void writePage() {
            for (List<TextPosition> textList : getCharactersByArticle()) {
                for (TextPosition position : textList) {
                    String unicode = position.getUnicode();
                    if (unicode == null || unicode.isEmpty()) {
                        continue;
                    }
                    glyphs.add(new Glyph(
                            unicode,
                            position.getXDirAdj(),
                            position.getYDirAdj(),
                            Math.max(position.getWidthDirAdj(), 0.01),
                            Math.max(position.getHeightDir(), 1)));
                }
            }
        }
    }
}
