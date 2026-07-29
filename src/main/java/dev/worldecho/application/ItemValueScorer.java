package dev.worldecho.application;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

public final class ItemValueScorer {

    public int score(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return 0;
        }

        int score = materialScore(itemStack.getType().name());
        ItemMeta meta = itemStack.getItemMeta();

        if (meta != null) {
            score += meta.getEnchants().entrySet().stream()
                    .mapToInt(entry -> 4 + Math.max(0, entry.getValue()) * 2)
                    .sum();

            if (meta.hasDisplayName()) {
                score += 8;
            }

            if (meta.isUnbreakable()) {
                score += 12;
            }

            if (meta instanceof Damageable damageable && damageable.hasDamage()) {
                score -= Math.min(10, damageable.getDamage() / 50);
            }
        }

        score += Math.min(5, Math.max(0, itemStack.getAmount() - 1));
        return Math.max(0, score);
    }

    private int materialScore(String materialName) {
        if (materialName.startsWith("NETHERITE_")) {
            return 60;
        }
        if (materialName.startsWith("DIAMOND_")) {
            return 45;
        }
        if (materialName.startsWith("GOLDEN_")) {
            return 25;
        }
        if (materialName.startsWith("IRON_")) {
            return 20;
        }
        if (materialName.startsWith("CHAINMAIL_")) {
            return 15;
        }
        if (materialName.startsWith("STONE_")) {
            return 8;
        }
        if (materialName.startsWith("WOODEN_") || materialName.startsWith("LEATHER_")) {
            return 4;
        }

        return switch (materialName) {
            case "ELYTRA" -> 55;
            case "TRIDENT", "MACE" -> 50;
            case "TOTEM_OF_UNDYING" -> 45;
            case "ENCHANTED_GOLDEN_APPLE" -> 60;
            case "NETHER_STAR" -> 55;
            case "DRAGON_EGG" -> 100;
            default -> 1;
        };
    }
}
