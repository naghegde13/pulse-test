# PULSE Pipeline Benchmark Plan — All 50 Scenarios

> **Purpose:** Detailed benchmark plan expanding every scenario from the 50-scenario index into full definitions with natural language requirements, data schemas, expected pipeline composition, and validation checklists.
> **Date:** 2026-02-25
> **Version:** 2.0
> **Scenarios:** 50
> **Data Sources:** TPC-DI (brokerage), Freddie Mac SFLLD (mortgage), Lending Club (consumer lending), Synthetic ACME (multi-format)

---

## Data Source Reference

| Source | Domain | Format | Size | Use For |
|--------|--------|--------|------|---------|
| **TPC-DI** (DIGen.jar) | Brokerage firm | CSV, pipe-delimited, XML, fixed-width | Configurable (SF=5 to SF=100) | Trades, accounts, customers, securities, financials, CDC, SCD2 |
| **Freddie Mac SFLLD** | Mortgage servicing | Pipe-delimited | 48M origination + 2.8B performance records | Loan origination, monthly payment performance, delinquency |
| **Lending Club** (Kaggle) | Consumer lending | CSV | 2.2M loans | Loan applications, credit decisions, payment history |
| **Synthetic: ACME** | Mortgage servicing | CSV, JSON, Parquet | Generated (configurable) | Payment summaries, credit bureau files, HR data, API payloads, Kafka events |

### TPC-DI Key Files

| File | Format | Key Columns |
|------|--------|-------------|
| `Customer.txt` | Pipe-delimited (CDC) | C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL |
| `Account.txt` | Pipe-delimited | CA_ID, CA_B_ID, CA_C_ID, CA_NAME, CA_TAX_ST, CA_ST_ID |
| `Trade.txt` | Pipe-delimited | T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE |
| `TradeHistory.txt` | Pipe-delimited | TH_T_ID, TH_DTS, TH_ST_ID |
| `DailyMarket.txt` | Pipe-delimited | DM_DATE, DM_S_SYMB, DM_CLOSE, DM_HIGH, DM_LOW, DM_VOL |
| `WatchHistory.txt` | Pipe-delimited | W_C_ID, W_S_SYMB, W_DTS, W_ACTION |
| `Prospect.csv` | CSV | AGENCY_ID, LAST_NAME, FIRST_NAME, MIDDLE_INITIAL, GENDER, ADDR_LINE1 |
| `CustomerMgmt.xml` | XML (nested) | ActionType, Customer (nested: Name, Address, ContactInfo, TaxInfo, Account) |
| `FINWIRE` | Fixed-width (multiplexed) | Record type (CMP/SEC/FIN), then type-specific fields |
| `StatusType.txt` | Pipe-delimited | ST_ID, ST_NAME |
| `TradeType.txt` | Pipe-delimited | TT_ID, TT_NAME, TT_IS_SELL, TT_IS_MRKT |
| `Industry.txt` | Pipe-delimited | IN_ID, IN_NAME, IN_SC_ID |
| `TaxRate.txt` | Pipe-delimited | TX_ID, TX_NAME, TX_RATE |
| `HR.csv` | CSV | EmployeeID, ManagerID, EmployeeFirstName, EmployeeLastName |
| `BatchDate.txt` | Text | BatchDate |

### Freddie Mac SFLLD Key Files

| File | Format | Key Columns |
|------|--------|-------------|
| Origination | Pipe-delimited | Credit Score, First Payment Date, First Time Buyer, Maturity Date, MSA, MI%, Units, Occupancy, CLTV, DTI, UPB, LTV, Interest Rate, Channel, Loan Sequence Number |
| Monthly Performance | Pipe-delimited | Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status, Loan Age, Remaining Months to Maturity |

### Lending Club Key Files

| File | Format | Key Columns |
|------|--------|-------------|
| Loans CSV | CSV | id, member_id, loan_amnt, funded_amnt, term, int_rate, installment, grade, sub_grade, emp_length, home_ownership, annual_inc, verification_status, issue_d, loan_status, purpose, addr_state, dti |

### Synthetic ACME Key Files

| File | Format | Key Columns |
|------|--------|-------------|
| payment_summary_YYYYMMDD.csv | CSV | payment_date, customer_id, amount, payment_type, region |
| credit_file_YYYY-MM-DD.pgp | PGP-encrypted CSV | ssn, first_name, last_name, credit_score, report_date |
| hr_employees.parquet | Parquet | employee_id, ssn, first_name, last_name, salary, department, hire_date, bank_account_number |
| trade_events (Kafka) | JSON | txn_id, txn_date, txn_type, amount, account_id, symbol |
| api/v1/transactions | REST JSON | txn_id, timestamp, amount, account_id, status |

---

## Infrastructure Requirements (Docker Compose)

```yaml
services:
  minio:         # S3-compatible storage for file sources/sinks
  postgres:      # Stands in for Oracle/JDBC (source databases)
  sftp:          # SFTP file drops (atmoz/sftp image)
  kafka:         # Streaming source/sink (with zookeeper)
  zookeeper:     # Kafka dependency
  airflow:       # DAG execution validation
```

---

# Tier 1: Fundamentals (S01–S10) — Single Source, Basic Transforms

> These scenarios test the basic pipeline composition loop. If these fail, nothing else matters.

---

## S01: File-to-Warehouse (CSV)

**Natural Language Requirement:**
> "We receive a daily pipe-delimited file of Freddie Mac loan origination data on S3. Load it into the reporting warehouse every morning."

**Data Source & Schema:**
- Source: Freddie Mac origination file on S3 (MinIO)
- Path: `s3://benchmark-data/raw/freddie_mac/origination/`
- File naming: `orig_YYYYMMDD.txt`
- Format: Pipe-delimited (`|`)
- Key columns: Loan Sequence Number, Credit Score, First Payment Date, First Time Buyer, Maturity Date, MSA, MI%, Units, Occupancy, CLTV, DTI, UPB, LTV, Interest Rate, Channel

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → WarehouseWriter`
- Wiring: `FileIngestion.data_output → WarehouseWriter.data_input`
- Periodicity: `time_grain=DAILY`, `file_naming_pattern=orig_%Y%m%d.txt`, `sensing_strategy=file`
- Sensor: `S3KeySensor` with time-grain-aware defaults (poke 5min, timeout 6h)
- Schedule: `@daily`
- FileIngestion params: `delimiter='|'`, `header=false`, schema declared
- WarehouseWriter params: `target_database`, `target_schema`, `target_table=freddie_mac_origination`

**Validation Checklist:**
- [ ] Correct blueprint selection (FileIngestion + WarehouseWriter)
- [ ] Port wiring is valid (`data_output → data_input`)
- [ ] S3KeySensor generated for file arrival
- [ ] Correct schedule derived from time grain (`@daily`)
- [ ] PySpark ingestion script reads pipe-delimited with declared schema (no header row)
- [ ] Warehouse write uses correct target identifiers
- [ ] Airflow DAG generated with TaskGroups: sensor >> ingest >> write

---

## S02: File-to-Warehouse (Parquet)

**Natural Language Requirement:**
> "The TPC-DI DailyMarket data has been converted to Parquet. Load it from S3 into the data lake every night."

**Data Source & Schema:**
- Source: TPC-DI DailyMarket converted to Parquet on S3 (MinIO)
- Path: `s3://benchmark-data/raw/tpcdi/daily_market/`
- File naming: `daily_market_YYYYMMDD.parquet`
- Format: Parquet
- Key columns: DM_DATE, DM_S_SYMB, DM_CLOSE, DM_HIGH, DM_LOW, DM_VOL

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → LakeWriter`
- Wiring: `FileIngestion.data_output → LakeWriter.data_input`
- Periodicity: `time_grain=DAILY`, `file_naming_pattern=daily_market_%Y%m%d.parquet`, `sensing_strategy=file`
- Sensor: `S3KeySensor`
- Schedule: `@daily`
- FileIngestion params: `format=parquet` (schema self-describing)
- LakeWriter params: `lake_format=parquet`, `write_mode=append`, `partition_by=[DM_DATE]`

**Validation Checklist:**
- [ ] Correct blueprint selection (FileIngestion + LakeWriter)
- [ ] Port wiring is valid
- [ ] Parquet format handled correctly (no delimiter, self-describing schema)
- [ ] S3KeySensor generated with `.parquet` file pattern
- [ ] LakeWriter outputs Parquet with partition by DM_DATE
- [ ] No schema declaration needed (Parquet is self-describing)
- [ ] DAG generated with correct sensor >> ingest >> write flow

---

## S03: Database Snapshot to Lake

**Natural Language Requirement:**
> "Take a nightly snapshot of the TPC-DI Trade, Account, and Customer tables from Postgres and write them as Parquet to the data lake."

**Data Source & Schema:**
- Source: TPC-DI tables loaded in PostgreSQL
- Tables: `Trade` (T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE), `Account` (CA_ID, CA_B_ID, CA_C_ID, CA_NAME, CA_TAX_ST, CA_ST_ID), `Customer` (C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL)
- Connection: PostgreSQL JDBC

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion → LakeWriter`
- Periodicity: `time_grain=DAILY`, `sensing_strategy=none` (database pull)
- Schedule: `0 2 * * *` (2am daily)
- SnapshotIngestion params: `connection_id=postgres_tpcdi`, `tables=[Trade, Account, Customer]`
- LakeWriter params: `lake_format=parquet`, `partition_by=[snapshot_date]`

**Validation Checklist:**
- [ ] SnapshotIngestion selected (not FileIngestion or CDCIngestion)
- [ ] PostgreSQL connection_id used in generated SparkSubmit
- [ ] JDBC read configured for all three tables
- [ ] LakeWriter outputs Parquet with `partition_by=[snapshot_date]`
- [ ] No sensor generated (database pull, no file arrival)
- [ ] DAG schedule set to cron `0 2 * * *`
- [ ] Each table snapshot generates its own task or TaskGroup

---

## S04: SFTP Encrypted File

**Natural Language Requirement:**
> "A credit bureau drops PGP-encrypted files on SFTP nightly. Decrypt them and load to the data lake as Parquet."

