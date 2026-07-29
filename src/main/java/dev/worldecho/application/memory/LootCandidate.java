package dev.worldecho.application.memory;

import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.item.ItemDescriptor;
import dev.worldecho.domain.item.ItemScore;

import java.util.Objects;

/**
 * The single dropped item WorldEcho considers worth remembering for a death.
 */
public record LootCandidate(
        IdentifiedContent content,
        ItemDescriptor descriptor,
        ItemScore score
) {

    public LootCandidate {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(score, "score");
    }
}
