package com.prosperr.cas.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prosperr.cas.CasAnalyzer;
import com.prosperr.cas.insight.PortfolioInsights;
import com.prosperr.cas.model.Portfolio;
import org.postgresql.util.PGobject;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Postgres store. The most recently saved statement is the current one.
 * The PDF and the password are not stored. Existing JSON files under data/ are copied in once, when the database is empty.
 */
public final class StatementStore {
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {};

    private final String url;
    private final String user;
    private final String password;
    private final ObjectMapper mapper;

    public StatementStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try (Connection connection = open()) {
            for (String sql : schemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            if (count(connection) == 0) {
                importJson(connection, Path.of(System.getenv().getOrDefault("CAS_DATA_DIR", "data")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Postgres is not available at " + url + ". Start it with: docker compose up -d", exception);
        }
    }

    public static StatementStore fromEnvironment() {
        return new StatementStore(
                System.getenv().getOrDefault("CAS_JDBC_URL", "jdbc:postgresql://localhost:5432/cas"),
                System.getenv().getOrDefault("CAS_DB_USER", "cas"),
                System.getenv().getOrDefault("CAS_DB_PASSWORD", "cas"));
    }

    public Stored save(CasAnalyzer.Analysis analysis) {
        Stored stored = new Stored();
        stored.id = UUID.randomUUID().toString();
        stored.createdAt = Instant.now().toString();
        stored.statementAsOf = analysis.portfolio().source.statementAsOf;
        stored.analysis = analysis;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                insert(connection, stored);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("The parsed statement could not be saved.", exception);
        }
        return stored;
    }

    public Stored current() {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id::text FROM cas_statement ORDER BY created_at DESC LIMIT 1");
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return null;
            }
            return load(connection, rows.getString(1));
        } catch (SQLException exception) {
            throw new IllegalStateException("Saved statements could not be read.", exception);
        }
    }

    public Stored get(String id) {
        try (Connection connection = open()) {
            return load(connection, id);
        } catch (SQLException exception) {
            throw new IllegalStateException("Saved statements could not be read.", exception);
        }
    }

