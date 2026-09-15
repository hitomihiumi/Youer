package com.mohistmc.youer.bukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftBoat;

/**
 * Youer - a concrete wrapper for a modded entity whose closest CraftBukkit type is abstract.
 */
public class YouerModsBoat extends CraftBoat {

    public YouerModsBoat(CraftServer server, net.minecraft.world.entity.vehicle.AbstractBoat entity) {
        super(server, entity);
    }
}
