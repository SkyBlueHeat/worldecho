package dev.worldecho.paper.listener;

import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.item.ItemDescriptor;
import dev.worldecho.domain.item.OwnershipSubject;
import dev.worldecho.domain.item.PhysicalObservationCycle;
import dev.worldecho.domain.item.PhysicalObservationReason;
import dev.worldecho.domain.item.PhysicalUniqueItemObservation;
import dev.worldecho.domain.item.PhysicalUniqueItemObservationService;
import dev.worldecho.domain.item.ReconciliationMetrics;
import dev.worldecho.domain.item.TrackedItemId;
import dev.worldecho.integration.bukkit.BukkitItems;
import dev.worldecho.paper.item.ItemIdentityAdapter;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Listens for non-player entity item pickup and entity death to observe
 * UNIQUE items as ENTITY subjects and handle death-drop transitions.
 *
 * <p>Player pickup is handled by the existing {@link PlayerInventoryObservationListener}
 * and the Sprint 0.3B reconciler. This listener only processes non-player
 * living entities.
 *
 * <p>On entity death, equipped UNIQUE items are checked. If a tracked UNIQUE
 * item was equipped but does not appear in the death drops, a terminal
 * SYSTEM observation is recorded. Items that do appear in drops will be
 * reconciled by {@link WorldDropObservationListener} via {@code ItemSpawnEvent}.
 */
public final class EntityItemOwnershipListener implements Listener {

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final ItemIdentityAdapter identityAdapter;
    private final PhysicalUniqueItemObservationService observationService;
    private final ReconciliationMetrics metrics;
    private final Executor asyncExecutor;
    private final AtomicLong observationSequenceCounter = new AtomicLong(0);
    private final String serverSessionId;

    public EntityItemOwnershipListener(
            Plugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            ItemIdentityAdapter identityAdapter,
            PhysicalUniqueItemObservationService observationService,
            ReconciliationMetrics metrics,
            Executor asyncExecutor,
            String serverSessionId
    ) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.identityAdapter = identityAdapter;
        this.observationService = observationService;
        this.metrics = metrics;
        this.asyncExecutor = asyncExecutor;
        this.serverSessionId = serverSessionId;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!isPhysicalTrackingEnabled(settings)) {
            return;
        }
        if (!settings.physicalTrackingEntityPickup()) {
            return;
        }

        if (event.getEntity() instanceof Player) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }

        Item itemEntity = event.getItem();
        ItemStack itemStack = itemEntity.getItemStack();
        if (itemStack == null || itemStack.getType().isAir()) {
            return;
        }

        ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(itemStack);
        if (identity.status() != ItemIdentityAdapter.IdentityStatus.EXISTING) {
            return;
        }

        TrackedItemId itemId = identity.itemId();

        ItemDescriptor descriptor = BukkitItems.describe(itemStack, "minecraft");
        ContentKey contentKey = ContentKey.parse(
                "minecraft:" + descriptor.materialKey().replace("minecraft:", ""));

        UUID entityUuid = livingEntity.getUniqueId();
        String entityDisplayName = "";

        UUID worldUuid = livingEntity.getWorld().getUID();
        String worldName = livingEntity.getWorld().getName();
        var location = livingEntity.getLocation();
        String contentFingerprint = descriptor.materialKey() + ":" + itemStack.getAmount();

        PhysicalObservationCycle cycle = PhysicalObservationCycle.create(
                observationSequenceCounter.incrementAndGet(), serverSessionId);

        PhysicalUniqueItemObservation observation = new PhysicalUniqueItemObservation(
                itemId,
                contentKey,
                descriptor.materialKey(),
                OwnershipSubject.entity(entityUuid, entityDisplayName),
                PhysicalObservationReason.ENTITY_HELD,
                itemEntity.getUniqueId(),
                worldUuid,
                worldName,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                contentFingerprint,
                cycle,
                Instant.now()
        );

        submitObservation(observation);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!isPhysicalTrackingEnabled(settings)) {
            return;
        }
        if (!settings.physicalTrackingEntityPickup()) {
            return;
        }

        if (event.getEntity() instanceof Player) {
            return;
        }

        LivingEntity entity = event.getEntity();
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }

        Set<TrackedItemId> equippedTrackedIds = new HashSet<>();
        ItemStack[] equippedItems = {
                equipment.getItemInMainHand(),
                equipment.getItemInOffHand(),
                equipment.getHelmet(),
                equipment.getChestplate(),
                equipment.getLeggings(),
                equipment.getBoots()
        };

        for (ItemStack itemStack : equippedItems) {
            if (itemStack == null || itemStack.getType().isAir()) {
                continue;
            }
            ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(itemStack);
            if (identity.status() == ItemIdentityAdapter.IdentityStatus.EXISTING) {
                equippedTrackedIds.add(identity.itemId());
            }
        }

        if (equippedTrackedIds.isEmpty()) {
            return;
        }

        Set<TrackedItemId> droppedTrackedIds = new HashSet<>();
        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(drop);
            if (identity.status() == ItemIdentityAdapter.IdentityStatus.EXISTING) {
                droppedTrackedIds.add(identity.itemId());
            }
        }

        UUID worldUuid = entity.getWorld().getUID();
        String worldName = entity.getWorld().getName();
        var location = entity.getLocation();
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        for (TrackedItemId itemId : equippedTrackedIds) {
            if (droppedTrackedIds.contains(itemId)) {
                continue;
            }

            String material = "unknown";
            ContentKey contentKey = ContentKey.parse("minecraft:unknown");
            String contentFingerprint = "unknown:1";

            PhysicalObservationCycle cycle = PhysicalObservationCycle.create(
                    observationSequenceCounter.incrementAndGet(), serverSessionId);

            PhysicalUniqueItemObservation observation = new PhysicalUniqueItemObservation(
                    itemId,
                    contentKey,
                    material,
                    OwnershipSubject.system("item-destroyed"),
                    PhysicalObservationReason.ENTITY_DEATH_DROP,
                    null,
                    worldUuid,
                    worldName,
                    blockX, blockY, blockZ,
                    contentFingerprint,
                    cycle,
                    Instant.now()
            );

            submitDeathObservation(observation);
        }
    }

    private void submitObservation(PhysicalUniqueItemObservation observation) {
        metrics.incrementPendingPhysical();
        metrics.recordEntityItemObservation();
        asyncExecutor.execute(() -> {
            try {
                observationService.process(observation);
            } finally {
                metrics.decrementPendingPhysical();
            }
        });
    }

    private void submitDeathObservation(PhysicalUniqueItemObservation observation) {
        metrics.incrementPendingPhysical();
        asyncExecutor.execute(() -> {
            try {
                observationService.process(observation);
            } finally {
                metrics.decrementPendingPhysical();
            }
        });
    }

    private boolean isPhysicalTrackingEnabled(WorldEchoSettings settings) {
        return settings.automaticTrackingEnabled()
                && settings.physicalTrackingEnabled();
    }
}