**Data Source & Schema:**
- Source: Synthetic credit bureau file on SFTP
- SFTP host: `sftp.creditvendor.com`
- Path: `/incoming/credit_bureau/`
- File naming: `credit_file_YYYY-MM-DD.pgp`
- Format: PGP-encrypted CSV (after decryption: ssn, first_name, last_name, credit_score, report_date)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → LakeWriter`
- FileIngestion params: `connector=sftp`, `encryption=PGP`, `decryption_key_ref=vault://pulse/credit-pgp-key`
- Periodicity: `time_grain=DAILY`, `file_naming_pattern=credit_file_%Y-%m-%d.pgp`, `sensing_strategy=file`
- Sensor: `SFTPSensor`
- LakeWriter params: `lake_format=parquet`

**Validation Checklist:**
- [ ] FileIngestion selected with SFTP connector (not S3)
- [ ] PGP decryption configured with SecretRef (never plaintext key)
- [ ] SFTPSensor generated (not S3KeySensor)
- [ ] Dataset classified as PII (credit bureau data — SSN, names)
- [ ] AI should ASK about data classification if not provided
- [ ] Generated PySpark handles decrypt → read CSV → write Parquet flow
- [ ] Airflow DAG: sensor >> decrypt >> ingest >> write

---

## S05: API Ingestion (REST)

**Natural Language Requirement:**
> "Poll our synthetic REST transaction endpoint every hour and append results to a Delta table in the data lake."

**Data Source & Schema:**
- Source: Synthetic REST endpoint
- URL: `https://api.acme-benchmark.com/v1/transactions`
- Auth: API key
- Response format: JSON array of `{txn_id, timestamp, amount, account_id, status}`
- Pagination: offset-based

**Expected Pipeline Composition:**
- Sub-pipelines: `ApiIngestion → LakeWriter`
- Periodicity: `time_grain=HOURLY`
- Schedule: `@hourly`
- ApiIngestion params: `endpoint=/v1/transactions`, `auth=api_key`, `pagination=offset`, `page_size=1000`
- LakeWriter params: `lake_format=delta`, `write_mode=append`

**Validation Checklist:**
- [ ] ApiIngestion selected
- [ ] API endpoint, auth method, and pagination configured
- [ ] Hourly schedule (`@hourly`)
- [ ] Delta Lake append mode
- [ ] No sensor (API pull, not file arrival)
- [ ] Generated PySpark uses requests library with API key from Airflow connection
- [ ] Pagination loop handles multiple pages until empty response

---

## S06: Kafka Stream to Delta

**Natural Language Requirement:**
> "We have a Kafka topic `trade-events` with real-time synthetic trade events. Write them to Delta Lake in 5-minute micro-batches."

**Data Source & Schema:**
- Source: Kafka topic `trade-events`
- Bootstrap servers: `kafka:9092` (Docker Compose)
- Message format: JSON `{txn_id, txn_date, txn_type, amount, account_id, symbol}`

**Expected Pipeline Composition:**
- Sub-pipelines: `StreamIngestion → LakeWriter`
- Periodicity: `time_grain=CONTINUOUS` (streaming job)
- Schedule: None (externally triggered or always-on)
- StreamIngestion params: `bootstrap_servers=kafka:9092`, `topic=trade-events`, `format=json`
- LakeWriter params: `lake_format=delta`, `write_mode=append`, `checkpoint_location=s3://benchmark-data/checkpoints/trade_events/`

**Validation Checklist:**
- [ ] StreamIngestion selected
- [ ] Kafka connection configured with bootstrap servers
- [ ] Generated PySpark uses `readStream` (structured streaming), not batch read
- [ ] Trigger interval = 5 minutes (`trigger(processingTime='5 minutes')`)
- [ ] Checkpoint location configured
- [ ] No Airflow sensor (continuous)
- [ ] DAG schedule = `None` or external trigger
- [ ] Schema deserialized from JSON within stream

---

## S07: CDC Ingestion

**Natural Language Requirement:**
> "The TPC-DI Customer file is in CDC format with action types (NEW, ADDACCT, UPDACCT, UPDCUST, CLOSEACCT, INACT). Ingest the changes and apply them to build a current-state table in the data lake."

**Data Source & Schema:**
- Source: TPC-DI `Customer.txt` (CDC format) on S3
- Format: Pipe-delimited
- Key columns: C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL, ActionType, ActionTS
- CDC action types: NEW, ADDACCT, UPDACCT, UPDCUST, CLOSEACCT, INACT

**Expected Pipeline Composition:**
- Sub-pipelines: `CDCIngestion → LakeWriter`
- Wiring: `CDCIngestion.data_output → LakeWriter.data_input`
- CDCIngestion params: `business_key=C_ID`, `action_type_column=ActionType`, `timestamp_column=ActionTS`
- Merge logic: NEW → insert, UPDCUST → update, INACT → soft-delete (set `is_active=false`)
- LakeWriter params: `lake_format=delta`, `write_mode=merge`

**Validation Checklist:**
- [ ] CDCIngestion blueprint selected (not FileIngestion or SnapshotIngestion)
- [ ] Business key correctly identified as `C_ID`
- [ ] Action type column mapped to CDC operations (insert/update/delete)
- [ ] Generated PySpark implements merge logic for each action type
- [ ] Delta Lake merge (MERGE INTO) used for upsert/soft-delete
- [ ] Soft-delete strategy for INACT records (not hard delete)
- [ ] Output table maintains current state with `is_active` flag
- [ ] Airflow DAG with sensor >> CDC-ingest >> merge-write

---

## S08: Bulk Backfill

**Natural Language Requirement:**
> "Backfill 3 years of Freddie Mac origination data into the data lake. The files are quarterly, totaling about 12 large files. Partition by year and quarter."

**Data Source & Schema:**
- Source: Freddie Mac origination files on S3 (3 years, quarterly)
- Path: `s3://benchmark-data/raw/freddie_mac/origination/historical/`
- Files: `orig_Q1_2023.txt` through `orig_Q4_2025.txt` (12 files)
- Format: Pipe-delimited
- Key columns: same as S01

**Expected Pipeline Composition:**
- Sub-pipelines: `BulkBackfill → LakeWriter`
- BulkBackfill params: `file_list=glob(s3://.../*.txt)`, `parallelism=4`
- LakeWriter params: `lake_format=parquet`, `partition_by=[year, quarter]`, `write_mode=overwrite_partition`
- No ongoing schedule — one-time execution

**Validation Checklist:**
- [ ] BulkBackfill blueprint selected (not FileIngestion)
- [ ] All 12 files discovered and processed
- [ ] Partitioned write by `[year, quarter]`
- [ ] Parallelism configured to avoid overwhelming cluster
- [ ] Write mode is `overwrite_partition` (idempotent re-run)
- [ ] No sensor — backfill is manually triggered
- [ ] AI asks user to confirm: "This will process 3 years of historical data. Confirm?"
- [ ] Large volume handling: executor memory and partition tuning

---

## S09: Simple Filter

**Natural Language Requirement:**
> "From the TPC-DI Trade file, keep only cash trades (T_IS_CASH = 1) and write them to the data lake."

**Data Source & Schema:**
- Source: TPC-DI `Trade.txt` on S3
- Format: Pipe-delimited
- Key columns: T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → GenericFilter → LakeWriter`
- Wiring: `FileIngestion.data_output → GenericFilter.data_input → GenericFilter.data_output → LakeWriter.data_input`
- GenericFilter params: `conditions=[{column: T_IS_CASH, operator: equals, value: 1}]`
- LakeWriter params: `lake_format=parquet`

**Validation Checklist:**
- [ ] Three-step pipeline: FileIngestion → GenericFilter → LakeWriter
- [ ] Filter condition correct: `T_IS_CASH = 1`
- [ ] Schema passes through filter unchanged (same columns, fewer rows)
- [ ] S3KeySensor generated for Trade.txt arrival
- [ ] Generated PySpark applies `.filter(col('T_IS_CASH') == 1)`
- [ ] Output contains only cash trades
- [ ] Row count validation: output rows < input rows

---

## S10: Simple Aggregation

**Natural Language Requirement:**
> "From the Freddie Mac monthly performance file, compute the average current loan delinquency status and total unpaid balance grouped by monthly reporting period. Write results to the warehouse."

**Data Source & Schema:**
- Source: Freddie Mac monthly performance file on S3
- Format: Pipe-delimited
- Key columns: Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → GenericAggregate → WarehouseWriter`
- Wiring: `FileIngestion.data_output → GenericAggregate.data_input → GenericAggregate.data_output → WarehouseWriter.data_input`
- GenericAggregate params: `group_by=[Monthly_Reporting_Period]`, `aggregations=[{column: Current_Loan_Delinquency_Status, function: avg, alias: avg_delinquency}, {column: Current_Actual_UPB, function: sum, alias: total_upb}]`

**Validation Checklist:**
- [ ] Three-step pipeline: FileIngestion → GenericAggregate → WarehouseWriter
- [ ] Group by `Monthly_Reporting_Period`
- [ ] Two aggregations: AVG(delinquency status), SUM(UPB)
- [ ] Output schema: `Monthly_Reporting_Period`, `avg_delinquency`, `total_upb`
- [ ] Row count = distinct count of Monthly_Reporting_Period values
- [ ] Generated PySpark uses `.groupBy().agg()`
- [ ] Warehouse write targets correct table

---

# Tier 2: Composition (S11–S20) — Multi-Step, Joins, DQ

> These test the system's ability to compose non-trivial pipelines with joins, filters, aggregations, and data quality.

---

## S11: Two-Source Join

**Natural Language Requirement:**
> "Join the Freddie Mac origination data with the monthly performance data to create a combined loan view. Join on loan sequence number."

**Data Source & Schema:**
- Source A: Freddie Mac origination on S3 (`orig_YYYYMMDD.txt`, pipe-delimited)
  - Key columns: Loan Sequence Number, Credit Score, UPB, LTV, DTI, Interest Rate, Channel
