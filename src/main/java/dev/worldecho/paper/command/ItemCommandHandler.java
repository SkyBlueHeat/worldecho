package dev.worldecho.paper.command;

import dev.worldecho.application.BindingEnricher;
import dev.worldecho.application.ItemValueScorer;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.item.ItemDescriptor;
import dev.worldecho.domain.item.ItemScore;
import dev.worldecho.domain.item.OwnershipLedgerEntry;
import dev.worldecho.domain.item.OwnershipResult;
import dev.worldecho.domain.item.OwnershipResultStatus;
import dev.worldecho.domain.item.OwnershipState;
import dev.worldecho.domain.item.OwnershipSubject;
import dev.worldecho.domain.item.OwnershipSubjectType;
import dev.worldecho.domain.item.OwnershipTransitionReason;
import dev.worldecho.domain.item.OwnershipTransitionService;
import dev.worldecho.domain.item.TrackedItemId;
import dev.worldecho.domain.item.TrackedItemRecord;
import dev.worldecho.integration.IntegrationRegistry;
import dev.worldecho.integration.bukkit.BukkitItems;
import dev.worldecho.paper.item.ItemIdentityAdapter;
import dev.worldecho.paper.message.PaperMessageService;
import dev.worldecho.persistence.OwnershipLedgerRepository;
import dev.worldecho.persistence.TrackedItemRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Handles the {@code /worldecho item ...} subcommands.
 */
public final class ItemCommandHandler {

    public static final String PERM_INSPECT = "worldecho.item.inspect";
    public static final String PERM_TRACK = "worldecho.item.track";
    public static final String PERM_HISTORY = "worldecho.item.history";
    public static final String PERM_ASSIGN = "worldecho.item.assign";

    static final List<String> ITEM_SUBCOMMANDS =
            List.of("track", "inspect", "owner", "history", "assign-owner");
    static final List<String> ASSIGN_SUBJECT_TYPES =
            List.of("player", "entity", "system");

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final Supplier<PaperMessageService> messageSupplier;
    private final Supplier<ItemValueScorer> scorerSupplier;
    private final Supplier<BindingEnricher> enricherSupplier;
    private final IntegrationRegistry integrations;
    private final ItemIdentityAdapter identityAdapter;
    private final TrackedItemRepository trackedItemRepository;
    private final OwnershipLedgerRepository ledgerRepository;
    private final OwnershipTransitionService transitionService;
    private final Executor queryExecutor;

    public ItemCommandHandler(
            Plugin plugin,
            Supplier<WorldEchoSettings> settingsSupplier,
            Supplier<PaperMessageService> messageSupplier,
            Supplier<ItemValueScorer> scorerSupplier,
            Supplier<BindingEnricher> enricherSupplier,
            IntegrationRegistry integrations,
            ItemIdentityAdapter identityAdapter,
            TrackedItemRepository trackedItemRepository,
            OwnershipLedgerRepository ledgerRepository,
            Executor queryExecutor
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.messageSupplier = Objects.requireNonNull(messageSupplier, "messageSupplier");
        this.scorerSupplier = Objects.requireNonNull(scorerSupplier, "scorerSupplier");
        this.enricherSupplier = Objects.requireNonNull(enricherSupplier, "enricherSupplier");
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.identityAdapter = Objects.requireNonNull(identityAdapter, "identityAdapter");
        this.trackedItemRepository = Objects.requireNonNull(trackedItemRepository, "trackedItemRepository");
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository");
        this.queryExecutor = Objects.requireNonNull(queryExecutor, "queryExecutor");
        this.transitionService = new OwnershipTransitionService(
                trackedItemRepository, ledgerRepository, Clock.systemUTC());
    }

