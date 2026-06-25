package io.papermc.testplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugin extends JavaPlugin implements Listener {
  public static TestPlugin INSTANCE;

  @Override
  public void onEnable() {
    INSTANCE = this;
    this.getServer().getPluginManager().registerEvents(this, this);

    // io.papermc.testplugin.brigtests.Registration.registerViaOnEnable(this);
  }

  @EventHandler
  public void interact(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    PlayerInventory inventory = player.getInventory();
    ItemStack itemInMainHand = inventory.getItemInMainHand();
    itemInMainHand.setAmount(itemInMainHand.getAmount() - 1);
    inventory.setItemInMainHand(itemInMainHand);
  }

  @EventHandler
  public void onBlockPlace(BlockPlaceEvent event) {
    event.setCancelled(true);
  }
}
