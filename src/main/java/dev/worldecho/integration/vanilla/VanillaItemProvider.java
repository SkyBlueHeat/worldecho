package dev.worldecho.integration.vanilla;

import dev.worldecho.domain.content.Capability;
import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.content.SemanticRole;
import dev.worldecho.integration.ItemContentProvider;
import dev.worldecho.integration.ProviderHealth;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public final class VanillaItemProvider implements ItemContentProvider {

    @Override
    public String providerId() {
        return "vanilla";
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.AVAILABLE;
    }

    @Override
    public boolean supports(ItemStack itemStack) {
        return itemStack != null && !itemStack.getType().isAir();
    }

    @Override
    public Optional<IdentifiedContent> identify(ItemStack itemStack) {
        EnumSet<SemanticRole> roles = EnumSet.noneOf(SemanticRole.class);
        EnumSet<Capability> capabilities = EnumSet.of(
                Capability.CAN_HAVE_HISTORY,
                Capability.CAN_CHANGE_OWNER,
                Capability.CAN_BE_STOLEN,
                Capability.CAN_BE_LOST
        );

        if (Tag.ITEMS_SWORDS.isTagged(itemStack.getType())
                || Tag.ITEMS_AXES.isTagged(itemStack.getType())) {
            roles.add(SemanticRole.WEAPON);
            roles.add(SemanticRole.LEGENDARY_CANDIDATE);
        }

        if (itemStack.getType().name().endsWith("_HELMET")
                || itemStack.getType().name().endsWith("_CHESTPLATE")
                || itemStack.getType().name().endsWith("_LEGGINGS")
                || itemStack.getType().name().endsWith("_BOOTS")) {
            roles.add(SemanticRole.ARMOR);
        }

        String displayName = itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName()
                ? itemStack.getItemMeta().getDisplayName()
                : itemStack.getType().translationKey();

        return Optional.of(new IdentifiedContent(
                new ContentKey(providerId(), itemStack.getType().getKey().asString()),
                displayName,
                roles,
                capabilities
        ));
    }
}
