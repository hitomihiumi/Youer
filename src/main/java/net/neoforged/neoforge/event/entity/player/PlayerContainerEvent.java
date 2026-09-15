/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.event.entity.player;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public class PlayerContainerEvent extends PlayerEvent {
    private final AbstractContainerMenu container;
    // Youer - the reason CraftBukkit should report for the InventoryCloseEvent this leads to
    private final org.bukkit.event.inventory.InventoryCloseEvent.Reason close$Reason;

    public PlayerContainerEvent(Player player, AbstractContainerMenu container) {
        this(player, container, org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNKNOWN);
    }

    // Youer start
    public PlayerContainerEvent(Player player, AbstractContainerMenu container, org.bukkit.event.inventory.InventoryCloseEvent.Reason close$Reason) {
        super(player);
        this.container = container;
        this.close$Reason = close$Reason;
    }

    public org.bukkit.event.inventory.InventoryCloseEvent.Reason getClose$Reason() {
        return this.close$Reason;
    }
    // Youer end

    public static class Open extends PlayerContainerEvent {
        public Open(Player player, AbstractContainerMenu container) {
            super(player, container);
        }
    }

    public static class Close extends PlayerContainerEvent {
        public Close(Player player, AbstractContainerMenu container) {
            super(player, container);
        }

        // Youer
        public Close(Player player, AbstractContainerMenu container, org.bukkit.event.inventory.InventoryCloseEvent.Reason close$Reason) {
            super(player, container, close$Reason);
        }
    }

    public AbstractContainerMenu getContainer() {
        return container;
    }
}
