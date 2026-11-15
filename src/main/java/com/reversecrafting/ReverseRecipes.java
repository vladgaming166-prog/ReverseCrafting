package com.reversecrafting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;

/**
 * Indexes vanilla shaped/shapeless recipes so a finished item can be turned
 * back into the ingredients that crafted it.
 */
final class ReverseRecipes {

    private final Map<Material, ReverseCraft> recipes = new EnumMap<>(Material.class);

    void rebuild() {
        recipes.clear();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            ReverseCraft craft = ReverseCraft.fromVanilla(iterator.next());
            if (craft == null) {
                continue;
            }
            recipes.putIfAbsent(craft.input(), craft);
        }
    }

    ReverseCraft find(ItemStack item) {
        if (isEmpty(item) || !isPlainVanilla(item)) {
            return null;
        }
        ReverseCraft craft = recipes.get(item.getType());
        if (craft == null || item.getAmount() < craft.consumeAmount()) {
            return null;
        }
        return craft;
    }

    ReverseCraft detect(CraftingInventory inventory) {
        if (inventory.getRecipe() != null) {
            return null;
        }
        ItemStack source = singleItem(inventory.getMatrix());
        if (source == null) {
            return null;
        }
        return find(source);
    }

    static ItemStack singleItem(ItemStack[] matrix) {
        ItemStack found = null;
        for (ItemStack slot : matrix) {
            if (isEmpty(slot)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = slot;
        }
        return found;
    }

    static boolean consume(CraftingInventory inventory, ReverseCraft craft, int times) {
        if (times <= 0) {
            return false;
        }
        int amount = craft.consumeAmount() * times;
        ItemStack[] matrix = inventory.getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            ItemStack slot = matrix[i];
            if (isEmpty(slot)) {
                continue;
            }
            if (slot.getType() != craft.input() || !isPlainVanilla(slot) || slot.getAmount() < amount) {
                return false;
            }
            ItemStack clone = slot.clone();
            int left = clone.getAmount() - amount;
            if (left <= 0) {
                matrix[i] = null;
            } else {
                clone.setAmount(left);
                matrix[i] = clone;
            }
            inventory.setMatrix(matrix);
            return true;
        }
        return false;
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    static boolean isPlainVanilla(ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }
        for (Enchantment enchantment : item.getEnchantments().keySet()) {
            if (item.getEnchantmentLevel(enchantment) > 0) {
                return false;
            }
        }
        if (!item.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.isUnbreakable()) {
            return false;
        }
        if (!meta.getItemFlags().isEmpty() || !meta.getPersistentDataContainer().getKeys().isEmpty()) {
            return false;
        }
        if (hasCustomModelData(meta) || hasAttributeModifiers(meta) || hasItemName(meta)) {
            return false;
        }
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        if (meta instanceof Repairable repairable && repairable.hasRepairCost() && repairable.getRepairCost() > 0) {
            return false;
        }
        if (meta instanceof LeatherArmorMeta leather) {
            Color def = Bukkit.getItemFactory().getDefaultLeatherColor();
            if (def != null && !def.equals(leather.getColor())) {
                return false;
            }
        }
        if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
            return false;
        }
        if (meta instanceof BundleMeta bundle && !bundle.getItems().isEmpty()) {
            return false;
        }
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            return false;
        }
        if (meta instanceof BannerMeta banner && !banner.getPatterns().isEmpty()) {
            return false;
        }
        if (meta instanceof SkullMeta skull && skull.hasOwner()) {
            return false;
        }
        if (meta instanceof CompassMeta compass && (compass.hasLodestone() || compass.isLodestoneTracked())) {
            return false;
        }
        if (meta instanceof CrossbowMeta crossbow && crossbow.hasChargedProjectiles()) {
            return false;
        }
        if (meta instanceof BlockStateMeta blockState && hasStoredItems(blockState)) {
            return false;
        }
        return !(meta instanceof PotionMeta)
                && !(meta instanceof FireworkMeta)
                && !(meta instanceof FireworkEffectMeta)
                && !(meta instanceof BookMeta)
                && !(meta instanceof MapMeta)
                && !(meta instanceof SuspiciousStewMeta);
    }

    private static boolean hasStoredItems(BlockStateMeta meta) {
        if (!meta.hasBlockState() || !(meta.getBlockState() instanceof Container container)) {
            return false;
        }
        for (ItemStack content : container.getInventory().getContents()) {
            if (!isEmpty(content)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCustomModelData(ItemMeta meta) {
        try {
            return meta.hasCustomModelData();
        } catch (NoSuchMethodError ignored) {
            return false;
        }
    }

    private static boolean hasAttributeModifiers(ItemMeta meta) {
        try {
            return meta.hasAttributeModifiers();
        } catch (NoSuchMethodError ignored) {
            return false;
        }
    }

    private static boolean hasItemName(ItemMeta meta) {
        try {
            Object value = ItemMeta.class.getMethod("hasItemName").invoke(meta);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    static final class ReverseCraft {
        private final Material input;
        private final int consumeAmount;
        private final List<ItemStack> rewards;

        private ReverseCraft(Material input, int consumeAmount, List<ItemStack> rewards) {
            this.input = input;
            this.consumeAmount = consumeAmount;
            this.rewards = List.copyOf(rewards);
        }

        Material input() {
            return input;
        }

        int consumeAmount() {
            return consumeAmount;
        }

        List<ItemStack> rewards() {
            List<ItemStack> clone = new ArrayList<>(rewards.size());
            for (ItemStack reward : rewards) {
                clone.add(reward.clone());
            }
            return clone;
        }

        ItemStack preview() {
            return rewards.get(0).clone();
        }

        static ReverseCraft fromVanilla(Recipe recipe) {
            if (!(recipe instanceof Keyed keyed) || !NamespacedKey.MINECRAFT.equals(keyed.getKey().getNamespace())) {
                return null;
            }
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
                return null;
            }

            ItemStack result = recipe.getResult();
            if (isEmpty(result) || !isPlainVanilla(result)) {
                return null;
            }

            LinkedHashMap<Material, Integer> counts = new LinkedHashMap<>();
            if (recipe instanceof ShapedRecipe shaped) {
                Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
                for (String row : shaped.getShape()) {
                    for (int i = 0; i < row.length(); i++) {
                        if (!addChoice(counts, choices.get(row.charAt(i)), true)) {
                            return null;
                        }
                    }
                }
            } else {
                for (RecipeChoice choice : ((ShapelessRecipe) recipe).getChoiceList()) {
                    if (!addChoice(counts, choice, false)) {
                        return null;
                    }
                }
            }
            if (counts.isEmpty()) {
                return null;
            }

            List<ItemStack> rewards = new ArrayList<>();
            for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
                rewards.add(new ItemStack(entry.getKey(), entry.getValue()));
            }
            return new ReverseCraft(result.getType(), result.getAmount(), rewards);
        }

        private static boolean addChoice(Map<Material, Integer> counts, RecipeChoice choice, boolean allowEmpty) {
            if (choice == null) {
                return allowEmpty;
            }
            Material material = resolve(choice);
            if (material == null || material.isAir() || !material.isItem()) {
                return false;
            }
            if (hasCraftingRemainder(material)) {
                return false;
            }
            counts.merge(material, 1, Integer::sum);
            return true;
        }

        @SuppressWarnings("deprecation")
        private static Material resolve(RecipeChoice choice) {
            if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
                try {
                    List<Material> options = materialChoice.getChoices();
                    return options.isEmpty() ? null : options.get(0);
                } catch (NoSuchMethodError ignored) {
                    ItemStack stack = materialChoice.getItemStack();
                    return isEmpty(stack) ? null : stack.getType();
                }
            }
            if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
                List<ItemStack> options = exactChoice.getChoices();
                if (options.size() != 1) {
                    return null;
                }
                ItemStack stack = options.get(0);
                if (isEmpty(stack) || stack.getAmount() != 1 || !isPlainVanilla(stack)) {
                    return null;
                }
                return stack.getType();
            }
            try {
                ItemStack stack = choice.getItemStack();
                return isEmpty(stack) ? null : stack.getType();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static boolean hasCraftingRemainder(Material material) {
            try {
                return material.getCraftingRemainingItem() != null;
            } catch (RuntimeException | LinkageError ignored) {
                String name = material.name();
                return name.endsWith("_BUCKET") || name.equals("HONEY_BOTTLE") || name.equals("DRAGON_BREATH");
            }
        }
    }
}
