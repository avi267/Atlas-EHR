package com.loc.atlas.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Synthetic OCR data source that simulates the raw, unstructured text blocks produced by
 * a hospital-grade OCR scanning pipeline across multiple geographic regions.
 *
 * Design rationale: In production, this layer would be backed by an actual OCR engine
 * (e.g., Azure Form Recognizer, AWS Textract) returning JSON payloads. Here we replicate
 * the exact schema and failure modes — low-confidence scores, garbled field names,
 * mixed-language tokens — so that the rest of the pipeline can be exercised without
 * requiring live infrastructure or licensed imaging models.
 *
 * Each entry is a {@code Map<String, String>} whose keys mirror what a real OCR adapter
 * would emit after initial field-extraction (pre-crosswalk, pre-validation).
 */
public final class MockOcrRegistry {

    private static final Logger LOG = Logger.getLogger(MockOcrRegistry.class.getName());

    /**
     * Unique registry key identifying each simulated scan document.
     * In production this would be a document UUID tied to the PACS or EHR encounter ID.
     */
    public static final String KEY_PATIENT_ID       = "patient_id";
    public static final String KEY_REGION           = "region";
    public static final String KEY_DRUG_NAME        = "drug_name";
    public static final String KEY_DOSAGE           = "dosage";
    public static final String KEY_BIOMARKER_EGFR   = "biomarker_egfr";
    public static final String KEY_OCR_CONFIDENCE   = "ocr_confidence";

    private MockOcrRegistry() {
        // Utility class — no instances
    }

    /**
     * Returns the complete catalogue of synthetic OCR scan payloads.
     *
     * Ordering is deterministic and representative of a realistic ingestion batch:
     *  1.  US  — high-confidence baseline record (golden path, EGFR Negative)
     *  2.  UK  — high-confidence oncology, trailing-whitespace noise on drug name
     *  3.  JP  — high-confidence, katakana drug name "ゼローダ", EGFR Positive
     *  4.  JP  — low-confidence fax scan, OCR artefacts (circuit-breaker trigger)
     *  5.  US  — missing EGFR biomarker (pending lab result edge case)
     *  6.  JP  — second clean high-confidence record, different drug (イレッサ/Gefitinib)
     *  7.  KR  — South Korean record, high confidence, EGFR Positive (drift scenario)
     *  8.  US  — borderline confidence (0.81), EGFR Positive, immunotherapy drug
     *  9.  JP  — mid-confidence record, full-width zenkaku dosage digits
     *  10. UK  — very low-confidence scan (circuit-breaker trigger), fragmented fields
     *  11. CN  — Chinese record, EGFR Positive; CN rate 51% → drift 0.36 > threshold 0.35
     *            → DEMOGRAPHIC_DRIFT_DETECTED flag (the canonical drift trigger case)
     *
     * @return unmodifiable ordered list of raw OCR payloads
     */
    public static List<Map<String, String>> getAllScans() {
        List<Map<String, String>> scans = new ArrayList<>();

        scans.add(buildUsHighConfidenceRecord());
        scans.add(buildUkOncologyRecord());
        scans.add(buildJapanHighConfidenceOncologyRecord());
        scans.add(buildJapanLowConfidenceRecord());
        scans.add(buildUsMissingBiomarkerRecord());
        scans.add(buildJapanGefitinibRecord());
        scans.add(buildKoreaHighConfidenceRecord());
        scans.add(buildUsBorderlineConfidenceRecord());
        scans.add(buildJapanZenkakuDosageRecord());
        scans.add(buildUkVeryLowConfidenceRecord());
        scans.add(buildChinaDriftRecord());

        LOG.info(String.format(
                "[MockOcrRegistry] Catalogue initialized — %d synthetic scan documents loaded.", scans.size()));

        return Collections.unmodifiableList(scans);
    }

    // -------------------------------------------------------------------------
    // Individual scan builders
    // -------------------------------------------------------------------------

