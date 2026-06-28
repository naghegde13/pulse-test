# PULSE Pipeline Benchmark Suite v2

> **Purpose:** Validate that PULSE can correctly compose, configure, and generate deployable data pipelines from real-world requirements.
> **Date:** 2026-02-25
> **Scenarios:** 50
> **Data Sources:** TPC-DI (brokerage), Freddie Mac (mortgage loans), Lending Club (consumer loans), Synthetic (ACME-specific)

---

## Data Sourcing Strategy

| Source | Domain | Format | Size | Use For |
|--------|--------|--------|------|---------|
| **TPC-DI** (DIGen.jar) | Brokerage firm | CSV, pipe-delimited, XML | Configurable (SF=5 to SF=100) | Trades, accounts, customers, securities, financials, CDC, SCD2 |
| **Freddie Mac SFLLD** | Mortgage servicing | Pipe-delimited | 48M origination + 2.8B performance records | Loan origination, monthly payment performance, delinquency |
| **Lending Club** (Kaggle) | Consumer lending | CSV | 2.2M loans | Loan applications, credit decisions, payment history |
| **Synthetic: ACME** | Mortgage servicing | CSV, JSON, Parquet | Generated (configurable) | Payment summaries, credit bureau files, HR data, API payloads, Kafka events |

### TPC-DI Data Files (from DIGen.jar)

The TPC-DI generator produces files for a brokerage firm. Key files:

| File | Description | Format | Key Columns |
|------|-------------|--------|-------------|
| `Customer.txt` | Customer master (CDC format) | Pipe-delimited | C_ID, C_TAX_ID, C_L_NAME, C_F_NAME, C_DOB, C_EMAIL, ... |
| `Account.txt` | Brokerage accounts | Pipe-delimited | CA_ID, CA_B_ID, CA_C_ID, CA_NAME, CA_TAX_ST, CA_ST_ID |
| `Trade.txt` | Stock trades | Pipe-delimited | T_ID, T_DTS, T_ST_ID, T_TT_ID, T_IS_CASH, T_S_SYMB, T_QTY, T_BID_PRICE, ... |
| `TradeHistory.txt` | Historical trade status changes | Pipe-delimited | TH_T_ID, TH_DTS, TH_ST_ID |
| `DailyMarket.txt` | Daily stock prices | Pipe-delimited | DM_DATE, DM_S_SYMB, DM_CLOSE, DM_HIGH, DM_LOW, DM_VOL |
| `WatchHistory.txt` | Customer watchlist changes | Pipe-delimited | W_C_ID, W_S_SYMB, W_DTS, W_ACTION |
| `Prospect.csv` | Marketing prospect data | CSV | AGENCY_ID, LAST_NAME, FIRST_NAME, MIDDLE_INITIAL, GENDER, ADDR_LINE1, ... |
| `CustomerMgmt.xml` | Customer management events (nested XML) | XML | ActionType, Customer (nested: Name, Address, ContactInfo, TaxInfo, Account) |
| `FINWIRE` | Financial news wire (fixed-width, multiplexed) | Fixed-width | Record type (CMP/SEC/FIN), then type-specific fields |
| `StatusType.txt` | Reference lookup | Pipe-delimited | ST_ID, ST_NAME |
| `TradeType.txt` | Reference lookup | Pipe-delimited | TT_ID, TT_NAME, TT_IS_SELL, TT_IS_MRKT |
| `Industry.txt` | Reference lookup | Pipe-delimited | IN_ID, IN_NAME, IN_SC_ID |
| `TaxRate.txt` | Reference lookup | Pipe-delimited | TX_ID, TX_NAME, TX_RATE |
| `HR.csv` | HR/employee data | CSV | EmployeeID, ManagerID, EmployeeFirstName, EmployeeLastName, ... |
| `BatchDate.txt` | Batch control dates | Text | BatchDate |

### Freddie Mac SFLLD Files

