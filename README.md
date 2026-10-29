# CAS portfolio service

A local Java service that reads a CAMS or KFintech Consolidated Account Statement, checks the holdings against the numbers printed on the statement, and stores the portfolio in Postgres. Open http://localhost:4110, choose a PDF, and the page shows market value, invested amount, gain, the check result, and the holdings.

The reader has been verified on about 15 CAMS and KFintech PDFs. Insight rules and the database columns are in [DESIGN.md](DESIGN.md).

This service is not wired into prosperr-apis, and it does not replace the Tarakki OTP flow.

## What you need

- Java 21
- Docker, with the Docker app open

## Run

From this directory:

```bash
docker compose up -d
./gradlew test
./gradlew run
```

Then open http://localhost:4110.

`docker compose up -d` starts Postgres 16 and keeps the data in a Docker volume named `cas-postgres-data`. The service creates the tables on startup. A password on the PDF is used only for that read. The PDF and the password are not stored.

Postgres is on `localhost:5432`.

| Setting | Value |
|---|---|
| Database | `cas` |
| User | `cas` |
| Password | `cas` |

That password is only for this local database. To point at another database, set `CAS_JDBC_URL`, `CAS_DB_USER`, and `CAS_DB_PASSWORD` before `./gradlew run`. Set `PORT` to change `4110`.

Stop the database with `docker compose stop`. `docker compose down -v` deletes the saved statements.

## Use the page

1. Choose a CAMS or KFintech PDF.
2. Enter the password if the file asks for one.
3. Click **Read statement**.

The page shows the statement saved most recently. `GET /cas/current` returns that same record. `GET /health` checks that the process is up. `POST /cas` accepts the PDF as a form field named `file`, with an optional `password`.

NSDL and CDSL files are recognised and rejected with `FORMAT_NOT_IN_PROTOTYPE`. A scan with no text layer is rejected with `NO_TEXT_LAYER`.

## Layout

| Package | Role |
|---|---|
| `model` | The portfolio record |
| `extract` | PDFBox text positions, grouped into rows and cells |
| `parse` | Format detection, CAMS/KFintech reader, checks |
| `insight` | Summary, weights, diversification, risk, horizon |
| `store` | Postgres. The most recently saved statement is current |
| `http` | `POST /cas`, `GET /cas/current`, `GET /health`, and the page |

XIRR, an AMFI fund master, and merging two statements are not in this build. No language model is used.
