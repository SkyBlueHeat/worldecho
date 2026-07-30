package dev.worldecho.paper.listener;

import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.paper.inventory.PlayerInventoryReconciliationScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;

import java.util.function.Supplier;

/**
 * Listens for events that may change a player's inventory and schedules
 * final-state reconciliation on the next tick.
 *
 * <p>Does not read final inventory state inside pre-mutation events.
 * Only marks the player as pending and lets the scheduler coalesce.
 */
public final class PlayerInventoryObservationListener implements Listener {

    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final PlayerInventoryReconciliationScheduler scheduler;

    public PlayerInventoryObservationListener(
            Supplier<WorldEchoSettings> settingsSupplier,
            PlayerInventoryReconciliationScheduler scheduler
    ) {
        this.settingsSupplier = settingsSupplier;
        this.scheduler = scheduler;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        if (!settingsSupplier.get().automaticTrackingReconcileOnJoin()) {
            return;
        }
        scheduler.scheduleReconciliation(event.getPlayer().getUniqueId(), "join");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        if (!settingsSupplier.get().automaticTrackingReconcileOnRespawn()) {
            return;
        }
        scheduler.scheduleReconciliation(event.getPlayer().getUniqueId(), "respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        if (!settingsSupplier.get().automaticTrackingReconcileAfterInventoryEvents()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            scheduler.scheduleReconciliation(player.getUniqueId(), "inventory-click");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        if (!settingsSupplier.get().automaticTrackingReconcileAfterInventoryEvents()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            scheduler.scheduleReconciliation(player.getUniqueId(), "inventory-drag");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        if (!settingsSupplier.get().automaticTrackingReconcileAfterInventoryEvents()) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            scheduler.scheduleReconciliation(player.getUniqueId(), "pickup");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        scheduler.scheduleReconciliation(event.getEntity().getUniqueId(), "death");
    }

    private boolean isAutomaticTrackingEnabled() {
        WorldEchoSettings settings = settingsSupplier.get();
        return settings.automaticTrackingEnabled()
                && settings.automaticTrackingPlayerInventories();
    }
}
