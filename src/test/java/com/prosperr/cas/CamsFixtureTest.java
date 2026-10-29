package com.prosperr.cas;

import com.prosperr.cas.model.Portfolio;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamsFixtureTest {
    @Test
    void sampleCamsMatchesThePrintedStatement() throws Exception {
        byte[] pdf = CamsFixtureTest.class.getResourceAsStream("/fixtures/cams.pdf").readAllBytes();
        CasAnalyzer.Analysis analysis = CasAnalyzer.analyze(pdf, null, "cams.pdf");
        Portfolio portfolio = analysis.portfolio();

        assertEquals("CAMS_KFINTECH", portfolio.source.format);
        assertEquals("PRIYA KAPOOR", portfolio.investor.name);
        assertEquals("priya.k@example.com", portfolio.investor.email);
        assertEquals("ABCDE1234F", portfolio.investor.pans.get(0));
        assertEquals("17,58,400.00", portfolio.printedTotals.raw.get("totalCostValue"));
        assertEquals("21,98,563.24", portfolio.printedTotals.raw.get("totalMarketValue"));
        assertEquals(4, portfolio.holdings.size());

        expect(portfolio.holdings.get(0), "INF846K01EW2", "Axis ELSS Tax Saver Fund - Direct Growth", "9104567 / 0", "105.000", "96.0000", "8,400.00", "10,080.00", 4);
        expect(portfolio.holdings.get(1), "INF179K01VQ4", "HDFC Flexi Cap Fund - Direct Plan - Growth Option (formerly HDFC Equity Fund)", "1122334 / 55", "1,003.278", "1,812.3456", "15,00,000.00", "18,18,286.47", 1);
        expect(portfolio.holdings.get(2), "INF179KB1HP9", "HDFC Liquid Fund - Direct Plan - Growth", "1122334 / 55", "0.000", "4,850.1234", "0.00", "0.00", 1);
        expect(portfolio.holdings.get(3), "INF879O01027", "Parag Parikh Flexi Cap Fund - Direct Plan Growth", "13579246", "4,321.987", "85.6543", "2,50,000.00", "3,70,196.77", 0);

        assertEquals("redeemed", portfolio.holdings.get(2).status);
        assertEquals("ok", portfolio.integrity.status, portfolio.integrity.warnings.toString());
        assertEquals(new BigDecimal("2198563.24"), analysis.insights().summary.totalMarketValue);
        assertEquals("Concentrated", analysis.insights().profile.diversification);
        assertEquals("Aggressive", analysis.insights().profile.risk);
        assertEquals("Long Term", analysis.insights().profile.horizon);
    }

    private static void expect(Portfolio.Holding holding, String isin, String name, String folio, String units, String nav, String cost, String value, int transactions) {
        assertEquals(isin, holding.isin);
        assertEquals(name, holding.schemeName);
        assertEquals(folio, holding.folio);
        assertEquals(units, holding.raw.get("units"));
        assertEquals(nav, holding.raw.get("nav"));
        assertEquals(cost, holding.raw.get("costValue"));
        assertEquals(value, holding.raw.get("marketValue"));
        assertEquals(transactions, holding.transactions.size());
        assertTrue(holding.flags.isEmpty(), holding.flags.toString());
    }
}