| File | Description | Format | Key Columns |
|------|-------------|--------|-------------|
| Origination | Loan origination data | Pipe-delimited | Credit Score, First Payment Date, First Time Buyer, Maturity Date, MSA, MI%, Units, Occupancy, CLTV, DTI, UPB, LTV, Interest Rate, Channel, ... |
| Monthly Performance | Monthly loan performance | Pipe-delimited | Loan Sequence Number, Monthly Reporting Period, Current Actual UPB, Current Loan Delinquency Status, Loan Age, Remaining Months to Maturity, ... |

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

Built later on external VPS. Scenarios are designed infrastructure-agnostic -- they define what's needed, Docker Compose provides it.

---

## Scenario Index

### Tier 1: Fundamentals (S01-S10) — Single source, basic transforms

| # | Name | Data Source | Blueprints | Tests |
|---|------|-------------|------------|-------|
| S01 | File-to-Warehouse (CSV) | Freddie Mac origination | FileIngestion → WarehouseWriter | Basic ingest + write |
| S02 | File-to-Warehouse (Parquet) | TPC-DI DailyMarket (converted) | FileIngestion → LakeWriter | Parquet format handling |
| S03 | Database Snapshot to Lake | TPC-DI tables loaded in Postgres | SnapshotIngestion → LakeWriter | JDBC read, Parquet write |
| S04 | SFTP Encrypted File | Synthetic credit bureau (PGP) | FileIngestion → LakeWriter | SFTP sensor, PGP decryption |
| S05 | API Ingestion (REST) | Synthetic REST endpoint | ApiIngestion → LakeWriter | API polling, pagination |
| S06 | Kafka Stream to Delta | Synthetic trade events | StreamIngestion → LakeWriter | Structured streaming, micro-batch |
| S07 | CDC Ingestion | TPC-DI Customer (CDC format) | CDCIngestion → LakeWriter | Change capture, merge logic |
| S08 | Bulk Backfill | Freddie Mac origination (3 years) | BulkBackfill → LakeWriter | Large volume, partitioned write |
| S09 | Simple Filter | TPC-DI Trade | FileIngestion → GenericFilter → LakeWriter | Column filter |
| S10 | Simple Aggregation | Freddie Mac performance | FileIngestion → GenericAggregate → WarehouseWriter | Group by + aggregate |

### Tier 2: Composition (S11-S20) — Multi-step, joins, DQ

| # | Name | Data Source | Blueprints | Tests |
|---|------|-------------|------------|-------|
| S11 | Two-Source Join | Freddie Mac orig + perf | 2x FileIngestion → GenericJoin → LakeWriter | Join on loan_sequence_number |
| S12 | Three-Source Join | TPC-DI Trade + Account + Customer | SnapshotIngestion x3 → Join → Join → WarehouseWriter | Multi-hop join |
| S13 | Filter + Aggregate | Lending Club loans | FileIngestion → GenericFilter → GenericAggregate → WarehouseWriter | Chained transforms |
| S14 | Router (Conditional Split) | TPC-DI Trade (buy/sell) | FileIngestion → GenericRouter → 2x WarehouseWriter | Branch by trade type |
| S15 | PII Masking | TPC-DI Prospect (SSN, names) | FileIngestion → PIIMasking → LakeWriter | Mask SSN, hash names |
| S16 | JSON Flattening | TPC-DI CustomerMgmt.xml (→ JSON) | FileIngestion → JsonFlatten → LakeWriter | Nested structure flattening |
| S17 | Struct Builder | Freddie Mac origination flat → nested | FileIngestion → JsonStruct → LakeWriter | Build nested from flat |
| S18 | DQ Validation (GX) | Freddie Mac performance | FileIngestion → GXExpectationSuite → LakeWriter | GX expectations inline |
| S19 | Schema Normalization | Lending Club + Freddie Mac (different schemas) | 2x FileIngestion → SchemaNormalization x2 → Union → LakeWriter | Normalize to common schema |
| S20 | Dedup and Merge | TPC-DI Trade (with duplicates) | FileIngestion → DedupeAndMerge → LakeWriter | Deduplication by business key |

