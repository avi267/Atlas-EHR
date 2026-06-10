package com.loc.atlas;

import com.loc.atlas.mock.MockOcrRegistry;
import com.loc.atlas.model.GlobalPatientSnapshot;
import com.loc.atlas.pipeline.IngestionOrchestrator;
import com.loc.atlas.validation.DemographicDriftGuardrail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Atlas-EHR platform entry point.
 *
 * Drives the full simulation lifecycle:
 *  1. Bootstraps the logging subsystem with a clean single-line formatter.
 *  2. Initializes the ingestion orchestrator and strategy registry.
 *  3. Loads synthetic OCR scan payloads from the mock registry.
 *  4. Runs each record through the three-stage pipeline with live terminal visualization.
 *  5. Prints a consolidated summary dashboard of all finalized patient snapshots.
 *
 * Execution is synchronous and single-threaded — appropriate for a batch simulation
 * whose primary output is a human-readable terminal dashboard.
 */
public final class AtlasEHRApplication {

    private static final Logger LOG = Logger.getLogger(AtlasEHRApplication.class.getName());

    private static final String PLATFORM_VERSION = "1.0.0";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    public static void main(String[] args) {

        printBanner();
        LOG.info("[Atlas-EHR] Simulation session started at " + TIMESTAMP_FMT.format(Instant.now()));

        // ── Initialize pipeline components ────────────────────────────────────
        printSectionHeader("PIPELINE INITIALIZATION");
        IngestionOrchestrator orchestrator = new IngestionOrchestrator();
        print("  ✔  IngestionOrchestrator initialized");
        print("  ✔  JapanTranslationStrategy registered (region: JP)");
        print("  ✔  DemographicDriftGuardrail configured:");
        print(String.format("       OCR confidence threshold : %.2f", DemographicDriftGuardrail.OCR_CONFIDENCE_THRESHOLD));
        print(String.format("       Western EGFR baseline    : %.0f%%", DemographicDriftGuardrail.WESTERN_EGFR_POSITIVE_RATE * 100));
        print(String.format("       Drift detection threshold: %.0f pp deviation", DemographicDriftGuardrail.DRIFT_DETECTION_EGFR_THRESHOLD * 100));

        // ── Load synthetic OCR payloads ───────────────────────────────────────
        printSectionHeader("OCR SCAN CATALOGUE");
        List<Map<String, String>> scans = MockOcrRegistry.getAllScans();
        print(String.format("  %d synthetic scan documents loaded from MockOcrRegistry:", scans.size()));
        for (int i = 0; i < scans.size(); i++) {
            Map<String, String> s = scans.get(i);
            print(String.format("  [%d] patient=%-15s region=%s  confidence=%s  drug=%s",
                    i + 1,
                    s.getOrDefault("patient_id",      "UNKNOWN"),
                    s.getOrDefault("region",          "??"),
                    s.getOrDefault("ocr_confidence",  "?"),
                    s.getOrDefault("drug_name",       "UNKNOWN")));
        }

        // ── Run pipeline ──────────────────────────────────────────────────────
        printSectionHeader("PIPELINE EXECUTION  [ Extraction → Crosswalk → Drift Check ]");

        for (int i = 0; i < scans.size(); i++) {
            Map<String, String> scan = scans.get(i);
            String patientId = scan.getOrDefault("patient_id", "UNKNOWN_" + i);
            String region    = scan.getOrDefault("region",     "??");
            String confidence = scan.getOrDefault("ocr_confidence", "?");

            printRecordHeader(i + 1, scans.size(), patientId, region, confidence);

            // Stage callouts — these print before the batch processes, giving a visual flow
            printStageLabel("Stage 1 │ OCR Extraction");
            print(String.format("  Raw fields received   : %d", scan.size()));
            print(String.format("  OCR confidence score  : %s", confidence));
            printStageSeparator();

            printStageLabel("Stage 2 │ Regional Crosswalk");
            boolean isJP = "JP".equalsIgnoreCase(region);
            print(String.format("  Resolving strategy    : %s",
                    isJP ? "JapanTranslationStrategy" : "PassthroughTranslationStrategy"));
            if (isJP) {
                print("  Drug dictionary       : PMDA + RxNorm crosswalk active");
                print("  EGFR normalization    : OCR-artefact correction active");
                print("  Dosage normalization  : zenkaku digit + unit parser active");
            }
            printStageSeparator();

            printStageLabel("Stage 3 │ Demographic Drift Guardrail");
            double ocrConf = parseDoubleSafe(confidence, 0.0);
            if (ocrConf < DemographicDriftGuardrail.OCR_CONFIDENCE_THRESHOLD) {
                print(String.format("  ⚠  CIRCUIT BREAKER → confidence %.2f < threshold %.2f",
                        ocrConf, DemographicDriftGuardrail.OCR_CONFIDENCE_THRESHOLD));
                print("  ↳  Record short-circuited → REQUIRES_HUMAN_AUDIT");
            } else {
                print("  Confidence gate       : PASSED");
                if (isJP) {
                    print(String.format("  Regional EGFR rate    : ~50%% (JP) vs %.0f%% (Western baseline)",
                            DemographicDriftGuardrail.WESTERN_EGFR_POSITIVE_RATE * 100));
                    print("  Drift detection       : EVALUATING ...");
                }
            }
        }

        // Actually run the batch (we printed visual previews above; now get real results)
        print("");
        print("  ═══════════════════════════════════════════════════════════════");
        print("  Dispatching all records to orchestrator ...");
        print("  ═══════════════════════════════════════════════════════════════");

        List<GlobalPatientSnapshot> snapshots = orchestrator.processBatch(scans);

        // ── Results dashboard ─────────────────────────────────────────────────
        printSectionHeader("FINALIZED PATIENT SNAPSHOT SUMMARY");
        print(String.format("  Total records processed : %d", snapshots.size()));

        long clean   = snapshots.stream().filter(s -> s.classificationFlags().isBlank()).count();
        long flagged = snapshots.stream().filter(s -> !s.classificationFlags().isBlank()).count();
        long audit   = snapshots.stream()
                .filter(s -> s.classificationFlags().contains(DemographicDriftGuardrail.FLAG_HUMAN_AUDIT))
                .count();
        long drift   = snapshots.stream()
                .filter(s -> s.classificationFlags().contains(DemographicDriftGuardrail.FLAG_DEMOGRAPHIC_DRIFT))
                .count();

        print(String.format("  Clean (no flags)        : %d", clean));
        print(String.format("  Flagged                 : %d  (audit=%d, drift=%d)", flagged, audit, drift));
        print("");

        for (int i = 0; i < snapshots.size(); i++) {
            GlobalPatientSnapshot snap = snapshots.get(i);
            print(String.format("  ─── Record %d/%d ─────────────────────────────────────────────",
                    i + 1, snapshots.size()));
            print(snap.toDisplayString());
            print("");
        }

        // ── Flag legend ───────────────────────────────────────────────────────
        printSectionHeader("CLASSIFICATION FLAG LEGEND");
        print("  REQUIRES_HUMAN_AUDIT     — OCR confidence below safety threshold (0.80).");
        print("                             Record must NOT be used in automated workflows.");
        print("  LOW_OCR_CONFIDENCE       — Supplemental low-confidence tag (accompanies HUMAN_AUDIT).");
        print("  DEMOGRAPHIC_DRIFT_DETECTED — Incoming record's regional EGFR profile deviates");
        print("                             significantly from the Western population baseline.");
        print("                             Informational — record is still usable; research team");
        print("                             should examine the full regional cohort.");
        print("  BIOMARKER_MISSING        — EGFR biomarker not present in the source document.");
        print("                             Pending lab correlation required.");
        print("  PIPELINE_ERROR           — Unhandled runtime error during processing.");
        print("                             Investigate logs and resubmit.");

        // ── Session close ─────────────────────────────────────────────────────
        printSectionHeader("SESSION COMPLETE");
        print(String.format("  Atlas-EHR v%s  |  %s", PLATFORM_VERSION, TIMESTAMP_FMT.format(Instant.now())));
        print("  All records processed. Exiting.");
        print(repeat("═", 70));
        print("");

        LOG.info("[Atlas-EHR] Simulation session ended normally.");
    }

