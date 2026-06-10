package com.loc.atlas.translation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Regional translation strategy for Japanese clinical records (ISO region: "JP").
 *
 * Responsibilities:
 *  1. Drug name crosswalk — maps Japanese brand/generic names (katakana, kanji, romaji)
 *     to INN names annotated with RxNorm concept identifiers.
 *  2. Dosage normalization — strips locale-specific formatting (全角digits, unit suffixes)
 *     and converts to a plain numeric milligram value.
 *  3. EGFR status normalization — maps Japanese clinical shorthand and mixed OCR corruption
 *     patterns to the three canonical states: Positive / Negative / Unknown.
 *  4. Text cleansing — removes Unicode noise introduced by OCR on low-quality fax scans
 *     (em-dashes replacing katakana long-vowel marks, letter-digit confusion, etc.).
 *
 * All dictionaries are compile-time constants — no I/O, no external lookups.
 * This keeps the strategy stateless and safe for concurrent use.
 */
public final class JapanTranslationStrategy implements TranslationStrategy {

    private static final Logger LOG = Logger.getLogger(JapanTranslationStrategy.class.getName());

    private static final String REGION = "JP";

    // -------------------------------------------------------------------------
    // Drug name crosswalk dictionary
    // Maps: Japanese name (normalized) → "INN (RxNorm ID: XXXXXXX)"
    // Sources: RxNorm, WHO INN list, PMDA (Japanese pharmaceuticals authority)
    // -------------------------------------------------------------------------
    private static final Map<String, String> DRUG_CROSSWALK = Map.ofEntries(
            Map.entry("ゼローダ",       "Capecitabine (RxNorm ID: 173562)"),  // Xeloda brand (katakana long vowel mark U+30FC)
            Map.entry("ゼロ—ダ",  "Capecitabine (RxNorm ID: 173562)"),  // em-dash U+2014 OCR artefact form
            Map.entry("capecitabine",   "Capecitabine (RxNorm ID: 173562)"),  // latin fallback
            Map.entry("タルセバ",       "Erlotinib (RxNorm ID: 352260)"),     // Tarceva brand
            Map.entry("イレッサ",       "Gefitinib (RxNorm ID: 210557)"),     // Iressa brand
            Map.entry("アバスチン",     "Bevacizumab (RxNorm ID: 414084)"),   // Avastin brand
            Map.entry("オプジーボ",     "Nivolumab (RxNorm ID: 1597876)"),    // Opdivo brand
            Map.entry("キイトルーダ",   "Pembrolizumab (RxNorm ID: 1798389)"),// Keytruda brand
            Map.entry("ハーセプチン",   "Trastuzumab (RxNorm ID: 224914)"),   // Herceptin brand
            Map.entry("リツキサン",     "Rituximab (RxNorm ID: 121191)"),     // Rituxan brand
            Map.entry("テモダール",     "Temozolomide (RxNorm ID: 258494)")   // Temodar brand
    );

    // -------------------------------------------------------------------------
    // EGFR status normalization dictionary
    // Maps raw OCR values (including corruption variants) → canonical label
    // -------------------------------------------------------------------------
    private static final Map<String, String> EGFR_NORMALIZATION = Map.ofEntries(
            Map.entry("positive",   "Positive"),
            Map.entry("陽性",       "Positive"),    // Japanese: positive
            Map.entry("po5itive",   "Positive"),    // digit-5 vs letter-s OCR confusion
            Map.entry("p0sitive",   "Positive"),    // digit-0 vs letter-o OCR confusion
            Map.entry("positve",    "Positive"),    // common OCR omission
            Map.entry("negative",   "Negative"),
            Map.entry("陰性",       "Negative"),    // Japanese: negative
            Map.entry("negat1ve",   "Negative"),    // digit-1 vs letter-i OCR confusion
            Map.entry("unknown",    "Unknown"),
            Map.entry("不明",       "Unknown"),     // Japanese: unknown/unclear
            Map.entry("pending",    "Unknown"),
            Map.entry("",           "Unknown")
    );