### Tier 3: Complex Patterns (S21-S30) — SCD2, multi-pipeline, temporal

| # | Name | Data Source | Blueprints | Tests |
|---|------|-------------|------------|-------|
| S21 | SCD2 Customer Dimension | TPC-DI Customer (daily snapshots) | SnapshotIngestion → SCD2Dimension → LakeWriter | Full SCD2 lifecycle |
| S22 | Incremental Merge | Freddie Mac monthly performance | FileIngestion → IncrementalMerge → LakeWriter | Merge new into existing |
| S23 | Fact Table Build | TPC-DI Trade + DimCustomer + DimSecurity | Ingestion x3 → Joins → FactBuild → WarehouseWriter | Star schema fact build |
| S24 | Snapshot Model | TPC-DI Account (daily full load) | SnapshotIngestion → SnapshotModel → LakeWriter | Point-in-time snapshots |
| S25 | Cross-Pipeline Dependency | Pipeline A (S01) output → Pipeline B analytics | Separate Business Pipelines with Airflow Dataset trigger | Dataset-triggered DAG |
| S26 | Mixed Periodicity Resolution | Hourly Kafka events + daily Freddie Mac file | StreamIngestion + FileIngestion → Join | AI must ask user to resolve |
| S27 | SQL Readiness Sensor | Postgres BATCH_JOBS table | SqlSensor → SnapshotIngestion → LakeWriter | Poll-based sensing |
| S28 | Late-Arriving Data (3-day window) | Freddie Mac performance (with corrections) | FileIngestion (lookback) → IncrementalMerge → LakeWriter | Lookback window merge |
| S29 | Timezone-Aware Sensing | Synthetic EST-named files on S3 | FileIngestion (timezone config) → LakeWriter | EST filename, UTC Airflow |
| S30 | Catchup/Backfill Prompt | Freddie Mac origination (6 months history) | FileIngestion → LakeWriter (catchup=True) | AI prompts user about backfill |

### Tier 4: Enterprise Patterns (S31-S40) — Fan-in, reconciliation, advanced

| # | Name | Data Source | Blueprints | Tests |
|---|------|-------------|------------|-------|
| S31 | 5-Source Fan-In | 3x Freddie Mac regional files + Lending Club + Synthetic API | 5x Ingestion → Normalize x5 → Union → LakeWriter | Parallel ingest, schema alignment |
| S32 | Enrichment Join (Lookup) | TPC-DI Trade + Industry + StatusType | Ingestion → EnrichmentJoin x2 → LakeWriter | Lookup-style enrichment |
| S33 | Conformance to Enterprise Model | TPC-DI Customer + Freddie Mac borrower | 2x Ingestion → ConformanceToEnterpriseModel → LakeWriter | Map to canonical model |
| S34 | Data Vault (Hub-Link-Sat) | TPC-DI Customer + Account + Trade | 3x Ingestion → DataVaultHubLinkSat → LakeWriter | Hub, Link, Satellite tables |
| S35 | Reconciliation (Source vs Target) | Freddie Mac origination (source) vs warehouse (target) | 2x Ingestion → Reconciliation → AlertWriter | Balance reconciliation |
| S36 | Wide Denormalized Mart | TPC-DI all dims + facts | Multiple ingestion → Joins → WideDenormalizedMart → WarehouseWriter | Flatten star to wide table |
| S37 | Derived Metrics Computation | Freddie Mac performance (compute DTI, LTV trends) | FileIngestion → DerivedMetricsComputation → LakeWriter | Calculated columns |
| S38 | Feature Table (ML-Ready) | Lending Club loans + payments | 2x Ingestion → Join → FeatureTablePublish → LakeWriter | ML feature engineering |
| S39 | Anomaly Detection | TPC-DI DailyMarket (inject anomalies) | FileIngestion → AnomalyDetection → LakeWriter + AlertWriter | Statistical anomaly flagging |
| S40 | Reference Data Publish | TPC-DI StatusType + TradeType + Industry | 3x FileIngestion → ReferenceDataPublish → WarehouseWriter | Small reference tables |

