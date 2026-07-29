package dev.worldecho.paper.listener;

import dev.worldecho.application.ItemValueScorer;
import dev.worldecho.application.MemoryRecorder;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.memory.MemoryEventType;
import dev.worldecho.domain.memory.StoryMemoryEvent;
import dev.worldecho.integration.IntegrationRegistry;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class PlayerDeathMemoryListener implements Listener {

    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final IntegrationRegistry integrations;
    private final ItemValueScorer itemValueScorer;
    private final MemoryRecorder memoryRecorder;

    public PlayerDeathMemoryListener(
            Supplier<WorldEchoSettings> settingsSupplier,
            IntegrationRegistry integrations,
            ItemValueScorer itemValueScorer,
            MemoryRecorder memoryRecorder
    ) {
        this.settingsSupplier = settingsSupplier;
        this.integrations = integrations;
        this.itemValueScorer = itemValueScorer;
        this.memoryRecorder = memoryRecorder;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        WorldEchoSettings settings = settingsSupplier.get();
        if (!settings.playerDeathCaptureEnabled()) {
            return;
        }

        String worldName = event.getPlayer().getWorld().getName();
        if (settings.ignoredWorlds().contains(worldName)) {
            return;
        }

        Entity killer = resolveKiller(event);
        if (!(killer instanceof LivingEntity)) {
            return;
        }

        Optional<IdentifiedContent> identifiedKiller =
                integrations.identifyEntity(killer);
        if (identifiedKiller.isEmpty()) {
            return;
        }

        Optional<ItemCandidate> candidate = event.getDrops().stream()
                .map(item -> new ItemCandidate(item.clone(), itemValueScorer.score(item)))
                .filter(item -> item.score() >= settings.minimumItemScore())
                .max(Comparator.comparingInt(ItemCandidate::score));

        if (candidate.isEmpty() && !settings.recordDeathWithoutValuableItem()) {
            return;
        }

        IdentifiedContent killerContent = identifiedKiller.get();
        Location location = event.getPlayer().getLocation();

        ItemSnapshot snapshot = candidate
                .flatMap(item -> integrations.identifyItem(item.item())
                        .map(content -> ItemSnapshot.from(
                                content,
                                item.item(),
                                item.score(),
                                settings.redactCustomItemNames()
                        )))
                .orElse(ItemSnapshot.empty());

        StoryMemoryEvent memory = new StoryMemoryEvent(
                UUID.randomUUID(),
                MemoryEventType.PLAYER_KILLED_BY_ENTITY,
                Instant.now(),
                event.getPlayer().getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                event.getPlayer().getUniqueId(),
                killerContent.key(),
                killer.getUniqueId().toString(),
                snapshot.key(),
                snapshot.serialized(),
                "killer=" + killerContent.key() + ";itemScore=" + snapshot.score()
        );

        memoryRecorder.record(memory);
    }

    private Entity resolveKiller(PlayerDeathEvent event) {
        if (!(event.getPlayer().getLastDamageCause()
                instanceof EntityDamageByEntityEvent damageEvent)) {
            return null;
        }

        Entity damager = damageEvent.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? entity : null;
        }

        return damager;
    }

    private record ItemCandidate(ItemStack item, int score) {
    }
}
