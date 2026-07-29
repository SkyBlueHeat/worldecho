package dev.worldecho.integration;

import dev.worldecho.domain.content.IdentifiedContent;
import org.bukkit.entity.Entity;

import java.util.Optional;

public interface EntityContentProvider {

    String providerId();

    int priority();

    ProviderHealth health();

    boolean supports(Entity entity);

    Optional<IdentifiedContent> identify(Entity entity);
}
