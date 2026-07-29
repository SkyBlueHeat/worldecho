package dev.worldecho.integration;

import dev.worldecho.domain.content.IdentifiedContent;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class IntegrationRegistry {

    private final List<EntityContentProvider> entityProviders = new ArrayList<>();
    private final List<ItemContentProvider> itemProviders = new ArrayList<>();

    public void registerEntityProvider(EntityContentProvider provider) {
        entityProviders.add(provider);
        entityProviders.sort(Comparator.comparingInt(EntityContentProvider::priority).reversed());
    }

    public void registerItemProvider(ItemContentProvider provider) {
        itemProviders.add(provider);
        itemProviders.sort(Comparator.comparingInt(ItemContentProvider::priority).reversed());
    }

    public Optional<IdentifiedContent> identifyEntity(Entity entity) {
        return entityProviders.stream()
                .filter(provider -> provider.health() == ProviderHealth.AVAILABLE)
                .filter(provider -> provider.supports(entity))
                .map(provider -> provider.identify(entity))
                .flatMap(Optional::stream)
                .findFirst();
    }

    public Optional<IdentifiedContent> identifyItem(ItemStack itemStack) {
        return itemProviders.stream()
                .filter(provider -> provider.health() == ProviderHealth.AVAILABLE)
                .filter(provider -> provider.supports(itemStack))
                .map(provider -> provider.identify(itemStack))
                .flatMap(Optional::stream)
                .findFirst();
    }

    public List<String> providerStatus() {
        List<String> result = new ArrayList<>();
        entityProviders.forEach(provider ->
                result.add("entity:" + provider.providerId() + "=" + provider.health()));
        itemProviders.forEach(provider ->
                result.add("item:" + provider.providerId() + "=" + provider.health()));
        return List.copyOf(result);
    }
}
