package dev.worldecho.integration.vanilla;

import dev.worldecho.domain.content.Capability;
import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.content.IdentifiedContent;
import dev.worldecho.domain.content.SemanticRole;
import dev.worldecho.integration.EntityContentProvider;
import dev.worldecho.integration.ProviderHealth;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public final class VanillaEntityProvider implements EntityContentProvider {

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
    public boolean supports(Entity entity) {
        return entity != null;
    }

    @Override
    public Optional<IdentifiedContent> identify(Entity entity) {
        Set<SemanticRole> roles = EnumSet.noneOf(SemanticRole.class);
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);

        if (entity instanceof Player) {
            roles.add(SemanticRole.PLAYER);
            capabilities.addAll(Set.of(
                    Capability.CAN_FIGHT,
                    Capability.CAN_SPEAK,
                    Capability.CAN_HOLD_ITEMS,
                    Capability.CAN_OWN_ITEMS,
                    Capability.CAN_CREATE_RIVALRY
            ));
        } else if (entity instanceof Villager) {
            roles.add(SemanticRole.CIVILIAN);
            capabilities.addAll(Set.of(
                    Capability.CAN_SPEAK,
                    Capability.CAN_TRADE
            ));
        } else if (entity instanceof Monster) {
            roles.add(SemanticRole.MONSTER);
            capabilities.addAll(Set.of(
                    Capability.CAN_FIGHT,
                    Capability.CAN_HOLD_ITEMS,
                    Capability.CAN_CREATE_RIVALRY
            ));
        } else if (entity instanceof Animals) {
            roles.add(SemanticRole.ANIMAL);
        }

        if (entity instanceof LivingEntity) {
            capabilities.add(Capability.CAN_FIGHT);
        }

        String contentId = entity.getType().getKey().asString();
        String displayName = entity.customName() == null
                ? entity.getType().translationKey()
                : entity.customName().toString();

        return Optional.of(new IdentifiedContent(
                new ContentKey(providerId(), contentId),
                displayName,
                roles,
                capabilities
        ));
    }
}
