# Atlas-EHR

> **Enterprise HealthTech Data Platform** — Automated EHR ingestion, cross-border clinical data standardization, and population-level demographic drift detection.

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Problem Statement](#2-problem-statement)
3. [Core Idea](#3-core-idea)
4. [Architecture Overview](#4-architecture-overview)
5. [Project Structure](#5-project-structure)
6. [Component Breakdown](#6-component-breakdown)
7. [Design Decisions](#7-design-decisions)
8. [Assumptions](#8-assumptions)
9. [How to Run](#9-how-to-run)
10. [Pipeline Walkthrough](#10-pipeline-walkthrough)
11. [Synthetic Dataset](#11-synthetic-dataset)
12. [Classification Flags Reference](#12-classification-flags-reference)
13. [Real-World Scalability Plan](#13-real-world-scalability-plan)
14. [Known Limitations & Improvements Needed](#14-known-limitations--improvements-needed)
15. [Glossary](#15-glossary)

---

## 1. Purpose

Atlas-EHR is a **proof-of-concept HealthTech data platform** that demonstrates how a real hospital data-engineering team would approach two of the hardest problems in clinical informatics:

1. **Automated extraction uncertainty** — EHR documents arrive as scanned images or unstructured text. OCR engines assign confidence scores to every extracted token. Records below a clinical safety threshold must never enter automated decision pipelines without human review.

2. **Cross-border data disparity** — A patient's drug name, dosage unit, and biomarker label look completely different depending on whether the document originated from a US hospital, a Japanese clinic (katakana drug names, zenkaku digits), or a Chinese HIS export. Standardizing these onto a single global schema without altering source truths is a non-trivial engineering problem.

This project is designed to be **read, extended, and demonstrated** — not deployed to production as-is. Every class is fully documented, every design decision is justified in code comments, and the architecture can be traced directly to the scalable reference diagram.

---

## 2. Problem Statement

### Problem A — OCR Extraction Uncertainty

Hospital documents enter the digital world through scanners, fax machines, and legacy PDF exports. An OCR engine reads these images and outputs field-value pairs — but with variable confidence. A fax with ink bleed may produce:

```
drug_name   = "ゼロ—ダ"    ← em-dash replacing katakana long-vowel mark
dosage      = "50Omg"     ← letter-O substituting digit-0
biomarker   = "Po5itive"  ← digit-5 substituting letter-s
confidence  = 0.62        ← engine reports low certainty
```

Feeding this corrupted data into an automated clinical workflow without a gate is dangerous. The platform must **detect, flag, and divert** these records before they influence any dosage calculation or biomarker decision.

### Problem B — Cross-Border Data Disparity

The same oncology drug is called different things in different countries:

| Country | Name in source document | Standard INN |
|---------|------------------------|--------------|
| Japan   | ゼローダ (Xeloda brand, katakana) | Capecitabine |
| US/UK   | Capecitabine / Erlotinib (English INN) | Already standardized |

Beyond nomenclature, populations differ genomically. East Asian patients carry EGFR mutation rates of ~50% in NSCLC, compared to ~15% in Western cohorts. A pipeline that ingests data from both populations without surfacing this disparity will silently produce skewed research outputs.

---

## 3. Core Idea

Atlas-EHR solves both problems through a **three-stage ingestion pipeline** governed by two architectural principles:

### Stage 1 — OCR Extraction
Accept raw, unstructured OCR field maps as they arrive from any scanning source. At this stage nothing is modified — the record is accepted verbatim, with its confidence score attached.

### Stage 2 — Regional Crosswalk
Route the record to the correct **TranslationStrategy** based on its region code. The strategy translates local terminology (drug brand names, unit formats, script-specific characters) into the global canonical schema. New regions are added by implementing one interface — no existing code changes.

### Stage 3 — Demographic Drift Guardrail
Apply two independent gates:
- **Circuit breaker** (hard gate): if OCR confidence < 0.80, halt automated processing and flag `REQUIRES_HUMAN_AUDIT`.
- **Drift detector** (soft telemetry): if the record's region carries a significantly different EGFR positive rate than the Western baseline (>35 percentage points), append `DEMOGRAPHIC_DRIFT_DETECTED` as a research advisory without blocking the record.

The output of every record — regardless of outcome — is an **immutable `GlobalPatientSnapshot` Java Record** carrying the standardized fields plus a pipe-delimited flag string.

---

## 4. Architecture Overview

The full scalable reference architecture is available as an interactive diagram:

```
docs/diagrams/atlas-ehr-hld.excalidraw
```

Open it at **[excalidraw.com](https://excalidraw.com)** (File → Open) or in VS Code with the [Excalidraw extension](https://marketplace.visualstudio.com/items?itemName=pomdtr.excalidraw-editor).

### High-Level Flow (Current Codebase)

```
MockOcrRegistry
      │  produces List<Map<String,String>>
      ▼
IngestionOrchestrator
      │
      ├─► [region = JP] ──► JapanTranslationStrategy.crosswalkToGlobal()
      │                          │  PMDA + RxNorm drug crosswalk
      │                          │  Zenkaku digit normalizer
      │                          │  EGFR artefact correction
      │                          ▼
      └─► [other regions] ──► PassthroughTranslationStrategy (built-in fallback)
                                   │
                                   ▼
                         GlobalPatientSnapshot (unflagged)
                                   │
                                   ▼
                         DemographicDriftGuardrail.evaluate()
                                   │
                         ┌─────────┴──────────────────────┐
                         │ confidence < 0.80?              │ EGFR drift > 35pp?
                         ▼                                 ▼
               REQUIRES_HUMAN_AUDIT           DEMOGRAPHIC_DRIFT_DETECTED
               LOW_OCR_CONFIDENCE             (advisory — record not blocked)
                         │                                 │
                         └─────────┬──────────────────────┘
                                   ▼
                         GlobalPatientSnapshot (flagged)
                                   │
                                   ▼
                         AtlasEHRApplication (terminal dashboard)
```

### Real-World Target Architecture

In a production environment, each stage above becomes an independent microservice, connected by Apache Kafka topics, backed by PostgreSQL, Redis, S3, and Elasticsearch. See the Excalidraw diagram for the full 8-layer reference design with all infrastructure components labelled.

---

## 5. Project Structure

```
AtlasEHR/
│
├── README.md                          ← You are here
│
├── pom.xml                            ← Maven build, Java 17, zero external deps
│
├── docs/
│   └── diagrams/
│       └── atlas-ehr-hld.excalidraw  ← Full HLD diagram (read-only reference)
│
└── src/
    └── main/
        └── java/
            └── com/loc/atlas/
                │
                ├── AtlasEHRApplication.java          ← Main entry point / CLI dashboard
                │
                ├── model/
                │   └── GlobalPatientSnapshot.java    ← Immutable Java Record (output model)
                │
                ├── mock/
                │   └── MockOcrRegistry.java          ← Synthetic OCR data source (11 records)
                │
                ├── translation/
                │   ├── TranslationStrategy.java      ← Strategy interface (OCP extension point)
                │   └── JapanTranslationStrategy.java ← JP: PMDA + RxNorm crosswalk
                │
                ├── validation/
                │   └── DemographicDriftGuardrail.java ← Circuit breaker + drift detector
                │
                └── pipeline/
                    └── IngestionOrchestrator.java    ← Pipeline controller + strategy registry
```

---

## 6. Component Breakdown

### `GlobalPatientSnapshot` (model)
A **Java 17 Record** — the single canonical data shape that all downstream systems read. Records are immutable by construction. Fields are validated in the compact constructor; nulls are rejected. The `withAdditionalFlag()` method returns a new instance rather than mutating the original, preserving thread safety when records fan out to multiple consumers.

| Field | Type | Description |
|-------|------|-------------|
| `patientId` | `String` | Globally unique patient identifier |
| `region` | `String` | ISO 3166-1 alpha-2 source region (US, JP, CN, KR, UK) |
| `standardizedDrugName` | `String` | INN name with optional RxNorm ID annotation |
| `dosageMg` | `double` | Normalized milligram dosage |
| `egfrStatus` | `String` | Canonical EGFR status: Positive / Negative / Unknown |
| `ocrConfidence` | `double` | Composite OCR confidence score [0.0–1.0] |
| `classificationFlags` | `String` | Pipe-delimited runtime telemetry flags |

---

### `MockOcrRegistry` (mock)
Produces a catalogue of **11 synthetic OCR scan payloads** covering every failure mode the pipeline must handle. In production this class is replaced by a real OCR adapter receiving JSON from AWS Textract, Azure Form Recognizer, or Google Document AI.

Each record is a `Map<String, String>` mirroring what a real OCR adapter would emit post-field-extraction, pre-crosswalk.

| # | Patient | Region | Scenario |
|---|---------|--------|----------|
| 1 | US-PAT-00412 | US | Golden path — high confidence, standard English, EGFR Negative |
| 2 | UK-PAT-00887 | UK | Trailing whitespace on drug name (minor OCR noise) |
| 3 | JP-PAT-20031 | JP | High confidence, katakana drug name `ゼローダ`, EGFR Positive |
| 4 | JP-PAT-20089 | JP | **Circuit breaker trigger** — confidence 0.62, em-dash and digit-letter corruption |
| 5 | US-PAT-00553 | US | Missing `biomarker_egfr` field (pending lab result) |
| 6 | JP-PAT-20114 | JP | Second JP drug (`イレッサ`/Gefitinib), kanji EGFR label `陽性` |
| 7 | KR-PAT-50023 | KR | South Korea — no registered strategy, passthrough applies |
| 8 | US-PAT-00731 | US | Borderline confidence 0.81 (one tick above circuit-breaker) |
| 9 | JP-PAT-20201 | JP | Full-width zenkaku dosage digits `６００ｍｇ`, Trastuzumab |
| 10 | UK-PAT-01102 | UK | **Circuit breaker trigger** — confidence 0.58, digit-4 substitutions throughout |
| 11 | CN-PAT-70055 | CN | **Drift trigger** — EGFR Positive, CN rate 51% → drift 0.36 > threshold 0.35 |

---

### `TranslationStrategy` (interface)
The Open-Closed extension point. Implementing this interface is the **only** change required to add a new regional language/format to the pipeline. The orchestrator never contains region-specific logic.

```java
public interface TranslationStrategy {
    String getSupportedRegion();
    Map<String, Object> crosswalkToGlobal(Map<String, String> rawOcrData);
}
```

**Contract obligations for implementors:**
- Must be stateless and thread-safe
- Must never throw for expected data anomalies — return sentinel values instead
- Output map must contain all six canonical keys documented in the interface Javadoc

---

### `JapanTranslationStrategy` (translation)
The only fully implemented regional strategy. Handles:

- **Drug name crosswalk** — 12-entry dictionary mapping katakana brand names to `INN (RxNorm ID: XXXXXXX)` strings. Lookup order: exact match → lowercase ASCII → artefact-cleaned variant → UNRESOLVED fallback.
- **EGFR normalization** — 14-entry dictionary covering kanji labels (`陽性`, `陰性`), common OCR corruption patterns (`Po5itive`, `Neg4tive`), and romanized variants.
- **Dosage normalization** — strips full-width zenkaku digits (U+FF10–U+FF19), corrects letter-O/digit-0 and letter-l/digit-1 substitutions, converts non-mg units via multiplier table.

---

### `DemographicDriftGuardrail` (validation)
A pure utility class (no instances) implementing two independent mechanisms:

**Circuit Breaker:**
```
if ocrConfidence < 0.80 → append LOW_OCR_CONFIDENCE + REQUIRES_HUMAN_AUDIT
                          short-circuit (skip remaining checks)
```
Clinical rationale: below 0.80, field-level error rate exceeds ~5%, which is unsafe for automated dosage and biomarker decisions.

**Drift Detector:**
```
impliedRate = regional EGFR positive rate (JP=50%, CN=51%, KR=44%, others...)
drift       = |impliedRate − Western baseline (15%)|
if drift > 0.35 threshold → append DEMOGRAPHIC_DRIFT_DETECTED (advisory only)
```
The flag does not block the record. It is a telemetry signal for research teams to examine the full regional cohort before cross-population analysis.

**Threshold constants** (all `public static final double`):

| Constant | Value | Purpose |
|----------|-------|---------|
| `OCR_CONFIDENCE_THRESHOLD` | 0.80 | Circuit breaker gate |
| `WESTERN_EGFR_POSITIVE_RATE` | 0.15 | Baseline for drift calculation |
| `DRIFT_DETECTION_EGFR_THRESHOLD` | 0.35 | Maximum allowed deviation before drift flag |

---

### `IngestionOrchestrator` (pipeline)
The primary system controller. Maintains a `Map<String, TranslationStrategy>` registry. On each record:
1. Resolves the correct strategy by region code (or falls back to the built-in `PassthroughTranslationStrategy`).
2. Calls `crosswalkToGlobal()` and builds a `GlobalPatientSnapshot`.
3. Passes the snapshot to `DemographicDriftGuardrail.evaluate()`.
4. Returns the flagged snapshot; never drops records — errors produce sentinel records with `PIPELINE_ERROR` flag.

---

### `AtlasEHRApplication` (entry point)
Drives the full simulation lifecycle and renders a step-by-step terminal dashboard. Bootstraps a custom single-line JUL (Java Util Logging) formatter to keep log output clean alongside the dashboard output.

---

## 7. Assumptions

| #  | Assumption | Impact if wrong |
|----|-----------|----------------|
| A1 | OCR engines output a single composite confidence score per document | If per-field scores are available, the guardrail could apply field-specific thresholds (e.g., stricter for `dosage` than `patient_id`) |
| A2 | EGFR mutation rate differences between Eastern and Western populations are population-level signals, not individual predictors | The drift detector flags regional cohorts, not individual patients; a single EGFR-Positive US patient is not flagged |
| A3 | Drug name translation dictionaries are static at deploy time | In practice, drug formularies and RxNorm mappings update quarterly; a Redis cache with TTL-based refresh would be required |
| A4 | All monetary-unit dosages in source documents are in standard metric units (mg, g, mcg) | Non-SI unit systems (e.g., grains, drams in legacy US documents) would require additional unit multipliers in the strategy |
| A5 | `region` field is always an ISO 3166-1 alpha-2 code | Real hospital EHR exports sometimes use free-text country names or SNOMED/LOINC jurisdiction codes — an upstream normalizer would be needed |
| A6 | The 0.80 confidence threshold is a fixed operational policy | A production system would likely make this configurable per document type (prescription vs. lab report vs. discharge summary) |
| A7 | Thread safety is achieved by immutability alone | If the orchestrator is used in a multi-threaded executor, the strategy registry `HashMap` would need to be replaced with `ConcurrentHashMap` |

---

## 8. How to Run

### Prerequisites
- Java 17 or later (`java -version`)
- Maven 3.8 or later (`mvn -version`)

### Build

```bash
cd AtlasEHR
mvn clean package
```

This produces `target/atlas-ehr.jar` — a self-contained executable JAR with no external dependencies.

### Run

```bash
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target/atlas-ehr.jar
```

> **Note:** The `-Dstdout.encoding=UTF-8` flag is required on Windows to render Japanese katakana, kanji, and box-drawing characters correctly in the terminal. On macOS/Linux this flag is usually unnecessary.

### Expected output structure
```
══════════════════════════════════════════════
  Atlas-EHR banner
══════════════════════════════════════════════

① PIPELINE INITIALIZATION       ← strategy registry boot
② OCR SCAN CATALOGUE            ← 11 records listed
③ PIPELINE EXECUTION            ← per-record stage visualization
   [Record N/11] Extraction → Crosswalk → Drift Check

  Dispatching to orchestrator...

④ FINALIZED PATIENT SNAPSHOT SUMMARY
   Total: 11  |  Clean: 7  |  Flagged: 4  (audit=2, drift=1)
   Per-record snapshot cards with flags

⑤ CLASSIFICATION FLAG LEGEND
⑥ SESSION COMPLETE
```

---

## 9. Pipeline Walkthrough

### Happy path (US-PAT-00412)
1. `MockOcrRegistry` emits `{drug_name: "Capecitabine", dosage: "1250mg", biomarker_egfr: "Negative", ocr_confidence: "0.97", region: "US"}`
2. `IngestionOrchestrator` resolves `PassthroughTranslationStrategy` (no US-specific strategy registered)
3. Passthrough strips whitespace, parses dosage → `1250.0`
4. `DemographicDriftGuardrail`: confidence 0.97 ≥ 0.80 ✓ | EGFR Negative → drift check skipped
5. Output: `GlobalPatientSnapshot(US-PAT-00412, US, "Capecitabine", 1250.0, "Negative", 0.97, "")`

### Circuit breaker path (JP-PAT-20089)
1. Registry emits `{drug_name: "ゼロ—ダ", dosage: "50Omg", ocr_confidence: "0.62", ...}`
2. Orchestrator resolves `JapanTranslationStrategy`; crosswalk resolves corrupted drug name via artefact-cleaning (`—` → `ー`), parses dosage `50O` → `500` via O→0 correction
3. `DemographicDriftGuardrail`: confidence 0.62 < 0.80 → **circuit breaker trips**
4. Output: `GlobalPatientSnapshot(JP-PAT-20089, JP, "Capecitabine...", 500.0, "Positive", 0.62, "LOW_OCR_CONFIDENCE|REQUIRES_HUMAN_AUDIT")`
5. Record diverted to human audit queue; no further automated processing

### Drift detection path (CN-PAT-70055)
1. Registry emits `{drug_name: "Osimertinib", biomarker_egfr: "Positive", ocr_confidence: "0.95", region: "CN"}`
2. Passthrough strategy: drug name passes through unchanged (INN already)
3. `DemographicDriftGuardrail`: confidence 0.95 ≥ 0.80 ✓ | EGFR Positive → `inferRegionalEgfrRate("CN")` = 0.51 | drift = |0.51 − 0.15| = 0.36 > 0.35 → **drift flag fires**
4. Output: `GlobalPatientSnapshot(CN-PAT-70055, CN, "Osimertinib", 80.0, "Positive", 0.95, "DEMOGRAPHIC_DRIFT_DETECTED")`
5. Record is fully usable; flag is advisory telemetry for the research team

---

## 10. Synthetic Dataset

The 11 mock records are deliberately constructed to cover every code path in the pipeline:

| Scenario | Record(s) | Code path exercised |
|----------|-----------|---------------------|
| Golden path, English INN | US-PAT-00412, US-PAT-00731 | Passthrough, no flags |
| Minor OCR noise (whitespace) | UK-PAT-00887 | Passthrough, `strip()` cleans it |
| Katakana drug name, full crosswalk | JP-PAT-20031, JP-PAT-20114 | `JapanTranslationStrategy.resolveDrugName()` |
| Kanji EGFR label (`陽性`) | JP-PAT-20114 | `normalizeEgfrStatus()` kanji branch |
| Zenkaku digit dosage (`６００ｍｇ`) | JP-PAT-20201 | `normalizeFullWidthDigits()` |
| OCR corruption + circuit breaker | JP-PAT-20089, UK-PAT-01102 | `DemographicDriftGuardrail` hard gate |
| Borderline confidence (0.81) | US-PAT-00731 | Gate edge case — passes, no flag |
| Missing biomarker field | US-PAT-00553 | `BIOMARKER_MISSING` flag, no block |
| No registered strategy (KR) | KR-PAT-50023 | `PassthroughTranslationStrategy` fallback |
| Demographic drift | CN-PAT-70055 | Drift detector soft flag |

---

## 11. Classification Flags Reference

Flags are written to `GlobalPatientSnapshot.classificationFlags()` as a pipe-delimited string. Multiple flags on one record are written in the order they were detected.

| Flag | Trigger | Blocking? | Action Required |
|------|---------|-----------|-----------------|
| `LOW_OCR_CONFIDENCE` | `ocrConfidence < 0.80` | Yes (paired with HUMAN_AUDIT) | Clinician reviews original document |
| `REQUIRES_HUMAN_AUDIT` | Same as above | Yes | Record must not enter any automated clinical workflow |
| `BIOMARKER_MISSING` | `egfrStatus = "Unknown"` (no field in source) | No | Lab team to link pending result and resubmit |
| `DEMOGRAPHIC_DRIFT_DETECTED` | Regional EGFR rate deviates > 35pp from Western baseline | No | Research team to review full regional cohort before cross-population analysis |
| `PIPELINE_ERROR` | Unhandled runtime exception during processing | Yes | Engineering team investigates logs; record resubmitted after fix |

---

## 12. Real-World Scalability Plan

The following describes how each current codebase component maps to production infrastructure. The complete diagram is in `docs/diagrams/atlas-ehr-hld.excalidraw`.

### OCR Layer (replaces `MockOcrRegistry`)
| Current | Production |
|---------|-----------|
| `MockOcrRegistry.java` (11 hardcoded records) | AWS Textract / Azure Form Recognizer / Google Document AI — real scanning engines with per-word confidence scores |
| Hardcoded `ocr_confidence` string literals | Composite confidence computed from character-level → word-level → field-level → document-level aggregation |
| In-memory `List<Map>` | Kafka topic `atlas.raw-ocr-scans` — OCR Adapter publishes JSON payloads; Ingestion Orchestrator consumes asynchronously |

### Translation Layer (extends `TranslationStrategy`)
| Current | Production |
|---------|-----------|
| Drug dictionaries as `Map.of()` compile-time constants | Redis cluster with 24-hour TTL, populated from RxNorm API, PMDA, and WHO INN database nightly refresh |
| One JP strategy, passthrough for all others | Dedicated microservice per major region (JP, CN, KR, DE, IN); Strategy registry loaded from DynamoDB config store |
| No vocabulary miss alerting | If `resolveDrugName()` returns `UNRESOLVED:`, publish to a `atlas.unknown-drugs` Kafka topic for pharmacist review |

### Storage (no persistence in current code)
| Store | Purpose |
|-------|---------|
| **PostgreSQL** | Finalized `GlobalPatientSnapshot` records, indexed by `patientId` and `region`. Replica set for read scalability. |
| **Redis** | Translation dictionary cache (RxNorm, PMDA). Eliminates per-record API calls. Target hit rate: >95%. |
| **AWS S3** | Immutable raw scan archive — original images and OCR JSON stored with versioning ON for HIPAA audit trail. |
| **Elasticsearch** | All classification flag events indexed for full-text search, Kibana dashboards, and compliance reporting. |
| **MongoDB** | Pre-crosswalk OCR payloads (schema-free; useful for reprocessing after strategy updates). |

### Observability
- **Prometheus** scrapes per-service metrics: OCR confidence P50/P95, Kafka consumer lag, pipeline throughput records/sec, circuit-breaker trip rate per region.
- **Grafana** dashboards: real-time pipeline health, regional flag distribution, EGFR drift trend over time.
- **PagerDuty** alert: if circuit-breaker trip rate exceeds 5% of batch volume in any 15-minute window.

### Deployment
- Each microservice packaged as a Docker image, deployed to Kubernetes via Helm chart.
- CI/CD via GitHub Actions: `mvn clean package` → unit tests → SonarQube quality gate → Docker build → staging deploy → smoke test → production rollout.
- Blue-green deployment behind AWS ALB to enable zero-downtime strategy dictionary updates.

---

## 13. Glossary

| Term | Definition |
|------|-----------|
| **EHR** | Electronic Health Record — a digital version of a patient's medical history |
| **OCR** | Optical Character Recognition — software that converts scanned images into machine-readable text |
| **INN** | International Non-proprietary Name — the WHO-standardized generic name for a drug (e.g., Capecitabine), distinct from brand names (e.g., Xeloda) |
| **RxNorm** | A standardized US drug nomenclature and drug database maintained by the US National Library of Medicine, assigning unique concept IDs to drugs |
| **PMDA** | Pharmaceuticals and Medical Devices Agency — the Japanese equivalent of the FDA, responsible for drug approval and nomenclature in Japan |
| **EGFR** | Epidermal Growth Factor Receptor — a protein whose gene mutation status is a key biomarker in lung cancer (NSCLC) treatment selection |
| **NSCLC** | Non-Small Cell Lung Cancer — the most common form of lung cancer; EGFR mutation rates vary significantly by ethnicity |
| **Katakana** | One of the three Japanese writing scripts; used for foreign loanwords and drug brand names in Japanese medical documentation |
| **Zenkaku** | Full-width Unicode characters (U+FF01–U+FF60) used in Japanese typography, including digits U+FF10–U+FF19 |
| **Crosswalk** | In health informatics, the mapping of a term or code from one classification system to an equivalent in another (e.g., Japanese brand name → INN + RxNorm ID) |
| **Circuit Breaker** | A software pattern that stops processing and routes a request to a fallback when a quality gate fails, analogous to an electrical circuit breaker |
| **Demographic Drift** | A statistically significant difference in population-level genomic or demographic characteristics between two cohorts being compared or merged |
| **FHIR** | Fast Healthcare Interoperability Resources — an HL7 standard for exchanging healthcare information electronically |
| **HIPAA** | Health Insurance Portability and Accountability Act — US federal law governing the privacy and security of health information |
| **PHI** | Protected Health Information — any individually identifiable health data governed by HIPAA |
| **GDPR** | General Data Protection Regulation — EU regulation governing personal data privacy, applicable to patient records of EU residents |
| **HIS** | Hospital Information System — the integrated software platform a hospital uses to manage patient data, billing, and clinical workflows |
| **HL7** | Health Level 7 — a set of international standards for the exchange of clinical and administrative data between health systems |

---

*Atlas-EHR was designed and built as an architectural reference implementation. All patient records are entirely synthetic — no real patient data was used at any point.*
