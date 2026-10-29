package com.prosperr.cas.parse;

import com.prosperr.cas.model.Portfolio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Flag a problem. The printed number is left as printed. */
public final class PortfolioValidator {
    private static final long TOTAL_TOLERANCE_PAISE = 100;
    private static final BigDecimal UNIT_TOLERANCE = new BigDecimal("0.002");
    private static final BigDecimal ROW_SLACK = new BigDecimal("0.05");

    private PortfolioValidator() {}

    public static Portfolio validate(Portfolio portfolio) {
        List<String> warnings = new ArrayList<>(portfolio.integrity.warnings);
        for (Portfolio.Holding holding : portfolio.holdings) {
            holding.flags = rowFlags(holding);
            for (String flag : holding.flags) {
                warnings.add(describe(flag, holding));
            }
        }
        List<Portfolio.Check> checks = new ArrayList<>();
        checks.add(reconcile(portfolio, "marketValue", "totalMarketValue", "TOTAL_MARKET_RECONCILES", "current value"));
        checks.add(reconcile(portfolio, "costValue", "totalCostValue", "TOTAL_COST_RECONCILES", "invested amount"));
        checks.add(holdingCount(portfolio));
        checks.add(duplicates(portfolio));
        Portfolio.Check amc = reconcileByAmc(portfolio);
        if (amc != null) {
            checks.add(amc);
            if (!amc.passed) {
                warnings.add(amc.detail);
            }
        }
        for (Portfolio.Check check : checks) {
            if (!check.passed && amc != check) {
                warnings.add(check.detail);
            }
        }
        boolean anyFlag = portfolio.holdings.stream().anyMatch(holding -> !holding.flags.isEmpty());
        portfolio.integrity.status = anyFlag || checks.stream().anyMatch(check -> !check.passed) ? "needs_review" : "ok";
        portfolio.integrity.checks = checks;
        portfolio.integrity.warnings = warnings;
        return portfolio;
    }

    private static List<String> rowFlags(Portfolio.Holding holding) {
        List<String> flags = new ArrayList<>();
        if (holding.isin == null || holding.schemeName == null) {
            flags.add("MISSING_REQUIRED");
        }
        if (holding.isin != null && !IsinCheck.isValid(holding.isin)) {
            flags.add("ISIN_INVALID");
        }
        if (holding.nav != null && holding.navDate == null) {
            flags.add("NAV_WITHOUT_DATE");
        }
        if (holding.marketValue != null && holding.units == null) {
            flags.add("VALUE_WITHOUT_UNITS");
        }
        if (unitsNavMismatch(holding)) {
            flags.add("UNITS_NAV_VALUE_MISMATCH");
        }
        if (unitBalanceMismatch(holding)) {
            flags.add("UNIT_BALANCE_MISMATCH");
        }
        return flags;
    }

    private static boolean unitsNavMismatch(Portfolio.Holding holding) {
        if (holding.units == null || holding.nav == null || holding.marketValue == null) {
            return false;
        }
        BigDecimal gap = holding.units.multiply(holding.nav).subtract(holding.marketValue).abs();
        return gap.compareTo(tolerance(holding.units, holding.raw.get("units"), holding.nav, holding.raw.get("nav"), holding.raw.get("marketValue"))) > 0;
    }

    private static BigDecimal tolerance(BigDecimal a, String rawA, BigDecimal b, String rawB, String rawC) {
        BigDecimal halfA = BigDecimal.valueOf(0.5).scaleByPowerOfTen(-decimals(rawB));
        BigDecimal halfB = BigDecimal.valueOf(0.5).scaleByPowerOfTen(-decimals(rawA));
        BigDecimal halfC = BigDecimal.valueOf(0.5).scaleByPowerOfTen(-decimals(rawC));
        return a.abs().multiply(halfA).add(b.abs().multiply(halfB)).add(halfC).add(ROW_SLACK);
    }