- Source B: Freddie Mac monthly performance on S3 (`perf_YYYYMMDD.txt`, pipe-delimited)
  - Key columns: Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status, Loan Age

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(orig) + FileIngestion(perf) → GenericJoin → LakeWriter`
- Wiring: `FileIngestion_A.data_output → GenericJoin.left_input`, `FileIngestion_B.data_output → GenericJoin.right_input`, `GenericJoin.data_output → LakeWriter.data_input`
- GenericJoin params: `join_key=Loan_Sequence_Number`, `join_type=inner`
- Sensors: Two S3KeySensors (one per file)

**Validation Checklist:**
- [ ] Two ingestion sub-pipelines (both FileIngestion)
- [ ] GenericJoin wired correctly (two inputs, one output)
- [ ] Join key = `Loan_Sequence_Number`
- [ ] AI should ask about join type if not specified (inner vs left)
- [ ] Schema correctly inferred through the join (columns from both sources)
- [ ] Column collision handling (prefix for duplicates)
- [ ] Two S3KeySensors, one per source file
- [ ] DAG: parallel sensors >> parallel ingests >> join >> write

---

## S12: Three-Source Join

**Natural Language Requirement:**
> "Create a trade analysis view by joining TPC-DI Trade data with Account and Customer tables from Postgres. Join Trade to Account on CA_ID = T_CA_ID, then join result with Customer on C_ID = CA_C_ID."

**Data Source & Schema:**
- Source A: TPC-DI `Trade.txt` on S3 (pipe-delimited)
  - Key columns: T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE, T_CA_ID
- Source B: TPC-DI `Account` in PostgreSQL
  - Key columns: CA_ID, CA_B_ID, CA_C_ID, CA_NAME, CA_TAX_ST
- Source C: TPC-DI `Customer` in PostgreSQL
  - Key columns: C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(Trade) + SnapshotIngestion(Account) + SnapshotIngestion(Customer) → GenericJoin(Trade×Account) → GenericJoin(result×Customer) → WarehouseWriter`
- Join 1: `Trade.T_CA_ID = Account.CA_ID`
- Join 2: `result.CA_C_ID = Customer.C_ID`

**Validation Checklist:**
- [ ] Three ingestion sub-pipelines: 1 FileIngestion + 2 SnapshotIngestion
- [ ] Two sequential joins (multi-hop)
- [ ] Join keys correct: `T_CA_ID = CA_ID`, then `CA_C_ID = C_ID`
- [ ] Mixed sensing: S3 sensor for Trade file, no sensor for DB snapshots
- [ ] Schema propagates correctly through both joins
- [ ] Output contains columns from all three sources
- [ ] DAG structure: (sensor >> file-ingest) + (db-ingest x2) >> join1 >> join2 >> write

---

## S13: Filter + Aggregate

**Natural Language Requirement:**
> "From the Lending Club loans file, filter only fully-paid loans (loan_status = 'Fully Paid') with loan amount above $10,000, then compute the average interest rate and total funded amount grouped by grade. Write to the warehouse."

**Data Source & Schema:**
- Source: Lending Club loans CSV on S3
- Key columns: id, loan_amnt, funded_amnt, term, int_rate, grade, sub_grade, loan_status

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → GenericFilter → GenericAggregate → WarehouseWriter`
- GenericFilter params: `conditions=[{column: loan_status, operator: equals, value: 'Fully Paid'}, {column: loan_amnt, operator: greater_than, value: 10000}]`, `logic=AND`
- GenericAggregate params: `group_by=[grade]`, `aggregations=[{column: int_rate, function: avg, alias: avg_interest_rate}, {column: funded_amnt, function: sum, alias: total_funded}]`

**Validation Checklist:**
- [ ] Four-step chain: ingest → filter → aggregate → write
- [ ] Filter conditions correct (two conditions, AND logic)
- [ ] Aggregation groups by `grade` with avg and sum
- [ ] Schema narrows through filter (same columns) then reshapes in aggregate (grade, avg_interest_rate, total_funded)
- [ ] Single S3 sensor for the Lending Club file
- [ ] Generated PySpark chains `.filter()` then `.groupBy().agg()`

---

## S14: Router (Conditional Split)

**Natural Language Requirement:**
> "Ingest the TPC-DI Trade file. Route buy trades (TT_ID starts with 'TB') to a buys table, sell trades (TT_ID starts with 'TS') to a sells table, and everything else to an unclassified table in the warehouse."

**Data Source & Schema:**
- Source: TPC-DI `Trade.txt` on S3 (pipe-delimited)
- Key columns: T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → GenericRouter → WarehouseWriter(buys) + WarehouseWriter(sells) + WarehouseWriter(unclassified)`
- GenericRouter params: `route_column=T_TT_ID`, conditions: `starts_with('TB') → port_buys`, `starts_with('TS') → port_sells`, `default → port_unclassified`
- Three WarehouseWriter instances targeting different tables

**Validation Checklist:**
- [ ] Router has 3 output ports (buys, sells, default/unclassified)
- [ ] Each output wired to its own WarehouseWriter
- [ ] Router conditions correct (column=T_TT_ID, operator=starts_with)
- [ ] All three writers target different tables in same schema
- [ ] Schema propagated correctly through all 3 branches (same columns)
- [ ] Single ingest, single sensor, fan-out at router
- [ ] DAG: sensor >> ingest >> router >> [write_buys, write_sells, write_unclassified]

---

## S15: PII Masking

**Natural Language Requirement:**
> "Ingest the TPC-DI Prospect file (has SSN-like tax IDs, names, addresses). Mask the AGENCY_ID, hash the names, then load to the silver zone in Delta Lake."

**Data Source & Schema:**
- Source: TPC-DI `Prospect.csv` on S3
- Format: CSV
- Key columns: AGENCY_ID, LAST_NAME, FIRST_NAME, MIDDLE_INITIAL, GENDER, ADDR_LINE1, ADDR_LINE2, POSTAL_CODE

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → PIIMasking → LakeWriter`
- PIIMasking params:
  - `AGENCY_ID → mask (show last 4: ***1234)`
  - `FIRST_NAME → hash (SHA256)`
  - `LAST_NAME → hash (SHA256)`
  - `ADDR_LINE1 → redact`
- Dataset classification: PII

**Validation Checklist:**
- [ ] PIIMasking blueprint selected
- [ ] AGENCY_ID masking strategy correct (partial mask)
- [ ] Name hashing strategy correct (one-way SHA256 hash)
- [ ] Address redaction applied
- [ ] Dataset marked as PII classification
- [ ] AI proactively detects PII columns and recommends masking
- [ ] Output schema has same columns but masked/hashed values
- [ ] Generated PySpark uses masking UDFs, not plaintext transforms

---

## S16: JSON Flattening

**Natural Language Requirement:**
> "The TPC-DI CustomerMgmt.xml has been converted to nested JSON. It has structure: {ActionType, Customer: {C_ID, Name: {C_F_NAME, C_L_NAME}, Address: {C_ADLINE1, C_CITY, C_STATE_PROV, C_ZIPCODE}, ContactInfo: {C_PHONE_1, C_EMAIL}, Account: {CA_ID, CA_TAX_ST}}}. Flatten it into a table and write to the data lake."

**Data Source & Schema:**
- Source: TPC-DI `CustomerMgmt.xml` converted to JSON on S3
- Format: Nested JSON
- Structure: ActionType (top-level), Customer (nested with sub-objects: Name, Address, ContactInfo, Account)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → JsonFlatten → LakeWriter`
- JsonFlatten params: `recursive_flatten=true`, `separator='_'`
- Expected output columns: ActionType, Customer_C_ID, Customer_Name_C_F_NAME, Customer_Name_C_L_NAME, Customer_Address_C_ADLINE1, Customer_Address_C_CITY, Customer_Address_C_STATE_PROV, Customer_Address_C_ZIPCODE, Customer_ContactInfo_C_PHONE_1, Customer_ContactInfo_C_EMAIL, Customer_Account_CA_ID, Customer_Account_CA_TAX_ST

**Validation Checklist:**
- [ ] JsonFlatten selected (not manual transforms)
- [ ] Recursive flatten configured for all nesting levels
- [ ] Nested structs flattened with underscore naming convention
- [ ] No arrays to explode in this case (unlike items in e-commerce)
- [ ] Schema inference produces correct flattened column names
- [ ] Column count matches total leaf-level fields in JSON
- [ ] Generated PySpark uses `select(col("Customer.Name.C_F_NAME").alias(...))` or equivalent flatten logic

---

## S17: Struct Builder

**Natural Language Requirement:**
> "Take the flat Freddie Mac origination file and build a nested JSON structure: group loan attributes (UPB, LTV, DTI, Interest_Rate) under a `loan_terms` struct and borrower attributes (Credit_Score, First_Time_Buyer, Occupancy) under a `borrower_profile` struct. Write to the lake as JSON."

**Data Source & Schema:**
- Source: Freddie Mac origination file on S3 (pipe-delimited, flat)
- Key columns: Loan Sequence Number, Credit Score, First Time Buyer, Occupancy, UPB, LTV, DTI, Interest Rate, Channel

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → JsonStruct → LakeWriter`
- JsonStruct params:
  - `structs=[{name: loan_terms, columns: [UPB, LTV, DTI, Interest_Rate]}, {name: borrower_profile, columns: [Credit_Score, First_Time_Buyer, Occupancy]}]`
  - Remaining columns (Loan_Sequence_Number, Channel) stay at top level
- LakeWriter params: `lake_format=json` or `lake_format=parquet` (nested struct preserved in Parquet)

**Validation Checklist:**
- [ ] JsonStruct blueprint selected
- [ ] Two struct groups defined: `loan_terms` and `borrower_profile`
- [ ] Correct columns assigned to each struct
- [ ] Top-level columns preserved (Loan_Sequence_Number, Channel)
- [ ] Output schema has nested structure
- [ ] Generated PySpark uses `struct()` to build nested columns
- [ ] Data readable back with correct nesting

---

## S18: DQ Validation (GX)

**Natural Language Requirement:**
> "Ingest the Freddie Mac monthly performance file and run data quality checks before loading to the lake. Validate: Loan Sequence Number is not null, Current Loan Delinquency Status is between 0 and 99, Current Actual UPB is positive, and Monthly Reporting Period matches date format."

**Data Source & Schema:**
- Source: Freddie Mac monthly performance on S3
- Format: Pipe-delimited
- Key columns: Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status, Loan Age

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → GXExpectationSuite → LakeWriter`
- GXExpectationSuite params:
  - `expect_column_values_to_not_be_null(Loan_Sequence_Number)`
  - `expect_column_values_to_be_between(Current_Loan_Delinquency_Status, 0, 99)`
  - `expect_column_values_to_be_between(Current_Actual_UPB, 0, None)`
  - `expect_column_values_to_match_regex(Monthly_Reporting_Period, '^\d{6}$')`

