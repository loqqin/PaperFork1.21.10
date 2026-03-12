package io.papermc.testplugin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.SplashPotion;
import org.bukkit.event.Listener;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Random;

public final class TestPlugin extends JavaPlugin implements Listener {
    public static TestPlugin INSTANCE;

    @Override
    public void onEnable() {
        INSTANCE = this;
        this.getServer().getPluginManager().registerEvents(this, this);
        System.out.println("ServerLoginPacketListenerImpl.disguiseFromTo = " + (ServerLoginPacketListenerImpl.disguiseFromTo));

        // io.papermc.testplugin.brigtests.Registration.registerViaOnEnable(this);
    }
}
