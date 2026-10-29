package com.prosperr.cas;

import com.prosperr.cas.model.Portfolio;
import com.prosperr.cas.store.StatementStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresStoreTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/cas";

    @Test
    void parsedStatementRoundTripsThroughPostgres() throws Exception {
        StatementStore store = StatementStore.fromEnvironment();
        byte[] pdf = PostgresStoreTest.class.getResourceAsStream("/fixtures/cams.pdf").readAllBytes();
        CasAnalyzer.Analysis analysis = CasAnalyzer.analyze(pdf, null, "cams.pdf");
        StatementStore.Stored stored = store.save(analysis);
        try {
            StatementStore.Stored loaded = store.get(stored.id);
            assertNotNull(loaded);
            Portfolio portfolio = loaded.analysis.portfolio();
            assertEquals("CAMS_KFINTECH", portfolio.source.format);
            assertEquals("PRIYA KAPOOR", portfolio.investor.name);
            assertEquals("2024-12-31", portfolio.source.statementAsOf);
            assertEquals(4, portfolio.holdings.size());
            assertEquals("INF879O01027", portfolio.holdings.get(3).isin);
            assertEquals("13579246", portfolio.holdings.get(3).folio);
            assertEquals(0, portfolio.holdings.get(3).marketValue.compareTo(new BigDecimal("370196.77")));
            assertEquals("3,70,196.77", portfolio.holdings.get(3).raw.get("marketValue"));
            assertEquals(4, portfolio.holdings.get(0).transactions.size());
            assertEquals("ok", portfolio.integrity.status);
            assertEquals(0, loaded.analysis.insights().summary.totalMarketValue.compareTo(new BigDecimal("2198563.24")));
            assertEquals("Concentrated", loaded.analysis.insights().profile.diversification);
        } finally {
            store.delete(stored.id);
        }

        try (Connection connection = DriverManager.getConnection(URL, "cas", "cas");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT total_market_value::text FROM cas_insight")) {
            Set<String> totals = new HashSet<>();
            while (rows.next()) {
                totals.add(new BigDecimal(rows.getString(1)).stripTrailingZeros().toPlainString());
            }
            assertTrue(totals.contains("559852.14"), totals.toString());
            assertTrue(totals.contains("607976.54"), totals.toString());
            assertTrue(totals.contains("2198563.24"), totals.toString());
        }
    }
}
