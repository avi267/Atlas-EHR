package com.loc.atlas.translation;

import java.util.Map;

/**
 * Open-Closed extension point for regional clinical data translation.
 *
 * Implementing this interface adds a new regional dialect to the ingestion pipeline
 * without modifying the orchestrator or any existing strategy — satisfying the
 * Open-Closed Principle. Each strategy is responsible for exactly one region and owns
 * its own terminology dictionaries, unit conversion tables, and text-cleansing rules.
 *
 * Contract:
 *  - {@link #getSupportedRegion()} must return a stable ISO 3166-1 alpha-2 code (e.g. "JP", "DE").
 *    The orchestrator uses this as a registry key; duplicate region registrations are rejected.
 *  - {@link #crosswalkToGlobal(Map)} receives raw, unstructured OCR field values and must return
 *    a map whose keys conform to the global canonical schema understood by
 *    {@code IngestionOrchestrator}. Required output keys:
 *      "patient_id"           – pass-through from input
 *      "region"               – pass-through from input
 *      "standardized_drug"    – INN name with optional RxNorm annotation
 *      "dosage_mg"            – numeric dosage as a String (parseable to double)
 *      "egfr_status"          – one of "Positive", "Negative", "Unknown"
 *      "ocr_confidence"       – pass-through as String (parseable to double)
 *  - Implementations must never throw unchecked exceptions for expected data anomalies
 *    (missing fields, unrecognized drug names). Instead, populate a sentinel value
 *    (e.g., "Unknown", "0.0") and let the downstream guardrail decide disposition.
 *  - Implementations must be stateless and thread-safe (safe to call concurrently).
 */
public interface TranslationStrategy {

    /**
     * Returns the ISO 3166-1 alpha-2 country/region code this strategy handles.
     *
     * @return non-null, non-blank region code, e.g. "JP"
     */
    String getSupportedRegion();

    /**
     * Translates a raw OCR field map from a regional clinical format into the
     * platform-canonical global schema.
     *
     * @param rawOcrData  immutable map of raw OCR key-value pairs as extracted by the
     *                    scanning adapter; values may contain noise, mixed scripts, or
     *                    locale-specific formatting
     * @return            non-null map of canonical global fields; must contain all
     *                    required keys listed in the interface contract
     */
    Map<String, Object> crosswalkToGlobal(Map<String, String> rawOcrData);
}