    public void handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages().send(sender, "item-usage");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "track" -> track(sender);
            case "inspect" -> inspect(sender);
            case "owner" -> owner(sender, args);
            case "history" -> history(sender, args);
            case "assign-owner" -> assignOwner(sender, args);
            default -> messages().send(sender, "item-usage");
        }
    }

    private void track(CommandSender sender) {
        if (!sender.hasPermission(PERM_TRACK)) {
            messages().send(sender, "no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "item-player-held-required");
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            messages().send(player, "item-missing");
            return;
        }

        ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(item);
        switch (identity.status()) {
            case MALFORMED -> {
                messages().send(player, "item-malformed-id");
                return;
            }
            case UNSUPPORTED_ITEM -> {
                messages().send(player, "item-unsupported");
                return;
            }
            case EXISTING -> {
                TrackedItemId existingId = identity.itemId();
                queryExecutor.execute(() -> {
                    boolean exists;
                    try {
                        exists = trackedItemRepository.exists(existingId);
                    } catch (Exception exception) {
                        plugin.getLogger().log(Level.WARNING, "Track check failed", exception);
                        runOnServerThread(() -> messages().send(player, "item-database-failed"));
                        return;
                    }
                    if (exists) {
                        runOnServerThread(() -> messages().send(player, "item-already-tracked",
                                Map.of("item-id", existingId.toString())));
                    } else {
                        reconcileTracking(player, item, existingId);
                    }
                });
                return;
            }
            case MISSING -> {
                TrackedItemId newId = TrackedItemId.random();
                ItemIdentityAdapter.IdentityResult assignResult = identityAdapter.assignNewIdentity(item);
                if (assignResult.status() != ItemIdentityAdapter.IdentityStatus.ASSIGNED) {
                    messages().send(player, "item-unsupported");
                    return;
                }
                TrackedItemId assignedId = assignResult.itemId();
                createTrackedItemRecord(player, item, assignedId, OwnershipTransitionReason.TRACKED);
            }
        }
    }

    private void reconcileTracking(Player player, ItemStack item, TrackedItemId existingId) {
        Optional<IdentifiedContent> identified = integrations.identifyItem(item);
        if (identified.isEmpty()) {
            runOnServerThread(() -> messages().send(player, "item-unsupported"));
            return;
        }
        IdentifiedContent content = identified.get();
        ItemDescriptor descriptor = BukkitItems.describe(item, content.key().providerId());
        ItemScore score = scorerSupplier.get().score(descriptor);
        long now = System.currentTimeMillis();
        TrackedItemRecord record = new TrackedItemRecord(
                existingId,
                java.time.Instant.ofEpochMilli(now),
                java.time.Instant.ofEpochMilli(now),
                java.time.Instant.ofEpochMilli(now),
                content.key(),
                content.key().providerId(),
                descriptor.materialKey(),
                settingsSupplier.get().redactCustomItemNames() ? "" : descriptor.customName(),
                score.value(),
                "reconcile",
                OwnershipSubject.player(player.getUniqueId()).describe()
        );
        try {
            trackedItemRepository.create(record);
            OwnershipResult result = transitionService.transition(
                    existingId,
                    OwnershipSubject.player(player.getUniqueId()),
                    OwnershipTransitionReason.TRACKED,
                    "track-" + existingId,
                    "reconcile",
                    ""
            );
            runOnServerThread(() -> {
                if (result.status() == OwnershipResultStatus.RECORDED
                        || result.status() == OwnershipResultStatus.IDEMPOTENT_REPLAY
                        || result.status() == OwnershipResultStatus.NO_CHANGE) {
                    messages().send(player, "item-reconciliation-success",
                            Map.of("item-id", existingId.toString()));
                } else {
                    messages().send(player, "item-track-failed");
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Reconciliation failed", exception);
            runOnServerThread(() -> messages().send(player, "item-track-failed"));
        }
    }

    private void createTrackedItemRecord(Player player, ItemStack item, TrackedItemId itemId,
                                         OwnershipTransitionReason reason) {
        Optional<IdentifiedContent> identified = integrations.identifyItem(item);
        if (identified.isEmpty()) {
            runOnServerThread(() -> messages().send(player, "item-unsupported"));
            return;
        }
        IdentifiedContent content = identified.get();
        ItemDescriptor descriptor = BukkitItems.describe(item, content.key().providerId());
        ItemScore score = scorerSupplier.get().score(descriptor);
        long now = System.currentTimeMillis();
        TrackedItemRecord record = new TrackedItemRecord(
                itemId,
                java.time.Instant.ofEpochMilli(now),
                java.time.Instant.ofEpochMilli(now),
                java.time.Instant.ofEpochMilli(now),
                content.key(),
                content.key().providerId(),
                descriptor.materialKey(),
                settingsSupplier.get().redactCustomItemNames() ? "" : descriptor.customName(),
                score.value(),
                reason.token(),
                OwnershipSubject.player(player.getUniqueId()).describe()
        );
        queryExecutor.execute(() -> {
            try {
                trackedItemRepository.create(record);
                OwnershipResult result = transitionService.transition(
                        itemId,
                        OwnershipSubject.player(player.getUniqueId()),
                        reason,
                        "track-" + itemId,
                        "admin",
                        ""
                );
                runOnServerThread(() -> {
                    if (result.status() == OwnershipResultStatus.RECORDED
                            || result.status() == OwnershipResultStatus.IDEMPOTENT_REPLAY) {
                        messages().send(player, "item-track-success",
                                Map.of("item-id", itemId.toString()));
                    } else if (result.status() == OwnershipResultStatus.NO_CHANGE) {
                        messages().send(player, "item-already-tracked",
                                Map.of("item-id", itemId.toString()));
                    } else {
                        messages().send(player, "item-track-failed");
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Track failed", exception);
                runOnServerThread(() -> messages().send(player, "item-track-failed"));
            }
        });
    }

    private void inspect(CommandSender sender) {
        if (!sender.hasPermission(PERM_INSPECT)) {
            messages().send(sender, "no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "item-player-held-required");
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            messages().send(player, "item-missing");
            return;
        }

        ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(item);
        switch (identity.status()) {
            case UNSUPPORTED_ITEM -> messages().send(player, "item-unsupported");
            case MALFORMED -> messages().send(player, "item-malformed-id");
            case MISSING -> {
                messages().send(player, "inspect-header");
                line(player, "tracked", "no");
                line(player, "item-id", "untracked");
            }
            case EXISTING -> {
                TrackedItemId itemId = identity.itemId();
                messages().send(player, "inspect-header");
                line(player, "tracked", "yes");
                line(player, "item-id", itemId.toString());
                queryExecutor.execute(() -> {
                    try {
                        Optional<TrackedItemRecord> record = trackedItemRepository.findById(itemId);
                        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
                        long historyCount = ledgerRepository.countHistory(itemId);
                        runOnServerThread(() -> {
                            if (record.isEmpty()) {
                                messages().send(player, "item-persistence-missing",
                                        Map.of("item-id", itemId.toString()));
                            } else {
                                TrackedItemRecord r = record.get();
                                line(player, "content-key", r.contentKey().toString());
                                line(player, "material", r.initialMaterial());
                            }
                            if (state.isPresent()) {
                                OwnershipState s = state.get();
                                line(player, "current-owner", s.optionalCurrentSubject()
                                        .map(OwnershipSubject::describe).orElse("-"));
                                line(player, "ownership-sequence", Integer.toString(s.latestSequence()));
                            } else {
                                line(player, "current-owner", "-");
                                line(player, "ownership-sequence", "0");
                            }
                            line(player, "history-count", Long.toString(historyCount));
                        });
                    } catch (Exception exception) {
                        plugin.getLogger().log(Level.WARNING, "Item inspect failed", exception);
                        runOnServerThread(() -> messages().send(player, "item-database-failed"));
                    }
                });
            }
        }
    }

    private void owner(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_INSPECT)) {
            messages().send(sender, "no-permission");
            return;
        }

        TrackedItemId itemId;
        if (sender instanceof Player player && args.length < 3) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.getType().isAir()) {
                messages().send(player, "item-missing");
                return;
            }
            ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(item);
            if (identity.status() != ItemIdentityAdapter.IdentityStatus.EXISTING) {
                messages().send(player, "item-not-tracked");
                return;
            }
            itemId = identity.itemId();
        } else if (args.length >= 3) {
            Optional<TrackedItemId> parsed = TrackedItemId.tryParse(args[2]);
            if (parsed.isEmpty()) {
                messages().send(sender, "item-invalid-id", Map.of("value", sanitize(args[2])));
                return;
            }
            itemId = parsed.get();
        } else {
            messages().send(sender, "item-console-requires-id");
            return;
        }

        queryExecutor.execute(() -> {
            try {
                Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
                runOnServerThread(() -> {
                    messages().send(sender, "item-owner-header",
                            Map.of("item-id", itemId.toString()));
                    if (state.isPresent()) {
                        OwnershipState s = state.get();
                        line(sender, "current-owner", s.optionalCurrentSubject()
                                .map(OwnershipSubject::describe).orElse("-"));
                        line(sender, "ownership-sequence", Integer.toString(s.latestSequence()));
                        line(sender, "history-count", Long.toString(s.historyCount()));
                    } else {
                        messages().send(sender, "item-history-empty");
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Owner query failed", exception);
                runOnServerThread(() -> messages().send(sender, "item-database-failed"));
            }
        });
    }

    private void history(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_HISTORY)) {
            messages().send(sender, "no-permission");
            return;
        }

        WorldEchoSettings settings = settingsSupplier.get();
        int limit = settings.itemHistoryDefaultLimit();
        TrackedItemId itemId;

        if (sender instanceof Player player && args.length < 3) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.getType().isAir()) {
                messages().send(player, "item-missing");
                return;
            }
            ItemIdentityAdapter.IdentityResult identity = identityAdapter.readIdentity(item);
            if (identity.status() != ItemIdentityAdapter.IdentityStatus.EXISTING) {
                messages().send(player, "item-not-tracked");
                return;
            }
            itemId = identity.itemId();
        } else if (args.length >= 3) {
            Optional<TrackedItemId> parsed = TrackedItemId.tryParse(args[2]);
            if (parsed.isEmpty()) {
                messages().send(sender, "item-invalid-id", Map.of("value", sanitize(args[2])));
                return;
            }
            itemId = parsed.get();
        } else {
            messages().send(sender, "item-console-requires-id");
            return;
        }

        if (args.length >= 4) {
            try {
                int requested = Integer.parseInt(args[3]);
                int maxLimit = settings.itemHistoryMaximumLimit();
                if (requested > maxLimit) {
                    messages().send(sender, "item-history-limit-clamped",
                            Map.of("requested", Integer.toString(requested),
                                    "maximum", Integer.toString(maxLimit)));
                    limit = maxLimit;
                } else {
                    limit = Math.max(1, requested);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        final int finalLimit = limit;
        queryExecutor.execute(() -> {
            try {
                List<OwnershipLedgerEntry> entries = ledgerRepository.findHistory(itemId, finalLimit);
                runOnServerThread(() -> {
                    messages().send(sender, "item-history-header",
                            Map.of("item-id", itemId.toString()));
                    if (entries.isEmpty()) {
                        messages().send(sender, "item-history-empty");
                        return;
                    }
                    for (OwnershipLedgerEntry entry : entries) {
                        String previous = entry.optionalPreviousSubject()
                                .map(OwnershipSubject::describe).orElse("-");
                        messages().send(sender, "item-history-entry", Map.of(
                                "sequence", Integer.toString(entry.sequenceNumber()),
                                "previous", previous,
                                "new", entry.newSubject().describe(),
                                "reason", entry.transitionReason().token(),
                                "time", TIME_FORMATTER.format(entry.occurredAt())
                        ));
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "History query failed", exception);
                runOnServerThread(() -> messages().send(sender, "item-database-failed"));
            }
        });
    }

    private void assignOwner(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ASSIGN)) {
            messages().send(sender, "no-permission");
            return;
        }

        if (args.length < 5) {
            messages().send(sender, "item-usage");
            return;
        }

        Optional<TrackedItemId> parsed = TrackedItemId.tryParse(args[2]);
        if (parsed.isEmpty()) {
            messages().send(sender, "item-invalid-id", Map.of("value", sanitize(args[2])));
            return;
        }
        TrackedItemId itemId = parsed.get();

        String subjectTypeStr = args[3].toLowerCase(Locale.ROOT);
        String subjectIdStr = args[4];

        OwnershipSubject newSubject;
        try {
            newSubject = buildSubject(subjectTypeStr, subjectIdStr);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("UUID")) {
                messages().send(sender, "item-assign-invalid-uuid",
                        Map.of("value", sanitize(subjectIdStr)));
            } else {
                messages().send(sender, "item-assign-invalid-subject",
                        Map.of("value", sanitize(subjectTypeStr)));
            }
            return;
        }

        queryExecutor.execute(() -> {
            try {
                if (!trackedItemRepository.exists(itemId)) {
                    runOnServerThread(() -> messages().send(sender, "item-assign-not-tracked",
                            Map.of("item-id", itemId.toString())));
                    return;
                }
                OwnershipResult result = transitionService.transition(
                        itemId,
                        newSubject,
                        OwnershipTransitionReason.ADMIN_ASSIGNMENT,
                        "assign-" + itemId + "-" + System.currentTimeMillis(),
                        "admin",
                        ""
                );
                runOnServerThread(() -> {
                    if (result.status() == OwnershipResultStatus.RECORDED
                            || result.status() == OwnershipResultStatus.IDEMPOTENT_REPLAY
                            || result.status() == OwnershipResultStatus.NO_CHANGE) {
                        messages().send(sender, "item-assign-success");
                    } else if (result.status() == OwnershipResultStatus.ITEM_NOT_TRACKED) {
                        messages().send(sender, "item-assign-not-tracked",
                                Map.of("item-id", itemId.toString()));
                    } else {
                        messages().send(sender, "item-assign-failed");
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Assign-owner failed", exception);
                runOnServerThread(() -> messages().send(sender, "item-assign-failed"));
            }
        });
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return WorldEchoCommand.filter(ITEM_SUBCOMMANDS, args[1]);
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("owner")
                || args[1].equalsIgnoreCase("history")
                || args[1].equalsIgnoreCase("assign-owner"))) {
            return List.of();
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("assign-owner")) {
            return WorldEchoCommand.filter(ASSIGN_SUBJECT_TYPES, args[3]);
        }
        return List.of();
    }

    private static OwnershipSubject buildSubject(String typeStr, String idStr) {
        OwnershipSubjectType type = switch (typeStr) {
            case "player" -> OwnershipSubjectType.PLAYER;
            case "entity" -> OwnershipSubjectType.ENTITY;
            case "system" -> OwnershipSubjectType.SYSTEM;
            default -> throw new IllegalArgumentException("Invalid subject type: " + typeStr);
        };
        return switch (type) {
            case PLAYER -> {
                try {
                    yield OwnershipSubject.player(UUID.fromString(idStr));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid UUID: " + idStr, exception);
                }
            }
            case ENTITY -> {
                try {
                    yield OwnershipSubject.entity(UUID.fromString(idStr));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid UUID: " + idStr, exception);
                }
            }
            case SYSTEM -> OwnershipSubject.system(idStr);
            default -> throw new IllegalArgumentException("Unsupported subject type: " + type);
        };
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

    private static String sanitize(String input) {
        return input == null ? "" : input.replaceAll("[&<>\u00a7\n\r]", "");
    }
}
