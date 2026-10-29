package com.prosperr.cas.parse;

import com.prosperr.cas.extract.StructuredDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Column bands from a table header. Cells are assigned by the midpoint between column centres. */
public final class TableColumns {
    private TableColumns() {}

    public static final class Column {
        public final String key;
        public final double center;

        Column(String key, double center) {
            this.key = key;
            this.center = center;
        }
    }

    public static List<Column> build(List<StructuredDocument.Row> headerRows, List<Classifier> classifiers) {
        record Fragment(double x, double right, double y, String str) {}
        List<Fragment> fragments = new ArrayList<>();
        for (StructuredDocument.Row row : headerRows) {
            for (StructuredDocument.Cell cell : row.cells) {
                fragments.add(new Fragment(cell.x, cell.right, row.y, cell.str));
            }
        }
        fragments.sort(Comparator.comparingDouble(Fragment::x));

        record Group(double left, double right, List<Fragment> parts) {
            Group with(Fragment cell) {
                parts.add(cell);
                return new Group(Math.min(left, cell.x), Math.max(right, cell.right), parts);
            }
        }
        List<Group> groups = new ArrayList<>();
        for (Fragment cell : fragments) {
            int found = -1;
            for (int i = 0; i < groups.size(); i++) {
                Group group = groups.get(i);
                if (cell.x <= group.right + 2 && cell.right >= group.left - 2) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                groups.set(found, groups.get(found).with(cell));
            } else {
                List<Fragment> parts = new ArrayList<>();
                parts.add(cell);
                groups.add(new Group(cell.x, cell.right, parts));
            }
        }
        groups.sort(Comparator.comparingDouble(Group::left));
        Set<String> used = new HashSet<>();
        List<Column> columns = new ArrayList<>();
        for (Group group : groups) {
            String label = group.parts.stream()
                    .sorted(Comparator.comparingDouble(Fragment::y).reversed().thenComparingDouble(Fragment::x))
                    .map(Fragment::str)
                    .reduce((a, b) -> a + " " + b)
                    .orElse("")
                    .replaceAll("\\s+", " ")
                    .trim();
            String key = null;
            for (Classifier classifier : classifiers) {
                if (!used.contains(classifier.key) && classifier.matches().test(label)) {
                    key = classifier.key;
                    used.add(key);
                    break;
                }
            }
            columns.add(new Column(key, (group.left + group.right) / 2));
        }
        return columns;
    }

    public static String assign(List<StructuredDocument.Cell> cells, List<Column> columns, String key) {
        StringBuilder value = new StringBuilder();
        for (StructuredDocument.Cell cell : cells) {
            Column column = columnFor((cell.x + cell.right) / 2, columns);
            if (column == null || !key.equals(column.key)) {
                continue;
            }
            if (!value.isEmpty()) {
                value.append(' ');
            }
            value.append(cell.str);
        }
        return value.isEmpty() ? null : value.toString();
    }

    public static boolean has(List<Column> columns, String... keys) {
        for (String key : keys) {
            boolean found = false;
            for (Column column : columns) {
                if (key.equals(column.key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static Column columnFor(double x, List<Column> columns) {
        for (int i = 0; i < columns.size(); i++) {
            Column next = i + 1 < columns.size() ? columns.get(i + 1) : null;
            double boundary = next == null ? Double.POSITIVE_INFINITY : (columns.get(i).center + next.center) / 2;
            if (x < boundary) {
                return columns.get(i);
            }
        }
        return null;
    }

    public record Classifier(String key, java.util.function.Predicate<String> matches) {}
}