**Validation Checklist:**
- [ ] GXExpectationSuite blueprint selected
- [ ] Four expectations defined with correct parameters
- [ ] Not-null check on Loan Sequence Number
- [ ] Range check on delinquency status (0–99)
- [ ] Positive value check on UPB
- [ ] Regex format check on reporting period (YYYYMM)
- [ ] DQ results logged/stored for downstream reporting
- [ ] Pipeline behavior on DQ failure configurable (fail-fast vs warn)
- [ ] Generated code integrates Great Expectations or equivalent

---

## S19: Schema Normalization

**Natural Language Requirement:**
> "Normalize the Lending Club loans and Freddie Mac origination files into a common loan schema with columns: loan_id, loan_amount, interest_rate, credit_score, loan_status, source_system. Union them into a single table."

**Data Source & Schema:**
- Source A: Lending Club loans CSV — columns: id, loan_amnt, int_rate, grade, loan_status
- Source B: Freddie Mac origination — columns: Loan Sequence Number, UPB, Interest Rate, Credit Score, Channel

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(LC) + FileIngestion(FM) → SchemaNormalization(LC) + SchemaNormalization(FM) → Union → LakeWriter`
- SchemaNormalization for LC: `{id → loan_id, loan_amnt → loan_amount, int_rate → interest_rate, null → credit_score, loan_status → loan_status, 'LendingClub' → source_system}`
- SchemaNormalization for FM: `{Loan_Sequence_Number → loan_id, UPB → loan_amount, Interest_Rate → interest_rate, Credit_Score → credit_score, null → loan_status, 'FreddieMac' → source_system}`

**Validation Checklist:**
- [ ] Two parallel ingestion branches
- [ ] Each branch has its own SchemaNormalization
- [ ] Column renaming correct for each source
- [ ] Missing columns filled with null or default values
- [ ] Type casting applied where needed (e.g., int_rate string → float)
- [ ] Union combines both branches into one dataset
- [ ] Output schema matches common target: loan_id, loan_amount, interest_rate, credit_score, loan_status, source_system
- [ ] `source_system` literal column added for lineage

---

## S20: Dedup and Merge

**Natural Language Requirement:**
> "The TPC-DI Trade file may contain duplicate records from overlapping extracts. Deduplicate by T_ID, keeping the record with the latest T_DTS, and merge into the existing trade table in the lake."

**Data Source & Schema:**
- Source: TPC-DI `Trade.txt` on S3 (with intentionally injected duplicates)
- Format: Pipe-delimited
- Key columns: T_ID (business key), T_DTS (timestamp for dedup), T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → DedupeAndMerge → LakeWriter`
- DedupeAndMerge params: `business_key=T_ID`, `order_by=T_DTS DESC`, `strategy=keep_latest`
- LakeWriter params: `lake_format=delta`, `write_mode=merge`, `merge_key=T_ID`

**Validation Checklist:**
- [ ] DedupeAndMerge blueprint selected
- [ ] Business key = `T_ID`
- [ ] Dedup ordering = `T_DTS DESC` (keep latest)
- [ ] Generated PySpark uses window function: `ROW_NUMBER() OVER (PARTITION BY T_ID ORDER BY T_DTS DESC)`
- [ ] Write mode = merge/upsert (not append — would re-introduce duplicates)
- [ ] Merge key = `T_ID` for Delta MERGE INTO
- [ ] Output row count ≤ input row count (duplicates removed)
- [ ] Idempotent: re-running produces same result

---

# Tier 3: Complex Patterns (S21–S30) — SCD2, Multi-Pipeline, Temporal

> These test advanced data engineering patterns including slowly changing dimensions, cross-pipeline dependencies, and temporal complexity.

---

## S21: SCD2 Customer Dimension

**Natural Language Requirement:**
> "Build a slowly changing dimension (Type 2) for TPC-DI customers. Source is a daily full extract from Postgres. Track changes to name, address, and email. Maintain effective dates and a current flag."

**Data Source & Schema:**
- Source: TPC-DI `Customer` table in PostgreSQL (daily snapshot)
- Key columns: C_ID (business key), C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion → SCD2Dimension → LakeWriter`
- SCD2Dimension params:
  - `business_key=C_ID`
  - `tracked_columns=[C_L_NAME, C_F_NAME, C_EMAIL]`
  - `effective_from_col=effective_start_date`
  - `effective_to_col=effective_end_date`
  - `current_flag_col=is_current`

**Validation Checklist:**
- [ ] SCD2Dimension blueprint selected
- [ ] Business key correctly identified as `C_ID` (not name)
- [ ] Tracked columns include mutable attributes (name, email)
- [ ] AI asks which columns to track if not specified
- [ ] Output schema: all source columns + `effective_start_date`, `effective_end_date`, `is_current`, `scd2_hash`
- [ ] Generated PySpark implements merge: match on business_key, compare hash, close old record, insert new
- [ ] Write mode = merge (not overwrite)
- [ ] First run: all records inserted with `is_current=true`, `effective_end_date=9999-12-31`
- [ ] Subsequent runs: changed records get closed and new version inserted

---

## S22: Incremental Merge

**Natural Language Requirement:**
> "Freddie Mac releases updated monthly performance data. Build an incremental pipeline that merges new/updated records into the existing performance table using Loan Sequence Number + Monthly Reporting Period as the composite key."

**Data Source & Schema:**
- Source: Freddie Mac monthly performance file on S3 (incremental updates)
- Format: Pipe-delimited
- Key columns: Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status, Loan Age, Remaining Months to Maturity

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → IncrementalMerge → LakeWriter`
- IncrementalMerge params:
  - `merge_keys=[Loan_Sequence_Number, Monthly_Reporting_Period]`
  - `strategy=upsert` (insert if new, update if exists)
- LakeWriter params: `lake_format=delta`, `write_mode=merge`

**Validation Checklist:**
- [ ] IncrementalMerge blueprint selected
- [ ] Composite merge key: `Loan_Sequence_Number + Monthly_Reporting_Period`
- [ ] Upsert strategy: new records inserted, existing records updated
- [ ] Generated PySpark uses Delta MERGE INTO with composite match condition
- [ ] Idempotent: re-running same file produces same result
- [ ] Performance: only processes new/changed records
- [ ] Existing unmatched records preserved unchanged

---

## S23: Fact Table Build

**Natural Language Requirement:**
> "Build a trade fact table from TPC-DI data. Join Trade records with DimCustomer (from S21) and DimSecurity (from Industry lookup). The fact table should have surrogate keys from the dimensions, trade metrics, and a snapshot date."

**Data Source & Schema:**
- Source A: TPC-DI `Trade.txt` on S3 (T_ID, T_DTS, T_ST_ID, T_TT_ID, T_S_SYMB, T_QTY, T_BID_PRICE, T_CA_ID)
- Source B: DimCustomer (output of S21) in Delta Lake (C_ID, effective_start_date, effective_end_date, is_current, sk_customer_id)
- Source C: TPC-DI `Industry.txt` reference on S3 (IN_ID, IN_NAME, IN_SC_ID)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(Trade) + SnapshotIngestion(DimCustomer) + FileIngestion(Industry) → GenericJoin(Trade×Account→Customer) → GenericJoin(result×Industry) → FactBuild → WarehouseWriter`
- FactBuild params:
  - `fact_key=T_ID`
  - `dimension_keys=[sk_customer_id, industry_id]`
  - `measures=[T_QTY, T_BID_PRICE, trade_amount(=T_QTY * T_BID_PRICE)]`
  - `snapshot_date_col=trade_date`

**Validation Checklist:**
- [ ] Three ingestion branches for Trade, DimCustomer, Industry
- [ ] Joins resolve surrogate keys from dimensions
- [ ] FactBuild creates fact table with surrogate FK references
- [ ] Derived measure `trade_amount = T_QTY * T_BID_PRICE` computed
- [ ] Star schema pattern: fact references dimensions by surrogate key
- [ ] Point-in-time correctness: Trade joined to DimCustomer version active at trade date
- [ ] Generated PySpark handles temporal join for SCD2 dimension

---

## S24: Snapshot Model

**Natural Language Requirement:**
> "Build a daily snapshot model of TPC-DI Account data. Each day's full extract should be stored as a point-in-time snapshot with a snapshot_date column."

**Data Source & Schema:**
- Source: TPC-DI `Account` table in PostgreSQL (daily full load)
- Key columns: CA_ID, CA_B_ID, CA_C_ID, CA_NAME, CA_TAX_ST, CA_ST_ID

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion → SnapshotModel → LakeWriter`
- SnapshotModel params:
  - `snapshot_date_col=snapshot_date`
  - `business_key=CA_ID`
  - `strategy=full_snapshot` (store complete state each day)
- LakeWriter params: `lake_format=delta`, `partition_by=[snapshot_date]`

**Validation Checklist:**
- [ ] SnapshotModel blueprint selected
- [ ] `snapshot_date` column added from execution date (`{{ ds }}`)
- [ ] Partition by `snapshot_date` for efficient time-travel queries
- [ ] Full snapshot stored each day (no dedup across days)
- [ ] Business key `CA_ID` used for optional change detection
- [ ] Write mode = append (each day adds a new partition)
- [ ] Generated PySpark adds `snapshot_date` literal column
- [ ] Historical queries possible: `WHERE snapshot_date = '2026-01-15'`

---

## S25: Cross-Pipeline Dependency

**Natural Language Requirement:**
> "Pipeline A ingests the daily Freddie Mac origination file to the silver zone (reuse S01 output). Pipeline B reads the silver zone, computes summary statistics by channel, and writes to the gold reporting warehouse. Pipeline B should only run after Pipeline A completes."

**Data Source & Schema:**
- Pipeline A source: Freddie Mac origination file on S3 (same as S01)
- Pipeline B source: Silver zone Delta table (output of Pipeline A)

**Expected Pipeline Composition:**

Pipeline A (Loan Ingestion):
- `FileIngestion → LakeWriter(silver)`
- Publishes Airflow Dataset: `s3://benchmark-data/silver/freddie_mac_origination/`

