# CAS portfolio service — design

A local Java service reads a CAMS or KFintech statement, checks the printed numbers, computes a few facts, and stores the result in Postgres.

- Checked on about 15 CAMS and KFintech PDFs.
- The PDF is read as text. Labels are matched with fixed rules. The insights are arithmetic.
- No language model is used, and nothing is sent to an external model.
- Tarakki’s OTP flow stays separate. A later mapper can fill this same record from `dtSummary` and `dtTransaction`.

## Brought over from MFResearch_Engine

The reader and the insight rules are a Java port of that engine.

Kept as they are:

- The portfolio record, and the holding key `ISIN|folio`.
- The CAMS walk: AMC, then folio, then scheme.
- Label reads: `Closing Unit Balance`, `NAV on`, `Market Value`, `Cost Value`.
- Transaction columns taken from the table header.
- Paise checks. A mismatch is flagged, and the printed number is left as printed.
- Diversification, risk, and horizon bands.

What changed:

- The code is Java.
- Text is read with PDFBox. Overlapping lines stay separate runs, so an ISIN is not scrambled.

Not brought over:

- NSDL and CDSL readers.
- XIRR.
- The AMFI fund master.
- Category matching is the shorter name list in this document.

## Uploads

- Each upload is inserted as a new row.
- Older uploads stay in the database.
- The page and `GET /cas/current` show only the latest upload.

## Principles

- Every figure is the one printed on the statement. The original text is kept beside the parsed number.
- Money is reconciled in integer paise. A missing value stays empty.
- A failed check is flagged. The number is not corrected.
- One portfolio record is the centre. The CAMS reader fills it now. A Tarakki response can fill the same record later.
- A holding’s identity is `ISIN|folio`. The same fund in two folios stays two rows.

## Flow

1. The client uploads the PDF at `POST /cas`. A password, if the file has one, is sent with that request only.
2. PDFBox extracts the text with positions.
3. Format detection decides CAMS/KFintech, NSDL, or CDSL. NSDL and CDSL are recognised and rejected in this prototype. Their readers are not built yet.
4. The CAMS reader walks AMC, then folio, then scheme. Each scheme starts at an ISIN. Units, NAV, cost, and market value are read by the printed label (`Closing Unit Balance`, `NAV on`, `Market Value`, `Cost Value`). The transaction table uses the header’s own columns.
5. Zero closing units are marked redeemed. A printed title is split from the investor name.
6. Checks compare the holdings with the statement. The status is `ok` or `needs_review`.
7. Insights are computed from the active holdings.
8. The statement, holdings, transactions, checks, and insights are inserted in Postgres. Older uploads stay in the database. `GET /cas/current` and the page show only the latest upload.

## Database schema

- One upload is one `cas_statement` row. A later upload does not delete the earlier one.
- The page shows the row with the latest `created_at`.
- Holdings, transactions, checks, and insights belong to that statement. They go away only if that statement is deleted.
- Money is rupees (`numeric`). Dates are ISO dates.
- `raw` and `printed_raw` keep the text printed on the PDF.
- `position` is the order on the statement, starting at 0.
- `holding_key` is `ISIN|folio`. The same fund in two folios is two rows.

### `cas_statement`

One uploaded statement.

| Column | Meaning |
|---|---|
| `id` | Statement id |
| `created_at` | When this upload was saved. The newest row is the one the page shows |
| `statement_as_of` | Statement date printed on the CAS |
| `period_from`, `period_to` | Period printed on the CAS |
| `format` | `CAMS_KFINTECH` for a file this service reads |
| `file_name` | Original file name |
| `investor_name`, `investor_title` | Name and title printed on the statement |
| `investor_pan`, `investor_email`, `investor_address` | Contact details printed on the statement |
| `integrity_status` | `ok` or `needs_review` |
| `total_cost_value`, `total_market_value` | Portfolio totals printed on the statement, when that line was found |
| `printed_raw` | The same totals as printed text |

### `cas_investor_pan`

Every PAN seen on the statement. `cas_statement.investor_pan` is the first one.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order found |
| `pan` | PAN |

### `cas_holding`

One scheme in one folio.

| Column | Meaning |
|---|---|
| `id` | Holding id |
| `statement_id` | Parent statement |
| `position` | Order on the statement |
| `holding_key` | `ISIN\|folio` |
| `isin`, `folio`, `scheme_name` | Identity and name |
| `rta_scheme_code` | Registrar scheme code, when printed |
| `amc` | Fund house heading above the folio |
| `pan` | PAN printed on that folio |
| `registrar` | `CAMS` or `KFINTECH` |
| `status` | `active`, or `redeemed` when closing units are zero |
| `opening_units`, `units` | Opening balance and closing balance |
| `nav`, `nav_date` | NAV and the date printed with it |
| `cost_value`, `market_value` | Printed cost and market value |
| `average_cost`, `printed_gain` | Printed average cost and gain, when present |
| `category`, `asset_class` | Filled by a later category source. Today’s risk and horizon are computed from the scheme name and stored on `cas_insight` |
| `raw` | Printed text for units, NAV, cost, and market value |

