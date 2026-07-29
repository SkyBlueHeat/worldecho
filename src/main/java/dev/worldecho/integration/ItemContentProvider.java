package dev.worldecho.integration;

import dev.worldecho.domain.content.IdentifiedContent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public interface ItemContentProvider {

    String providerId();

    int priority();

    ProviderHealth health();

    boolean supports(ItemStack itemStack);

    Optional<IdentifiedContent> identify(ItemStack itemStack);
}