Pipeline B (Loan Analytics):
- Reads silver zone → `GenericAggregate(group_by=[Channel]) → WarehouseWriter(gold)`
- Triggered by: Airflow Dataset from Pipeline A
- Schedule: Dataset-triggered (not cron)

**Validation Checklist:**
- [ ] Two separate Business Pipelines generated
- [ ] Pipeline A publishes an Airflow Dataset on completion
- [ ] Pipeline B schedule = `[Dataset("s3://benchmark-data/silver/freddie_mac_origination/")]`
- [ ] Pipeline B has no S3 file sensor (triggered by upstream dataset)
- [ ] Cross-pipeline dependency via Airflow Dataset (not ExternalTaskSensor)
- [ ] Each pipeline generates its own DAG file
- [ ] Pipeline B aggregates by Channel correctly

---

## S26: Mixed Periodicity Resolution

**Natural Language Requirement:**
> "Build a reconciliation pipeline. Source A is hourly Kafka trade events. Source B is a daily Freddie Mac origination file. Combine them for analysis."

**Data Source & Schema:**
- Source A: Kafka topic `trade-events` (hourly micro-batch)
- Source B: Freddie Mac origination file on S3 (daily)

**Expected Behavior:**
- AI MUST detect the time grain mismatch (hourly vs daily)
- AI MUST ask the user to resolve: "Your sources have different cadences (hourly events vs daily file). Do you want to run hourly (re-reads daily data each hour) or daily (accumulates all hourly events per day)?"
- Pipeline schedule set based on user's answer

**Expected Pipeline Composition (daily resolution):**
- `StreamIngestion(Kafka, daily batch window) + FileIngestion(FM) → GenericJoin → LakeWriter`

**Validation Checklist:**
- [ ] AI detects the time grain mismatch
- [ ] AI asks the user to resolve (not silently defaulting)
- [ ] Pipeline schedule matches user's choice
- [ ] If daily: Kafka source reads a full day's worth of events per run
- [ ] If hourly: file source re-reads same daily file each hour (with caching note)
- [ ] Join logic handles the periodicity alignment
- [ ] Generated DAG correctly implements chosen cadence

---

## S27: SQL Readiness Sensor

**Natural Language Requirement:**
> "We ingest from the TPC-DI tables in Postgres, but we can only pull after the nightly batch job finishes. The status is tracked in BATCH_JOBS(job_name, status, as_of_date). Poll it to check if 'TPCDI_DAILY_LOAD' is 'COMPLETED' for today."

**Data Source & Schema:**
- Source: TPC-DI tables in PostgreSQL
- Control table: `BATCH_JOBS` (job_name VARCHAR, status VARCHAR, as_of_date DATE)

**Expected Pipeline Composition:**
- Sub-pipelines: `SqlSensor → SnapshotIngestion → LakeWriter`
- Readiness query: `SELECT COUNT(*) FROM BATCH_JOBS WHERE job_name = 'TPCDI_DAILY_LOAD' AND status = 'COMPLETED' AND as_of_date = '{{ ds }}'`
- Sensor: `SqlSensor` with poke_interval=300, timeout=21600

**Validation Checklist:**
- [ ] SqlSensor generated (not S3KeySensor)
- [ ] Readiness query uses `{{ ds }}` Airflow macro for date resolution
- [ ] Connection for sensor = PostgreSQL connection
- [ ] Sensor precedes the actual data ingestion task
- [ ] Time-grain-aware defaults (daily: poke 5min, timeout 6h)
- [ ] AI explains the sensor configuration to the user
- [ ] DAG: sql_sensor >> snapshot_ingest >> write

---

## S28: Late-Arriving Data (3-Day Window)

**Natural Language Requirement:**
> "Freddie Mac performance file corrections can arrive up to 3 days late. Build an incremental pipeline that re-processes the last 3 days on each run to handle late corrections."

**Data Source & Schema:**
- Source: Freddie Mac performance files on S3 (daily, naming: `perf_YYYYMMDD.txt`)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(lookback) → IncrementalMerge → LakeWriter`
- Lookback window: 3 days
- Generated code reads files for `{{ ds }}`, `{{ macros.ds_add(ds, -1) }}`, `{{ macros.ds_add(ds, -2) }}`
- Write mode: merge (not append)

**Validation Checklist:**
- [ ] Lookback window = 3 days configured
- [ ] Generated PySpark reads 3 files (today + 2 previous days)
- [ ] Write mode is merge/upsert (not append — would create duplicates)
- [ ] Merge key = `Loan_Sequence_Number + Monthly_Reporting_Period`
- [ ] AI should ask about the merge key if not obvious
- [ ] Airflow DAG has `depends_on_past=False` (each run is self-contained)
- [ ] Idempotent: corrections overwrite previous values

---

## S29: Timezone-Aware Sensing

**Natural Language Requirement:**
> "Synthetic payment files are generated in US Eastern time (EST/EDT). Filenames use local dates: `payment_summary_20260225.csv`. Our Airflow runs in UTC. Make sure the sensor looks for the correct file."

**Data Source & Schema:**
- Source: Synthetic ACME payment files on S3 named in EST dates
- File naming: `payment_summary_YYYYMMDD.csv` (EST dates)

**Expected Pipeline Composition:**
- Same as S01, but with timezone handling
- FileIngestion params: `arrival_timezone=America/New_York`
- Sensor resolves filename using local timezone, not UTC

**Validation Checklist:**
- [ ] `arrival_timezone` configured as `America/New_York`
- [ ] Generated sensor code converts `execution_date` to local timezone before resolving filename
- [ ] Handles EST/EDT transition correctly
- [ ] Example: Airflow run 2026-02-25 UTC → sensor checks for `payment_summary_20260225.csv`
- [ ] No off-by-one date errors at day boundary
- [ ] Generated code uses `pendulum` or `pytz` for timezone conversion

---

## S30: Catchup/Backfill Prompt

**Natural Language Requirement:**
> "We're setting up a new pipeline for Freddie Mac origination data. We need to backfill from January 1, 2026. There are about 56 days of history."

**Expected Behavior:**
- AI MUST ask: "Do you need to process historical data from January 1? This will create ~56 backfill runs. Confirm?"
- `catchup=True`
- `start_date=2026-01-01`
- `max_active_runs` limited (e.g., 3)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → LakeWriter` (same as S01 but with catchup)
- DAG params: `catchup=True`, `start_date='2026-01-01'`, `max_active_runs=3`

**Validation Checklist:**
- [ ] AI prompts user about backfill (not silently enabling)
- [ ] `catchup=True` in generated DAG
- [ ] `start_date = 2026-01-01`
- [ ] `max_active_runs` is constrained (not unlimited)
- [ ] AI warns about potential cost/resource implications
- [ ] Generated DAG handles missing files gracefully (some historical dates may not have data)
- [ ] Backfill runs execute in chronological order

---

# Tier 4: Enterprise Patterns (S31–S40) — Fan-In, Reconciliation, Advanced

> These test real-world enterprise complexity including multi-source fan-in, data vault, reconciliation, and ML feature engineering.

---

## S31: 5-Source Fan-In

**Natural Language Requirement:**
> "Five different sources provide loan data in different formats: 3 regional Freddie Mac files on S3 (CSV), 1 Lending Club file on S3 (CSV), and 1 synthetic API endpoint. Normalize all into a common loan schema and load into a single unified loans table."

**Data Source & Schema:**
- Source A: Freddie Mac Region East (`s3://benchmark-data/raw/fm_east/orig_east.txt`, pipe-delimited)
- Source B: Freddie Mac Region West (`s3://benchmark-data/raw/fm_west/orig_west.txt`, pipe-delimited)
- Source C: Freddie Mac Region Central (`s3://benchmark-data/raw/fm_central/orig_central.txt`, pipe-delimited)
- Source D: Lending Club loans (`s3://benchmark-data/raw/lending_club/loans.csv`, CSV)
- Source E: Synthetic API (`https://api.acme-benchmark.com/v1/loans`, REST JSON)

**Expected Pipeline Composition:**
- 5 ingestion sub-pipelines: `3x FileIngestion(FM) + 1x FileIngestion(LC) + 1x ApiIngestion`
- 5 SchemaNormalization transforms → common schema: `loan_id, loan_amount, interest_rate, credit_score, source_system, region`
- `Union → GXExpectationSuite → LakeWriter`

**Validation Checklist:**
- [ ] 5 parallel ingestion branches
- [ ] Each FM branch has S3 sensor; LC has S3 sensor; API has no sensor
- [ ] Schema normalization aligns all 5 to common output columns
- [ ] Column renaming/type casting correct per source
- [ ] Union step combines all 5 branches
- [ ] DQ runs on unified output (not per-branch)
- [ ] Single write to target table
- [ ] `source_system` column identifies origin of each record
- [ ] DAG: 5 parallel TaskGroups >> union >> DQ >> write

---

## S32: Enrichment Join (Lookup)

**Natural Language Requirement:**
> "Enrich TPC-DI Trade records by looking up the Industry name from Industry.txt and the status description from StatusType.txt. Add these as new columns to the trade data."

**Data Source & Schema:**
- Source A: TPC-DI `Trade.txt` on S3 (T_ID, T_DTS, T_ST_ID, T_TT_ID, T_S_SYMB, T_QTY, T_BID_PRICE)
- Source B: TPC-DI `Industry.txt` on S3 (IN_ID, IN_NAME, IN_SC_ID)
- Source C: TPC-DI `StatusType.txt` on S3 (ST_ID, ST_NAME)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(Trade) + FileIngestion(Industry) + FileIngestion(StatusType) → EnrichmentJoin(Trade×StatusType on T_ST_ID=ST_ID) → EnrichmentJoin(result×Industry) → LakeWriter`
- EnrichmentJoin params: lookup-style left join, small dimension broadcast

**Validation Checklist:**
- [ ] EnrichmentJoin blueprint selected (lookup pattern, not full join)
- [ ] Two sequential enrichment joins
- [ ] Join keys: `T_ST_ID = ST_ID` (status lookup), then symbol-to-industry mapping
- [ ] Lookup tables broadcast (small reference data)
- [ ] Output schema: all Trade columns + `ST_NAME`, `IN_NAME`
- [ ] Generated PySpark uses broadcast join for small lookup tables
- [ ] No data loss from Trade table (left join semantics)

---

## S33: Conformance to Enterprise Model

**Natural Language Requirement:**
> "Map TPC-DI Customer data and Freddie Mac borrower data to a canonical enterprise Customer model: customer_id, full_name, date_of_birth, email, source_system, conformance_date."

**Data Source & Schema:**
- Source A: TPC-DI `Customer` in PostgreSQL (C_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL)
- Source B: Freddie Mac origination (borrower data extracted: Borrower_ID, Borrower_Name, DOB, Contact_Email)

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion(TPC-DI Customer) + FileIngestion(FM borrower) → ConformanceToEnterpriseModel(TPC-DI) + ConformanceToEnterpriseModel(FM) → Union → LakeWriter`
- ConformanceToEnterpriseModel params for TPC-DI: `{C_ID → customer_id, concat(C_F_NAME, ' ', C_L_NAME) → full_name, C_DOB → date_of_birth, C_EMAIL → email, 'TPC-DI' → source_system}`
- ConformanceToEnterpriseModel params for FM: `{Borrower_ID → customer_id, Borrower_Name → full_name, DOB → date_of_birth, Contact_Email → email, 'FreddieMac' → source_system}`

