package com.hotel.backend.service;

import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutReconciliationAccessPolicyTest {

    private final CheckoutReconciliationAccessPolicy policy =
            new CheckoutReconciliationAccessPolicy();

    @Test
    void staffAndAdminCanCreateButOnlyAdminCanResolve() {
        User staff = User.builder().type(UserType.STAFF).build();
        User admin = User.builder().type(UserType.ADMIN).build();
        User customer = User.builder().type(UserType.CUSTOMER).build();

        assertDoesNotThrow(() -> policy.requireOperationsUser(staff));
        assertDoesNotThrow(() -> policy.requireOperationsUser(admin));
        assertThrows(AppException.class, () -> policy.requireOperationsUser(customer));
        assertThrows(AppException.class, () -> policy.requireOperationsUser(null));

        assertDoesNotThrow(() -> policy.requireAdmin(admin));
        assertThrows(AppException.class, () -> policy.requireAdmin(staff));
        assertThrows(AppException.class, () -> policy.requireAdmin(null));
    }

    @Test
    void actorNameKeepsFullNameUsernameAndIdFallbackOrder() {
        User user = User.builder()
                .fullName("  Nguyen Van Admin  ")
                .username("admin")
                .build();
        user.setId(7L);
        assertEquals("Nguyen Van Admin", policy.actorName(user));

        user.setFullName(" ");
        assertEquals("admin", policy.actorName(user));

        user.setUsername(null);
        assertEquals("user:7", policy.actorName(user));
    }
}
