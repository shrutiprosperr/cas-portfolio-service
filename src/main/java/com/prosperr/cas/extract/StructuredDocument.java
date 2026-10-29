package com.prosperr.cas.extract;

import java.util.ArrayList;
import java.util.List;

/** Rows and cells rebuilt from PDF text positions. Parsers read cells. text is only for labels. */
public final class StructuredDocument {
    public int pageCount;
    public int textItemCount;
    public List<Page> pages = new ArrayList<>();

    public static final class Page {
        public int pageNumber;
        public List<Row> rows = new ArrayList<>();
    }

    public static final class Row {
        public double y;
        public double height;
        public String text;
        public List<Cell> cells = new ArrayList<>();
    }

    public static final class Cell {
        public double x;
        public double right;
        public String str;

        public Cell(double x, double right, String str) {
            this.x = x;
            this.right = right;
            this.str = str;
        }
    }
}