**Validation Checklist:**
- [ ] ConformanceToEnterpriseModel blueprint selected
- [ ] Two separate conformance mappings (one per source)
- [ ] Column transformations correct (e.g., name concatenation for TPC-DI)
- [ ] All output schemas identical before union
- [ ] `source_system` and `conformance_date` added
- [ ] Generated PySpark applies column mapping per source
- [ ] Union produces unified enterprise customer table

---

## S34: Data Vault (Hub-Link-Sat)

**Natural Language Requirement:**
> "Build a Data Vault model from TPC-DI data: Hub_Customer (from Customer), Hub_Account (from Account), Link_Customer_Account (relationship), and Sat_Customer (customer attributes). Use hash keys."

**Data Source & Schema:**
- Source A: TPC-DI `Customer` in PostgreSQL (C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL)
- Source B: TPC-DI `Account` in PostgreSQL (CA_ID, CA_B_ID, CA_C_ID, CA_NAME, CA_TAX_ST)
- Source C: TPC-DI `Trade` on S3 (T_ID, T_DTS, T_CA_ID, T_S_SYMB, T_QTY)

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion(Customer) + SnapshotIngestion(Account) + FileIngestion(Trade) → DataVaultHubLinkSat → LakeWriter(hub_customer) + LakeWriter(hub_account) + LakeWriter(link_customer_account) + LakeWriter(sat_customer)`
- DataVaultHubLinkSat params:
  - `hubs=[{name: hub_customer, business_key: C_ID, hash_key: hk_customer}, {name: hub_account, business_key: CA_ID, hash_key: hk_account}]`
  - `links=[{name: link_customer_account, hub_refs: [hub_customer, hub_account], hash_key: hk_cust_acct}]`
  - `satellites=[{name: sat_customer, hub: hub_customer, attributes: [C_L_NAME, C_F_NAME, C_DOB, C_EMAIL], hashdiff: hd_customer}]`

**Validation Checklist:**
- [ ] DataVaultHubLinkSat blueprint selected
- [ ] Two hubs created with correct business keys and hash keys
- [ ] Link connects Customer and Account via `CA_C_ID`
- [ ] Satellite stores customer attributes with hash diff for change detection
- [ ] All tables include `load_date` and `record_source`
- [ ] Hash keys generated using MD5 or SHA-256
- [ ] Generated PySpark creates separate DataFrames for each DV entity
- [ ] Multiple LakeWriter outputs (one per DV table)

---

## S35: Reconciliation (Source vs Target)

**Natural Language Requirement:**
> "Reconcile the Freddie Mac origination source file against the warehouse target table. Compare record counts, total UPB, and check for orphan records in either direction."

**Data Source & Schema:**
- Source: Freddie Mac origination file on S3 (the source of truth)
- Target: Warehouse table `freddie_mac_origination` (loaded by S01)
- Reconciliation keys: Loan Sequence Number

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(source file) + SnapshotIngestion(warehouse table) → Reconciliation → AlertWriter`
- Reconciliation params:
  - `match_key=Loan_Sequence_Number`
  - `checks=[{type: row_count, tolerance: 0}, {type: sum, column: UPB, tolerance: 0.01}, {type: orphan_check, direction: both}]`

**Validation Checklist:**
- [ ] Reconciliation blueprint selected
- [ ] Two ingestion branches (source file + warehouse table)
- [ ] Row count comparison with zero tolerance
- [ ] UPB sum comparison with 0.01 tolerance
- [ ] Orphan detection in both directions (source-only, target-only records)
- [ ] AlertWriter outputs reconciliation report/discrepancies
- [ ] Generated PySpark computes counts, sums, and anti-joins
- [ ] Reconciliation results logged for audit trail

---

## S36: Wide Denormalized Mart

**Natural Language Requirement:**
> "Build a wide denormalized reporting mart from TPC-DI by flattening the star schema: join Trade (fact) with Customer, Account, Industry, StatusType, and TradeType dimensions into one wide table."

**Data Source & Schema:**
- Fact: TPC-DI `Trade` in PostgreSQL (T_ID, T_DTS, T_ST_ID, T_TT_ID, T_S_SYMB, T_QTY, T_BID_PRICE, T_CA_ID)
- Dim: `Customer` (C_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL)
- Dim: `Account` (CA_ID, CA_B_ID, CA_C_ID, CA_NAME)
- Ref: `Industry` (IN_ID, IN_NAME, IN_SC_ID)
- Ref: `StatusType` (ST_ID, ST_NAME)
- Ref: `TradeType` (TT_ID, TT_NAME, TT_IS_SELL, TT_IS_MRKT)

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion x4 + FileIngestion x2 → GenericJoin(Trade×Account) → GenericJoin(×Customer) → GenericJoin(×StatusType) → GenericJoin(×TradeType) → WideDenormalizedMart → WarehouseWriter`
- Multiple sequential joins flattening star to wide table

**Validation Checklist:**
- [ ] All 6 sources ingested (4 DB snapshots + 2 reference files)
- [ ] 4+ sequential joins to denormalize
- [ ] Join keys correct at each hop
- [ ] WideDenormalizedMart produces single wide table
- [ ] Output has columns from all sources (30+ columns)
- [ ] Reference tables broadcast-joined
- [ ] Generated PySpark chains joins efficiently (small tables broadcast)
- [ ] No duplicate column names in final output (aliased properly)

---

## S37: Derived Metrics Computation

**Natural Language Requirement:**
> "From the Freddie Mac monthly performance data, compute derived metrics: Month-over-Month UPB change, cumulative delinquency count, and a delinquency trend flag (improving/worsening/stable). Partition output by year-month."

**Data Source & Schema:**
- Source: Freddie Mac monthly performance on S3
- Key columns: Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status, Loan Age

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → DerivedMetricsComputation → LakeWriter`
- DerivedMetricsComputation params:
  - `partition_key=Loan_Sequence_Number`
  - `order_key=Monthly_Reporting_Period`
  - `metrics=[{name: mom_upb_change, formula: current_upb - lag(current_upb, 1)}, {name: cumulative_delinquency, formula: running_sum(delinquency_status)}, {name: delinquency_trend, formula: case(mom_delinquency_change > 0 → 'WORSENING', < 0 → 'IMPROVING', else → 'STABLE')}]`

**Validation Checklist:**
- [ ] DerivedMetricsComputation blueprint selected
- [ ] Window functions used: `LAG()`, running sum, `CASE WHEN`
- [ ] Partition by `Loan_Sequence_Number`, ordered by `Monthly_Reporting_Period`
- [ ] Month-over-month UPB change calculated correctly
- [ ] Cumulative delinquency count is running total
- [ ] Delinquency trend flag derived from month-over-month change
- [ ] Output partitioned by year-month for efficient queries
- [ ] Generated PySpark uses `Window.partitionBy().orderBy()` with lag/sum

---

## S38: Feature Table (ML-Ready)

**Natural Language Requirement:**
> "Build an ML-ready feature table from Lending Club data. Join loans with payment history, compute features: loan_to_income_ratio, months_since_earliest_credit_line, payment_to_installment_ratio, is_verified. Publish as a feature table."

**Data Source & Schema:**
- Source A: Lending Club loans CSV (id, loan_amnt, annual_inc, installment, term, int_rate, grade, verification_status, earliest_cr_line, issue_d)
- Source B: Lending Club payments CSV (loan_id, payment_date, payment_amnt)

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion(loans) + FileIngestion(payments) → GenericJoin → FeatureTablePublish → LakeWriter`
- FeatureTablePublish params:
  - `entity_key=loan_id`
  - `features=[{name: loan_to_income_ratio, formula: loan_amnt / annual_inc}, {name: months_since_earliest_credit_line, formula: months_between(issue_d, earliest_cr_line)}, {name: payment_to_installment_ratio, formula: avg(payment_amnt) / installment}, {name: is_verified, formula: verification_status == 'Verified'}]`
  - `timestamp_col=issue_d`

**Validation Checklist:**
- [ ] FeatureTablePublish blueprint selected
- [ ] Two sources joined on loan id
- [ ] Four derived features computed correctly
- [ ] Feature table has entity_key, timestamp, and feature columns
- [ ] Division-by-zero handling (annual_inc = 0, installment = 0)
- [ ] Null handling for missing values
- [ ] Output suitable for ML training (no data leakage from future data)
- [ ] Generated PySpark computes all features with proper null guards

---

## S39: Anomaly Detection

**Natural Language Requirement:**
> "Monitor TPC-DI DailyMarket data for anomalies. Flag any stock where daily close price deviates more than 3 standard deviations from its 30-day moving average. Write flagged records to an alerts table and all records (with anomaly flag) to the lake."

**Data Source & Schema:**
- Source: TPC-DI `DailyMarket.txt` on S3 (with injected anomalies)
- Key columns: DM_DATE, DM_S_SYMB, DM_CLOSE, DM_HIGH, DM_LOW, DM_VOL

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → AnomalyDetection → LakeWriter + AlertWriter`
- AnomalyDetection params:
  - `partition_key=DM_S_SYMB`
  - `order_key=DM_DATE`
  - `metric_column=DM_CLOSE`
  - `method=z_score`
  - `window=30` (30-day rolling)
  - `threshold=3.0` (3 standard deviations)

