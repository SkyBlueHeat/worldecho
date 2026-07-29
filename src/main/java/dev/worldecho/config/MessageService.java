package dev.worldecho.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.Objects;

public final class MessageService {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final YamlConfiguration messages;

    private MessageService(YamlConfiguration messages) {
        this.messages = messages;
    }

    public static MessageService load(JavaPlugin plugin, String locale) {
        String fileName = locale.equalsIgnoreCase("tr")
                ? "messages_tr.yml"
                : "messages_en.yml";
        File destination = new File(plugin.getDataFolder(), fileName);

        if (!destination.exists()) {
            plugin.saveResource(fileName, false);
        }

        return new MessageService(YamlConfiguration.loadConfiguration(destination));
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> placeholders) {
        String prefix = messages.getString("prefix", "");
        String raw = Objects.requireNonNullElse(
                messages.getString(key),
                "<red>Missing message: " + key + "</red>"
        );

        String resolved = prefix + raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace(
                    "{" + entry.getKey() + "}",
                    escape(entry.getValue())
            );
        }

        return miniMessage.deserialize(resolved);
    }

    private String escape(String input) {
        return input == null ? "" : MiniMessage.miniMessage().escapeTags(input);
    }
}