### `cas_holding_flag`

A problem on one holding. The printed number is not changed.

| Column | Meaning |
|---|---|
| `holding_id` | Parent holding |
| `position` | Order of the flag |
| `flag` | `MISSING_REQUIRED`, `ISIN_INVALID`, `NAV_WITHOUT_DATE`, `VALUE_WITHOUT_UNITS`, `UNITS_NAV_VALUE_MISMATCH`, or `UNIT_BALANCE_MISMATCH` |

### `cas_transaction`

One row of the transaction table under a holding.

| Column | Meaning |
|---|---|
| `id` | Transaction id |
| `holding_id` | Parent holding |
| `position` | Order in that table |
| `txn_date` | Transaction date |
| `description` | Particulars printed on the row |
| `amount`, `units`, `price`, `unit_balance` | Amount, units, price or NAV, and running unit balance |
| `raw` | Those values as printed text |

### `cas_amc_total`

A printed AMC total from the portfolio summary, when the statement has one.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order in the summary |
| `amc` | Fund house name |
| `cost_value`, `market_value` | Printed totals for that AMC |
| `raw` | Those totals as printed text |

### `cas_check`

One reconciliation check for the statement. A failed check sets `integrity_status` to `needs_review`.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order of the check |
| `name` | Check name, such as `TOTAL_MARKET_RECONCILES` |
| `passed` | True when the holdings agree with the statement within ₹1 |
| `detail` | Sentence shown on the page |

### `cas_warning`

The same failures, as short messages.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order |
| `message` | Warning text |

### `cas_insight`

Facts computed from active holdings. One row per statement.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `active_count`, `redeemed_count` | Fund counts |
| `total_market_value` | Sum of active printed market values |
| `total_cost_value` | Sum of printed cost, where both cost and value were read |
| `unrealised_gain` | Market value of those costed holdings, minus their cost |
| `holdings_without_cost` | Active holdings skipped from invested and gain |
| `diversification` | `Concentrated`, `Under Diversified`, `Well Diversified`, or `Over Diversified` |
| `risk` | `Aggressive` through `Conservative`. Empty when any active fund has no category |
| `horizon` | `Long Term` through `Short Term`. Empty on the same rule as risk |

### `cas_insight_amc`

Market value and weight of each AMC, largest first.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order, largest first |
| `amc` | Fund house |
| `market_value` | Sum of that AMC’s active holdings |
| `weight_pct` | Share of the portfolio, in percent |

### `cas_insight_weight`

Market value and weight of each active holding, largest first.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order, largest first |
| `holding_key` | `ISIN\|folio` |
| `scheme_name`, `isin` | Fund |
| `market_value` | Printed market value |
| `weight_pct` | Share of total market value, in percent |

### `cas_insight_unclassified`

Active funds whose scheme name did not match a category. Risk and horizon are empty when this table has rows.

| Column | Meaning |
|---|---|
| `statement_id` | Parent statement |
| `position` | Order |
| `scheme_name` | Fund that could not be classified |

### `cas_verdict`

Empty today. An advisor mark of Buy, Hold, or Sell can be stored here later, keyed by `holding_key`, without changing `cas_holding`.

| Column | Meaning |
|---|---|
| `id` | Verdict id |
| `statement_id` | Parent statement |
| `holding_key` | `ISIN\|folio` |
| `verdict` | `buy`, `hold`, or `sell` |
| `note` | Optional note |
| `marked_at` | When the mark was saved |

## Checks

These say whether the read agrees with the statement, within ₹1.

- Sum of holding market values against the printed portfolio total, when that total was found.
- Sum of holding cost values against the printed invested total, when that total was found.
- Units × NAV against the printed market value, per holding.
- The last transaction’s unit balance against the closing units.
- ISIN check digit, missing name, and a duplicate ISIN and folio.
- A failed check sets the statement to Needs review. The printed number is left as printed.

On one real detailed report in that check:

- The printed portfolio total line was not found, so the statement stays Needs review.
- The 17 holdings still sum to ₹6,07,976.54.
- Invested and gain stay empty when the file has no readable cost.

## Insights calculated today

