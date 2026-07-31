package dev.worldecho.paper.listener;

import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.item.ItemDescriptor;
import dev.worldecho.domain.item.BoundedPhysicalObservationQueue;
import dev.worldecho.domain.item.ItemIdentityPolicy;
import dev.worldecho.domain.item.ObservedItemDescriptor;
import dev.worldecho.domain.item.OwnershipSubject;
import dev.worldecho.domain.item.PhysicalObservationCycle;
import dev.worldecho.domain.item.PhysicalObservationReason;
import dev.worldecho.domain.item.PhysicalObservationSequencer;
import dev.worldecho.domain.item.PhysicalUniqueItemObservation;
import dev.worldecho.domain.item.PhysicalUniqueItemObservationService;
import dev.worldecho.domain.item.ReconciliationMetrics;
import dev.worldecho.domain.item.TrackedItemId;
import dev.worldecho.integration.bukkit.BukkitItems;
import dev.worldecho.paper.item.ItemIdentityAdapter;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Listens for player drops and generic Item entity spawns to observe
 * UNIQUE items as WORLD_DROP subjects.
 *
 * <p>All Bukkit state is captured on the main thread into immutable
 * {@link PhysicalUniqueItemObservation} records, then processed asynchronously.
 */
public final class WorldDropObservationListener implements Listener {

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final ItemIdentityAdapter identityAdapter;
    private final PhysicalUniqueItemObservationService observationService;
    private final ReconciliationMetrics metrics;
    private final BoundedPhysicalObservationQueue observationQueue;
    private final PlayerInventoryObservationListener inventoryListener;
    private final PhysicalObservationSequencer sequencer;

    public WorldDropObservationListener(
            Plugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            ItemIdentityAdapter identityAdapter,
            PhysicalUniqueItemObservationService observationService,
            ReconciliationMetrics metrics,
            BoundedPhysicalObservationQueue observationQueue,
            PlayerInventoryObservationListener inventoryListener,
            PhysicalObservationSequencer sequencer
    ) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.identityAdapter = identityAdapter;
        this.observationService = observationService;
        this.metrics = metrics;
        this.observationQueue = observationQueue;
        this.inventoryListener = inventoryListener;
        this.sequencer = sequencer;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!isPhysicalTrackingEnabled(settings)) {
            return;
        }
        if (!settings.physicalTrackingWorldDrops()) {
            return;
        }

        Player player = event.getPlayer();
        Item itemEntity = event.getItemDrop();
        ItemStack itemStack = itemEntity.getItemStack();

        if (itemStack == null || itemStack.getType().isAir()) {
            return;
        }

        ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(itemStack);
        TrackedItemId itemId = resolveOrAssignIdentity(identity, itemStack, settings);
        if (itemId == null) {
            return;
        }

        PhysicalUniqueItemObservation observation = buildObservation(
                itemEntity, itemStack, itemId, PhysicalObservationReason.DROPPED);

        submitObservation(observation);

        if (settings.automaticTrackingEnabled()
                && settings.automaticTrackingReconcileAfterInventoryEvents()) {
            inventoryListener.onPlayerDropItem(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!isPhysicalTrackingEnabled(settings)) {
            return;
        }
        if (!settings.physicalTrackingWorldDrops()) {
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
        if (identity.status() == ItemIdentityAdapter.IdentityStatus.MALFORMED) {
            return;
        }
        if (identity.status() == ItemIdentityAdapter.IdentityStatus.UNSUPPORTED_ITEM) {
            return;
        }

        TrackedItemId itemId;
        if (identity.status() == ItemIdentityAdapter.IdentityStatus.EXISTING) {
            itemId = identity.itemId();
        } else if (identity.status() == ItemIdentityAdapter.IdentityStatus.MISSING) {
            if (!isClassifiedUnique(itemStack)) {
                return;
            }
            ItemIdentityAdapter.IdentityResult assigned = identityAdapter.ensureIdentity(itemStack);
            if (assigned.status() != ItemIdentityAdapter.IdentityStatus.ASSIGNED) {
                return;
            }
            itemId = assigned.itemId();
            itemEntity.setItemStack(itemStack);
            metrics.recordIdentityAssigned();
        } else {
            return;
        }

        PhysicalUniqueItemObservation observation = buildObservation(
                itemEntity, itemStack, itemId, PhysicalObservationReason.WORLD_DROP_OBSERVED);

        submitObservation(observation);
    }

    private PhysicalUniqueItemObservation buildObservation(
            Item itemEntity, ItemStack itemStack, TrackedItemId itemId,
            PhysicalObservationReason reason
    ) {
        ItemDescriptor descriptor = BukkitItems.describe(itemStack, "minecraft");
        ContentKey contentKey = ContentKey.parse(
                "minecraft:" + descriptor.materialKey().replace("minecraft:", ""));

        UUID itemEntityUuid = itemEntity.getUniqueId();
        UUID worldUuid = itemEntity.getWorld().getUID();
        String worldName = itemEntity.getWorld().getName();
        var location = itemEntity.getLocation();
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        String contentFingerprint = descriptor.materialKey() + ":" + itemStack.getAmount();

        PhysicalObservationCycle cycle = sequencer.nextCycle();

        return new PhysicalUniqueItemObservation(
                itemId,
                contentKey,
                descriptor.materialKey(),
                dev.worldecho.domain.item.OwnershipSubject.worldDrop(itemEntityUuid),
                reason,
                itemEntityUuid,
                worldUuid,
                worldName,
                blockX, blockY, blockZ,
                contentFingerprint,
                cycle,
                Instant.now()
        );
    }

    private TrackedItemId resolveOrAssignIdentity(
            ItemIdentityAdapter.IdentityResult identity,
            ItemStack itemStack,
            WorldEchoSettings settings
    ) {
        return switch (identity.status()) {
            case EXISTING -> identity.itemId();
            case MISSING -> {
                if (!isClassifiedUnique(itemStack)) {
                    yield null;
                }
                ItemIdentityAdapter.IdentityResult assigned = identityAdapter.ensureIdentity(itemStack);
                if (assigned.status() == ItemIdentityAdapter.IdentityStatus.ASSIGNED) {
                    metrics.recordIdentityAssigned();
                    yield assigned.itemId();
                }
                yield null;
            }
            default -> null;
        };
    }

    private boolean isClassifiedUnique(ItemStack itemStack) {
        ItemDescriptor descriptor = BukkitItems.describe(itemStack, "minecraft");
        ObservedItemDescriptor observed = ObservedItemDescriptor.builder()
                .providerId("minecraft")
                .material(descriptor.materialKey())
                .maxStackSize(itemStack.getMaxStackSize())
                .amount(itemStack.getAmount())
                .damageable(descriptor.maxDurability() > 0)
                .damageValue(descriptor.damage())
                .customNamePresent(descriptor.hasCustomName())
                .enchantmentsPresent(!descriptor.enchantments().isEmpty())
                .build();
        return ItemIdentityPolicy.classify(observed).isUnique();
    }

    private void submitObservation(PhysicalUniqueItemObservation observation) {
        metrics.recordWorldDropObservation();
        observationQueue.submit(observation, observationService);
    }

    private boolean isPhysicalTrackingEnabled(WorldEchoSettings settings) {
        return settings.automaticTrackingEnabled()
                && settings.physicalTrackingEnabled();
    }
}