    // -------------------------------------------------------------------------
    // Dosage unit multipliers for non-mg inputs
    // -------------------------------------------------------------------------
    private static final Map<String, Double> UNIT_MULTIPLIER = Map.of(
            "mg",  1.0,
            "g",   1000.0,
            "mcg", 0.001,
            "µg",  0.001
    );

    @Override
    public String getSupportedRegion() {
        return REGION;
    }

    @Override
    public Map<String, Object> crosswalkToGlobal(Map<String, String> rawOcrData) {
        LOG.info(String.format(
                "[JP Strategy] Beginning crosswalk for patient '%s'",
                rawOcrData.getOrDefault("patient_id", "UNKNOWN")));

        Map<String, Object> canonical = new LinkedHashMap<>();

        // Pass-through identity fields
        canonical.put("patient_id",     rawOcrData.getOrDefault("patient_id", "UNKNOWN"));
        canonical.put("region",         REGION);
        canonical.put("ocr_confidence", rawOcrData.getOrDefault("ocr_confidence", "0.0"));

        // Drug name crosswalk
        String rawDrug = rawOcrData.getOrDefault("drug_name", "").strip();
        String standardizedDrug = resolveDrugName(rawDrug);
        canonical.put("standardized_drug", standardizedDrug);
        LOG.fine(String.format("[JP Strategy] Drug crosswalk: '%s' → '%s'", rawDrug, standardizedDrug));

        // Dosage normalization
        String rawDosage = rawOcrData.getOrDefault("dosage", "0mg");
        double dosageMg = normalizeDosage(rawDosage);
        canonical.put("dosage_mg", String.valueOf(dosageMg));
        LOG.fine(String.format("[JP Strategy] Dosage normalization: '%s' → %.1f mg", rawDosage, dosageMg));

        // EGFR status normalization
        String rawEgfr = rawOcrData.getOrDefault("biomarker_egfr", "").strip().toLowerCase();
        String egfrStatus = normalizeEgfrStatus(rawEgfr);
        canonical.put("egfr_status", egfrStatus);
        LOG.fine(String.format("[JP Strategy] EGFR normalization: '%s' → '%s'", rawEgfr, egfrStatus));

        LOG.info(String.format(
                "[JP Strategy] Crosswalk complete for patient '%s' — drug='%s', dosage=%.1fmg, egfr='%s'",
                canonical.get("patient_id"), standardizedDrug, dosageMg, egfrStatus));

        return canonical;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a raw drug name to its INN + RxNorm form.
     *
     * Resolution order:
     *  1. Exact match in dictionary (handles katakana, kanji, romaji)
     *  2. Lowercase normalized match (handles ASCII case variation)
     *  3. Cleaned variant (strips punctuation artefacts, then retries dictionary)
     *  4. Falls back to the raw value with an UNRESOLVED annotation so data is not silently dropped
     */
    private String resolveDrugName(String rawDrug) {
        if (rawDrug == null || rawDrug.isBlank()) {
            return "UNRESOLVED_DRUG";
        }

        // Exact match (covers katakana/kanji entries)
        if (DRUG_CROSSWALK.containsKey(rawDrug)) {
            return DRUG_CROSSWALK.get(rawDrug);
        }

        // Lowercase ASCII match
        String lower = rawDrug.toLowerCase();
        if (DRUG_CROSSWALK.containsKey(lower)) {
            return DRUG_CROSSWALK.get(lower);
        }

        // Strip common OCR punctuation artefacts and retry
        String cleaned = rawDrug
                .replace("—", "ー")   // em-dash → katakana long vowel mark
                .replace("–", "ー")   // en-dash variant
                .replace(" ", "")      // remove spaces introduced by character segmentation
                .strip();
        if (DRUG_CROSSWALK.containsKey(cleaned)) {
            LOG.warning(String.format(
                    "[JP Strategy] Drug name required artefact-cleaning before match: '%s' → '%s'",
                    rawDrug, cleaned));
            return DRUG_CROSSWALK.get(cleaned);
        }

        LOG.warning(String.format(
                "[JP Strategy] Drug name '%s' not found in crosswalk dictionary — flagging as UNRESOLVED",
                rawDrug));
        return "UNRESOLVED: " + rawDrug;
    }

    /**
     * Parses and normalizes a dosage string to milligrams.
     *
     * Handles:
     *  - Trailing/mixed unit suffixes: "500mg", "0.5g", "250 mg"
     *  - OCR letter-digit confusions: "50Omg" (letter-O → digit-0), "l50mg" (letter-l → digit-1)
     *  - Zenkaku (full-width) digit characters common in Japanese typography
     */
    private double normalizeDosage(String rawDosage) {
        if (rawDosage == null || rawDosage.isBlank()) {
            LOG.warning("[JP Strategy] Empty dosage field — defaulting to 0.0 mg");
            return 0.0;
        }

        // Normalize full-width digits to ASCII
        String normalized = normalizeFullWidthDigits(rawDosage);

        // Correct common OCR character substitutions in the numeric portion
        // Process character by character: replace O→0 and l→1 only in digit positions
        normalized = fixOcrDigitSubstitutions(normalized);

        // Extract numeric portion and unit suffix
        StringBuilder digits = new StringBuilder();
        StringBuilder unitChars = new StringBuilder();
        boolean inUnit = false;

        for (char c : normalized.toCharArray()) {
            if (!inUnit && (Character.isDigit(c) || c == '.')) {
                digits.append(c);
            } else {
                inUnit = true;
                if (Character.isLetter(c) || c == 'µ') {
                    unitChars.append(c);
                }
            }
        }

        double numericValue;
        try {
            numericValue = digits.length() > 0 ? Double.parseDouble(digits.toString()) : 0.0;
        } catch (NumberFormatException e) {
            LOG.warning(String.format(
                    "[JP Strategy] Cannot parse numeric portion of dosage '%s' — defaulting to 0.0", rawDosage));
            return 0.0;
        }

        String unit = unitChars.toString().toLowerCase().strip();
        double multiplier = UNIT_MULTIPLIER.getOrDefault(unit, 1.0);  // default to mg if unrecognized

        return numericValue * multiplier;
    }

    /**
     * Maps an OCR-extracted EGFR status string to one of three canonical values.
     * Uses the normalization dictionary with a fallback to "Unknown" for truly unrecognized values.
     */
    private String normalizeEgfrStatus(String rawEgfr) {
        if (rawEgfr == null) {
            return "Unknown";
        }
        String lower = rawEgfr.strip().toLowerCase();
        return EGFR_NORMALIZATION.getOrDefault(lower, "Unknown");
    }

    /**
     * Converts Unicode full-width (zenkaku) digits U+FF10–U+FF19 to their ASCII equivalents.
     * Japanese hospital systems commonly use zenkaku digits in printed forms.
     */
    private String normalizeFullWidthDigits(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Corrects common OCR digit-letter substitutions in the numeric prefix of a dosage string.
     * Only applies substitutions before the first letter unit character to avoid corrupting unit names.
     */
    private String fixOcrDigitSubstitutions(String input) {
        StringBuilder sb = new StringBuilder();
        boolean reachedUnit = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!reachedUnit) {
                // 'O' (capital letter O) in numeric position → '0' (digit zero)
                if (c == 'O' && sb.length() > 0) {
                    sb.append('0');
                }
                // 'l' (lowercase letter L) at start → '1' (digit one)
                else if (c == 'l' && sb.length() == 0) {
                    sb.append('1');
                }
                else if (Character.isLetter(c)) {
                    reachedUnit = true;
                    sb.append(c);
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
