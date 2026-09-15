package com.mohistmc.youer.bukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftChestBoat;

/**
 * Youer - a concrete wrapper for a modded entity whose closest CraftBukkit type is abstract.
 */
public class YouerModsChestBoat extends CraftChestBoat {

    public YouerModsChestBoat(CraftServer server, net.minecraft.world.entity.vehicle.AbstractChestBoat entity) {
        super(server, entity);
    }
}
