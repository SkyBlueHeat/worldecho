package dev.worldecho.paper.command;

import dev.worldecho.application.ItemValueScorer;
import dev.worldecho.config.MessageService;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.memory.StoryMemoryEvent;
import dev.worldecho.integration.IntegrationRegistry;
import dev.worldecho.persistence.StoryEventRepository;
import dev.worldecho.persistence.StoryWriteQueue;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class WorldEchoCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final Supplier<MessageService> messageSupplier;
    private final IntegrationRegistry integrations;
    private final StoryEventRepository repository;
    private final StoryWriteQueue writeQueue;
    private final ItemValueScorer itemValueScorer;
    private final Runnable reloadAction;

    public WorldEchoCommand(
            JavaPlugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            Supplier<MessageService> messageSupplier,
            IntegrationRegistry integrations,
            StoryEventRepository repository,
            StoryWriteQueue writeQueue,
            ItemValueScorer itemValueScorer,
            Runnable reloadAction
    ) {
        this.plugin = plugin;
        this.settingsSupplier = settingsSupplier;
        this.messageSupplier = messageSupplier;
        this.integrations = integrations;
        this.repository = repository;
        this.writeQueue = writeQueue;
        this.itemValueScorer = itemValueScorer;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("worldecho.admin")) {
            sender.sendMessage(messageSupplier.get().component("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(messageSupplier.get().component("usage"));
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "recent" -> recent(sender, args);
            case "inspect" -> inspect(sender, args);
            case "reload" -> reload(sender);
            default -> {
                sender.sendMessage(
                        messageSupplier.get().component("unknown-subcommand")
                );
                yield true;
            }
        };
    }

    private boolean status(CommandSender sender) {
        MessageService messages = messageSupplier.get();
        sender.sendMessage(messages.component("status-header"));
        statusLine(sender, "version", plugin.getPluginMeta().getVersion());
        statusLine(sender, "database", "initialized");
        StoryWriteQueue.QueueStatus queue = writeQueue.status();
        statusLine(sender, "queue.pending", Long.toString(queue.pending()));
        statusLine(sender, "queue.failed", Long.toString(queue.failed()));
        statusLine(sender, "providers", String.join(", ", integrations.providerStatus()));

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return repository.count();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                })
                .whenComplete((count, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable == null) {
                                statusLine(sender, "events", Long.toString(count));
                            } else {
                                statusLine(sender, "events", "error");
                                plugin.getLogger().warning(
                                        "Could not count story events: "
                                                + throwable.getMessage()
                                );
                            }
                        })
                );

        return true;
    }

    private void statusLine(CommandSender sender, String key, String value) {
        sender.sendMessage(messageSupplier.get().component(
                "status-line",
                Map.of("key", key, "value", value)
        ));
    }

    private boolean recent(CommandSender sender, String[] args) {
        WorldEchoSettings settings = settingsSupplier.get();
        int requested = settings.recentDefaultCount();

        if (args.length >= 2) {
            try {
                requested = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                requested = settings.recentDefaultCount();
            }
        }

        int count = Math.max(1, Math.min(requested, settings.recentMaximumCount()));
        sender.sendMessage(messageSupplier.get().component("recent-loading"));

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return repository.findRecent(count);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                })
                .whenComplete((events, throwable) ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                showRecent(sender, events, throwable))
                );

        return true;
    }

    private void showRecent(
            CommandSender sender,
            List<StoryMemoryEvent> events,
            Throwable throwable
    ) {
        MessageService messages = messageSupplier.get();
        if (throwable != null) {
            plugin.getLogger().warning(
                    "Could not load recent memories: " + throwable.getMessage()
            );
            sender.sendMessage(messages.component("recent-failed"));
            return;
        }

        sender.sendMessage(messages.component("recent-header"));
        if (events.isEmpty()) {
            sender.sendMessage(messages.component("recent-empty"));
            return;
        }

        for (StoryMemoryEvent event : events) {
            sender.sendMessage(messages.component(
                    "recent-line",
                    Map.of(
                            "time", DATE_FORMATTER.format(event.occurredAt()),
                            "type", event.type().name(),
                            "id", event.id().toString().substring(0, 8)
                    )
            ));
        }
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageSupplier.get().component("usage"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(messageSupplier.get().component("usage"));
            return true;
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "item" -> inspectItem(player);
            case "entity" -> inspectEntity(player);
            default -> {
                sender.sendMessage(messageSupplier.get().component("usage"));
                yield true;
            }
        };
    }

    private boolean inspectItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(messageSupplier.get().component("inspect-no-item"));
            return true;
        }

        Optional<IdentifiedContent> identified = integrations.identifyItem(item);
        if (identified.isEmpty()) {
            player.sendMessage(messageSupplier.get().component("inspect-no-item"));
            return true;
        }

        showContent(player, identified.get());
        statusLine(player, "score", Integer.toString(itemValueScorer.score(item)));
        return true;
    }

    private boolean inspectEntity(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                12.0,
                entity -> !entity.getUniqueId().equals(player.getUniqueId())
        );

        Entity entity = result == null ? null : result.getHitEntity();
        if (entity == null) {
            player.sendMessage(messageSupplier.get().component("inspect-no-entity"));
            return true;
        }

        Optional<IdentifiedContent> identified = integrations.identifyEntity(entity);
        if (identified.isEmpty()) {
            player.sendMessage(messageSupplier.get().component("inspect-no-entity"));
            return true;
        }

        showContent(player, identified.get());
        return true;
    }

    private void showContent(CommandSender sender, IdentifiedContent content) {
        sender.sendMessage(messageSupplier.get().component("inspect-header"));
        statusLine(sender, "key", content.key().toString());
        statusLine(sender, "display", content.displayName());
        statusLine(sender, "roles", content.roles().toString());
        statusLine(sender, "capabilities", content.capabilities().toString());
    }

    private boolean reload(CommandSender sender) {
        try {
            reloadAction.run();
            sender.sendMessage(messageSupplier.get().component("reload-success"));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "WorldEcho config reload failed: " + exception.getMessage()
            );
            sender.sendMessage(messageSupplier.get().component("reload-failed"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            return List.of("status", "recent", "inspect", "reload").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            return List.of("item", "entity").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