**Validation Checklist:**
- [ ] AnomalyDetection blueprint selected
- [ ] 30-day rolling window statistics computed per symbol
- [ ] Z-score calculation correct: `(close - rolling_mean) / rolling_stddev`
- [ ] Threshold = 3.0 standard deviations
- [ ] Anomaly flag added to all records (`is_anomaly` boolean)
- [ ] Two outputs: full dataset to lake, flagged records to alert sink
- [ ] Generated PySpark uses `Window.partitionBy(symbol).orderBy(date).rowsBetween(-29, 0)`
- [ ] Handles edge cases: insufficient history (< 30 days), zero stddev

---

## S40: Reference Data Publish

**Natural Language Requirement:**
> "Publish TPC-DI reference data tables (StatusType, TradeType, Industry) to the warehouse. These are small, infrequently changing lookup tables. Full refresh on each load."

**Data Source & Schema:**
- Source A: TPC-DI `StatusType.txt` on S3 (ST_ID, ST_NAME)
- Source B: TPC-DI `TradeType.txt` on S3 (TT_ID, TT_NAME, TT_IS_SELL, TT_IS_MRKT)
- Source C: TPC-DI `Industry.txt` on S3 (IN_ID, IN_NAME, IN_SC_ID)

**Expected Pipeline Composition:**
- Sub-pipelines: `3x FileIngestion → ReferenceDataPublish → 3x WarehouseWriter`
- ReferenceDataPublish params: `strategy=full_refresh`, `cache=true` (small tables)
- Each WarehouseWriter targets a separate reference table
- Write mode: `overwrite` (full refresh)

**Validation Checklist:**
- [ ] ReferenceDataPublish blueprint selected
- [ ] Three parallel ingestion branches
- [ ] Full refresh strategy (overwrite, not append)
- [ ] Small table handling (no partitioning needed)
- [ ] Each table written to its own warehouse target
- [ ] Generated PySpark reads small files and writes with overwrite mode
- [ ] DAG: 3 parallel (sensor >> ingest >> write) branches
- [ ] Optionally cached for downstream join performance

---

# Tier 5: DQ Deep Dive (S41–S45) — Data Quality Focused

> These scenarios specifically test deep data quality capabilities beyond basic validation.

---

## S41: Freshness Check

**Natural Language Requirement:**
> "The Freddie Mac monthly performance file should arrive by the 5th of each month. Build a freshness check that raises an alert if the file hasn't arrived by the SLA deadline."

**Data Source & Schema:**
- Source: Freddie Mac monthly performance file on S3
- SLA: File must arrive by 5th of month
- File naming: `perf_YYYYMM.txt`

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → FreshnessChecks → LakeWriter`
- FreshnessChecks params:
  - `sla_deadline=day_of_month(5)`
  - `alert_on_breach=true`
  - `alert_channel=email` (or Slack/PagerDuty)
  - `check_last_modified_date=true`

**Validation Checklist:**
- [ ] FreshnessChecks blueprint selected
- [ ] SLA deadline configured: file expected by 5th of month
- [ ] Alert triggered if file not present by deadline
- [ ] File arrival timestamp checked (S3 last_modified or equivalent)
- [ ] Generated Airflow uses `TimeSensor` or custom sensor for SLA window
- [ ] Alert includes: expected file, deadline, current status
- [ ] Pipeline proceeds normally if file arrives on time
- [ ] Late arrival still processed (alert is warning, not blocking)

---

## S42: Schema Drift Detection

**Natural Language Requirement:**
> "The TPC-DI Customer file schema may change between batches (columns added or removed). Detect schema drift and alert before processing. Allow new columns but reject removed columns."

**Data Source & Schema:**
- Source: TPC-DI `Customer.txt` on S3 (schema may change between batches)
- Baseline schema: C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL
- Possible drift: new columns added (e.g., C_PHONE), or columns removed

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → SchemaDriftDetection → LakeWriter`
- SchemaDriftDetection params:
  - `baseline_schema=stored_schema_from_previous_run`
  - `policy={new_columns: allow, removed_columns: reject, type_changes: warn}`
  - `alert_on_drift=true`

**Validation Checklist:**
- [ ] SchemaDriftDetection blueprint selected
- [ ] Baseline schema stored from previous successful run
- [ ] New columns: detected, logged, allowed to pass through
- [ ] Removed columns: detected, pipeline halted, alert sent
- [ ] Type changes: detected, warning raised
- [ ] Generated PySpark compares incoming schema vs stored baseline
- [ ] Schema evolution log maintained for audit
- [ ] First run establishes baseline (no drift possible)

---

## S43: Referential Integrity Check

**Natural Language Requirement:**
> "Validate referential integrity between TPC-DI Trade and Account tables. Every T_CA_ID in Trade must exist in Account.CA_ID. Flag orphan trades that reference non-existent accounts."

**Data Source & Schema:**
- Source A: TPC-DI `Trade` in PostgreSQL (T_ID, T_CA_ID, ...)
- Source B: TPC-DI `Account` in PostgreSQL (CA_ID, ...)

**Expected Pipeline Composition:**
- Sub-pipelines: `SnapshotIngestion(Trade) + SnapshotIngestion(Account) → ReferentialIntegrityCheck`
- ReferentialIntegrityCheck params:
  - `child_table=Trade`
  - `child_key=T_CA_ID`
  - `parent_table=Account`
  - `parent_key=CA_ID`
  - `on_violation=flag_and_quarantine`

**Validation Checklist:**
- [ ] ReferentialIntegrityCheck blueprint selected
- [ ] Foreign key relationship: `Trade.T_CA_ID → Account.CA_ID`
- [ ] Orphan records detected via anti-join
- [ ] Violation report includes: orphan T_IDs, missing CA_IDs, count
- [ ] Quarantine table for orphan records
- [ ] Generated PySpark performs left anti-join to find orphans
- [ ] Summary metrics: total trades, valid trades, orphan count, orphan percentage
- [ ] Alert if orphan percentage exceeds threshold

---

## S44: DQ Scorecard Publish

**Natural Language Requirement:**
> "Aggregate data quality results from multiple pipelines (S18 DQ validation, S41 freshness, S42 schema drift, S43 referential integrity) into a single DQ scorecard dashboard table."

**Data Source & Schema:**
- Source: DQ result tables/logs from S18, S41, S42, S43
- Each DQ result has: pipeline_id, check_name, check_type, status (pass/fail), details, timestamp

**Expected Pipeline Composition:**
- Sub-pipelines: `Multiple DQ result reads → DQScorecardPublish → WarehouseWriter(dq_scorecard)`
- DQScorecardPublish params:
  - `dimensions=[pipeline_id, check_type, date]`
  - `metrics=[total_checks, passed_checks, failed_checks, pass_rate]`
  - `sla_thresholds={overall_pass_rate: 95}`

**Validation Checklist:**
- [ ] DQScorecardPublish blueprint selected
- [ ] Aggregates results from multiple upstream DQ checks
- [ ] Scorecard dimensions: pipeline, check type, date
- [ ] Metrics computed: total, passed, failed, pass rate
- [ ] SLA threshold: alert if overall pass rate < 95%
- [ ] Output table suitable for dashboard visualization
- [ ] Generated PySpark reads DQ logs and computes aggregates
- [ ] Historical trend maintained (not just latest run)

---

## S45: Full DQ Pipeline (Fail-Fast vs Quarantine)

**Natural Language Requirement:**
> "Ingest Lending Club loan data with injected bad records. Apply DQ rules: loan_amnt must be positive, int_rate must be between 0 and 100, loan_status must not be null. For critical violations (null loan_status), fail the pipeline. For non-critical violations (out-of-range values), quarantine the bad records and continue processing good records."

**Data Source & Schema:**
- Source: Lending Club loans CSV with injected bad records
- Bad records: negative loan amounts, int_rate > 100, null loan_status

**Expected Pipeline Composition:**
- Sub-pipelines: `FileIngestion → GXExpectationSuite(critical: fail-fast) → GXExpectationSuite(non-critical: quarantine) → LakeWriter(good records) + LakeWriter(quarantine)`
- DQ routing policy:
  - Critical: `expect_column_values_to_not_be_null(loan_status)` → fail pipeline on violation
  - Non-critical: `expect_column_values_to_be_between(loan_amnt, 0, None)`, `expect_column_values_to_be_between(int_rate, 0, 100)` → quarantine bad, pass good

**Validation Checklist:**
- [ ] Two-tier DQ: fail-fast for critical, quarantine for non-critical
- [ ] Critical violation (null loan_status) stops pipeline
- [ ] Non-critical violations routed to quarantine table
- [ ] Good records continue to main target
- [ ] Quarantine table has same schema + violation_reason column
- [ ] DQ results logged for scorecard (S44)
- [ ] Generated PySpark implements filter-based routing
- [ ] Pipeline idempotent: re-run produces same quarantine/good split

---

# Tier 6: AI Reasoning (S46–S50) — Conversation Quality

> These test whether the AI asks the right questions and makes the right inferences. No pipeline correctness is tested — only conversation quality.

---

## S46: Incomplete Requirements — Missing Schedule

**Natural Language Requirement:**
> "Load the Freddie Mac origination file from S3 into the warehouse."

**Expected AI Behavior:**
- AI should ask: "How often does this file arrive? (daily, weekly, monthly?)"
- AI should ask: "What does the filename pattern look like? (e.g., orig_20260225.txt)"

**Data Source & Schema:**
- Source: Freddie Mac origination file on S3 (format/naming not specified by user)

**Validation Checklist:**
- [ ] AI does NOT assume `@daily` schedule
- [ ] AI asks about file arrival frequency
- [ ] AI asks about filename pattern/convention
- [ ] AI does not proceed to generate pipeline until schedule is clarified
- [ ] AI may suggest common patterns but requests confirmation

---

## S47: Incomplete Requirements — Missing DQ / PII Detection

**Natural Language Requirement:**
> "Ingest the TPC-DI Prospect data and load it to the warehouse."

**Expected AI Behavior:**
- AI should detect PII from column names: AGENCY_ID (tax-ID-like), FIRST_NAME, LAST_NAME, ADDR_LINE1, POSTAL_CODE
- AI should recommend PII masking
- AI should suggest DQ expectations (not-null checks, format validation)
- AI should ask about data classification policy

