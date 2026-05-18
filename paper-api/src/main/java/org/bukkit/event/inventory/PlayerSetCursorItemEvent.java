package org.bukkit.event.inventory;

import net.minecraft.world.item.ItemStack;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerSetCursorItemEvent extends Event {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    public org.bukkit.inventory.ItemStack oldItem;
    public org.bukkit.inventory.ItemStack newItem;

    public PlayerSetCursorItemEvent(final org.bukkit.inventory.ItemStack oldItem, final org.bukkit.inventory.ItemStack newItem) {
        this.oldItem = oldItem;
        this.newItem = newItem;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
