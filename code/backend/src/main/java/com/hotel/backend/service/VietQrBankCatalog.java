package com.hotel.backend.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/** Shared normalization for values sent to the VietQR image generator. */
public final class VietQrBankCatalog {

    private static final Set<String> VIET_QR_CODES = Set.of(
            "VCB", "BIDV", "ICB", "VBA", "TCB", "MB", "ACB", "VPB", "TPB", "STB",
            "VIB", "HDB", "SHB", "MSB", "OCB", "LPB", "EIB", "SCB", "SEAB", "NAB");

    private static final Map<String, String> LEGACY_CODE_ALIASES = Map.ofEntries(
            entry("CTG", "ICB"),
            entry("AGR", "VBA"),
            entry("VTCBVNVX", "TCB"),
            entry("VIETCOMBANK", "VCB"),
            entry("VIETINBANK", "ICB"),
            entry("AGRIBANK", "VBA"),
            entry("TECHCOMBANK", "TCB"),
            entry("MBBANK", "MB"),
            entry("VPBANK", "VPB"),
            entry("TPBANK", "TPB"),
            entry("SACOMBANK", "STB"),
            entry("HDBANK", "HDB"),
            entry("LPBANK", "LPB"),
            entry("LIENVIETPOSTBANK", "LPB"),
            entry("EXIMBANK", "EIB"),
            entry("SEABANK", "SEAB"),
            entry("NAMABANK", "NAB"));

    private VietQrBankCatalog() {
    }

    public static String canonicalCode(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return LEGACY_CODE_ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * Old recipient rows may contain a SWIFT value while retaining a valid bank
     * display name. Prefer a known VietQR code derived from either field.
     */
    public static String canonicalCode(String value, String bankName) {
        String code = canonicalCode(value);
        if (code != null && VIET_QR_CODES.contains(code)) return code;

        String codeFromName = canonicalCode(bankName);
        return codeFromName != null && VIET_QR_CODES.contains(codeFromName) ? codeFromName : code;
    }

    /** VietQR documents the holder field as uppercase text without diacritics. */
    public static String normalizeHolder(String value) {
        if (value == null) return null;
        String withoutDiacritics = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('Đ', 'D')
                .replace('đ', 'd');
        return withoutDiacritics
                .replaceAll("[^A-Za-z0-9 .'-]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
    }
}
