package com.mohistmc.youer.bukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftThrownPotion;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * Youer - a concrete wrapper for a modded entity whose closest CraftBukkit type is abstract.
 */
public class YouerModsThrownPotion extends CraftThrownPotion {

    public YouerModsThrownPotion(CraftServer server, net.minecraft.world.entity.projectile.AbstractThrownPotion entity) {
        super(server, entity);
    }

    @Override
    public PotionMeta getPotionMeta() {
        // A modded thrown potion can carry any item, so read the meta off that item instead of
        // assuming one of the two vanilla potion types; fall back to the splash potion layout
        // when the carried item has no potion meta of its own.
        ItemMeta meta = CraftItemStack.getItemMeta(this.getHandle().getItem());
        if (meta instanceof PotionMeta potionMeta) {
            return potionMeta;
        }
        return (PotionMeta) CraftItemStack.getItemMeta(this.getHandle().getItem(), ItemType.SPLASH_POTION);
    }
}
