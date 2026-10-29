-- Parsed CAS records. The PDF and the password are not stored.
-- cas_verdict is empty until an advisor marks Buy, Hold, or Sell. That mark
-- is keyed by ISIN|folio and does not rewrite the parsed holding.

CREATE TABLE IF NOT EXISTS cas_statement (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    statement_as_of date,
    period_from date,
    period_to date,
    format text,
    file_name text,
    investor_name text,
    investor_title text,
    investor_pan text,
    investor_email text,
    investor_address text,
    integrity_status text NOT NULL,
    total_cost_value numeric,
    total_market_value numeric,
    printed_raw jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS cas_investor_pan (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    pan text NOT NULL,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_holding (
    id bigserial PRIMARY KEY,
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    holding_key text NOT NULL,
    isin text,
    folio text,
    scheme_name text,
    rta_scheme_code text,
    amc text,
    pan text,
    registrar text,
    status text NOT NULL,
    opening_units numeric,
    units numeric,
    nav numeric,
    nav_date date,
    cost_value numeric,
    market_value numeric,
    average_cost numeric,
    printed_gain numeric,
    category text,
    asset_class text,
    raw jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS cas_holding_flag (
    holding_id bigint NOT NULL REFERENCES cas_holding (id) ON DELETE CASCADE,
    position integer NOT NULL,
    flag text NOT NULL,
    PRIMARY KEY (holding_id, position)
);

CREATE TABLE IF NOT EXISTS cas_transaction (
    id bigserial PRIMARY KEY,
    holding_id bigint NOT NULL REFERENCES cas_holding (id) ON DELETE CASCADE,
    position integer NOT NULL,
    txn_date date,
    description text,
    amount numeric,
    units numeric,
    price numeric,
    unit_balance numeric,
    raw jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS cas_amc_total (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    amc text,
    cost_value numeric,
    market_value numeric,
    raw jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_check (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    name text NOT NULL,
    passed boolean NOT NULL,
    detail text,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_warning (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    message text NOT NULL,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_insight (
    statement_id uuid PRIMARY KEY REFERENCES cas_statement (id) ON DELETE CASCADE,
    active_count integer NOT NULL,
    redeemed_count integer NOT NULL,
    total_market_value numeric,
    total_cost_value numeric,
    unrealised_gain numeric,
    holdings_without_cost integer NOT NULL,
    diversification text,
    risk text,
    horizon text
);

CREATE TABLE IF NOT EXISTS cas_insight_amc (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    amc text,
    market_value numeric,
    weight_pct numeric,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_insight_weight (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    holding_key text,
    scheme_name text,
    isin text,
    market_value numeric,
    weight_pct numeric,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_insight_unclassified (
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    position integer NOT NULL,
    scheme_name text,
    PRIMARY KEY (statement_id, position)
);

CREATE TABLE IF NOT EXISTS cas_verdict (
    id bigserial PRIMARY KEY,
    statement_id uuid NOT NULL REFERENCES cas_statement (id) ON DELETE CASCADE,
    holding_key text NOT NULL,
    verdict text NOT NULL CHECK (verdict IN ('buy', 'hold', 'sell')),
    note text,
    marked_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (statement_id, holding_key)
);

CREATE INDEX IF NOT EXISTS cas_statement_current_idx
    ON cas_statement (created_at DESC);
