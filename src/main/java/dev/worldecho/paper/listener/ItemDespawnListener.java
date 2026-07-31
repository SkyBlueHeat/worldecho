package dev.worldecho.paper.listener;

import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.item.BoundedPhysicalObservationQueue;
import dev.worldecho.domain.item.OwnershipSubject;
import dev.worldecho.domain.item.PhysicalObservationCycle;
import dev.worldecho.domain.item.PhysicalObservationReason;
import dev.worldecho.domain.item.PhysicalObservationSequencer;
import dev.worldecho.domain.item.PhysicalUniqueItemObservation;
import dev.worldecho.domain.item.PhysicalUniqueItemObservationService;
import dev.worldecho.domain.item.ReconciliationMetrics;
import dev.worldecho.domain.item.TrackedItemId;
import dev.worldecho.paper.item.ItemIdentityAdapter;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Listens for Item entity despawn to record a terminal SYSTEM observation
 * for UNIQUE tracked items.
 */
public final class ItemDespawnListener implements Listener {

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final ItemIdentityAdapter identityAdapter;
    private final PhysicalUniqueItemObservationService observationService;
    private final ReconciliationMetrics metrics;
    private final BoundedPhysicalObservationQueue observationQueue;
    private final PhysicalObservationSequencer sequencer;

    public ItemDespawnListener(
            Plugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            ItemIdentityAdapter identityAdapter,
            PhysicalUniqueItemObservationService observationService,
            ReconciliationMetrics metrics,
            BoundedPhysicalObservationQueue observationQueue,
            PhysicalObservationSequencer sequencer
    ) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.identityAdapter = identityAdapter;
        this.observationService = observationService;
        this.metrics = metrics;
        this.observationQueue = observationQueue;
        this.sequencer = sequencer;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!isPhysicalTrackingEnabled(settings)) {
            return;
        }
        if (!settings.physicalTrackingItemDespawn()) {
            return;
        }

        if (!(event.getEntity() instanceof Item itemEntity)) {
            return;
        }

        ItemStack itemStack = itemEntity.getItemStack();
        if (itemStack == null || itemStack.getType().isAir()) {
            return;
        }

        ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(itemStack);
        if (identity.status() != ItemIdentityAdapter.IdentityStatus.EXISTING) {
            return;
        }

        TrackedItemId itemId = identity.itemId();

        UUID itemEntityUuid = itemEntity.getUniqueId();
        UUID worldUuid = itemEntity.getWorld().getUID();
        String worldName = itemEntity.getWorld().getName();
        var location = itemEntity.getLocation();

        String material = itemStack.getType().name().toLowerCase();
        ContentKey contentKey = ContentKey.parse("minecraft:" + material);
        String contentFingerprint = material + ":" + itemStack.getAmount();

        PhysicalObservationCycle cycle = sequencer.nextCycle();

        PhysicalUniqueItemObservation observation = new PhysicalUniqueItemObservation(
                itemId,
                contentKey,
                material,
                OwnershipSubject.system("item-despawned"),
                PhysicalObservationReason.DESPAWNED,
                itemEntityUuid,
                worldUuid,
                worldName,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                contentFingerprint,
                cycle,
                Instant.now()
        );

        submitObservation(observation);
    }

    private void submitObservation(PhysicalUniqueItemObservation observation) {
        observationQueue.submit(observation, observationService);
    }

    private boolean isPhysicalTrackingEnabled(WorldEchoSettings settings) {
        return settings.automaticTrackingEnabled()
                && settings.physicalTrackingEnabled();
    }
}
