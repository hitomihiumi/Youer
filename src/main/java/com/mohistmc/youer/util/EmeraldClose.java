package com.mohistmc.youer.util;

import com.mohistmc.youer.YouerConfig;
import java.util.Collection;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.item.Items;

public class EmeraldClose {

    public static void init(LivingEntity livingEntity, Collection<ItemEntity> items) {
        if (!YouerConfig.custom_raid_no_emerald) return;
        if (livingEntity instanceof Vindicator || livingEntity instanceof Evoker) {
            items.removeIf(item -> item.getItem().getItem() == Items.EMERALD);
        }
    }

    /**
     * Youer - 1.21.8 CraftBukkit collects a mob's drops as Paper's DefaultDrop records and spawns them
     * itself after EntityDeathEvent, so the raid drops are filtered on that list instead.
     */
    public static void initDefaultDrops(LivingEntity livingEntity, Collection<net.minecraft.world.entity.Entity.DefaultDrop> drops) {
        if (!YouerConfig.custom_raid_no_emerald) return;
        if (livingEntity instanceof Vindicator || livingEntity instanceof Evoker) {
            drops.removeIf(drop -> drop.item() == Items.EMERALD);
        }
    }
}
