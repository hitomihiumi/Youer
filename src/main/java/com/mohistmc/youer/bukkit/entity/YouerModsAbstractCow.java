package com.mohistmc.youer.bukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftAbstractCow;

/**
 * Youer - a concrete wrapper for a modded entity whose closest CraftBukkit type is abstract.
 */
public class YouerModsAbstractCow extends CraftAbstractCow {

    public YouerModsAbstractCow(CraftServer server, net.minecraft.world.entity.animal.AbstractCow entity) {
        super(server, entity);
    }
}
