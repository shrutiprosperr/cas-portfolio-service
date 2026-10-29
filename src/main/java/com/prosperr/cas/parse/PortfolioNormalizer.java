package com.prosperr.cas.parse;

import com.prosperr.cas.model.Portfolio;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Trim text, mark zero-unit rows redeemed, and split a printed title. Nothing is guessed. */
public final class PortfolioNormalizer {
    private static final Pattern TITLE = Pattern.compile("^(MRS|MR|MS|MISS|DR|SHRI|SMT|KUMARI|KUM)\\.?\\s+(?=\\S)", Pattern.CASE_INSENSITIVE);

    private PortfolioNormalizer() {}

    public static Portfolio normalize(Portfolio portfolio) {
        for (Portfolio.Holding holding : portfolio.holdings) {
            holding.schemeName = tidy(holding.schemeName);
            holding.amc = tidy(holding.amc);
            holding.folio = tidy(holding.folio);
            holding.status = holding.units != null && holding.units.compareTo(BigDecimal.ZERO) == 0 ? "redeemed" : "active";
        }
        Set<String> pans = new LinkedHashSet<>();
        if (portfolio.investor.pan != null) {
            pans.add(portfolio.investor.pan);
        }
        for (Portfolio.Holding holding : portfolio.holdings) {
            if (holding.pan != null) {
                pans.add(holding.pan);
            }
        }
        portfolio.investor.pans.addAll(pans);
        if (portfolio.investor.pan == null && !portfolio.investor.pans.isEmpty()) {
            portfolio.investor.pan = portfolio.investor.pans.get(0);
        }
        portfolio.investor.name = tidy(portfolio.investor.name);
        if (portfolio.investor.name != null) {
            Matcher title = TITLE.matcher(portfolio.investor.name);
            if (title.find()) {
                portfolio.investor.title = title.group(1).toUpperCase();
                portfolio.investor.name = portfolio.investor.name.substring(title.end());
            }
        }
        return portfolio;
    }

    private static String tidy(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
