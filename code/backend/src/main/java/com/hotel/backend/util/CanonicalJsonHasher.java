package com.hotel.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class CanonicalJsonHasher {

    private final ObjectMapper objectMapper;

    public String hash(Object value) {
        try {
            return SecurityTokenHasher.sha256(
                    objectMapper.writeValueAsString(canonicalTree(value)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot canonicalize JSON for hashing", exception);
        }
    }

    public JsonNode canonicalTree(Object value) {
        return canonicalize(objectMapper.valueToTree(value));
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        ObjectNode canonical = objectMapper.createObjectNode();
        ArrayList<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        Collections.sort(fieldNames);
        fieldNames.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
        return canonical;
    }
}
