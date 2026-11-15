package com.reversecrafting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
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
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds reverse crafts from vanilla shaped/shapeless recipes and registers
 * real shapeless recipes so Paper 26.x will display a result.
 */
final class ReverseRecipes {

    private final JavaPlugin plugin;
    private final Map<Material, ReverseCraft> byInput = new EnumMap<>(Material.class);
    private final Map<NamespacedKey, ReverseCraft> byKey = new LinkedHashMap<>();
    private final List<NamespacedKey> registered = new ArrayList<>();

    ReverseRecipes(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    int size() {
        return byInput.size();
    }

    void rebuild() {
        unregister();
        byInput.clear();
        byKey.clear();

        Set<Material> vanillaSingleIngredient = EnumSet.noneOf(Material.class);
        List<Recipe> vanilla = new ArrayList<>();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            vanilla.add(recipe);
            Material onlyIngredient = singleIngredient(recipe);
            if (onlyIngredient != null) {
                vanillaSingleIngredient.add(onlyIngredient);
            }
        }

        for (Recipe recipe : vanilla) {
            ReverseCraft craft = ReverseCraft.fromVanilla(recipe);
            if (craft == null) {
                continue;
            }
            byInput.putIfAbsent(craft.input(), craft);
        }

        for (ReverseCraft craft : byInput.values()) {
            if (craft.consumeAmount() != 1 || vanillaSingleIngredient.contains(craft.input())) {
                continue;
            }
            register(craft);
        }
    }

    void unregister() {
        for (NamespacedKey key : registered) {
            try {
                Bukkit.removeRecipe(key);
            } catch (RuntimeException ignored) {
                // Recipe may already be gone during shutdown/reload.
            }
        }
        registered.clear();
    }

    ReverseCraft detect(CraftingInventory inventory) {
        Recipe current = inventory.getRecipe();
        if (current instanceof Keyed keyed) {
            ReverseCraft ours = byKey.get(keyed.getKey());
            if (ours != null) {
                return usable(inventory, ours);
            }
            if (plugin.getName().equalsIgnoreCase(keyed.getKey().getNamespace())) {
                return null;
            }
        }
        if (current != null) {
            return null;
        }
        ItemStack source = singleItem(inventory.getMatrix());
        if (source == null) {
            return null;
        }
        return find(source);
    }

