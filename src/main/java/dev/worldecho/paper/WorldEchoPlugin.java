package dev.worldecho.paper;

import dev.worldecho.application.ItemValueScorer;
import dev.worldecho.application.MemoryRecorder;
import dev.worldecho.config.MessageService;
import dev.worldecho.config.SettingsLoader;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.integration.IntegrationRegistry;
import dev.worldecho.integration.vanilla.VanillaEntityProvider;
import dev.worldecho.integration.vanilla.VanillaItemProvider;
import dev.worldecho.paper.command.WorldEchoCommand;
import dev.worldecho.paper.listener.PlayerDeathMemoryListener;
import dev.worldecho.persistence.DatabaseManager;
import dev.worldecho.persistence.SqliteStoryEventRepository;
import dev.worldecho.persistence.StoryEventRepository;
import dev.worldecho.persistence.StoryWriteQueue;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;

public final class WorldEchoPlugin extends JavaPlugin {

    private volatile WorldEchoSettings settings;
    private volatile MessageService messages;
    private StoryWriteQueue writeQueue;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSafeConfiguration();

        try {
            Path databasePath = getDataFolder()
                    .toPath()
                    .resolve(settings.sqliteFile());

            DatabaseManager databaseManager = new DatabaseManager(databasePath);
            databaseManager.initialize();

            StoryEventRepository repository =
                    new SqliteStoryEventRepository(databaseManager);

            this.writeQueue = new StoryWriteQueue(
                    repository,
                    throwable -> getLogger().log(
                            Level.SEVERE,
                            "Asynchronous story event write failed",
                            throwable
                    )
            );

            IntegrationRegistry integrations = new IntegrationRegistry();
            integrations.registerEntityProvider(new VanillaEntityProvider());
            integrations.registerItemProvider(new VanillaItemProvider());

            ItemValueScorer itemValueScorer = new ItemValueScorer();
            MemoryRecorder memoryRecorder = new MemoryRecorder(writeQueue);

            getServer().getPluginManager().registerEvents(
                    new PlayerDeathMemoryListener(
                            () -> settings,
                            integrations,
                            itemValueScorer,
                            memoryRecorder
                    ),
                    this
            );

            WorldEchoCommand command = new WorldEchoCommand(
                    this,
                    () -> settings,
                    () -> messages,
                    integrations,
                    repository,
                    writeQueue,
                    itemValueScorer,
                    this::reloadSafeConfiguration
            );

            PluginCommand pluginCommand = Objects.requireNonNull(
                    getCommand("worldecho"),
                    "worldecho command missing from plugin.yml"
            );
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);

            getLogger().info(
                    "WorldEcho enabled with providers: "
                            + String.join(", ", integrations.providerStatus())
            );
        } catch (Exception exception) {
            getLogger().log(
                    Level.SEVERE,
                    "WorldEcho could not initialize and will be disabled",
                    exception
            );
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (writeQueue != null && settings != null) {
            boolean clean = writeQueue.shutdown(settings.shutdownTimeout());
            if (!clean) {
                getLogger().warning(
                        "WorldEcho persistence queue did not shut down cleanly."
                );
            }
        }
    }

    private void reloadSafeConfiguration() {
        reloadConfig();
        this.settings = SettingsLoader.load(this);
        this.messages = MessageService.load(this, settings.locale());
    }
}
