package com.prosperr.cas.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The portfolio record. Every reader fills this shape. A PDF fills it today.
 * A later source, including Tarakki, can fill the same fields and leave the ones it does not have empty.
 */
public final class Portfolio {
    public Source source = new Source();
    public Investor investor = new Investor();
    public List<Holding> holdings = new ArrayList<>();
    public PrintedTotals printedTotals = new PrintedTotals();
    public Integrity integrity = new Integrity();

    public static final class Source {
        public String format;
        public String statementAsOf;
        public Period statementPeriod = new Period();
        public String rawFileName;
    }

    public static final class Period {
        public String from;
        public String to;
    }

    public static final class Investor {
        public String name;
        public String title;
        public String pan;
        public List<String> pans = new ArrayList<>();
        public String email;
        public String address;
    }

    public static final class Holding {
        public String isin;
        public String schemeName;
        public String rtaSchemeCode;
        public String amc;
        public String folio;
        public String pan;
        public String registrar;
        public String status = "active";
        public BigDecimal openingUnits;
        public BigDecimal units;
        public BigDecimal nav;
        public String navDate;
        public BigDecimal costValue;
        public BigDecimal marketValue;
        public BigDecimal averageCost;
        public BigDecimal printedGain;
        public Map<String, String> raw = new LinkedHashMap<>();
        public List<Transaction> transactions = new ArrayList<>();
        public String category;
        public String assetClass;
        public List<String> flags = new ArrayList<>();
    }

    public static final class Transaction {
        public String date;
        public String description;
        public BigDecimal amount;
        public BigDecimal units;
        public BigDecimal price;
        public BigDecimal unitBalance;
        public Map<String, String> raw = new LinkedHashMap<>();
    }

    public static final class PrintedTotals {
        public BigDecimal totalCostValue;
        public BigDecimal totalMarketValue;
        public Map<String, String> raw = new LinkedHashMap<>();
        public List<AmcTotal> byAmc = new ArrayList<>();
    }

    public static final class AmcTotal {
        public String amc;
        public BigDecimal costValue;
        public BigDecimal marketValue;
        public Map<String, String> raw = new LinkedHashMap<>();
    }

    public static final class Integrity {
        public String status = "ok";
        public List<Check> checks = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    public static final class Check {
        public String name;
        public boolean passed;
        public String detail;

        public Check() {}

        public Check(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }
    }

    public static String holdingKey(Holding holding) {
        return (holding.isin == null ? "" : holding.isin) + "|" + (holding.folio == null ? "" : holding.folio);
    }
}
