package com.loc.atlas.pipeline;

import com.loc.atlas.model.GlobalPatientSnapshot;
import com.loc.atlas.translation.JapanTranslationStrategy;
import com.loc.atlas.translation.TranslationStrategy;
import com.loc.atlas.validation.DemographicDriftGuardrail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Primary system controller for the Atlas-EHR ingestion pipeline.
 *
 * The orchestrator manages a three-stage processing stream per record:
 *
 *   Stage 1 — OCR Extraction:  Accept raw OCR scan payloads from the mock registry.
 *   Stage 2 — Crosswalk:       Dynamically resolve the correct {@link TranslationStrategy}
 *                               for the record's region and translate to the global schema.
 *   Stage 3 — Drift Check:     Pass the translated snapshot through the
 *                               {@link DemographicDriftGuardrail} for confidence gating
 *                               and demographic drift telemetry.
 *
 * Strategy registry: New regional strategies are added by calling
 * {@link #registerStrategy(TranslationStrategy)} — the orchestrator never hardcodes
 * region-specific logic, satisfying the Open-Closed Principle.
 *
 * Records whose region lacks a registered strategy are processed by a built-in
 * passthrough strategy that performs minimal normalization (whitespace trimming,
 * unit stripping) without any locale-specific dictionary lookups.
 */
public final class IngestionOrchestrator {

    private static final Logger LOG = Logger.getLogger(IngestionOrchestrator.class.getName());

    /** Strategy registry: region code → strategy implementation */
    private final Map<String, TranslationStrategy> strategyRegistry = new HashMap<>();

    /**
     * Constructs a fully wired orchestrator with all built-in regional strategies registered.
     * Additional strategies can be injected post-construction via {@link #registerStrategy}.
     */
    public IngestionOrchestrator() {
        registerStrategy(new JapanTranslationStrategy());
        LOG.info(String.format("[Orchestrator] Initialized — %d regional strategies registered: %s",
                strategyRegistry.size(), strategyRegistry.keySet()));
    }

    /**
     * Registers a regional translation strategy.
     *
     * @param strategy  non-null strategy implementation
     * @throws IllegalArgumentException if a strategy for the same region is already registered
     */
    public void registerStrategy(TranslationStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Cannot register a null strategy");
        }
        String region = strategy.getSupportedRegion();
        if (strategyRegistry.containsKey(region)) {
            throw new IllegalArgumentException("Strategy for region '" + region + "' is already registered. "
                    +"Deregister the existing one before replacing it.");
        }
        strategyRegistry.put(region, strategy);
        LOG.info(String.format("[Orchestrator] Registered strategy: %s → %s", region, strategy.getClass().getSimpleName()));
    }

    /**
     * Processes a batch of raw OCR scan payloads through the full three-stage pipeline.
     *
     * Records that fail at any stage are captured in the returned list with appropriate
     * classification flags rather than silently dropped — ensuring complete auditability.
     *
     * @param rawScans  ordered list of raw OCR field maps as returned by the mock registry
     * @return          ordered, unmodifiable list of {@link GlobalPatientSnapshot} instances;
     *                  one per input record, regardless of processing outcome
     */
    public List<GlobalPatientSnapshot> processBatch(List<Map<String, String>> rawScans) {
        LOG.info(String.format("[Orchestrator] Starting batch ingestion — %d records to process.", rawScans.size()));

        List<GlobalPatientSnapshot> results = new ArrayList<>(rawScans.size());

        for (int i = 0; i < rawScans.size(); i++) {
            Map<String, String> scan = rawScans.get(i);
            String patientId = scan.getOrDefault("patient_id", "UNKNOWN_" + i);

            LOG.info(String.format("[Orchestrator] ── Record %d/%d ── patient='%s' region='%s'",
                i + 1, rawScans.size(), patientId, scan.getOrDefault("region", "?")));

            try {
                GlobalPatientSnapshot snapshot = processRecord(scan);
                results.add(snapshot);
            } catch (Exception e) {
                // Unexpected runtime failure — log and emit a sentinel error record
                // so the batch completes and the failure is visible in downstream audit
                LOG.severe(String.format(
                        "[Orchestrator] Unhandled exception processing patient '%s': %s — " +
                        "emitting error sentinel record.", patientId, e.getMessage()));
                results.add(buildErrorSentinel(patientId, scan.getOrDefault("region", "UNKNOWN"), e));
            }
        }

        LOG.info(String.format(
                "[Orchestrator] Batch ingestion complete — %d records processed.", results.size()));
        return Collections.unmodifiableList(results);
    }

    // -------------------------------------------------------------------------
    // Private pipeline stages
    // -------------------------------------------------------------------------

    /**
     * Drives a single OCR scan through all three pipeline stages.
     */
    private GlobalPatientSnapshot processRecord(Map<String, String> rawScan) {

        // ── Stage 1: OCR Extraction (accept raw data, log entry telemetry) ───
        String region = rawScan.getOrDefault("region", "UNKNOWN").strip().toUpperCase();
        String patientId = rawScan.getOrDefault("patient_id", "UNKNOWN").strip();
        LOG.fine(String.format("[Stage 1 | Extraction] patient='%s' region='%s' fields=%d",patientId, region, rawScan.size()));

        // ── Stage 2: Crosswalk (translate to global canonical schema) ─────────
        TranslationStrategy strategy = resolveStrategy(region);
        LOG.fine(String.format("[Stage 2 | Crosswalk] Using strategy: %s for region '%s'",strategy.getClass().getSimpleName(), region));

        Map<String, Object> canonical = strategy.crosswalkToGlobal(rawScan);

        GlobalPatientSnapshot snapshot = buildSnapshot(canonical);
        LOG.fine(String.format("[Stage 2 | Crosswalk] Snapshot built: patient='%s' drug='%s' dosage=%.1fmg",snapshot.patientId(),
                snapshot.standardizedDrugName(), snapshot.dosageMg()));

        // ── Stage 3: Drift Check (apply guardrails) ──────────────────────────
        LOG.fine(String.format("[Stage 3 | DriftCheck] Sending patient '%s' to guardrail evaluation.", snapshot.patientId()));

        GlobalPatientSnapshot validated = DemographicDriftGuardrail.evaluate(snapshot);

        LOG.fine(String.format("[Stage 3 | DriftCheck] Guardrail complete for patient '%s' — flags: [%s]",validated.patientId(),
                validated.classificationFlags().isBlank() ? "NONE" : validated.classificationFlags()));

        return validated;
    }

    /**
     * Resolves the translation strategy for the given region code.
     * Falls back to the built-in passthrough strategy for unregistered regions
     * so that no record is silently dropped.
     */
    private TranslationStrategy resolveStrategy(String region) {
        if (strategyRegistry.containsKey(region)) {
            return strategyRegistry.get(region);
        }
        LOG.warning(String.format("[Orchestrator] No registered strategy for region '%s' — applying passthrough strategy.",region));
        return new PassthroughTranslationStrategy(region);
    }

    /**
     * Constructs a {@link GlobalPatientSnapshot} from a canonical crosswalk output map.
     * Handles missing or unparseable fields defensively, substituting safe sentinels.
     */
    private GlobalPatientSnapshot buildSnapshot(Map<String, Object> canonical) {
        String patientId   = safeString(canonical, "patient_id",      "UNKNOWN");
        String region      = safeString(canonical, "region",           "UNKNOWN");
        String drug        = safeString(canonical, "standardized_drug","UNRESOLVED_DRUG");
        String egfr        = safeString(canonical, "egfr_status",      "Unknown");
        double dosageMg    = safeDouble(canonical, "dosage_mg",        0.0);
        double confidence  = safeDouble(canonical, "ocr_confidence",   0.0);

        return GlobalPatientSnapshot.unflagged(patientId, region, drug, dosageMg, egfr, confidence);
    }

    private String safeString(Map<String, Object> map, String key, String fallback) {
        Object val = map.get(key);
        if (val == null) return fallback;
        String s = val.toString().strip();
        return s.isBlank() ? fallback : s;
    }

    private double safeDouble(Map<String, Object> map, String key, double fallback) {
        Object val = map.get(key);
        if (val == null) return fallback;
        try {
            return Double.parseDouble(val.toString().strip());
        } catch (NumberFormatException e) {
            LOG.warning(String.format(
                    "[Orchestrator] Cannot parse '%s'='%s' as double — using fallback %.1f",
                    key, val, fallback));
            return fallback;
        }
    }

    /**
     * Builds an error-sentinel record for unhandled exceptions during batch processing.
     * These records are clearly flagged and contain a zero dosage to prevent any downstream
     * clinical decision support from acting on them.
     */
    private GlobalPatientSnapshot buildErrorSentinel(String patientId, String region, Exception cause) {
        return new GlobalPatientSnapshot(
                patientId,
                region,
                "PIPELINE_ERROR",
                0.0,
                "Unknown",
                0.0,
                "PIPELINE_ERROR|REQUIRES_HUMAN_AUDIT"
        );
    }

    // -------------------------------------------------------------------------
    // Built-in passthrough strategy (inner class — not exported)
    // -------------------------------------------------------------------------

    /**
     * Minimal passthrough translation strategy for regions without a dedicated implementation.
     * Performs whitespace trimming and basic unit stripping but no dictionary lookups.
     * Keeps the pipeline operational while a proper strategy is under development.
     */
    private static final class PassthroughTranslationStrategy implements TranslationStrategy {

        private final String region;

        PassthroughTranslationStrategy(String region) {
            this.region = region;
        }

        @Override
        public String getSupportedRegion() {
            return region;
        }

        @Override
        public Map<String, Object> crosswalkToGlobal(Map<String, String> rawOcrData) {
            Map<String, Object> out = new HashMap<>();
            out.put("patient_id",      rawOcrData.getOrDefault("patient_id",      "UNKNOWN").strip());
            out.put("region",          region);
            out.put("standardized_drug", rawOcrData.getOrDefault("drug_name",     "UNKNOWN").strip());
            out.put("dosage_mg",       parseDosageFallback(rawOcrData.getOrDefault("dosage", "0mg")));
            out.put("egfr_status",     normalizeEgfrFallback(rawOcrData.getOrDefault("biomarker_egfr", "")));
            out.put("ocr_confidence",  rawOcrData.getOrDefault("ocr_confidence",  "0.0").strip());
            return out;
        }

        private String parseDosageFallback(String raw) {
            // Strip all non-numeric and non-dot characters to get a best-effort numeric value
            String numeric = raw.replaceAll("[^0-9.]", "");
            return numeric.isBlank() ? "0.0" : numeric;
        }

        private String normalizeEgfrFallback(String raw) {
            if (raw == null || raw.isBlank()) return "Unknown";
            String lower = raw.strip().toLowerCase();
            if (lower.startsWith("pos") || lower.contains("陽")) return "Positive";
            if (lower.startsWith("neg") || lower.contains("陰")) return "Negative";
            return "Unknown";
        }
    }
}