    public void delete(String id) {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM cas_statement WHERE id = ?")) {
            statement.setObject(1, UUID.fromString(id));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("The statement could not be deleted.", exception);
        }
    }

    private void importJson(Connection connection, Path directory) throws SQLException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                Stored stored = mapper.readValue(path.toFile(), Stored.class);
                connection.setAutoCommit(false);
                try {
                    insert(connection, stored);
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw new SQLException("Could not import " + path.getFileName(), exception);
                }
            }
        } catch (IOException exception) {
            throw new SQLException("Saved JSON statements could not be imported.", exception);
        }
    }

    private void insert(Connection connection, Stored stored) throws SQLException {
        Portfolio portfolio = stored.analysis.portfolio();
        PortfolioInsights.Result insights = stored.analysis.insights();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO cas_statement (
                    id, created_at, statement_as_of, period_from, period_to, format, file_name,
                    investor_name, investor_title, investor_pan, investor_email, investor_address,
                    integrity_status, total_cost_value, total_market_value, printed_raw)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setObject(1, UUID.fromString(stored.id));
            statement.setTimestamp(2, Timestamp.from(Instant.parse(stored.createdAt)));
            setDate(statement, 3, portfolio.source.statementAsOf);
            setDate(statement, 4, portfolio.source.statementPeriod.from);
            setDate(statement, 5, portfolio.source.statementPeriod.to);
            statement.setString(6, portfolio.source.format);
            statement.setString(7, portfolio.source.rawFileName);
            statement.setString(8, portfolio.investor.name);
            statement.setString(9, portfolio.investor.title);
            statement.setString(10, portfolio.investor.pan);
            statement.setString(11, portfolio.investor.email);
            statement.setString(12, portfolio.investor.address);
            statement.setString(13, portfolio.integrity.status);
            setDecimal(statement, 14, portfolio.printedTotals.totalCostValue);
            setDecimal(statement, 15, portfolio.printedTotals.totalMarketValue);
            setJson(statement, 16, portfolio.printedTotals.raw);
            statement.executeUpdate();
        }
        insertPans(connection, stored.id, portfolio.investor.pans);
        for (int i = 0; i < portfolio.holdings.size(); i++) {
            insertHolding(connection, stored.id, i, portfolio.holdings.get(i));
        }
        insertAmcTotals(connection, stored.id, portfolio.printedTotals.byAmc);
        insertChecks(connection, stored.id, portfolio.integrity.checks);
        insertWarnings(connection, stored.id, portfolio.integrity.warnings);
        insertInsight(connection, stored.id, insights);
    }

    private void insertPans(Connection connection, String statementId, List<String> pans) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_investor_pan (statement_id, position, pan) VALUES (?,?,?)")) {
            for (int i = 0; i < pans.size(); i++) {
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, pans.get(i));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertHolding(Connection connection, String statementId, int position, Portfolio.Holding holding)
            throws SQLException {
        long holdingId;
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO cas_holding (
                    statement_id, position, holding_key, isin, folio, scheme_name, rta_scheme_code, amc, pan, registrar, status,
                    opening_units, units, nav, nav_date, cost_value, market_value, average_cost, printed_gain, category, asset_class, raw)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setObject(1, UUID.fromString(statementId));
            statement.setInt(2, position);
            statement.setString(3, Portfolio.holdingKey(holding));
            statement.setString(4, holding.isin);
            statement.setString(5, holding.folio);
            statement.setString(6, holding.schemeName);
            statement.setString(7, holding.rtaSchemeCode);
            statement.setString(8, holding.amc);
            statement.setString(9, holding.pan);
            statement.setString(10, holding.registrar);
            statement.setString(11, holding.status);
            setDecimal(statement, 12, holding.openingUnits);
            setDecimal(statement, 13, holding.units);
            setDecimal(statement, 14, holding.nav);
            setDate(statement, 15, holding.navDate);
            setDecimal(statement, 16, holding.costValue);
            setDecimal(statement, 17, holding.marketValue);
            setDecimal(statement, 18, holding.averageCost);
            setDecimal(statement, 19, holding.printedGain);
            statement.setString(20, holding.category);
            statement.setString(21, holding.assetClass);
            setJson(statement, 22, holding.raw);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                holdingId = keys.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_holding_flag (holding_id, position, flag) VALUES (?,?,?)")) {
            for (int i = 0; i < holding.flags.size(); i++) {
                statement.setLong(1, holdingId);
                statement.setInt(2, i);
                statement.setString(3, holding.flags.get(i));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO cas_transaction (holding_id, position, txn_date, description, amount, units, price, unit_balance, raw)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            for (int i = 0; i < holding.transactions.size(); i++) {
                Portfolio.Transaction transaction = holding.transactions.get(i);
                statement.setLong(1, holdingId);
                statement.setInt(2, i);
                setDate(statement, 3, transaction.date);
                statement.setString(4, transaction.description);
                setDecimal(statement, 5, transaction.amount);
                setDecimal(statement, 6, transaction.units);
                setDecimal(statement, 7, transaction.price);
                setDecimal(statement, 8, transaction.unitBalance);
                setJson(statement, 9, transaction.raw);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAmcTotals(Connection connection, String statementId, List<Portfolio.AmcTotal> totals)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_amc_total (statement_id, position, amc, cost_value, market_value, raw) VALUES (?,?,?,?,?,?)")) {
            for (int i = 0; i < totals.size(); i++) {
                Portfolio.AmcTotal total = totals.get(i);
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, total.amc);
                setDecimal(statement, 4, total.costValue);
                setDecimal(statement, 5, total.marketValue);
                setJson(statement, 6, total.raw);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertChecks(Connection connection, String statementId, List<Portfolio.Check> checks) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_check (statement_id, position, name, passed, detail) VALUES (?,?,?,?,?)")) {
            for (int i = 0; i < checks.size(); i++) {
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, checks.get(i).name);
                statement.setBoolean(4, checks.get(i).passed);
                statement.setString(5, checks.get(i).detail);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertWarnings(Connection connection, String statementId, List<String> warnings) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_warning (statement_id, position, message) VALUES (?,?,?)")) {
            for (int i = 0; i < warnings.size(); i++) {
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, warnings.get(i));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertInsight(Connection connection, String statementId, PortfolioInsights.Result insights) throws SQLException {
        PortfolioInsights.Summary summary = insights.summary;
        PortfolioInsights.Profile profile = insights.profile;
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO cas_insight (
                    statement_id, active_count, redeemed_count, total_market_value, total_cost_value, unrealised_gain,
                    holdings_without_cost, diversification, risk, horizon)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setObject(1, UUID.fromString(statementId));
            statement.setInt(2, summary.activeCount);
            statement.setInt(3, summary.redeemedCount);
            setDecimal(statement, 4, summary.totalMarketValue);
            setDecimal(statement, 5, summary.totalCostValue);
            setDecimal(statement, 6, summary.unrealisedGain);
            statement.setInt(7, summary.holdingsWithoutCost);
            statement.setString(8, profile.diversification);
            statement.setString(9, profile.risk);
            statement.setString(10, profile.horizon);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_insight_amc (statement_id, position, amc, market_value, weight_pct) VALUES (?,?,?,?,?)")) {
            for (int i = 0; i < summary.byAmc.size(); i++) {
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, summary.byAmc.get(i).amc);
                setDecimal(statement, 4, summary.byAmc.get(i).marketValue);
                setDecimal(statement, 5, summary.byAmc.get(i).weightPct);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO cas_insight_weight (statement_id, position, holding_key, scheme_name, isin, market_value, weight_pct)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            for (int i = 0; i < summary.holdings.size(); i++) {
                PortfolioInsights.HoldingWeight weight = summary.holdings.get(i);
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, weight.holdingKey);
                statement.setString(4, weight.schemeName);
                statement.setString(5, weight.isin);
                setDecimal(statement, 6, weight.marketValue);
                setDecimal(statement, 7, weight.weightPct);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        List<String> unclassified = profile.unclassified == null ? List.of() : profile.unclassified;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cas_insight_unclassified (statement_id, position, scheme_name) VALUES (?,?,?)")) {
            for (int i = 0; i < unclassified.size(); i++) {
                statement.setObject(1, UUID.fromString(statementId));
                statement.setInt(2, i);
                statement.setString(3, unclassified.get(i));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Stored load(Connection connection, String id) throws SQLException {
        Stored stored = new Stored();
        Portfolio portfolio = new Portfolio();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT created_at, statement_as_of, period_from, period_to, format, file_name,
                       investor_name, investor_title, investor_pan, investor_email, investor_address,
                       integrity_status, total_cost_value, total_market_value, printed_raw::text
                FROM cas_statement WHERE id = ?
                """)) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                stored.id = id;
                stored.createdAt = row.getTimestamp(1).toInstant().toString();
                portfolio.source.statementAsOf = date(row, 2);
                portfolio.source.statementPeriod.from = date(row, 3);
                portfolio.source.statementPeriod.to = date(row, 4);
                portfolio.source.format = row.getString(5);
                portfolio.source.rawFileName = row.getString(6);
                portfolio.investor.name = row.getString(7);
                portfolio.investor.title = row.getString(8);
                portfolio.investor.pan = row.getString(9);
                portfolio.investor.email = row.getString(10);
                portfolio.investor.address = row.getString(11);
                portfolio.integrity.status = row.getString(12);
                portfolio.printedTotals.totalCostValue = row.getBigDecimal(13);
                portfolio.printedTotals.totalMarketValue = row.getBigDecimal(14);
                portfolio.printedTotals.raw = jsonMap(row.getString(15));
            }
        }
        stored.statementAsOf = portfolio.source.statementAsOf;
        portfolio.investor.pans = pans(connection, id);
        portfolio.holdings = holdings(connection, id);
        portfolio.printedTotals.byAmc = amcTotals(connection, id);
        portfolio.integrity.checks = checks(connection, id);
        portfolio.integrity.warnings = warnings(connection, id);
        stored.analysis = new CasAnalyzer.Analysis(portfolio, insight(connection, id));
        return stored;
    }

    private List<String> pans(Connection connection, String id) throws SQLException {
        List<String> pans = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pan FROM cas_investor_pan WHERE statement_id = ? ORDER BY position")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    pans.add(rows.getString(1));
                }
            }
        }
        return pans;
    }

    private List<Portfolio.Holding> holdings(Connection connection, String id) throws SQLException {
        List<Portfolio.Holding> holdings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT id, holding_key, isin, folio, scheme_name, rta_scheme_code, amc, pan, registrar, status,
                       opening_units, units, nav, nav_date, cost_value, market_value, average_cost, printed_gain,
                       category, asset_class, raw::text
                FROM cas_holding WHERE statement_id = ? ORDER BY position
                """)) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Portfolio.Holding holding = new Portfolio.Holding();
                    long holdingId = rows.getLong(1);
                    holding.isin = rows.getString(3);
                    holding.folio = rows.getString(4);
                    holding.schemeName = rows.getString(5);
                    holding.rtaSchemeCode = rows.getString(6);
                    holding.amc = rows.getString(7);
                    holding.pan = rows.getString(8);
                    holding.registrar = rows.getString(9);
                    holding.status = rows.getString(10);
                    holding.openingUnits = rows.getBigDecimal(11);
                    holding.units = rows.getBigDecimal(12);
                    holding.nav = rows.getBigDecimal(13);
                    holding.navDate = date(rows, 14);
                    holding.costValue = rows.getBigDecimal(15);
                    holding.marketValue = rows.getBigDecimal(16);
                    holding.averageCost = rows.getBigDecimal(17);
                    holding.printedGain = rows.getBigDecimal(18);
                    holding.category = rows.getString(19);
                    holding.assetClass = rows.getString(20);
                    holding.raw = jsonMap(rows.getString(21));
                    holding.flags = flags(connection, holdingId);
                    holding.transactions = transactions(connection, holdingId);
                    holdings.add(holding);
                }
            }
        }
        return holdings;
    }

    private List<String> flags(Connection connection, long holdingId) throws SQLException {
        List<String> flags = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT flag FROM cas_holding_flag WHERE holding_id = ? ORDER BY position")) {
            statement.setLong(1, holdingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    flags.add(rows.getString(1));
                }
            }
        }
        return flags;
    }

    private List<Portfolio.Transaction> transactions(Connection connection, long holdingId) throws SQLException {
        List<Portfolio.Transaction> transactions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT txn_date, description, amount, units, price, unit_balance, raw::text
                FROM cas_transaction WHERE holding_id = ? ORDER BY position
                """)) {
            statement.setLong(1, holdingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Portfolio.Transaction transaction = new Portfolio.Transaction();
                    transaction.date = date(rows, 1);
                    transaction.description = rows.getString(2);
                    transaction.amount = rows.getBigDecimal(3);
                    transaction.units = rows.getBigDecimal(4);
                    transaction.price = rows.getBigDecimal(5);
                    transaction.unitBalance = rows.getBigDecimal(6);
                    transaction.raw = jsonMap(rows.getString(7));
                    transactions.add(transaction);
                }
            }
        }
        return transactions;
    }

    private List<Portfolio.AmcTotal> amcTotals(Connection connection, String id) throws SQLException {
        List<Portfolio.AmcTotal> totals = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT amc, cost_value, market_value, raw::text
                FROM cas_amc_total WHERE statement_id = ? ORDER BY position
                """)) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Portfolio.AmcTotal total = new Portfolio.AmcTotal();
                    total.amc = rows.getString(1);
                    total.costValue = rows.getBigDecimal(2);
                    total.marketValue = rows.getBigDecimal(3);
                    total.raw = jsonMap(rows.getString(4));
                    totals.add(total);
                }
            }
        }
        return totals;
    }

    private List<Portfolio.Check> checks(Connection connection, String id) throws SQLException {
        List<Portfolio.Check> checks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, passed, detail FROM cas_check WHERE statement_id = ? ORDER BY position")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    checks.add(new Portfolio.Check(rows.getString(1), rows.getBoolean(2), rows.getString(3)));
                }
            }
        }
        return checks;
    }

    private List<String> warnings(Connection connection, String id) throws SQLException {
        List<String> warnings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT message FROM cas_warning WHERE statement_id = ? ORDER BY position")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    warnings.add(rows.getString(1));
                }
            }
        }
        return warnings;
    }

    private PortfolioInsights.Result insight(Connection connection, String id) throws SQLException {
        PortfolioInsights.Result result = new PortfolioInsights.Result();
        PortfolioInsights.Summary summary = new PortfolioInsights.Summary();
        PortfolioInsights.Profile profile = new PortfolioInsights.Profile();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT active_count, redeemed_count, total_market_value, total_cost_value, unrealised_gain,
                       holdings_without_cost, diversification, risk, horizon
                FROM cas_insight WHERE statement_id = ?
                """)) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    summary.activeCount = row.getInt(1);
                    summary.redeemedCount = row.getInt(2);
                    summary.totalMarketValue = row.getBigDecimal(3);
                    summary.totalCostValue = row.getBigDecimal(4);
                    summary.unrealisedGain = row.getBigDecimal(5);
                    summary.holdingsWithoutCost = row.getInt(6);
                    profile.diversification = row.getString(7);
                    profile.risk = row.getString(8);
                    profile.horizon = row.getString(9);
                }
            }
        }
        summary.byAmc = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amc, market_value, weight_pct FROM cas_insight_amc WHERE statement_id = ? ORDER BY position")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    PortfolioInsights.AmcShare share = new PortfolioInsights.AmcShare();
                    share.amc = rows.getString(1);
                    share.marketValue = rows.getBigDecimal(2);
                    share.weightPct = rows.getBigDecimal(3);
                    summary.byAmc.add(share);
                }
            }
        }
        summary.holdings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT holding_key, scheme_name, isin, market_value, weight_pct
                FROM cas_insight_weight WHERE statement_id = ? ORDER BY position
                """)) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    PortfolioInsights.HoldingWeight weight = new PortfolioInsights.HoldingWeight();
                    weight.holdingKey = rows.getString(1);
                    weight.schemeName = rows.getString(2);
                    weight.isin = rows.getString(3);
                    weight.marketValue = rows.getBigDecimal(4);
                    weight.weightPct = rows.getBigDecimal(5);
                    summary.holdings.add(weight);
                }
            }
        }
        List<String> unclassified = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT scheme_name FROM cas_insight_unclassified WHERE statement_id = ? ORDER BY position")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    unclassified.add(rows.getString(1));
                }
            }
        }
        profile.unclassified = unclassified;
        result.summary = summary;
        result.profile = profile;
        return result;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM cas_statement")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private List<String> schemaStatements() {
        try (var resource = StatementStore.class.getResourceAsStream("/db/schema.sql")) {
            if (resource == null) {
                throw new IllegalStateException("The database schema is missing.");
            }
            String script = new String(resource.readAllBytes()).replaceAll("(?m)^--.*(?:\\R|$)", "");
            List<String> statements = new ArrayList<>();
            for (String part : script.split(";")) {
                if (!part.isBlank()) {
                    statements.add(part.trim());
                }
            }
            return statements;
        } catch (IOException exception) {
            throw new IllegalStateException("The database schema could not be read.", exception);
        }
    }

    private void setDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }

    private void setDate(PreparedStatement statement, int index, String iso) throws SQLException {
        if (iso == null || iso.isBlank()) {
            statement.setNull(index, Types.DATE);
        } else {
            statement.setDate(index, Date.valueOf(LocalDate.parse(iso)));
        }
    }

    private void setJson(PreparedStatement statement, int index, Map<String, String> raw) throws SQLException {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(mapper.writeValueAsString(raw == null ? Map.of() : raw));
            statement.setObject(index, object);
        } catch (IOException exception) {
            throw new SQLException("A printed value could not be stored.", exception);
        }
    }

    private LinkedHashMap<String, String> jsonMap(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (IOException exception) {
            throw new SQLException("A printed value could not be read.", exception);
        }
    }

    private static String date(ResultSet row, int index) throws SQLException {
        Date value = row.getDate(index);
        return value == null ? null : value.toLocalDate().toString();
    }

    public static final class Stored {
        public String id;
        public String createdAt;
        public String statementAsOf;
        public CasAnalyzer.Analysis analysis;
    }
}
