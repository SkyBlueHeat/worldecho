package dev.worldecho.paper.command;

import dev.worldecho.application.ItemValueScorer;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.item.ItemDescriptor;
import dev.worldecho.domain.item.ItemScore;
import dev.worldecho.domain.memory.StoryMemoryEvent;
import dev.worldecho.integration.IntegrationRegistry;
import dev.worldecho.integration.bukkit.BukkitItems;
import dev.worldecho.paper.message.PaperMessageService;
import dev.worldecho.persistence.DatabaseManager;
import dev.worldecho.persistence.StoryEventRepository;
import dev.worldecho.persistence.StoryWriteQueue;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Administration command. Database access always runs on the WorldEcho query executor and
 * results are sent back on the server thread.
 */
public final class WorldEchoCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "worldecho.admin";

    private static final double ENTITY_TRACE_DISTANCE = 12.0d;
    private static final List<String> SUBCOMMANDS =
            List.of("status", "recent", "inspect", "reload");
    private static final List<String> INSPECT_TARGETS = List.of("item", "entity");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final Supplier<PaperMessageService> messageSupplier;
    private final Supplier<ItemValueScorer> scorerSupplier;
    private final IntegrationRegistry integrations;
    private final DatabaseManager databaseManager;
    private final StoryEventRepository repository;
    private final StoryWriteQueue writeQueue;
    private final Executor queryExecutor;
    private final Supplier<List<String>> reloadAction;

    public WorldEchoCommand(
            Plugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            Supplier<PaperMessageService> messageSupplier,
            Supplier<ItemValueScorer> scorerSupplier,
            IntegrationRegistry integrations,
            DatabaseManager databaseManager,
            StoryEventRepository repository,
            StoryWriteQueue writeQueue,
            Executor queryExecutor,
            Supplier<List<String>> reloadAction
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.messageSupplier = Objects.requireNonNull(messageSupplier, "messageSupplier");
        this.scorerSupplier = Objects.requireNonNull(scorerSupplier, "scorerSupplier");
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
        this.queryExecutor = Objects.requireNonNull(queryExecutor, "queryExecutor");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            messages().send(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            messages().send(sender, "usage");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "recent" -> recent(sender, args);
            case "inspect" -> inspect(sender, args);
            case "reload" -> reload(sender);
            default -> messages().send(sender, "unknown-subcommand");
        }

        return true;
    }

    private void status(CommandSender sender) {
        WorldEchoSettings settings = settingsSupplier.get();
        StoryWriteQueue.QueueStatus queue = writeQueue.status();

        messages().send(sender, "status-header");
        line(sender, "version", plugin.getPluginMeta().getVersion());
        line(sender, "locale", settings.locale());
        line(sender, "queue.pending", Integer.toString(queue.pending()));
        line(sender, "queue.written", Long.toString(queue.written()));
        line(sender, "queue.failed", Long.toString(queue.failed()));
        line(sender, "queue.dropped", Long.toString(queue.dropped()));
        line(sender, "providers", String.join(", ", integrations.describeProviders()));

        query(
                sender,
                () -> new DatabaseStatus(
                        databaseManager.healthy(),
                        databaseManager.schemaVersion(),
                        repository.count()
                ),
                status -> {
                    line(sender, "database", status.healthy() ? "ok" : "unavailable");
                    line(sender, "schema.version", Integer.toString(status.schemaVersion()));
                    line(sender, "events", Long.toString(status.events()));
                },
                "status-failed"
        );
    }

    private void recent(CommandSender sender, String[] args) {
        WorldEchoSettings settings = settingsSupplier.get();
        int requested = settings.recentDefaultCount();

        if (args.length >= 2) {
            try {
                requested = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                messages().send(sender, "recent-invalid-count",
                        Map.of("value", args[1], "default", Integer.toString(requested)));
            }
        }

        int count = Math.max(1, Math.min(requested, settings.recentMaximumCount()));
        messages().send(sender, "recent-loading", Map.of("count", Integer.toString(count)));

        query(
                sender,
                () -> repository.findRecent(count),
                events -> showRecent(sender, events),
                "recent-failed"
        );
    }

    private void showRecent(CommandSender sender, List<StoryMemoryEvent> events) {
        messages().send(sender, "recent-header");
        if (events.isEmpty()) {
            messages().send(sender, "recent-empty");
            return;
        }

        for (StoryMemoryEvent event : events) {
            messages().send(sender, "recent-line", Map.of(
                    "time", TIME_FORMATTER.format(event.occurredAt()),
                    "type", event.type().name(),
                    "actor", event.actor().toString(),
                    "item", event.optionalItem().map(Object::toString).orElse("-"),
                    "id", event.id().toString().substring(0, 8)
            ));
        }
    }

    private void inspect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return;
        }

        if (args.length < 2) {
            messages().send(sender, "inspect-usage");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "item" -> inspectItem(player);
            case "entity" -> inspectEntity(player);
            default -> messages().send(sender, "inspect-usage");
        }
    }

    private void inspectItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            messages().send(player, "inspect-no-item");
            return;
        }

        Optional<IdentifiedContent> identified = integrations.identifyItem(item);
        if (identified.isEmpty()) {
            messages().send(player, "inspect-no-item");
            return;
        }

        ItemDescriptor descriptor =
                BukkitItems.describe(item, identified.get().key().providerId());
        ItemScore score = scorerSupplier.get().score(descriptor);

        showContent(player, identified.get());
        line(player, "material", descriptor.materialKey());
        line(player, "score", Integer.toString(score.value()));
        line(player, "score.factors", score.explain());
        line(player, "minimum-score",
                Integer.toString(settingsSupplier.get().minimumItemScore()));
    }

    private void inspectEntity(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                ENTITY_TRACE_DISTANCE,
                entity -> !entity.getUniqueId().equals(player.getUniqueId())
        );

        Entity entity = result == null ? null : result.getHitEntity();
        if (entity == null) {
            messages().send(player, "inspect-no-entity");
            return;
        }

        Optional<IdentifiedContent> identified = integrations.identifyEntity(entity);
        if (identified.isEmpty()) {
            messages().send(player, "inspect-no-entity");
            return;
        }

        showContent(player, identified.get());
        line(player, "runtime-id", entity.getUniqueId().toString());
    }

    private void showContent(CommandSender sender, IdentifiedContent content) {
        messages().send(sender, "inspect-header");
        line(sender, "key", content.key().toString());
        line(sender, "display", content.displayName());
        line(sender, "roles", describe(content.roles()));
        line(sender, "capabilities", describe(content.capabilities()));
    }

    private void reload(CommandSender sender) {
        try {
            List<String> warnings = reloadAction.get();
            messages().send(sender, "reload-success");
            for (String warning : warnings) {
                messages().send(sender, "reload-warning", Map.of("warning", warning));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "WorldEcho configuration reload failed", exception);
            messages().send(sender, "reload-failed");
        }
    }

    /**
     * Runs a blocking database call on the query executor and delivers the result on the
     * server thread.
     */
    private <T> void query(
            CommandSender sender,
            ThrowingSupplier<T> supplier,
            Consumer<T> consumer,
            String failureKey
    ) {
        queryExecutor.execute(() -> {
            T value = null;
            Exception failure = null;
            try {
                value = supplier.get();
            } catch (Exception exception) {
                failure = exception;
            }

            T result = value;
            Exception thrown = failure;
            runOnServerThread(() -> {
                if (thrown != null) {
                    plugin.getLogger().log(Level.WARNING, "WorldEcho query failed", thrown);
                    messages().send(sender, failureKey);
                    return;
                }
                consumer.accept(result);
            });
        });
    }

    private void runOnServerThread(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    private void line(CommandSender sender, String key, String value) {
        messages().send(sender, "status-line", Map.of("key", key, "value", value));
    }

    private PaperMessageService messages() {
        return messageSupplier.get();
    }

    private static String describe(Collection<? extends Enum<?>> values) {
        if (values.isEmpty()) {
            return "-";
        }
        return values.stream().map(Enum::name).sorted().reduce((a, b) -> a + ", " + b).orElse("-");
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            return filter(INSPECT_TARGETS, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("recent")) {
            WorldEchoSettings settings = settingsSupplier.get();
            return filter(
                    List.of(
                            Integer.toString(settings.recentDefaultCount()),
                            Integer.toString(settings.recentMaximumCount())
                    ),
                    args[1]
            );
        }

        return List.of();
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.startsWith(normalized)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record DatabaseStatus(boolean healthy, int schemaVersion, long events) {
    }
}