**Data Source & Schema:**
- Source: TPC-DI `Prospect.csv` with PII columns

**Validation Checklist:**
- [ ] AI proactively identifies PII risk from column names
- [ ] AI recommends masking for sensitive columns (names, addresses, IDs)
- [ ] AI suggests DQ validation rules
- [ ] AI asks about data classification (PII, FINANCIAL, PUBLIC)
- [ ] AI does not silently skip PII handling

---

## S48: Ambiguous Join Type

**Natural Language Requirement:**
> "Combine the Freddie Mac origination data with the monthly performance data."

**Expected AI Behavior:**
- AI should ask: "Should I include only loans that appear in both files (inner join), or all origination records even if they haven't had a performance update yet (left join)?"
- AI should ask: "What column should I join on? (e.g., Loan Sequence Number)"

**Data Source & Schema:**
- Source A: Freddie Mac origination (Loan Sequence Number, ...)
- Source B: Freddie Mac monthly performance (Loan Sequence Number, ...)

**Validation Checklist:**
- [ ] AI does NOT assume inner join
- [ ] AI asks about join type (inner, left, full outer)
- [ ] AI asks about join key if not obvious
- [ ] AI explains the implications of each join type
- [ ] AI only proceeds after user confirms join strategy

---

## S49: Conflicting Requirements

**Natural Language Requirement:**
> "I need the TPC-DI trade analysis pipeline to run every hour AND process only complete daily trade data."

**Expected AI Behavior:**
- AI should identify the contradiction: hourly runs cannot process complete daily data (day isn't done)
- AI should propose resolution options:
  1. "Run daily to process complete data"
  2. "Run hourly with intra-day partial data, plus a daily reconciliation"
  3. "Run hourly but only process previous day's complete data"

**Validation Checklist:**
- [ ] AI identifies the hourly + complete-daily contradiction
- [ ] AI does NOT silently pick one interpretation
- [ ] AI proposes at least 2 resolution options
- [ ] AI explains trade-offs of each option
- [ ] AI waits for user decision before generating pipeline

---

## S50: Proactive Data Classification

**Natural Language Requirement:**
> "Ingest the synthetic HR employee table with columns: employee_id, ssn, first_name, last_name, salary, department, hire_date, bank_account_number."

**Expected AI Behavior:**
- Classify dataset as PII: ssn, first_name, last_name
- Classify as FINANCIAL: salary, bank_account_number
- Recommend masking for ssn and bank_account_number
- Recommend access controls and audit logging
- Flag the dataset as requiring special handling

**Data Source & Schema:**
- Source: Synthetic ACME `hr_employees.parquet` on S3
- Columns: employee_id, ssn, first_name, last_name, salary, department, hire_date, bank_account_number

**Validation Checklist:**
- [ ] AI infers PII classification from column names (ssn, first_name, last_name)
- [ ] AI infers FINANCIAL classification from column names (salary, bank_account_number)
- [ ] AI recommends masking for ssn (partial mask or hash)
- [ ] AI recommends masking for bank_account_number (partial mask)
- [ ] AI suggests access controls or audit logging
- [ ] AI does NOT require user to explicitly identify PII/financial columns
- [ ] AI applies data governance best practices proactively

---

# Scoring Table

| Tier | Scenarios | Weight | Minimum Pass Rate | Target Pass Rate |
|------|-----------|--------|-------------------|------------------|
| 1: Fundamentals | S01–S10 | 20% | 100% | 100% |
| 2: Composition | S11–S20 | 20% | 80% | 100% |
| 3: Complex Patterns | S21–S30 | 20% | 60% | 90% |
| 4: Enterprise Patterns | S31–S40 | 20% | 50% | 80% |
| 5: DQ Deep Dive | S41–S45 | 10% | 60% | 90% |
| 6: AI Reasoning | S46–S50 | 10% | 80% | 100% |

**Minimum Viable:** Tier 1 = 100%, Tier 2 ≥ 80%
**Production Ready:** All tiers at minimum
**Best in Class:** All tiers at target or above

---

# Blueprint Coverage Matrix

Every active PULSE blueprint must appear in at least one scenario.

| Blueprint | Category | Scenarios |
|-----------|----------|-----------|
| FileIngestion | INGESTION | S01, S02, S04, S08, S09, S10, S11, S13, S14, S15, S16, S17, S18, S19, S20, S22, S28, S29, S31, S37, S38, S40, S41, S42, S45 |
| SnapshotIngestion | INGESTION | S03, S12, S21, S23, S24, S27, S32, S33, S34, S36 |
| ApiIngestion | INGESTION | S05, S31 |
| StreamIngestion | INGESTION | S06, S26 |
| CDCIngestion | INGESTION | S07 |
| BulkBackfill | INGESTION | S08 |
| GenericFilter | TRANSFORM | S09, S13 |
| GenericJoin | TRANSFORM | S11, S12, S23, S26, S36, S38 |
| GenericAggregate | TRANSFORM | S10, S13 |
| GenericRouter | TRANSFORM | S14 |
| PIIMasking | TRANSFORM | S15 |
| JsonFlatten | TRANSFORM | S16 |
| JsonStruct | TRANSFORM | S17 |
| SchemaNormalization | TRANSFORM | S19, S31 |
| DedupeAndMerge | TRANSFORM | S20 |
| BronzeToSilverCleaning | TRANSFORM | (covered by S19 normalization flow) |
| EnrichmentJoin | TRANSFORM | S32 |
| ConformanceToEnterpriseModel | TRANSFORM | S33 |
| DerivedMetricsComputation | TRANSFORM | S37 |
| SCD2Dimension | MODELING | S21 |
| IncrementalMerge | MODELING | S22, S28 |
| FactBuild | MODELING | S23 |
| SnapshotModel | MODELING | S24 |
| DataVaultHubLinkSat | MODELING | S34 |
| WideDenormalizedMart | MODELING | S36 |
| AggregateMaterialization | MODELING | (covered by S10 GenericAggregate) |
| TimeSeriesOptimization | MODELING | (covered by S37 DerivedMetrics with time-series data) |
| FeatureTablePublish | MODELING | S38 |
| ReferenceDataPublish | MODELING | S40 |
| DQValidator | DQ | S18 (via GX), S45 |
| GXExpectationSuite | DQ | S18, S45 |
| FreshnessChecks | DQ | S41 |
| SchemaDriftDetection | DQ | S42 |
| AnomalyDetection | DQ | S39 |
| Reconciliation | DQ | S35 |
| ReferentialIntegrityCheck | DQ | S43 |
| DQScorecardPublish | DQ | S44 |
| WarehouseWriter | DESTINATION | S01, S10, S12, S13, S14, S23, S31, S36, S40 |
| LakeWriter | DESTINATION | S02, S03, S04, S05, S06, S07, S08, S09, S11, S15, S16, S17, S18, S19, S20, S21, S22, S24, S28, S29, S32, S33, S34, S37, S38, S42 |
| StreamWriter | DESTINATION | (Kafka write — paired with S39 anomaly alert output) |
| DatabaseWriter | DESTINATION | S27, S35 |

---

# Connector Coverage Matrix

| Connector | Scenarios |
|-----------|-----------|
| S3 (MinIO) | S01, S02, S08, S09, S10, S11, S13, S14, S15, S19, S20, S22, S28, S29, S31, S37, S38, S40, S41, S42, S45 |
| SFTP | S04 |
| PostgreSQL (as Oracle stand-in) | S03, S12, S21, S23, S24, S27, S32, S33, S34, S35, S36, S43 |
| Kafka | S06, S26, S39 |
| REST API | S05 |
| Snowflake (simulated via Postgres) | S01, S10, S12, S13, S14, S23, S36, S40 |

---

# Periodicity / Sensing Coverage Matrix

| Pattern | Scenarios |
|---------|-----------|
| File sensor (S3) | S01, S02, S04, S09, S10, S11, S13, S14, S15, S16, S17, S18, S19, S20, S22, S28, S29, S31, S37, S38, S40, S41, S42, S45 |
| File sensor (SFTP) | S04 |
| SQL readiness sensor | S27 |
| No sensor (DB pull) | S03, S12, S21, S24, S32, S33, S34, S36 |
| No sensor (streaming) | S06, S26 |
| Trigger-based (Airflow Dataset) | S25 |
| Timezone-aware | S29 |
| Catchup/backfill | S30 |
| Late-arriving (lookback window) | S28 |
| Mixed periodicity | S26 |

---

# How to Run

Each scenario will be executed in two modes:

**Mode 1: Chat-driven** — The natural language requirement is given as a prompt to the PULSE AI chat. The AI composes the full pipeline through conversation, asking questions where needed.

**Mode 2: Manual + AI validation** — The pipeline is composed manually through the UI. The AI validates correctness, detects gaps, and recommends improvements.

Both modes should produce the same final pipeline. The generated Airflow DAG + PySpark scripts are validated against each scenario's checklist.

---

# Data Generation Plan

For the benchmark to be executable, data generators must produce:

| Generator | Output | Format | Scenarios |
|-----------|--------|--------|-----------|
| TPC-DI DIGen.jar (SF=5) | All TPC-DI files | Pipe-delimited, CSV, XML, fixed-width | S02, S03, S07, S09, S12, S14, S15, S16, S20, S21, S23, S24, S27, S32, S34, S36, S39, S40, S42, S43 |
| Freddie Mac SFLLD (sample) | Origination + Performance | Pipe-delimited | S01, S08, S10, S11, S17, S18, S19, S22, S25, S26, S28, S30, S35, S37, S41 |
| Lending Club (Kaggle download) | Loans + Payments | CSV | S13, S19, S38, S45 |
| Synthetic ACME generator | Payments, credit, HR, API, Kafka | CSV, JSON, Parquet, PGP, REST, Kafka | S04, S05, S06, S29, S31, S33, S46–S50 |
| Anomaly injector | DailyMarket with outliers | Pipe-delimited | S39 |
| Duplicate injector | Trade with duplicates | Pipe-delimited | S20 |
| Schema drift injector | Customer with added/removed columns | Pipe-delimited | S42 |
| Bad records injector | Lending Club with violations | CSV | S45 |

---

*End of benchmark plan. All 50 scenarios defined.*
