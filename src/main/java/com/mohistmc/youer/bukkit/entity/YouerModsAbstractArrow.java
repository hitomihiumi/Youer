package com.mohistmc.youer.bukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftAbstractArrow;

/**
 * Youer - a concrete wrapper for a modded entity whose closest CraftBukkit type is abstract.
 */
public class YouerModsAbstractArrow extends CraftAbstractArrow {

    public YouerModsAbstractArrow(CraftServer server, net.minecraft.world.entity.projectile.AbstractArrow entity) {
        super(server, entity);
    }
}
