package com.hotel.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomControllerContractTest {

    @Test
    void preservesRoomEndpointMappingsAndAuthorization() {
        assertBasePath(RoomCatalogController.class);
        assertBasePath(RoomAssignmentController.class);
        assertBasePath(RoomMaintenanceController.class);

        List<EndpointContract> contracts = List.of(
                endpoint(RoomCatalogController.class, "create", PostMapping.class, "", "hasRole('ADMIN')"),
                endpoint(RoomCatalogController.class, "update", PutMapping.class, "/{id}", "hasRole('ADMIN')"),
                endpoint(RoomCatalogController.class, "getById", GetMapping.class, "/{id}", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomCatalogController.class, "getAll", GetMapping.class, "", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomCatalogController.class, "getList", GetMapping.class, "/list", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomCatalogController.class, "search", GetMapping.class, "/search", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomCatalogController.class, "delete", DeleteMapping.class, "/{id}", "hasRole('ADMIN')"),
                endpoint(RoomAssignmentController.class, "getAvailableRoomsForReservation", GetMapping.class,
                        "/available-for-reservation", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomAssignmentController.class, "updateStatus", PatchMapping.class,
                        "/{id}/status", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomAssignmentController.class, "updateCleaningStatus", PatchMapping.class,
                        "/{id}/cleaning-status", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomAssignmentController.class, "transferCheckedInRoom", PatchMapping.class,
                        "/{sourceRoomId}/transfer", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomAssignmentController.class, "getActiveReservation", GetMapping.class,
                        "/{roomId}/active-reservation", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomMaintenanceController.class, "startMaintenance", PatchMapping.class,
                        "/{roomId}/maintenance/start", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomMaintenanceController.class, "addMaintenanceLog", PostMapping.class,
                        "/{roomId}/maintenance/logs", "hasAnyRole('ADMIN', 'STAFF')"),
                endpoint(RoomMaintenanceController.class, "completeMaintenance", PatchMapping.class,
                        "/{roomId}/maintenance/complete", "hasAnyRole('ADMIN', 'STAFF')"));

        contracts.forEach(this::assertContract);
    }

    private void assertBasePath(Class<?> controllerType) {
        RequestMapping mapping = controllerType.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/api/rooms");
    }

    private void assertContract(EndpointContract contract) {
        Method method = Arrays.stream(contract.controllerType().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(contract.methodName()))
                .findFirst()
                .orElseThrow();
        Annotation mapping = method.getAnnotation(contract.mappingType());
        assertThat(mapping).isNotNull();
        String[] paths = mappingPath(mapping);
        String actualPath = paths.length == 0 ? "" : paths[0];
        assertThat(actualPath).isEqualTo(contract.path());
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(contract.authorization());
    }

    private String[] mappingPath(Annotation mapping) {
        if (mapping instanceof GetMapping annotation) return annotation.value();
        if (mapping instanceof PostMapping annotation) return annotation.value();
        if (mapping instanceof PutMapping annotation) return annotation.value();
        if (mapping instanceof PatchMapping annotation) return annotation.value();
        if (mapping instanceof DeleteMapping annotation) return annotation.value();
        throw new IllegalArgumentException("Unsupported mapping " + mapping);
    }

    private EndpointContract endpoint(
            Class<?> controllerType,
            String methodName,
            Class<? extends Annotation> mappingType,
            String path,
            String authorization) {
        return new EndpointContract(controllerType, methodName, mappingType, path, authorization);
    }

    private record EndpointContract(
            Class<?> controllerType,
            String methodName,
            Class<? extends Annotation> mappingType,
            String path,
            String authorization) {
    }
}