- Only active holdings are used.
- Zero closing units means redeemed, and that holding is left out of the sums.
- The same ISIN in two folios is two holding rows, and one fund for diversification, risk, and horizon. The two market values are added.

### 1. Market value

Sum of the printed market value of each active holding, in paise, then shown in rupees. If any active holding has no readable market value, the total stays empty.

### 2. Invested amount

Sum of the printed cost value, only across active holdings that have both a cost and a market value. If none do, invested stays empty.

### 3. Gain

Market value of those same costed holdings, minus their cost. It is not a percentage. It stays empty when invested is empty.

### 4. Fund count

Active count and redeemed count.

### 5. Weight of each fund

That holding’s market value divided by the total market value, as a percentage to two decimal places. Holdings are listed largest first.

### 6. Weight of each AMC

Market value grouped by AMC, divided by the total of those AMC amounts.

### 7. Diversification

- Count of distinct active ISINs.
- One fund, or any fund that is at least half the portfolio, is Concentrated.
- Otherwise the count is compared with a band for the portfolio size:

| Portfolio value | Well diversified when the fund count is |
|---|---|
| Below ₹5 lakh | 2 to 4 |
| ₹5 lakh to below ₹25 lakh | 3 to 6 |
| ₹25 lakh to below ₹1 crore | 4 to 8 |
| ₹1 crore to ₹5 crore | 5 to 10 |
| Above ₹5 crore | 6 to 13 |

- Below the band: Under Diversified.
- Above the band: Over Diversified.
- Inside the band: Well Diversified.

### 8. Category, from the scheme name

The scheme name is matched in this order. The first match wins. There is no AMFI fund master.

| Name contains | Category |
|---|---|
| retirement, children, or child | Unclassified on purpose |
| arbitrage | Arbitrage |
| equity savings | Equity savings |
| liquid | Liquid |
| ELSS, tax saver, flexi cap, multi cap, large cap, index, or ETF | Equity |
| small cap | Small cap |
| mid cap | Mid cap |
| gold or silver | Gold or silver |
| anything else | Unclassified |

- A silver ETF matches “ETF” first, so it is treated as equity.
- A gold fund whose name does not say ETF matches gold.

### 9. Risk

- Calculated only when every active fund with a value has a category.
- One unclassified fund leaves risk empty, and that scheme name is listed.
- Each category has an equity factor from 0 to 100.
- The score is the market-value-weighted average of those factors.

| Category | Equity factor |
|---|---|
| Equity, mid cap, small cap | 100 |
| Aggressive hybrid | 70 |
| Balanced hybrid, balanced advantage | 50 |
| Multi asset | 40 |
| Equity savings, gold or silver | 30 |
| Conservative hybrid | 20 |
| Liquid, arbitrage, and the other debt categories | 0 |

| Weighted score | Risk |
|---|---|
| 80 or above | Aggressive |
| 60 to below 80 | Moderately Aggressive |
| 40 to below 60 | Moderate |
| 20 to below 40 | Moderately Conservative |
| Below 20 | Conservative |

The sample statement is all equity, so the score is 100 and the risk is Aggressive.

### 10. Horizon

- Same gate as risk: one unclassified fund leaves horizon empty.
- Each category has a horizon in years.
- The score is the market-value-weighted average.

| Category | Years |
|---|---|
| Overnight, liquid | 0.25 |
| Ultra short, low duration, money market, floater, arbitrage | 0.75 |
| Short duration, corporate bond, banking and PSU | 2 |
| Conservative hybrid, equity savings | 2.5 |
| Medium duration, credit risk, dynamic bond | 3.5 |
| Balanced hybrid, balanced advantage, multi asset, gold or silver | 4 |
| Gilt, medium to long | 4.5 |
| Aggressive hybrid | 5 |
| Long duration, equity | 7 |
| Mid cap | 8 |
| Small cap | 10 |

| Weighted years | Horizon |
|---|---|
| 5 or more | Long Term |
| 3 to below 5 | Mid to Long Term |
| 1 to below 3 | Mid Term |
| Below 1 | Short Term |

## What is not calculated

- No LLM, and no external API, for reading or for insights.
- No XIRR. That needs a dated opening cost as well as the transactions.
- No trailing returns, benchmark, or recommendation.
- No Buy, Hold, or Sell. The `cas_verdict` table is ready for that mark.
- No AMFI category master. Category is the scheme name only.
- No NSDL or CDSL reader.
- No merge of two overlapping statements.

## What a later service adds

The reader, the record, and the checks stay. Around them:

- The PDF in S3, and this same schema on RDS.
- Encryption, and a decision on how long a PDF is kept.
- PAN stays out of logs.
- Tarakki maps onto this record.
- Advisor marks go into `cas_verdict`.