    // -------------------------------------------------------------------------
    // Terminal visualization helpers
    // -------------------------------------------------------------------------

    private static void printBanner() {
        print("");
        print(repeat("═", 70));
        print("  █████╗ ████████╗██╗      █████╗ ███████╗    ███████╗██╗  ██╗██████╗ ");
        print(" ██╔══██╗╚══██╔══╝██║     ██╔══██╗██╔════╝    ██╔════╝██║  ██║██╔══██╗");
        print(" ███████║   ██║   ██║     ███████║███████╗    █████╗  ███████║██████╔╝");
        print(" ██╔══██║   ██║   ██║     ██╔══██║╚════██║    ██╔══╝  ██╔══██║██╔══██╗");
        print(" ██║  ██║   ██║   ███████╗██║  ██║███████║    ███████╗██║  ██║██║  ██║");
        print(" ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝╚══════╝    ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝");
        print("");
        print("  Enterprise HealthTech EHR Ingestion Platform");
        print(repeat("═", 70));
        print("");
    }

    private static void printSectionHeader(String title) {
        print("");
        print(repeat("─", 70));
        print("  ◆  " + title);
        print(repeat("─", 70));
    }

    private static void printRecordHeader(int index, int total, String patientId, String region, String confidence) {
        print("");
        print(String.format("  ┌─[ Record %d / %d ]──────────────────────────────────────────────",
                index, total));
        print(String.format("  │  Patient : %s   Region : %s   OCR Confidence : %s",
                patientId, region, confidence));
        print("  │");
    }

    private static void printStageLabel(String label) {
        print("  ├── " + label);
    }

    private static void printStageSeparator() {
        print("  │");
    }

    private static void print(String line) {
        System.out.println(line);
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    private static double parseDoubleSafe(String s, double fallback) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }
}
