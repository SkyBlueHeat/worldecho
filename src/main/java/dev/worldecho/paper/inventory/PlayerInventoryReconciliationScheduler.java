package dev.worldecho.paper.inventory;

import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.item.ObservedInventorySnapshot;
import dev.worldecho.domain.item.ReconciliationMetrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Coalesces multiple inventory events for the same player into one next-tick reconciliation.
 *
 * <p>One pending reconciliation per player.  No every-tick scanner.  No unbounded task creation.
 */
public final class PlayerInventoryReconciliationScheduler {

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final PlayerInventoryReconciler reconciler;
    private final Executor asyncExecutor;
    private final ReconciliationMetrics metrics;
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean shutdown = false;

    public PlayerInventoryReconciliationScheduler(
            Plugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            PlayerInventoryReconciler reconciler,
            Executor asyncExecutor,
            ReconciliationMetrics metrics
    ) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.reconciler = reconciler;
        this.asyncExecutor = asyncExecutor;
        this.metrics = metrics;
    }

    /**
     * Marks a player as pending reconciliation and schedules a next-tick task.
     * Multiple calls for the same player in the same tick coalesce into one.
     */
    public void scheduleReconciliation(UUID playerUuid, String triggerReason) {
        if (shutdown) {
            return;
        }
        WorldEchoSettings settings = settingsSupplier.get();
        if (!settings.automaticTrackingEnabled() || !settings.automaticTrackingPlayerInventories()) {
            return;
        }
        if (!pendingPlayers.add(playerUuid)) {
            return;
        }
        metrics.incrementPending();

        BukkitTask task = Bukkit.getScheduler().runTask(plugin, () -> {
            pendingPlayers.remove(playerUuid);
            metrics.decrementPending();

            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            ObservedInventorySnapshot snapshot = reconciler.captureSnapshot(
                    player, triggerReason, Bukkit.getCurrentTick());
            asyncExecutor.execute(() -> reconciler.processSnapshot(snapshot));
        });
    }

    /**
     * Schedules reconciliation for all currently online players.
     * Used on plugin enable.
     */
    public void scheduleForAllOnline(String triggerReason) {
        if (shutdown) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleReconciliation(player.getUniqueId(), triggerReason);
        }
    }

    public void shutdown() {
        shutdown = true;
        pendingPlayers.clear();
    }

    public int pendingCount() {
        return pendingPlayers.size();
    }

    public boolean isPending(UUID playerUuid) {
        return pendingPlayers.contains(playerUuid);
    }
}
