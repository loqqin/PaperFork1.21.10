package org.bukkit.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class MoveTowardsClosestSpaceEvent extends Event implements Cancellable {
  public static HandlerList handlers = new HandlerList();
  public boolean cancelled = false;

  public final Entity entity;

  public MoveTowardsClosestSpaceEvent(Entity entity) {
    this.entity = entity;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return handlers;
  }

  @NotNull
  public static HandlerList getHandlerList() {
    return handlers;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    this.cancelled = cancel;
  }
}
