package com.hotel.backend.service;

import com.hotel.backend.util.EmailFormatValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailFormatValidatorTest {

    @Test
    void acceptsOptionalBlankAndCommonAddresses() {
        assertTrue(EmailFormatValidator.isValidOptional(null));
        assertTrue(EmailFormatValidator.isValidOptional("  "));
        assertTrue(EmailFormatValidator.isValidOptional(" guest@example.com "));
        assertTrue(EmailFormatValidator.isValid("khach+booking@sub.hotel.vn"));
    }

    @Test
    void rejectsAmbiguousOrIncompleteAddresses() {
        assertFalse(EmailFormatValidator.isValid("guest@@example.com"));
        assertFalse(EmailFormatValidator.isValid("guest example@example.com"));
        assertFalse(EmailFormatValidator.isValid("@example.com"));
        assertFalse(EmailFormatValidator.isValid("guest@example"));
        assertFalse(EmailFormatValidator.isValid("guest@example."));
    }

    @Test
    void rejectsOversizedInputBeforeScanningDomainRules() {
        assertFalse(EmailFormatValidator.isValid("a".repeat(245) + "@example.com"));
    }
}
