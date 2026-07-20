package com.hotel.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VietQrBankCatalogTest {

    @Test
    void normalizesLegacyCodesAndNamesUsedByVietQr() {
        assertEquals("ICB", VietQrBankCatalog.canonicalCode("CTG"));
        assertEquals("VBA", VietQrBankCatalog.canonicalCode("AGR"));
        assertEquals("TCB", VietQrBankCatalog.canonicalCode("VTCBVNVX", "TECHCOMBANK"));
        assertEquals("LPB", VietQrBankCatalog.canonicalCode("unknown", "LPBank"));
    }

    @Test
    void normalizesHolderToUppercaseAscii() {
        assertEquals("PHAN VIET ANH TUAN", VietQrBankCatalog.normalizeHolder("Phan Việt Anh Tuấn"));
    }
}
