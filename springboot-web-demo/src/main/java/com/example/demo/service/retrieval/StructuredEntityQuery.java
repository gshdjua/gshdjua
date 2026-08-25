package com.example.demo.service.retrieval;

public class StructuredEntityQuery {

    private final boolean strict;
    private final EntityType entityType;
    private final String entityValue;

    public StructuredEntityQuery(boolean strict, EntityType entityType, String entityValue) {
        this.strict = strict;
        this.entityType = entityType == null ? EntityType.UNKNOWN : entityType;
        this.entityValue = entityValue == null ? "" : entityValue.trim();
    }

    public boolean isStrict() {
        return strict;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public String getEntityValue() {
        return entityValue;
    }

    public boolean hasEntity() {
        return !entityValue.isEmpty();
    }
}
