package com.mohistmc.youer.bukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftAbstractVillager;

/**
 * Youer - a concrete wrapper for a modded entity whose closest CraftBukkit type is abstract.
 */
public class YouerModsAbstractVillager extends CraftAbstractVillager {

    public YouerModsAbstractVillager(CraftServer server, net.minecraft.world.entity.npc.AbstractVillager entity) {
        super(server, entity);
    }
}
