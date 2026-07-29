package dev.worldecho.domain.content;

import java.util.Objects;

public record ContentKey(String providerId, String contentId) {

    public ContentKey {
        providerId = requireIdentifier(providerId, "providerId");
        contentId = requireIdentifier(contentId, "contentId");
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return providerId + ":" + contentId;
    }
}
