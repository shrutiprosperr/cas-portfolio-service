package com.prosperr.cas.parse;

import com.prosperr.cas.extract.StructuredDocument;
import com.prosperr.cas.model.Portfolio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAMS / KFintech reader. Layout is AMC, then folio, then scheme.
 * Each scheme is anchored on its ISIN. Values are read by label, not by a fixed column.
 */
public final class CamsKfintechReader {
    private static final String DATE = "\\d{1,2}-[A-Za-z]{3}-\\d{4}";
    private static final String NUM = "\\(?-?[\\d,]+(?:\\.\\d+)?\\)?";
    private static final String CURRENCY = "(?:INR|₹|Rs\\.?)?\\s*";

    private static final Pattern PERIOD = Pattern.compile("(" + DATE + ")\\s*To\\s*(" + DATE + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("E-?mail\\s*(?:Id|ID|Address)?\\s*[:\\-]?\\s*(\\S+@\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY = Pattern.compile("PORTFOLIO\\s+SUMMARY", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_ROW = Pattern.compile("^Total\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMC_HEADING = Pattern.compile("^[A-Za-z][A-Za-z0-9&().,'\\s-]*(?:Mutual\\s*Fund|\\bMF)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLIO = Pattern.compile("Folio\\s*No\\.?\\s*:?\\s*([A-Za-z0-9/ ]+?)\\s*(?=PAN|KYC|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN = Pattern.compile("PAN\\s*:?\\s*([A-Z]{5}\\d{4}[A-Z])");
    private static final Pattern ISIN = Pattern.compile("ISIN\\s*:?\\s*([A-Z]{2}[A-Z0-9]{9}\\d)");
    private static final Pattern ISIN_LABEL_ONLY = Pattern.compile("ISIN\\s*:?\\s*$");
    private static final Pattern ISIN_PARTIAL = Pattern.compile("ISIN\\s*:?\\s*([A-Z]{2}[A-Z0-9]{0,9})(?=\\s*(?:Registrar\\b.*)?$)");
    private static final Pattern REGISTRAR_CELL = Pattern.compile("\\s*Registrar\\s*:?\\s*(?:CAMS|KFINTECH|KFIN|KARVY)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGISTRAR = Pattern.compile("Registrar\\s*:?\\s*(CAMS|KFINTECH|KFIN|KARVY)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME_CODE = Pattern.compile("^([A-Z0-9]{1,15})-(?=\\S)");
    private static final Pattern OPENING = Pattern.compile("Opening\\s+Unit\\s+Balance\\s*:?\\s*(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING = Pattern.compile("Closing\\s+Unit\\s+Balance\\s*:?\\s*(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV = Pattern.compile("NAV\\s+on\\s+(" + DATE + ")\\s*:?\\s*" + CURRENCY + "(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST = Pattern.compile("(?:Total\\s+)?Cost\\s+Value\\s*:?\\s*" + CURRENCY + "(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKET = Pattern.compile("(?:Market\\s+Value|Valuation)\\s+on\\s+(" + DATE + ")\\s*:?\\s*" + CURRENCY + "(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern TXN_DATE = Pattern.compile("^(" + DATE + ")\\s*(.*)$");
    private static final Pattern ADDRESS_STOP = Pattern.compile("Mobile|Phone|PORTFOLIO|Consolidated|statement", Pattern.CASE_INSENSITIVE);

    private static final List<TableColumns.Classifier> SUMMARY_COLUMNS = List.of(
            new TableColumns.Classifier("costValue", label -> label.matches("(?i).*Cost.*")),
            new TableColumns.Classifier("marketValue", label -> label.matches("(?i).*(Market|Value).*")));
    private static final List<TableColumns.Classifier> TXN_COLUMNS = List.of(
            new TableColumns.Classifier("date", label -> label.matches("(?i)^Date.*")),
            new TableColumns.Classifier("description", label -> label.matches("(?i).*(Transaction|Description|Particulars).*")),
            new TableColumns.Classifier("amount", label -> label.matches("(?i).*Amount.*")),
            new TableColumns.Classifier("unitBalance", label -> label.matches("(?i).*Balance.*")),
            new TableColumns.Classifier("price", label -> label.matches("(?i).*(Price|NAV).*")),
            new TableColumns.Classifier("units", label -> label.matches("(?i).*Units.*")));

    private CamsKfintechReader() {}

    public static Portfolio read(StructuredDocument document, String fileName) {
        Portfolio portfolio = new Portfolio();
        portfolio.source.format = FormatDetector.CAMS_KFINTECH;
        portfolio.source.rawFileName = fileName;
        List<StructuredDocument.Row> rows = new ArrayList<>();
        for (StructuredDocument.Page page : document.pages) {
            rows.addAll(page.rows);
        }
        readHeader(rows, portfolio);
        readSummary(rows, portfolio);
        readSchemes(rows, portfolio);
        return portfolio;
    }

    private static void readHeader(List<StructuredDocument.Row> rows, Portfolio portfolio) {
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < Math.min(60, rows.size()); i++) {
            header.append(rows.get(i).text).append('\n');
        }
        Matcher period = PERIOD.matcher(header.toString());
        if (period.find()) {
            portfolio.source.statementPeriod.from = PrintedValues.parseDate(period.group(1));
            portfolio.source.statementPeriod.to = PrintedValues.parseDate(period.group(2));
            portfolio.source.statementAsOf = portfolio.source.statementPeriod.to;
        }
        int emailIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            Matcher email = EMAIL.matcher(rows.get(i).text);
            if (email.find()) {
                portfolio.investor.email = email.group(1);
                emailIndex = i;
                break;
            }
        }
        String labelled = InvestorName.findLabelled(rows);
        if (labelled != null) {
            portfolio.investor.name = labelled;
            return;
        }
        if (emailIndex >= 0) {
            InvestorName.Found near = InvestorName.nameNearAnchor(rows, emailIndex);
            if (near != null) {
                portfolio.investor.name = near.name();
                portfolio.investor.address = readAddress(rows, near.index() + 1);
                return;
            }
        }
        portfolio.investor.name = InvestorName.nameUnderFolios(rows, FOLIO);
    }

    private static String readAddress(List<StructuredDocument.Row> rows, int from) {
        List<String> lines = new ArrayList<>();
        for (int i = from; i < rows.size() && lines.size() < 6; i++) {
            StringBuilder line = new StringBuilder();
            for (StructuredDocument.Cell cell : rows.get(i).cells) {
                if (cell.x < 280) {
                    if (!line.isEmpty()) {
                        line.append(' ');
                    }
                    line.append(cell.str);
                }
            }
            String text = line.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (ADDRESS_STOP.matcher(text).find() || EMAIL.matcher(text).find()) {
                break;
            }
            lines.add(text);
        }
        return lines.isEmpty() ? null : String.join(", ", lines);
    }

    private static void readSummary(List<StructuredDocument.Row> rows, Portfolio portfolio) {
        int start = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (SUMMARY.matcher(rows.get(i).text).find()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return;
        }
        List<TableColumns.Column> columns = null;
        for (int i = start + 1; i < Math.min(rows.size(), start + 80); i++) {
            StructuredDocument.Row row = rows.get(i);
            if (columns == null && row.text.matches("(?i).*Market\\s*Value.*")) {
                columns = TableColumns.build(List.of(row), SUMMARY_COLUMNS);
                continue;
            }
            List<StructuredDocument.Cell> numeric = row.cells.stream().filter(cell -> PrintedValues.isNumberToken(cell.str)).toList();
            if (numeric.isEmpty()) {
                continue;
            }
            String costRaw;
            String marketRaw;
            if (columns != null && TableColumns.has(columns, "marketValue")) {
                costRaw = TableColumns.assign(numeric, columns, "costValue");
                marketRaw = TableColumns.assign(numeric, columns, "marketValue");
            } else {
                costRaw = numeric.size() > 1 ? numeric.get(0).str : null;
                marketRaw = numeric.get(numeric.size() - 1).str;
            }
            if (TOTAL_ROW.matcher(row.text).find()) {
                setTotal(portfolio, "totalCostValue", costRaw);
                setTotal(portfolio, "totalMarketValue", marketRaw);
                return;
            }
            String amc = row.cells.stream()
                    .filter(cell -> !PrintedValues.isNumberToken(cell.str))
                    .map(cell -> cell.str)
                    .reduce((a, b) -> a + " " + b)
                    .orElse("")
                    .trim();
            if (!amc.isEmpty() && columns != null) {
                Portfolio.AmcTotal entry = new Portfolio.AmcTotal();
                entry.amc = amc;
                entry.costValue = PrintedValues.parseNumber(costRaw);
                entry.marketValue = PrintedValues.parseNumber(marketRaw);
                entry.raw.put("costValue", costRaw);
                entry.raw.put("marketValue", marketRaw);
                portfolio.printedTotals.byAmc.add(entry);
            }
        }
    }

    private static void setTotal(Portfolio portfolio, String key, String raw) {
        BigDecimal value = PrintedValues.parseNumber(raw);
        if (value == null) {
            return;
        }
        if ("totalCostValue".equals(key)) {
            portfolio.printedTotals.totalCostValue = value;
        } else {
            portfolio.printedTotals.totalMarketValue = value;
        }
        portfolio.printedTotals.raw.put(key, raw.trim());
    }

    private static void readSchemes(List<StructuredDocument.Row> rows, Portfolio portfolio) {
        Context context = new Context();
        Set<String> summaryAmcs = new HashSet<>();
        for (Portfolio.AmcTotal entry : portfolio.printedTotals.byAmc) {
            summaryAmcs.add(normalize(entry.amc));
        }
        Portfolio.Holding holding = null;
        List<TableColumns.Column> txnColumns = null;
        for (int i = 0; i < rows.size(); i++) {
            StructuredDocument.Row row = rows.get(i);
            String text = row.text.trim();
            String schemeLine = joinWrappedIsin(rows, i);
            if (schemeLine != null) {
                holding = startHolding(rows, i, schemeLine, context);
                portfolio.holdings.add(holding);
                txnColumns = null;
                continue;
            }
            boolean numeric = row.cells.stream().anyMatch(cell -> PrintedValues.isNumberToken(cell.str));
            if (!numeric && (AMC_HEADING.matcher(text).matches() || summaryAmcs.contains(normalize(text)))) {
                context.amc = text;
                continue;
            }
            Matcher folio = FOLIO.matcher(text);
            if (folio.find()) {
                context.folio = folio.group(1).replaceAll("\\s+", " ").trim();
                Matcher pan = PAN.matcher(text);
                context.pan = pan.find() ? pan.group(1) : null;
                context.folioRowIndex = i;
                continue;
            }
            if (holding == null) {
                continue;
            }
            if (txnColumns == null && text.matches("(?i).*Amount.*") && text.matches("(?i).*Units.*") && text.matches("(?i).*(Price|NAV).*")) {
                txnColumns = TableColumns.build(headerRows(rows, i), TXN_COLUMNS);
                continue;
            }
            readLabels(text, holding);
            if (!row.cells.isEmpty() && TXN_DATE.matcher(row.cells.get(0).str).find()) {
                holding.transactions.add(readTransaction(row, txnColumns));
            }
            if (holding.raw.get("units") != null && holding.raw.get("marketValue") != null) {
                holding = null;
            }
        }
    }

    private static String joinWrappedIsin(List<StructuredDocument.Row> rows, int i) {
        String text = rows.get(i).text;
        if (ISIN.matcher(text).find()) {
            return text;
        }
        String next = i + 1 < rows.size() ? rows.get(i + 1).text : "";
        if (ISIN_LABEL_ONLY.matcher(text).find() && ISIN.matcher(text + " " + next).find()) {
            return text + " " + next;
        }
        Matcher partial = ISIN_PARTIAL.matcher(text);
        if (partial.find() && !next.isBlank()) {
            Matcher rest = Pattern.compile("^[A-Z0-9]+").matcher(next.trim());
            String tail = rest.find() ? rest.group() : "";
            String code = (partial.group(1) + tail);
            if (code.length() >= 12) {
                code = code.substring(0, 12);
            }
            if (code.length() == 12 && IsinCheck.isValid(code)) {
                String replaced = text.replaceFirst("ISIN\\s*:?\\s*" + Pattern.quote(partial.group(1)), "ISIN: " + code);
                return replaced + " " + next.trim().substring(12 - partial.group(1).length());
            }
        }
        return null;
    }

    private static Portfolio.Holding startHolding(List<StructuredDocument.Row> rows, int i, String schemeLine, Context context) {
        Portfolio.Holding holding = new Portfolio.Holding();
        Matcher isin = ISIN.matcher(schemeLine);
        holding.isin = isin.find() ? isin.group(1) : null;
        holding.amc = context.amc;
        holding.folio = context.folio;
        holding.pan = context.pan;
        String registrarText = schemeLine + " " + textAt(rows, i + 1) + " " + textAt(rows, i + 2);
        Matcher registrar = REGISTRAR.matcher(registrarText);
        if (registrar.find()) {
            holding.registrar = "CAMS".equalsIgnoreCase(registrar.group(1)) ? "CAMS" : "KFINTECH";
        }
        String namePart = withoutRegistrar(schemeLine).split("\\s*-?\\s*ISIN\\b")[0].trim();
        List<String> fragments = new ArrayList<>();
        for (int back = 1; back <= 2 && !SCHEME_CODE.matcher(namePart).find(); back++) {
            int index = i - back;
            if (index < 0 || index <= context.folioRowIndex + 1 || !isNameFragment(rows.get(index))) {
                break;
            }
            fragments.add(0, withoutRegistrar(rows.get(index).text).trim());
            if (SCHEME_CODE.matcher(fragments.get(0)).find()) {
                namePart = joinName(String.join(" ", fragments), namePart);
                break;
            }
        }
        if (namePart.isEmpty() && !fragments.isEmpty()) {
            namePart = fragments.get(fragments.size() - 1);
        }
        Matcher code = SCHEME_CODE.matcher(namePart);
        if (code.find()) {
            holding.rtaSchemeCode = code.group(1);
            holding.schemeName = namePart.substring(code.end()).replaceAll("[\\s-]+$", "").trim();
        } else {
            holding.schemeName = namePart.replaceAll("[\\s-]+$", "").trim();
        }
        if (holding.schemeName != null && holding.schemeName.isEmpty()) {
            holding.schemeName = null;
        }
        return holding;
    }

    private static boolean isNameFragment(StructuredDocument.Row row) {
        return !withoutRegistrar(row.text).contains(":") && row.cells.stream().noneMatch(cell -> PrintedValues.isNumberToken(cell.str));
    }

    private static String withoutRegistrar(String text) {
        return REGISTRAR_CELL.matcher(text).replaceAll("");
    }

    private static String joinName(String first, String second) {
        if (second == null || second.isEmpty()) {
            return first.trim();
        }
        return second.matches("-\\S.*") ? first.trim() + second : first.trim() + " " + second;
    }

    private static List<StructuredDocument.Row> headerRows(List<StructuredDocument.Row> rows, int i) {
        List<StructuredDocument.Row> header = new ArrayList<>();
        header.add(rows.get(i));
        if (i + 1 < rows.size()) {
            StructuredDocument.Row next = rows.get(i + 1);
            String first = next.cells.isEmpty() ? "" : next.cells.get(0).str;
            if (!TXN_DATE.matcher(first).find() && next.text.matches("(?i).*(\\(INR\\)|\\(Rs|Units|Price).*")) {
                header.add(next);
            }
        }
        return header;
    }

    private static void readLabels(String text, Portfolio.Holding holding) {
        Matcher opening = OPENING.matcher(text);
        if (opening.find()) {
            setValue(holding, "openingUnits", opening.group(1));
        }
        Matcher closing = CLOSING.matcher(text);
        if (closing.find()) {
            setValue(holding, "units", closing.group(1));
        }
        Matcher nav = NAV.matcher(text);
        if (nav.find()) {
            setValue(holding, "nav", nav.group(2));
            holding.navDate = PrintedValues.parseDate(nav.group(1));
        }
        Matcher cost = COST.matcher(text);
        if (cost.find()) {
            setValue(holding, "costValue", cost.group(1));
        }
        Matcher market = MARKET.matcher(text);
        if (market.find()) {
            setValue(holding, "marketValue", market.group(2));
        }
    }

    private static void setValue(Portfolio.Holding holding, String key, String raw) {
        holding.raw.put(key, raw.trim());
        BigDecimal value = PrintedValues.parseNumber(raw);
        switch (key) {
            case "openingUnits" -> holding.openingUnits = value;
            case "units" -> holding.units = value;
            case "nav" -> holding.nav = value;
            case "costValue" -> holding.costValue = value;
            case "marketValue" -> holding.marketValue = value;
            default -> {
            }
        }
    }

    private static Portfolio.Transaction readTransaction(StructuredDocument.Row row, List<TableColumns.Column> columns) {
        String dateCell = row.cells.get(0).str;
        Matcher date = TXN_DATE.matcher(dateCell);
        date.find();
        List<StructuredDocument.Cell> rest = row.cells.subList(1, row.cells.size());
        List<StructuredDocument.Cell> numeric = rest.stream().filter(cell -> PrintedValues.isNumberToken(cell.str)).toList();
        String description = ((date.group(2) == null ? "" : date.group(2)) + " "
                + rest.stream().filter(cell -> !PrintedValues.isNumberToken(cell.str)).map(cell -> cell.str).reduce((a, b) -> a + " " + b).orElse(""))
                .trim();
        String amount;
        String units;
        String price;
        String balance;
        if (numeric.isEmpty()) {
            String[] tokens = description.split("\\s+");
            List<String> trailing = new ArrayList<>();
            List<String> words = new ArrayList<>(List.of(tokens));
            while (!words.isEmpty() && PrintedValues.isNumberToken(words.get(words.size() - 1))) {
                trailing.add(0, words.remove(words.size() - 1));
            }
            description = String.join(" ", words);
            String[] mapped = mapByCount(trailing);
            amount = mapped[0];
            units = mapped[1];
            price = mapped[2];
            balance = mapped[3];
        } else if (columns != null && TableColumns.has(columns, "amount", "units", "unitBalance")) {
            amount = TableColumns.assign(numeric, columns, "amount");
            units = TableColumns.assign(numeric, columns, "units");
            price = TableColumns.assign(numeric, columns, "price");
            balance = TableColumns.assign(numeric, columns, "unitBalance");
        } else {
            String[] mapped = mapByCount(numeric.stream().map(cell -> cell.str).toList());
            amount = mapped[0];
            units = mapped[1];
            price = mapped[2];
            balance = mapped[3];
        }
        Portfolio.Transaction transaction = new Portfolio.Transaction();
        transaction.date = PrintedValues.parseDate(date.group(1));
        transaction.description = description;
        transaction.amount = PrintedValues.parseNumber(amount);
        transaction.units = PrintedValues.parseNumber(units);
        transaction.price = PrintedValues.parseNumber(price);
        transaction.unitBalance = PrintedValues.parseNumber(balance);
        transaction.raw.put("amount", amount);
        transaction.raw.put("units", units);
        transaction.raw.put("price", price);
        transaction.raw.put("unitBalance", balance);
        return transaction;
    }

    private static String[] mapByCount(List<String> values) {
        String[] mapped = new String[4];
        if (values.size() >= 4) {
            int start = values.size() - 4;
            mapped[0] = values.get(start);
            mapped[1] = values.get(start + 1);
            mapped[2] = values.get(start + 2);
            mapped[3] = values.get(start + 3);
        } else if (values.size() == 3) {
            mapped[1] = values.get(0);
            mapped[2] = values.get(1);
            mapped[3] = values.get(2);
        } else if (values.size() == 1) {
            mapped[0] = values.get(0);
        }
        return mapped;
    }

    private static String textAt(List<StructuredDocument.Row> rows, int index) {
        return index >= 0 && index < rows.size() ? rows.get(index).text : "";
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static final class Context {
        String amc;
        String folio;
        String pan;
        int folioRowIndex = -1;
    }
}