    ReverseCraft find(ItemStack item) {
        if (!isUnmodifiedVanilla(item)) {
            return null;
        }
        ReverseCraft cached = byInput.get(item.getType());
        if (cached != null && item.getAmount() >= cached.consumeAmount()) {
            return cached;
        }
        ReverseCraft best = null;
        int bestCount = -1;
        try {
            for (Recipe recipe : Bukkit.getRecipesFor(new ItemStack(item.getType()))) {
                ReverseCraft craft = ReverseCraft.fromVanilla(recipe);
                if (craft == null || item.getAmount() < craft.consumeAmount()) {
                    continue;
                }
                int count = craft.totalItems();
                if (count > bestCount) {
                    best = craft;
                    bestCount = count;
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.FINE, "Recipe lookup failed for " + item.getType(), ex);
        }
        if (best != null) {
            byInput.putIfAbsent(best.input(), best);
        }
        return best;
    }

    boolean isOurs(Recipe recipe) {
        return recipe instanceof Keyed keyed && byKey.containsKey(keyed.getKey());
    }

    private ReverseCraft usable(CraftingInventory inventory, ReverseCraft craft) {
        ItemStack source = singleItem(inventory.getMatrix());
        if (source == null || source.getType() != craft.input() || !isUnmodifiedVanilla(source)) {
            return null;
        }
        if (source.getAmount() < craft.consumeAmount()) {
            return null;
        }
        return craft;
    }

    private void register(ReverseCraft craft) {
        NamespacedKey key = new NamespacedKey(plugin, "uncraft_" + craft.input().name().toLowerCase(Locale.ROOT));
        try {
            ShapelessRecipe recipe = new ShapelessRecipe(key, craft.preview());
            recipe.addIngredient(craft.input());
            if (!Bukkit.addRecipe(recipe)) {
                return;
            }
            registered.add(key);
            byKey.put(key, craft);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not register reverse recipe for " + craft.input(), ex);
        }
    }

    private static Material singleIngredient(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed) || !NamespacedKey.MINECRAFT.equals(keyed.getKey().getNamespace())) {
            return null;
        }
        LinkedHashMap<Material, Integer> counts = new LinkedHashMap<>();
        if (!collectIngredients(recipe, counts)) {
            return null;
        }
        if (counts.size() != 1) {
            return null;
        }
        Map.Entry<Material, Integer> entry = counts.entrySet().iterator().next();
        return entry.getValue() == 1 ? entry.getKey() : null;
    }

    private static boolean collectIngredients(Recipe recipe, Map<Material, Integer> counts) {
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (int i = 0; i < row.length(); i++) {
                    if (!addChoice(counts, choices.get(row.charAt(i)), true)) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                if (!addChoice(counts, choice, false)) {
                    return false;
                }
            }
            return true;
        }
        return false;
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
            if (slot.getType() != craft.input() || !isUnmodifiedVanilla(slot) || slot.getAmount() < amount) {
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
        if (item == null) {
            return true;
        }
        try {
            return item.isEmpty();
        } catch (NoSuchMethodError ignored) {
            return item.getType().isAir() || item.getAmount() <= 0;
        }
    }

    static boolean isUnmodifiedVanilla(ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }
        ItemStack baseline = new ItemStack(item.getType());
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        baseline.setAmount(1);
        if (normalized.isSimilar(baseline)) {
            return true;
        }
        if (!item.getEnchantments().isEmpty()) {
            return false;
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
        if (hasItemName(meta) || !meta.getPersistentDataContainer().getKeys().isEmpty()) {
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

    private static boolean hasItemName(ItemMeta meta) {
        try {
            Object value = ItemMeta.class.getMethod("hasItemName").invoke(meta);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean addChoice(Map<Material, Integer> counts, RecipeChoice choice, boolean allowEmpty) {
        if (choice == null || isEmptyChoice(choice)) {
            return allowEmpty;
        }
        Material material = resolve(choice);
        if (material == null || material.isAir()) {
            return allowEmpty;
        }
        if (!isItem(material) || hasCraftingRemainder(material)) {
            return false;
        }
        counts.merge(material, 1, Integer::sum);
        return true;
    }

    private static boolean isEmptyChoice(RecipeChoice choice) {
        try {
            ItemStack stack = choice.getItemStack();
            return isEmpty(stack);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Material resolve(RecipeChoice choice) {
        if (choice == null) {
            return null;
        }
        Material fromItemTypes = materialFromItemTypes(choice);
        if (fromItemTypes != null) {
            return fromItemTypes;
        }
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            try {
                List<Material> options = materialChoice.getChoices();
                if (!options.isEmpty()) {
                    return options.get(0);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Fall through to getItemStack().
            }
        }
        if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            List<ItemStack> options = exactChoice.getChoices();
            if (options.size() != 1) {
                return null;
            }
            ItemStack stack = options.get(0);
            return isEmpty(stack) ? null : stack.getType();
        }
        try {
            ItemStack stack = choice.getItemStack();
            return isEmpty(stack) ? null : stack.getType();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Material materialFromItemTypes(RecipeChoice choice) {
        try {
            Object types = choice.getClass().getMethod("itemTypes").invoke(choice);
            if (!(types instanceof Iterable<?> iterable)) {
                return null;
            }
            for (Object itemType : iterable) {
                Material material = materialFrom(itemType);
                if (material != null && !material.isAir()) {
                    return material;
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static Material materialFrom(Object itemType) {
        if (itemType instanceof Material material) {
            return material;
        }
        try {
            Object asMaterial = itemType.getClass().getMethod("asMaterial").invoke(itemType);
            if (asMaterial instanceof Material material) {
                return material;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Try key-based matching next.
        }
        try {
            Object key = itemType.getClass().getMethod("key").invoke(itemType);
            if (key != null) {
                Material matched = Material.matchMaterial(key.toString());
                if (matched != null) {
                    return matched;
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
        return null;
    }

    private static boolean isItem(Material material) {
        try {
            return material.isItem();
        } catch (RuntimeException | LinkageError ignored) {
            return true;
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

        int totalItems() {
            int total = 0;
            for (ItemStack reward : rewards) {
                total += reward.getAmount();
            }
            return total;
        }

        List<ItemStack> rewards() {
            List<ItemStack> clone = new ArrayList<>(rewards.size());
            for (ItemStack reward : rewards) {
                clone.add(reward.clone());
            }
            return clone;
        }

        ItemStack preview() {
            if (rewards.size() == 1) {
                return rewards.get(0).clone();
            }
            ItemStack bundle = bundlePreview();
            return bundle != null ? bundle : rewards.get(0).clone();
        }

        private ItemStack bundlePreview() {
            try {
                ItemStack bundle = new ItemStack(Material.BUNDLE);
                ItemMeta meta = bundle.getItemMeta();
                if (!(meta instanceof BundleMeta bundleMeta)) {
                    return null;
                }
                List<ItemStack> items = new ArrayList<>();
                for (ItemStack reward : rewards) {
                    items.add(reward.clone());
                }
                bundleMeta.setItems(items);
                bundle.setItemMeta(bundleMeta);
                ItemMeta applied = bundle.getItemMeta();
                if (applied instanceof BundleMeta check && check.hasItems()) {
                    return bundle;
                }
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
            return null;
        }

        static ReverseCraft fromVanilla(Recipe recipe) {
            if (!(recipe instanceof Keyed keyed) || !NamespacedKey.MINECRAFT.equals(keyed.getKey().getNamespace())) {
                return null;
            }
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
                return null;
            }
            ItemStack result = recipe.getResult();
            if (isEmpty(result)) {
                return null;
            }
            LinkedHashMap<Material, Integer> counts = new LinkedHashMap<>();
            if (!collectIngredients(recipe, counts) || counts.isEmpty()) {
                return null;
            }
            List<ItemStack> rewards = new ArrayList<>();
            for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
                rewards.add(new ItemStack(entry.getKey(), entry.getValue()));
            }
            return new ReverseCraft(result.getType(), Math.max(1, result.getAmount()), rewards);
        }
    }
}
