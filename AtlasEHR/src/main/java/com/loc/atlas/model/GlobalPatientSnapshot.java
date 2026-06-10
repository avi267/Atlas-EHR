package com.loc.atlas.model;

/**
 * Immutable, thread-safe data carrier representing a fully standardized patient record
 * after the complete ingestion pipeline (OCR extraction → crosswalk → drift validation).
 *
 * Using a Java Record enforces value-based equality and prevents downstream mutation,
 * which is critical when records fan out to multiple parallel audit/research consumers.
 *
 * @param patientId         Globally unique patient identifier (UUID or hospital-scoped ID)
 * @param region            ISO 3166-1 alpha-2 country code of the originating clinical record
 * @param standardizedDrugName  INN drug name with optional RxNorm annotation, post-crosswalk
 * @param dosageMg          Normalized dosage in milligrams (metric conversion applied for non-SI sources)
 * @param egfrStatus        Standardized EGFR mutation status label: "Positive", "Negative", or "Unknown"
 * @param ocrConfidence     Composite OCR confidence score [0.0–1.0] from the extraction layer
 * @param classificationFlags  Pipe-delimited runtime telemetry flags appended by guardrails
 */
public record GlobalPatientSnapshot(
        String patientId,
        String region,
        String standardizedDrugName,
        double dosageMg,
        String egfrStatus,
        double ocrConfidence,
        String classificationFlags
) {

    /**
     * Compact canonical constructor with defensive validation.
     * Records do not allow null in any field that downstream consumers treat as non-nullable.
     */
    public GlobalPatientSnapshot {
        if (patientId == null || patientId.isBlank()) {
            throw new IllegalArgumentException("patientId must be non-null and non-blank");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region must be non-null and non-blank");
        }
        if (standardizedDrugName == null || standardizedDrugName.isBlank()) {
            throw new IllegalArgumentException("standardizedDrugName must be non-null and non-blank");
        }
        if (dosageMg < 0.0) {
            throw new IllegalArgumentException("dosageMg cannot be negative; received: " + dosageMg);
        }
        if (egfrStatus == null || egfrStatus.isBlank()) {
            throw new IllegalArgumentException("egfrStatus must be non-null and non-blank");
        }
        if (ocrConfidence < 0.0 || ocrConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "ocrConfidence must be in [0.0, 1.0]; received: " + ocrConfidence);
        }
        // classificationFlags may be empty string (no flags) — null is coerced to empty
        if (classificationFlags == null) {
            classificationFlags = "";
        }
    }

    /**
     * Convenience factory for constructing a snapshot with no classification flags yet assigned.
     * Used during the crosswalk phase before guardrail evaluation.
     */
    public static GlobalPatientSnapshot unflagged(
            String patientId,
            String region,
            String standardizedDrugName,
            double dosageMg,
            String egfrStatus,
            double ocrConfidence
    ) {
        return new GlobalPatientSnapshot(
                patientId, region, standardizedDrugName, dosageMg, egfrStatus, ocrConfidence, "");
    }

    /**
     * Returns a new snapshot with the given flag appended to the existing flag string.
     * Preserves immutability — the original record is not modified.
     */
    public GlobalPatientSnapshot withAdditionalFlag(String flag) {
        String updated = classificationFlags.isBlank()
                ? flag
                : classificationFlags + "|" + flag;
        return new GlobalPatientSnapshot(
                patientId, region, standardizedDrugName, dosageMg, egfrStatus, ocrConfidence, updated);
    }

    /**
     * Human-readable summary for terminal dashboards and audit logs.
     */
    public String toDisplayString() {
        return String.format(
                "  ┌─ Patient       : %s  [%s]%n" +
                "  │  Drug          : %s%n" +
                "  │  Dosage        : %.1f mg%n" +
                "  │  EGFR Status   : %s%n" +
                "  │  OCR Confidence: %.2f%n" +
                "  └─ Flags         : %s",
                patientId, region,
                standardizedDrugName,
                dosageMg,
                egfrStatus,
                ocrConfidence,
                classificationFlags.isBlank() ? "NONE" : classificationFlags
        );
    }
}
