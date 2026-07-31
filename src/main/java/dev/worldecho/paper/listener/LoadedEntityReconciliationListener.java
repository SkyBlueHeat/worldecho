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
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reconciles loaded Item entities and entity equipment after chunk load
 * or server restart.
 *
 * <p>Captures all Bukkit state on the main thread into immutable observation
 * records, then processes them asynchronously.  Does not load unloaded chunks
 * or scan the entire world — only processes entities loaded by the server.
 */
public final class LoadedEntityReconciliationListener implements Listener {

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final ItemIdentityAdapter identityAdapter;
    private final PhysicalUniqueItemObservationService observationService;
    private final ReconciliationMetrics metrics;
    private final Executor asyncExecutor;
    private final AtomicLong observationSequenceCounter = new AtomicLong(0);
    private final String serverSessionId;

    public LoadedEntityReconciliationListener(
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!isPhysicalTrackingEnabled(settings)) {
            return;
        }
        if (!settings.physicalTrackingReconcileLoadedEntities()) {
            return;
        }

        List<PhysicalUniqueItemObservation> observations = new ArrayList<>();

        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof Item itemEntity) {
                PhysicalUniqueItemObservation obs = observeItemEntity(itemEntity);
                if (obs != null) {
                    observations.add(obs);
                }
            } else if (entity instanceof LivingEntity livingEntity
                    && !(entity instanceof Player)) {
                observations.addAll(observeEntityEquipment(livingEntity));
            }
        }

        if (observations.isEmpty()) {
            return;
        }

        metrics.recordLoadedEntityReconciliation();
        for (PhysicalUniqueItemObservation observation : observations) {
            submitObservation(observation);
        }
    }

    private PhysicalUniqueItemObservation observeItemEntity(Item itemEntity) {
        ItemStack itemStack = itemEntity.getItemStack();
        if (itemStack == null || itemStack.getType().isAir()) {
            return null;
        }

        ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(itemStack);
        if (identity.status() != ItemIdentityAdapter.IdentityStatus.EXISTING) {
            return null;
        }

        TrackedItemId itemId = identity.itemId();
        ItemDescriptor descriptor = BukkitItems.describe(itemStack, "minecraft");
        ContentKey contentKey = ContentKey.parse(
                "minecraft:" + descriptor.materialKey().replace("minecraft:", ""));

        UUID itemEntityUuid = itemEntity.getUniqueId();
        UUID worldUuid = itemEntity.getWorld().getUID();
        String worldName = itemEntity.getWorld().getName();
        var location = itemEntity.getLocation();
        String contentFingerprint = descriptor.materialKey() + ":" + itemStack.getAmount();

        PhysicalObservationCycle cycle = PhysicalObservationCycle.create(
                observationSequenceCounter.incrementAndGet(), serverSessionId);

        return new PhysicalUniqueItemObservation(
                itemId,
                contentKey,
                descriptor.materialKey(),
                OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.LOADED_ITEM,
                itemEntityUuid,
                worldUuid,
                worldName,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                contentFingerprint,
                cycle,
                Instant.now()
        );
    }

    private List<PhysicalUniqueItemObservation> observeEntityEquipment(LivingEntity entity) {
        List<PhysicalUniqueItemObservation> observations = new ArrayList<>();
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return observations;
        }

        UUID entityUuid = entity.getUniqueId();
        String entityDisplayName = "";
        UUID worldUuid = entity.getWorld().getUID();
        String worldName = entity.getWorld().getName();
        var location = entity.getLocation();
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        ItemStack[] items = {
                equipment.getItemInMainHand(),
                equipment.getItemInOffHand(),
                equipment.getHelmet(),
                equipment.getChestplate(),
                equipment.getLeggings(),
                equipment.getBoots()
        };

        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType().isAir()) {
                continue;
            }

            ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(itemStack);
            if (identity.status() != ItemIdentityAdapter.IdentityStatus.EXISTING) {
                continue;
            }

            TrackedItemId itemId = identity.itemId();
            ItemDescriptor descriptor = BukkitItems.describe(itemStack, "minecraft");
            ContentKey contentKey = ContentKey.parse(
                    "minecraft:" + descriptor.materialKey().replace("minecraft:", ""));
            String contentFingerprint = descriptor.materialKey() + ":" + itemStack.getAmount();

            PhysicalObservationCycle cycle = PhysicalObservationCycle.create(
                    observationSequenceCounter.incrementAndGet(), serverSessionId);

            observations.add(new PhysicalUniqueItemObservation(
                    itemId,
                    contentKey,
                    descriptor.materialKey(),
                    OwnershipSubject.entity(entityUuid, entityDisplayName),
                    PhysicalObservationReason.LOADED_ENTITY_EQUIPMENT,
                    null,
                    worldUuid,
                    worldName,
                    blockX, blockY, blockZ,
                    contentFingerprint,
                    cycle,
                    Instant.now()
            ));
        }

        return observations;
    }

    private void submitObservation(PhysicalUniqueItemObservation observation) {
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
