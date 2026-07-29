package dev.worldecho.paper;

import dev.worldecho.application.ItemValueScorer;
import dev.worldecho.application.MemoryRecorder;
import dev.worldecho.config.SettingsLoadResult;
import dev.worldecho.config.SettingsLoader;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.integration.IntegrationRegistry;
import dev.worldecho.integration.vanilla.VanillaEntityProvider;
import dev.worldecho.integration.vanilla.VanillaItemProvider;
import dev.worldecho.paper.command.WorldEchoCommand;
import dev.worldecho.paper.config.BukkitConfigurationSource;
import dev.worldecho.paper.listener.PlayerDeathMemoryListener;
import dev.worldecho.paper.message.PaperMessageService;
import dev.worldecho.persistence.DatabaseManager;
import dev.worldecho.persistence.SqliteStoryEventRepository;
import dev.worldecho.persistence.StoryEventRepository;
import dev.worldecho.persistence.StoryWriteQueue;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Plugin entry point. Wires the memory kernel and owns every background resource.
 */
public final class WorldEchoPlugin extends JavaPlugin {

    private static final Duration STORAGE_INIT_TIMEOUT = Duration.ofSeconds(30);

    private volatile WorldEchoSettings settings;
    private volatile PaperMessageService messages;
    private volatile ItemValueScorer itemValueScorer;

    private IntegrationRegistry integrations;
    private DatabaseManager databaseManager;
    private StoryEventRepository repository;
    private StoryWriteQueue writeQueue;
    private ExecutorService queryExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfiguration().forEach(warning -> getLogger().warning("Configuration: " + warning));

        queryExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "worldecho-sqlite-reader");
            thread.setDaemon(true);
            return thread;
        });

        databaseManager = new DatabaseManager(
                getDataFolder().toPath().resolve(settings.sqliteFile()));

        if (!initializeStorage()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        repository = new SqliteStoryEventRepository(databaseManager);
        writeQueue = new StoryWriteQueue(
                repository,
                throwable -> getLogger().log(Level.SEVERE, "Story write failed", throwable),
                settings.writeQueueCapacity(),
                settings.writeBatchSize()
        );
        writeQueue.start();

        integrations = new IntegrationRegistry((providerId, throwable) -> getLogger().log(
                Level.WARNING, "Content provider '" + providerId + "' failed", throwable));
        integrations.registerEntityProvider(new VanillaEntityProvider());
        integrations.registerItemProvider(new VanillaItemProvider());
        getLogger().info("Content providers: "
                + String.join(", ", integrations.describeProviders()));

        MemoryRecorder memoryRecorder = new MemoryRecorder(
                writeQueue,
                event -> getLogger().warning(
                        "Write queue is saturated; dropped memory event " + event.id())
        );

        getServer().getPluginManager().registerEvents(
                new PlayerDeathMemoryListener(
                        () -> settings,
                        integrations,
                        () -> itemValueScorer,
                        memoryRecorder
                ),
                this
        );

        registerCommand();
        getLogger().info("WorldEcho memory kernel enabled");
    }

    @Override
    public void onDisable() {
        if (writeQueue != null) {
            boolean drained = writeQueue.shutdown(settings.shutdownTimeout());
            StoryWriteQueue.QueueStatus status = writeQueue.status();
            if (drained) {
                getLogger().info("Story write queue drained: " + status.written() + " event(s) stored");
            } else {
                getLogger().warning("Story write queue did not drain in time; pending="
                        + status.pending() + " failed=" + status.failed()
                        + " dropped=" + status.dropped());
            }
        }

        if (queryExecutor != null) {
            queryExecutor.shutdownNow();
            try {
                if (!queryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("Query executor did not stop cleanly");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        getLogger().info("WorldEcho memory kernel disabled");
    }

    /**
     * Re-reads config.yml and the locale files. Persistence and providers keep running so a
     * reload can never lose queued memories.
     *
     * @return validation warnings that should be shown to the administrator
     */
    public List<String> reloadSettings() {
        reloadConfig();
        return applyConfiguration();
    }

    /**
     * Runs migrations on the storage thread and waits for the result.
     *
     * <p>Enable is not a tick, so waiting here is safe, and keeping the JDBC work off the
     * server thread preserves the rule that SQLite is only ever touched by WorldEcho
     * worker threads.</p>
     */
    private boolean initializeStorage() {
        try {
            int applied = queryExecutor
                    .submit(() -> databaseManager.initialize())
                    .get(STORAGE_INIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            getLogger().info("Storage ready at " + databaseManager.databasePath()
                    + " (" + applied + " migration(s) applied)");
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            getLogger().severe("Interrupted while preparing WorldEcho storage");
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            getLogger().log(Level.SEVERE,
                    "WorldEcho could not initialize its database and will stay disabled",
                    exception);
            return false;
        }
    }

    private List<String> applyConfiguration() {
        SettingsLoadResult result =
                SettingsLoader.load(new BukkitConfigurationSource(getConfig()));
        settings = result.settings();
        itemValueScorer = new ItemValueScorer(settings.itemScoreWeights());
        messages = PaperMessageService.load(this, settings.locale());
        return result.warnings();
    }

    private void registerCommand() {
        PluginCommand command = getCommand("worldecho");
        if (command == null) {
            getLogger().severe("Command 'worldecho' is missing from plugin.yml");
            return;
        }

        WorldEchoCommand executor = new WorldEchoCommand(
                this,
                () -> settings,
                () -> messages,
                () -> itemValueScorer,
                integrations,
                databaseManager,
                repository,
                writeQueue,
                queryExecutor,
                this::reloadSettings
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
