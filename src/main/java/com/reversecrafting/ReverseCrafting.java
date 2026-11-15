package com.reversecrafting;

import java.util.HashMap;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.reversecrafting.ReverseRecipes.ReverseCraft;

public final class ReverseCrafting extends JavaPlugin implements Listener {

    private ReverseRecipes recipes;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        recipes = new ReverseRecipes(this);
        getServer().getPluginManager().registerEvents(this, this);
        recipes.rebuild();
        getLogger().info("Plugin enabled successfully!");
        getLogger().info("Reverse crafting system initialized!");
        getServer().getScheduler().runTask(this, recipes::rebuild);
    }

    @Override
    public void onDisable() {
        if (recipes != null) {
            recipes.unregister();
        }
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        recipes.rebuild();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.isRepair()) {
            return;
        }
        CraftingInventory inventory = event.getInventory();
        ReverseCraft craft = recipes.detect(inventory);
        if (craft != null) {
            inventory.setResult(craft.preview());
            return;
        }
        if (inventory.getRecipe() == null) {
            inventory.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        handleResultTake(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event instanceof CraftItemEvent) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof CraftingInventory crafting)) {
            return;
        }
        ReverseCraft craft = recipes.detect(crafting);
        if (craft == null) {
            scheduleRefresh(crafting, event.getWhoClicked() instanceof Player player ? player : null);
            return;
        }
        if (event.getClick() == ClickType.DOUBLE_CLICK && matchesReward(event.getCursor(), craft)) {
            event.setCancelled(true);
            return;
        }
        if (!isResultSlot(event)) {
            scheduleRefresh(crafting, event.getWhoClicked() instanceof Player player ? player : null);
            return;
        }
        handleResultTake(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory() instanceof CraftingInventory crafting)) {
            return;
        }
        if (event.getRawSlots().contains(0) && recipes.detect(crafting) != null) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            scheduleRefresh(crafting, player);
        }
    }

    private void handleResultTake(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory() instanceof CraftingInventory crafting)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ReverseCraft craft = recipes.detect(crafting);
        if (craft == null) {
            return;
        }
        if (!isResultSlot(event) && !(event instanceof CraftItemEvent)) {
            return;
        }

        event.setCancelled(true);
        switch (event.getClick()) {
            case LEFT, RIGHT, CREATIVE -> takeOneToCursor(player, crafting, craft);
            case SHIFT_LEFT, SHIFT_RIGHT -> takeManyToInventory(player, crafting, craft);
            case NUMBER_KEY -> takeOneToHotbar(player, crafting, craft, event.getHotbarButton());
            case SWAP_OFFHAND -> takeOneToOffhand(player, crafting, craft);
            case DROP, CONTROL_DROP -> dropOne(player, crafting, craft);
            default -> {
            }
        }
        refresh(crafting);
        player.updateInventory();
    }

    private boolean isResultSlot(InventoryClickEvent event) {
        return event.getRawSlot() == 0 || event.getSlotType() == InventoryType.SlotType.RESULT;
    }

    private void scheduleRefresh(CraftingInventory crafting, Player player) {
        getServer().getScheduler().runTask(this, () -> {
            refresh(crafting);
            if (player != null) {
                player.updateInventory();
            }
        });
    }

    private void takeOneToCursor(Player player, CraftingInventory crafting, ReverseCraft craft) {
        List<ItemStack> rewards = craft.rewards();
        ItemStack first = rewards.get(0);
        ItemStack cursor = player.getItemOnCursor();
        if (ReverseRecipes.isEmpty(cursor)) {
            if (!ReverseRecipes.consume(crafting, craft, 1)) {
                return;
            }
            player.setItemOnCursor(first);
            giveRest(player, rewards, 1);
            return;
        }
        if (!cursor.isSimilar(first) || cursor.getAmount() + first.getAmount() > cursor.getMaxStackSize()) {
            return;
        }
        if (!ReverseRecipes.consume(crafting, craft, 1)) {
            return;
        }
        cursor.setAmount(cursor.getAmount() + first.getAmount());
        player.setItemOnCursor(cursor);
        giveRest(player, rewards, 1);
    }

    private void takeManyToInventory(Player player, CraftingInventory crafting, ReverseCraft craft) {
        ItemStack source = ReverseRecipes.singleItem(crafting.getMatrix());
        if (source == null) {
            return;
        }
        int available = source.getAmount() / craft.consumeAmount();
        int times = maxFits(player.getInventory().getStorageContents(), craft.rewards(), available);
        if (times <= 0 || !ReverseRecipes.consume(crafting, craft, times)) {
            return;
        }
        for (int i = 0; i < times; i++) {
            for (ItemStack reward : craft.rewards()) {
                giveOrDrop(player, reward);
            }
        }
    }

    private void takeOneToHotbar(Player player, CraftingInventory crafting, ReverseCraft craft, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            return;
        }
        List<ItemStack> rewards = craft.rewards();
        ItemStack first = rewards.get(0);
        ItemStack hotbar = player.getInventory().getItem(hotbarSlot);
        if (ReverseRecipes.isEmpty(hotbar)) {
            if (!ReverseRecipes.consume(crafting, craft, 1)) {
                return;
            }
            player.getInventory().setItem(hotbarSlot, first);
            giveRest(player, rewards, 1);
            return;
        }
        if (!hotbar.isSimilar(first) || hotbar.getAmount() + first.getAmount() > hotbar.getMaxStackSize()) {
            return;
        }
        if (!ReverseRecipes.consume(crafting, craft, 1)) {
            return;
        }
        hotbar.setAmount(hotbar.getAmount() + first.getAmount());
        player.getInventory().setItem(hotbarSlot, hotbar);
        giveRest(player, rewards, 1);
    }

    private void takeOneToOffhand(Player player, CraftingInventory crafting, ReverseCraft craft) {
        List<ItemStack> rewards = craft.rewards();
        ItemStack first = rewards.get(0);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (ReverseRecipes.isEmpty(offhand)) {
            if (!ReverseRecipes.consume(crafting, craft, 1)) {
                return;
            }
            player.getInventory().setItemInOffHand(first);
            giveRest(player, rewards, 1);
            return;
        }
        if (!offhand.isSimilar(first) || offhand.getAmount() + first.getAmount() > offhand.getMaxStackSize()) {
            return;
        }
        if (!ReverseRecipes.consume(crafting, craft, 1)) {
            return;
        }
        offhand.setAmount(offhand.getAmount() + first.getAmount());
        player.getInventory().setItemInOffHand(offhand);
        giveRest(player, rewards, 1);
    }

    private void dropOne(Player player, CraftingInventory crafting, ReverseCraft craft) {
        if (!ReverseRecipes.consume(crafting, craft, 1)) {
            return;
        }
        for (ItemStack reward : craft.rewards()) {
            player.getWorld().dropItemNaturally(player.getLocation(), reward);
        }
    }

    private void refresh(CraftingInventory crafting) {
        ReverseCraft craft = recipes.detect(crafting);
        if (craft != null) {
            crafting.setResult(craft.preview());
            return;
        }
        if (crafting.getRecipe() == null) {
            crafting.setResult(null);
        }
    }

    private static void giveRest(Player player, List<ItemStack> rewards, int start) {
        for (int i = start; i < rewards.size(); i++) {
            giveOrDrop(player, rewards.get(i));
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private static boolean matchesReward(ItemStack item, ReverseCraft craft) {
        if (ReverseRecipes.isEmpty(item)) {
            return false;
        }
        for (ItemStack reward : craft.rewards()) {
            if (item.isSimilar(reward)) {
                return true;
            }
        }
        return item.getType().name().contains("BUNDLE");
    }

    private static int maxFits(ItemStack[] storage, List<ItemStack> rewards, int maxTimes) {
        ItemStack[] contents = cloneItems(storage);
        int crafts = 0;
        while (crafts < maxTimes) {
            ItemStack[] attempt = cloneItems(contents);
            boolean fits = true;
            for (ItemStack reward : rewards) {
                if (addTo(attempt, reward.clone()) > 0) {
                    fits = false;
                    break;
                }
            }
            if (!fits) {
                break;
            }
            contents = attempt;
            crafts++;
        }
        return crafts;
    }

    private static int addTo(ItemStack[] storage, ItemStack item) {
        int remaining = item.getAmount();
        int max = item.getMaxStackSize();
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack slot = storage[i];
            if (ReverseRecipes.isEmpty(slot) || !slot.isSimilar(item)) {
                continue;
            }
            int space = Math.min(max, slot.getMaxStackSize()) - slot.getAmount();
            if (space <= 0) {
                continue;
            }
            int used = Math.min(space, remaining);
            slot.setAmount(slot.getAmount() + used);
            remaining -= used;
        }
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            if (!ReverseRecipes.isEmpty(storage[i])) {
                continue;
            }
            int used = Math.min(max, remaining);
            ItemStack placed = item.clone();
            placed.setAmount(used);
            storage[i] = placed;
            remaining -= used;
        }
        return remaining;
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] clone = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            clone[i] = items[i] == null ? null : items[i].clone();
        }
        return clone;
    }
}
