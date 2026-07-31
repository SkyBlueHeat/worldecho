package dev.worldecho.paper.listener;

import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.domain.item.TrackedItemId;
import dev.worldecho.paper.item.ItemIdentityAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Preserves WorldEcho tracked-item identity across anvil, smithing table,
 * and grindstone transformations.
 *
 * <p>Two-phase approach:
 * <ol>
 *   <li>At {@link EventPriority#LOW} — before the transaction completes — read
 *       the identity from the source item(s) and store it per-player.</li>
 *   <li>At {@link EventPriority#MONITOR} — after the transaction completes —
 *       check the cursor for the result item and write the captured identity.</li>
 * </ol>
 *
 * <p>This ensures the result item inherits the source item's WorldEcho UUID,
 * maintaining identity continuity through repairs, renames, upgrades, and
 * enchantment removals.
 */
public final class ItemTransformationListener implements Listener {

    private final Supplier<WorldEchoSettings> settingsSupplier;
    private final ItemIdentityAdapter identityAdapter;
    private final Map<UUID, TrackedItemId> pendingTransforms = new ConcurrentHashMap<>();

    public ItemTransformationListener(
            Supplier<WorldEchoSettings> settingsSupplier,
            ItemIdentityAdapter identityAdapter
    ) {
        this.settingsSupplier = settingsSupplier;
        this.identityAdapter = identityAdapter;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClickLow(InventoryClickEvent event) {
        if (!isAutomaticTrackingEnabled()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        InventoryType type = inventory.getType();
        if (!isTransformationInventory(type)) {
            return;
        }

        InventoryAction action = event.getAction();
        if (action != InventoryAction.PICKUP_ALL
                && action != InventoryAction.PICKUP_HALF
                && action != InventoryAction.MOVE_TO_OTHER_INVENTORY
                && action != InventoryAction.COLLECT_TO_CURSOR
                && action != InventoryAction.SWAP_WITH_CURSOR) {
            return;
        }

        int resultSlot = getResultSlot(type);
        if (event.getRawSlot() != resultSlot) {
            return;
        }

        TrackedItemId sourceId = readSourceIdentity(inventory, type);
        if (sourceId != null) {
            pendingTransforms.put(player.getUniqueId(), sourceId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickMonitor(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        TrackedItemId sourceId = pendingTransforms.remove(player.getUniqueId());
        if (sourceId == null) {
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            ItemStack resultSlotItem = event.getInventory().getItem(getResultSlot(event.getInventory().getType()));
            if (resultSlotItem != null && !resultSlotItem.getType().isAir()) {
                identityAdapter.writeIdentity(resultSlotItem, sourceId);
            }
            return;
        }

        identityAdapter.writeIdentity(cursor, sourceId);
    }

    private boolean isTransformationInventory(InventoryType type) {
        return type == InventoryType.ANVIL
                || type == InventoryType.SMITHING
                || type == InventoryType.GRINDSTONE;
    }

    private int getResultSlot(InventoryType type) {
        if (type == InventoryType.SMITHING) {
            return 3;
        }
        return 2;
    }

    private TrackedItemId readSourceIdentity(Inventory inventory, InventoryType type) {
        ItemStack firstInput = inventory.getItem(0);
        if (firstInput != null && !firstInput.getType().isAir()) {
            ItemIdentityAdapter.IdentityResult result = identityAdapter.readIdentity(firstInput);
            if (result.status() == ItemIdentityAdapter.IdentityStatus.EXISTING) {
                return result.itemId();
            }
        }

        if (type == InventoryType.ANVIL || type == InventoryType.SMITHING) {
            ItemStack secondInput = inventory.getItem(1);
            if (secondInput != null && !secondInput.getType().isAir()) {
                ItemIdentityAdapter.IdentityResult result = identityAdapter.readIdentity(secondInput);
                if (result.status() == ItemIdentityAdapter.IdentityStatus.EXISTING) {
                    return result.itemId();
                }
            }
        }

        return null;
    }

    private boolean isAutomaticTrackingEnabled() {
        WorldEchoSettings settings = settingsSupplier.get();
        return settings.automaticTrackingEnabled()
                && settings.automaticTrackingPlayerInventories()
                && settings.automaticTrackingTransformIdentityContinuity();
    }
}
