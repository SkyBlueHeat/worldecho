package dev.worldecho.paper.listener;

import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.content.IdentifiedContent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public record ItemSnapshot(ContentKey key, String serialized, int score) {

    public static ItemSnapshot from(
            IdentifiedContent content,
            ItemStack item,
            int score,
            boolean redactCustomName
    ) {
        ItemMeta meta = item.getItemMeta();
        String customName = "";
        if (!redactCustomName && meta != null && meta.hasDisplayName()) {
            customName = meta.getDisplayName();
        }

        String value = "content=" + content.key()
                + ";material=" + item.getType().getKey().asString()
                + ";amount=" + item.getAmount()
                + ";customName=" + customName
                + ";score=" + score;

        return new ItemSnapshot(content.key(), value, score);
    }

    public static ItemSnapshot empty() {
        return new ItemSnapshot(null, "", 0);
    }
}
