package com.prosperr.cas.insight;

import com.prosperr.cas.model.Portfolio;
import com.prosperr.cas.parse.PrintedValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Facts computed from the portfolio record. Risk and horizon use the scheme name only when it
 * states the category plainly. An unclassified fund leaves those two empty.
 */
public final class PortfolioInsights {
    private PortfolioInsights() {}

    public static Result build(Portfolio portfolio) {
        Result result = new Result();
        result.summary = summary(portfolio);
        result.profile = profile(portfolio);
        return result;
    }

    private static Summary summary(Portfolio portfolio) {
        List<Portfolio.Holding> active = portfolio.holdings.stream().filter(h -> "active".equals(h.status)).toList();
        Long market = sum(active.stream().map(h -> h.raw.get("marketValue")).toList());
        List<Portfolio.Holding> withCost = active.stream()
                .filter(h -> PrintedValues.toPaise(h.raw.get("costValue")) != null && PrintedValues.toPaise(h.raw.get("marketValue")) != null)
                .toList();
        Long cost = withCost.isEmpty() ? null : sum(withCost.stream().map(h -> h.raw.get("costValue")).toList());
        Long costedMarket = withCost.isEmpty() ? null : sum(withCost.stream().map(h -> h.raw.get("marketValue")).toList());
        Summary summary = new Summary();
        summary.activeCount = active.size();
        summary.redeemedCount = portfolio.holdings.size() - active.size();
        summary.totalMarketValue = rupees(market);
        summary.totalCostValue = rupees(cost);
        summary.unrealisedGain = market == null || cost == null ? null : rupees(costedMarket - cost);
        summary.holdingsWithoutCost = active.size() - withCost.size();
        Map<String, Long> byAmc = new LinkedHashMap<>();
        for (Portfolio.Holding holding : active) {
            Long paise = PrintedValues.toPaise(holding.raw.get("marketValue"));
            if (paise == null) {
                continue;
            }
            String amc = holding.amc == null ? "Unspecified AMC" : holding.amc;
            byAmc.merge(amc, paise, Long::sum);
        }
        long allocated = byAmc.values().stream().mapToLong(Long::longValue).sum();
        summary.byAmc = new ArrayList<>();
        byAmc.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> {
                    AmcShare share = new AmcShare();
                    share.amc = entry.getKey();
                    share.marketValue = rupees(entry.getValue());
                    share.weightPct = allocated == 0 ? BigDecimal.ZERO : percent(entry.getValue(), allocated);
                    summary.byAmc.add(share);
                });
        summary.holdings = new ArrayList<>();
        for (Portfolio.Holding holding : active) {
            Long paise = PrintedValues.toPaise(holding.raw.get("marketValue"));
            HoldingWeight weight = new HoldingWeight();
            weight.holdingKey = Portfolio.holdingKey(holding);
            weight.schemeName = holding.schemeName;
            weight.isin = holding.isin;
            weight.marketValue = rupees(paise);
            weight.weightPct = paise == null || market == null || market == 0 ? null : percent(paise, market);
            summary.holdings.add(weight);
        }
        summary.holdings.sort(Comparator.comparing((HoldingWeight row) -> row.marketValue == null ? BigDecimal.ZERO : row.marketValue).reversed());
        return summary;
    }

    private static Profile profile(Portfolio portfolio) {
        Map<String, Fund> funds = new LinkedHashMap<>();
        for (Portfolio.Holding holding : portfolio.holdings) {
            if (!"active".equals(holding.status)) {
                continue;
            }
            Long paise = PrintedValues.toPaise(holding.raw.get("marketValue"));
            if (paise == null) {
                continue;
            }
            String key = holding.isin == null ? holding.schemeName : holding.isin;
            Fund fund = funds.computeIfAbsent(key, ignored -> new Fund(holding.schemeName, holding.isin, category(holding.schemeName)));
            fund.paise += paise;
        }
        List<Fund> list = new ArrayList<>(funds.values());
        long total = list.stream().mapToLong(fund -> fund.paise).sum();
        Profile profile = new Profile();
        profile.diversification = diversification(list, total);
        List<String> unclassified = list.stream().filter(fund -> fund.category == null).map(fund -> fund.name).toList();
        profile.unclassified = unclassified;
        if (!list.isEmpty() && unclassified.isEmpty()) {
            long weightedFactor = 0;
            long weightedYears = 0;
            for (Fund fund : list) {
                weightedFactor += fund.paise * equityFactor(fund.category);
                weightedYears += fund.paise * yearsTimes100(fund.category);
            }
            profile.risk = band(weightedFactor, total, new int[] {80, 60, 40, 20, 0}, new String[] {
                    "Aggressive", "Moderately Aggressive", "Moderate", "Moderately Conservative", "Conservative"});
            profile.horizon = band(weightedYears, total * 100L, new int[] {5, 3, 1, 0}, new String[] {
                    "Long Term", "Mid to Long Term", "Mid Term", "Short Term"});
        }
        return profile;
    }

    private static String diversification(List<Fund> funds, long total) {
        if (funds.isEmpty()) {
            return null;
        }
        int count = funds.size();
        boolean dominant = funds.stream().anyMatch(fund -> fund.paise * 2 >= total && total > 0);
        if (count == 1 || dominant) {
            return "Concentrated";
        }
        long fiveLakh = 50_000_000L;
        long twentyFiveLakh = 250_000_000L;
        long oneCrore = 1_000_000_000L;
        long fiveCrore = 5_000_000_000L;
        int min;
        int max;
        if (total < fiveLakh) {
            min = 2;
            max = 4;
        } else if (total < twentyFiveLakh) {
            min = 3;
            max = 6;
        } else if (total < oneCrore) {
            min = 4;
            max = 8;
        } else if (total <= fiveCrore) {
            min = 5;
            max = 10;
        } else {
            min = 6;
            max = 13;
        }
        if (count < min) {
            return "Under Diversified";
        }
        if (count > max) {
            return "Over Diversified";
        }
        return "Well Diversified";
    }

    private static String band(long weighted, long denominator, int[] thresholds, String[] labels) {
        for (int i = 0; i < thresholds.length; i++) {
            if (weighted >= (long) thresholds[i] * denominator) {
                return labels[i];
            }
        }
        return labels[labels.length - 1];
    }

    private static int equityFactor(String category) {
        return switch (category) {
            case "equity", "midCap", "smallCap" -> 100;
            case "aggressiveHybrid" -> 70;
            case "balancedHybrid", "balancedAdvantage" -> 50;
            case "multiAsset" -> 40;
            case "equitySavings", "goldSilver" -> 30;
            case "conservativeHybrid" -> 20;
            default -> 0;
        };
    }

    private static int yearsTimes100(String category) {
        return switch (category) {
            case "overnight", "liquid" -> 25;
            case "ultraShort", "lowDuration", "moneyMarket", "floater", "arbitrage" -> 75;
            case "shortDuration", "corporateBond", "bankingPsu" -> 200;
            case "conservativeHybrid", "equitySavings" -> 250;
            case "mediumDuration", "creditRisk", "dynamicBond" -> 350;
            case "balancedHybrid", "balancedAdvantage", "multiAsset", "goldSilver" -> 400;
            case "gilt", "mediumToLong" -> 450;
            case "aggressiveHybrid" -> 500;
            case "longDuration", "equity" -> 700;
            case "midCap" -> 800;
            case "smallCap" -> 1000;
            default -> 0;
        };
    }

    private static String category(String schemeName) {
        String text = schemeName == null ? "" : schemeName.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (text.isBlank()) {
            return null;
        }
        Rule[] rules = {
                new Rule(null, "retirement|children|\\bchild\\b"),
                new Rule("arbitrage", "arbitrage"),
                new Rule("equitySavings", "equity\\s*savings"),
                new Rule("liquid", "\\bliquid\\b"),
                new Rule("equity", "\\belss\\b|tax[\\s-]*sav|flexi[\\s-]*cap|multi[\\s-]*cap|large[\\s-]*cap|\\bindex\\b|\\betf\\b"),
                new Rule("smallCap", "small[\\s-]*cap"),
                new Rule("midCap", "mid[\\s-]*cap"),
                new Rule("goldSilver", "\\bgold\\b|\\bsilver\\b")
        };
        for (Rule rule : rules) {
            if (Pattern.compile(rule.pattern).matcher(text).find()) {
                return rule.category;
            }
        }
        return null;
    }

    private static Long sum(List<String> raws) {
        if (raws.isEmpty()) {
            return null;
        }
        long total = 0;
        for (String raw : raws) {
            Long paise = PrintedValues.toPaise(raw);
            if (paise == null) {
                return null;
            }
            total += paise;
        }
        return total;
    }

    private static BigDecimal rupees(Long paise) {
        return paise == null ? null : BigDecimal.valueOf(paise, 2);
    }

    private static BigDecimal percent(long part, long total) {
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private record Rule(String category, String pattern) {}

    private static final class Fund {
        final String name;
        final String isin;
        final String category;
        long paise;

        Fund(String name, String isin, String category) {
            this.name = name;
            this.isin = isin;
            this.category = category;
        }
    }

    public static final class Result {
        public Summary summary;
        public Profile profile;
    }

    public static final class Summary {
        public int activeCount;
        public int redeemedCount;
        public BigDecimal totalMarketValue;
        public BigDecimal totalCostValue;
        public BigDecimal unrealisedGain;
        public int holdingsWithoutCost;
        public List<AmcShare> byAmc;
        public List<HoldingWeight> holdings;
    }

    public static final class AmcShare {
        public String amc;
        public BigDecimal marketValue;
        public BigDecimal weightPct;
    }

    public static final class HoldingWeight {
        public String holdingKey;
        public String schemeName;
        public String isin;
        public BigDecimal marketValue;
        public BigDecimal weightPct;
    }

    public static final class Profile {
        public String diversification;
        public String risk;
        public String horizon;
        public List<String> unclassified = List.of();
    }
}