### Tier 5: DQ Deep Dive (S41-S45) — Data quality focused

| # | Name | Data Source | Blueprints | Tests |
|---|------|-------------|------------|-------|
| S41 | Freshness Check | Freddie Mac monthly file (inject late arrival) | FileIngestion → FreshnessChecks → LakeWriter | Freshness SLA violation |
| S42 | Schema Drift Detection | TPC-DI Customer (add columns between batches) | FileIngestion → SchemaDriftDetection → LakeWriter | New/removed column detection |
| S43 | Referential Integrity Check | TPC-DI Trade + Account (orphan trades) | 2x Ingestion → ReferentialIntegrityCheck | FK validation across datasets |
| S44 | DQ Scorecard Publish | Multiple DQ results from S18, S41-S43 | DQ results → DQScorecardPublish → Dashboard | Aggregate DQ scorecard |
| S45 | Full DQ Pipeline (fail-fast vs quarantine) | Lending Club (inject bad records) | FileIngestion → GXExpectationSuite (fail-fast) + quarantine branch | DQ routing policy |

### Tier 6: AI Reasoning (S46-S50) — Conversation quality, not pipeline correctness

| # | Name | Tests |
|---|------|-------|
| S46 | Incomplete Requirements — Missing Schedule | AI asks about periodicity, doesn't assume @daily |
| S47 | Incomplete Requirements — Missing DQ / PII Detection | AI detects PII from column names, recommends masking |
| S48 | Ambiguous Join Type | AI asks inner vs left, asks join key |
| S49 | Conflicting Requirements | AI identifies hourly+daily contradiction, proposes resolution |
| S50 | Proactive Data Classification | AI infers PII + FINANCIAL from column names (ssn, salary, bank_account) |

---

## Scoring

| Tier | Scenarios | Weight | Minimum | Target |
|------|-----------|--------|---------|--------|
| 1: Fundamentals | S01-S10 | 20% | 100% | 100% |
| 2: Composition | S11-S20 | 20% | 80% | 100% |
| 3: Complex | S21-S30 | 20% | 60% | 90% |
| 4: Enterprise | S31-S40 | 20% | 50% | 80% |
| 5: DQ Deep Dive | S41-S45 | 10% | 60% | 90% |
| 6: AI Reasoning | S46-S50 | 10% | 80% | 100% |

---

## Blueprint Coverage Matrix

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
| StreamWriter | DESTINATION | (Kafka write — add to S39 anomaly alert output) |
| DatabaseWriter | DESTINATION | S27, S35 |

### Connector Coverage

| Connector | Scenarios |
|-----------|-----------|
| S3 (MinIO) | S01, S02, S08, S09, S10, S11, S13, S14, S15, S19, S20, S22, S28, S29, S31, S37, S38, S40, S41, S42, S45 |
| SFTP | S04 |
| PostgreSQL (as Oracle stand-in) | S03, S12, S21, S23, S24, S27, S32, S33, S34, S35, S36, S43 |
| Kafka | S06, S26, S39 |
| REST API | S05 |
| Snowflake (simulated via Postgres) | S01, S10, S12, S13, S14, S23, S36, S40 |

### Periodicity/Sensing Coverage

| Pattern | Scenarios |
|---------|-----------|
| File sensor (S3) | S01, S02, S04, S09, S10, S11, S13, S14, S15, ... |
| File sensor (SFTP) | S04 |
| SQL readiness sensor | S27 |
| No sensor (DB pull) | S03, S12, S21, S24, S32, S33, S34, S36 |
| No sensor (streaming) | S06, S26 |
| Trigger-based | S25 (Airflow Dataset) |
| Timezone-aware | S29 |
| Catchup/backfill | S30 |
| Late-arriving (lookback) | S28 |
| Mixed periodicity | S26 |