    private static int decimals(String raw) {
        if (raw == null) {
            return 0;
        }
        int dot = raw.lastIndexOf('.');
        if (dot < 0) {
            return 0;
        }
        int count = 0;
        for (int i = dot + 1; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                break;
            }
            count++;
        }
        return count;
    }

    private static boolean unitBalanceMismatch(Portfolio.Holding holding) {
        if (holding.units == null || holding.transactions.isEmpty()) {
            return false;
        }
        BigDecimal last = null;
        for (int i = holding.transactions.size() - 1; i >= 0; i--) {
            if (holding.transactions.get(i).unitBalance != null) {
                last = holding.transactions.get(i).unitBalance;
                break;
            }
        }
        if (last != null && last.subtract(holding.units).abs().compareTo(UNIT_TOLERANCE) > 0) {
            return true;
        }
        if (holding.openingUnits == null) {
            return false;
        }
        BigDecimal moved = BigDecimal.ZERO;
        for (Portfolio.Transaction transaction : holding.transactions) {
            if (transaction.units != null) {
                moved = moved.add(transaction.units);
            }
        }
        return holding.openingUnits.add(moved).subtract(holding.units).abs().compareTo(UNIT_TOLERANCE) > 0;
    }

    private static Portfolio.Check reconcile(Portfolio portfolio, String holdingKey, String totalKey, String name, String label) {
        List<Long> values = new ArrayList<>();
        for (Portfolio.Holding holding : portfolio.holdings) {
            Long paise = PrintedValues.toPaise(holding.raw.get(holdingKey));
            if (paise != null) {
                values.add(paise);
            }
        }
        BigDecimal printed = "totalCostValue".equals(totalKey) ? portfolio.printedTotals.totalCostValue : portfolio.printedTotals.totalMarketValue;
        if (printed == null) {
            boolean passed = values.isEmpty();
            return new Portfolio.Check(name, passed, passed
                    ? "The statement does not print a " + label + ", so there is nothing to reconcile."
                    : "The statement's printed total " + label + " could not be found.");
        }
        long printedPaise = printed.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        long sum = values.stream().mapToLong(Long::longValue).sum();
        boolean passed = Math.abs(sum - printedPaise) <= TOTAL_TOLERANCE_PAISE;
        String detail = passed
                ? "Sum of holdings matches the printed total " + label + "."
                : "Total " + label + " does not match the statement within ₹1.";
        return new Portfolio.Check(name, passed, detail);
    }

    private static Portfolio.Check reconcileByAmc(Portfolio portfolio) {
        if (portfolio.printedTotals.byAmc.isEmpty()) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (Portfolio.AmcTotal entry : portfolio.printedTotals.byAmc) {
            Long printed = PrintedValues.toPaise(entry.raw.get("marketValue"));
            if (printed == null) {
                continue;
            }
            long read = 0;
            for (Portfolio.Holding holding : portfolio.holdings) {
                if (key(holding.amc).equals(key(entry.amc))) {
                    Long paise = PrintedValues.toPaise(holding.raw.get("marketValue"));
                    if (paise != null) {
                        read += paise;
                    }
                }
            }
            if (Math.abs(read - printed) > TOTAL_TOLERANCE_PAISE) {
                problems.add(entry.amc + " does not match the statement summary.");
            }
        }
        return new Portfolio.Check("AMC_TOTALS_RECONCILE", problems.isEmpty(),
                problems.isEmpty() ? "Every fund house matches the statement's portfolio summary." : String.join(" ", problems));
    }

    private static Portfolio.Check holdingCount(Portfolio portfolio) {
        int count = portfolio.holdings.size();
        boolean passed = count > 0 && count <= 500;
        return new Portfolio.Check("HOLDING_COUNT_SANITY", passed, passed ? count + " holding(s) read." : "No holdings could be read from this statement.");
    }

    private static Portfolio.Check duplicates(Portfolio portfolio) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Portfolio.Holding holding : portfolio.holdings) {
            String key = Portfolio.holdingKey(holding);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        return new Portfolio.Check("NO_DUPLICATE_ISIN_FOLIO", duplicates.isEmpty(),
                duplicates.isEmpty() ? "Every ISIN and folio pair is unique." : "Holding read more than once.");
    }

    private static String describe(String flag, Portfolio.Holding holding) {
        String name = holding.schemeName != null ? holding.schemeName : holding.isin;
        return switch (flag) {
            case "MISSING_REQUIRED" -> name + ": ISIN or scheme name could not be read.";
            case "ISIN_INVALID" -> name + ": ISIN fails the check-digit test.";
            case "NAV_WITHOUT_DATE" -> name + ": NAV is printed without a NAV date.";
            case "VALUE_WITHOUT_UNITS" -> name + ": a value is printed but no unit balance was found.";
            case "UNITS_NAV_VALUE_MISMATCH" -> name + ": units × NAV does not match the printed market value.";
            case "UNIT_BALANCE_MISMATCH" -> name + ": opening units plus transactions do not equal the closing unit balance.";
            default -> name + ": " + flag;
        };
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
