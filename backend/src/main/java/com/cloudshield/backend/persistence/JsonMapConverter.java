package com.cloudshield.backend.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;

@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Override public String convertToDatabaseColumn(Map<String, Object> value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalArgumentException("Audit details must be JSON serializable", ex); }
    }
    @Override public Map<String, Object> convertToEntityAttribute(String value) {
        if (value == null) return null;
        try { return MAPPER.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException ex) { throw new IllegalStateException("Stored audit details are invalid JSON", ex); }
    }
}
