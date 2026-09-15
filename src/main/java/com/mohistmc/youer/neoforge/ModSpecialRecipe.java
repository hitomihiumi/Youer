package com.mohistmc.youer.neoforge;

import com.mohistmc.youer.api.ServerAPI;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftComplexRecipe;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class ModSpecialRecipe extends CraftComplexRecipe {

    private final Recipe<?> recipe;

    public ModSpecialRecipe(NamespacedKey id, Recipe<?> recipe) {
        super(id, new ItemStack(Material.AIR), null);
        this.recipe = recipe;
    }

    @Override
    public @NotNull ItemStack getResult() {
        // Youer - Recipe#getResultItem is gone in 1.21.8; a mod's special recipe assembles its result from
        // the crafting input, so there is no static result to report here
        return new ItemStack(Material.AIR);
    }

    @Override
    public void addToCraftingManager() {
        // Youer - RecipeHolder is keyed by a ResourceKey now, not a ResourceLocation
        ServerAPI.getNMSServer().getRecipeManager().addRecipe(new RecipeHolder<>(org.bukkit.craftbukkit.inventory.CraftRecipe.toMinecraft(this.getKey()), this.recipe));
    }
}