    /**
     * US record — near-perfect OCR quality, standard English drug labeling.
     * Represents the Western population baseline against which drift is measured.
     */
    private static Map<String, String> buildUsHighConfidenceRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "US-PAT-00412");
        scan.put(KEY_REGION,         "US");
        scan.put(KEY_DRUG_NAME,      "Capecitabine");
        scan.put(KEY_DOSAGE,         "1250mg");
        scan.put(KEY_BIOMARKER_EGFR, "Negative");
        scan.put(KEY_OCR_CONFIDENCE, "0.97");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * UK record — NHS-style documentation; dosage specified per BSA convention with
     * a trailing whitespace artefact on the drug name to simulate minor OCR noise.
     */
    private static Map<String, String> buildUkOncologyRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "UK-PAT-00887");
        scan.put(KEY_REGION,         "UK");
        scan.put(KEY_DRUG_NAME,      "Capecitabine ");   // trailing whitespace — realistic OCR noise
        scan.put(KEY_DOSAGE,         "825mg");
        scan.put(KEY_BIOMARKER_EGFR, "Negative");
        scan.put(KEY_OCR_CONFIDENCE, "0.91");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * Japan high-confidence record — localized drug name in katakana ("ゼローダ" = Xeloda/Capecitabine),
     * positive EGFR status typical of East Asian NSCLC demographics (~50% prevalence),
     * and a dosage string in the format used by Japanese hospital pharmacy systems.
     *
     * This record exercises the happy-path of the JP TranslationStrategy: all fields
     * resolve cleanly, the crosswalk succeeds, and the demographic drift guardrail fires
     * an informational flag (EGFR rate differs from Western baseline) but does NOT block.
     */
    private static Map<String, String> buildJapanHighConfidenceOncologyRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "JP-PAT-20031");
        scan.put(KEY_REGION,         "JP");
        scan.put(KEY_DRUG_NAME,      "ゼローダ");           // Xeloda (brand) → Capecitabine (INN)
        scan.put(KEY_DOSAGE,         "500mg");
        scan.put(KEY_BIOMARKER_EGFR, "Positive");          // 陽性 in source doc, pre-translated by OCR adapter
        scan.put(KEY_OCR_CONFIDENCE, "0.94");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * Japan low-confidence record — simulates a scanned fax with ink degradation.
     * OCR engine reports 0.62 confidence, the drug name is partially corrupted ("ゼロ—ダ"
     * with an em-dash artefact replacing the katakana "ー"), and the dosage field
     * contains a garbled unit suffix.
     *
     * This record is specifically designed to trigger the DemographicDriftGuardrail
     * circuit-breaker (confidence < 0.80 threshold), resulting in a REQUIRES_HUMAN_AUDIT flag
     * and early pipeline exit for this record.
     */
    private static Map<String, String> buildJapanLowConfidenceRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "JP-PAT-20089");
        scan.put(KEY_REGION,         "JP");
        scan.put(KEY_DRUG_NAME,      "ゼロ—ダ");        // em-dash OCR corruption
        scan.put(KEY_DOSAGE,         "50Omg");              // letter-O vs digit-0 OCR error
        scan.put(KEY_BIOMARKER_EGFR, "Po5itive");           // digit-5 vs letter-s OCR error
        scan.put(KEY_OCR_CONFIDENCE, "0.62");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * US record with a missing EGFR biomarker — laboratory result not yet linked to the
     * encounter document at time of OCR scan. Tests the pipeline's handling of absent
     * optional fields without crashing.
     */
    private static Map<String, String> buildUsMissingBiomarkerRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "US-PAT-00553");
        scan.put(KEY_REGION,         "US");
        scan.put(KEY_DRUG_NAME,      "Erlotinib");
        scan.put(KEY_DOSAGE,         "150mg");
        // biomarker_egfr intentionally absent — simulates pending lab result
        scan.put(KEY_OCR_CONFIDENCE, "0.88");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * Japan second clean record — different oncology drug (イレッサ = Iressa/Gefitinib),
     * used to verify that the JP crosswalk resolves multiple dictionary entries correctly
     * and that a second clean JP record passes the drift guardrail independently.
     * EGFR Positive is consistent with Gefitinib's primary therapeutic indication.
     */
    private static Map<String, String> buildJapanGefitinibRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "JP-PAT-20114");
        scan.put(KEY_REGION,         "JP");
        scan.put(KEY_DRUG_NAME,      "イレッサ");      // Iressa brand → Gefitinib (INN)
        scan.put(KEY_DOSAGE,         "250mg");
        scan.put(KEY_BIOMARKER_EGFR, "陽性");          // Japanese: Positive — exercises full kanji→canonical path
        scan.put(KEY_OCR_CONFIDENCE, "0.96");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * South Korea record — high confidence, EGFR Positive.
     * KR is in the elevated EGFR positive cohort (~44% prevalence), so this record
     * exercises the drift guardrail's regional inference table for a non-JP Asian region.
     * Drug is Nivolumab (오프디보 — Opdivo brand in Korean romanization), a PD-1 inhibitor
     * increasingly used in EGFR-mutant NSCLC salvage lines.
     *
     * Note: KR uses a passthrough strategy (no KR-specific strategy registered), so the
     * drug name will pass through as-is and the guardrail still fires DEMOGRAPHIC_DRIFT_DETECTED
     * based on the KR region code in the epidemiological lookup table.
     */
    private static Map<String, String> buildKoreaHighConfidenceRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "KR-PAT-50023");
        scan.put(KEY_REGION,         "KR");
        scan.put(KEY_DRUG_NAME,      "Nivolumab");     // English INN used by Korean hospital EHR export
        scan.put(KEY_DOSAGE,         "240mg");
        scan.put(KEY_BIOMARKER_EGFR, "Positive");
        scan.put(KEY_OCR_CONFIDENCE, "0.93");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * US borderline-confidence record — OCR score at 0.81, just above the 0.80 circuit-breaker
     * threshold. Exercises the guardrail's boundary condition: the record passes the confidence
     * gate but should not receive a LOW_OCR_CONFIDENCE flag. Drug is Pembrolizumab (Keytruda),
     * an immune checkpoint inhibitor with broad oncology use. EGFR Positive combination
     * scenario (atypical for US population) — tests drift check on a non-Asian region with
     * a Positive biomarker result (should NOT trigger DEMOGRAPHIC_DRIFT_DETECTED since US
     * infers the Western baseline rate).
     */
    private static Map<String, String> buildUsBorderlineConfidenceRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "US-PAT-00731");
        scan.put(KEY_REGION,         "US");
        scan.put(KEY_DRUG_NAME,      "Pembrolizumab");
        scan.put(KEY_DOSAGE,         "200mg");
        scan.put(KEY_BIOMARKER_EGFR, "Positive");
        scan.put(KEY_OCR_CONFIDENCE, "0.81");          // one tick above circuit-breaker threshold
        return Collections.unmodifiableMap(scan);
    }

    /**
     * Japan mid-confidence record with full-width (zenkaku) dosage digits.
     * Japanese hospital pharmacy printouts frequently use Unicode full-width numerals
     * (U+FF10–U+FF19). The dosage field "５００ｍｇ" uses entirely zenkaku characters,
     * exercising the JapanTranslationStrategy's zenkaku normalization path.
     * Drug is ハーセプチン (Herceptin/Trastuzumab), used in HER2-positive breast cancer;
     * EGFR status is Negative (as expected for Trastuzumab patients — HER2≠EGFR mutation).
     */
    private static Map<String, String> buildJapanZenkakuDosageRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "JP-PAT-20201");
        scan.put(KEY_REGION,         "JP");
        scan.put(KEY_DRUG_NAME,      "ハーセプチン");   // Herceptin brand → Trastuzumab (INN)
        scan.put(KEY_DOSAGE,         "６００ｍｇ");     // full-width zenkaku digits and unit — U+FF16 U+FF10 U+FF10 U+FF4D U+FF47
        scan.put(KEY_BIOMARKER_EGFR, "陰性");          // Japanese: Negative
        scan.put(KEY_OCR_CONFIDENCE, "0.85");
        return Collections.unmodifiableMap(scan);
    }

    /**
     * China record — the canonical DEMOGRAPHIC_DRIFT_DETECTED trigger case.
     *
     * Chinese NSCLC cohorts carry an EGFR mutation positive rate of approximately 51%
     * (Wu et al. 2022 meta-analysis, range 47–54%), giving a drift value of:
     *   |0.51 − 0.15| = 0.36 > DRIFT_DETECTION_EGFR_THRESHOLD (0.35)
     *
     * This is the only record in the catalogue guaranteed to fire the DEMOGRAPHIC_DRIFT_DETECTED
     * telemetry flag. The record itself is clinically valid (high OCR confidence, complete fields),
     * so the circuit-breaker does NOT fire — only the soft drift advisory flag is appended.
     *
     * Drug: Osimertinib (奥希替尼 / Tagrisso brand), a third-generation EGFR TKI that is the
     * first-line standard of care for EGFR-mutant NSCLC in China. The drug name is supplied in
     * INN form (as exported by modern Chinese hospital HIS/EHR systems that follow WHO INN policy)
     * so it passes through the passthrough strategy cleanly without needing a CN crosswalk.
     */
    private static Map<String, String> buildChinaDriftRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "CN-PAT-70055");
        scan.put(KEY_REGION,         "CN");
        scan.put(KEY_DRUG_NAME,      "Osimertinib");   // INN; brand: Tagrisso (泰瑞沙 in Chinese)
        scan.put(KEY_DOSAGE,         "80mg");
        scan.put(KEY_BIOMARKER_EGFR, "Positive");      // EGFR exon 19 deletion — common CN mutation subtype
        scan.put(KEY_OCR_CONFIDENCE, "0.95");          // high-quality digital EHR export — no artefacts
        return Collections.unmodifiableMap(scan);
    }

    /**
     * UK very-low-confidence record — OCR score of 0.58, well below the 0.80 circuit-breaker
     * threshold. Simulates a water-damaged paper NHS discharge summary where the ink has
     * bled across columns, producing severe field-level corruption. The drug name is
     * partially unintelligible ("Bev4cizum4b" — digit-4 substitutions), and the dosage
     * string is fragmented ("4OOmg"). Designed to trigger the circuit-breaker independently
     * of the existing JP-PAT-20089 record, confirming the guardrail fires for any region.
     */
    private static Map<String, String> buildUkVeryLowConfidenceRecord() {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(KEY_PATIENT_ID,     "UK-PAT-01102");
        scan.put(KEY_REGION,         "UK");
        scan.put(KEY_DRUG_NAME,      "Bev4cizum4b");  // digit-4 substituting letter-a — severe OCR corruption
        scan.put(KEY_DOSAGE,         "4OOmg");         // digit-4 prefix + letter-O for zero
        scan.put(KEY_BIOMARKER_EGFR, "Neg4tive");      // digit-4 substituting letter-a
        scan.put(KEY_OCR_CONFIDENCE, "0.58");          // well below 0.80 threshold
        return Collections.unmodifiableMap(scan);
    }
}
