package org.bukkit.event.entity;

import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class FishingParticleEvent extends Event {
    static public HandlerList handlers = new HandlerList();
    public final FishHook fishHook;
    public final Location location;

    public FishingParticleEvent(final FishHook fishHook, final Location location) {
        this.fishHook = fishHook;
        this.location = location;
    }


    @Override
    @NotNull
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
