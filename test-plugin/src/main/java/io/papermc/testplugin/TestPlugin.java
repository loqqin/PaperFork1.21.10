package io.papermc.testplugin;

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
        getServer().getCommandMap().register("test", new Command("test") {
            @Override
            public boolean execute(@NotNull final CommandSender sender, @NotNull final String commandLabel, final @NotNull String @NotNull [] args) {
                for (int i = 0; i < 20; i++) {
                    final int finalI = i;
                    Bukkit.getScheduler().runTaskLater(INSTANCE, () -> {
                        final World world = Bukkit.createWorld(WorldCreator.name(String.valueOf(finalI))
                            .biomeProvider(new BiomeProvider() {
                                @Override
                                public @NotNull Biome getBiome(@NotNull final WorldInfo worldInfo, final int x, final int y, final int z) {
                                    return Biome.THE_VOID;
                                }

                                @Override
                                public @NotNull List<Biome> getBiomes(@NotNull final WorldInfo worldInfo) {
                                    return List.of();
                                }
                            }).generator(new ChunkGenerator() {
                            @Override
                            public void generateNoise(@NotNull final WorldInfo worldInfo, @NotNull final Random random, final int chunkX, final int chunkZ, @NotNull final ChunkData chunkData) {

                            }

                            @Override
                            public @Nullable Location getFixedSpawnLocation(@NotNull final World world, @NotNull final Random random) {
                                return new Location(world, 0, 100, 0);
                            }

                            @Override
                            public boolean canSpawn(@NotNull final World world, final int x, final int z) {
                                return true;
                            }
                        }));
                        ((Player) sender).teleport(world.getSpawnLocation());
                        Bukkit.getScheduler().runTaskLater(INSTANCE, () -> {
                            System.out.println("Bukkit.unloadWorld(world, false) = " + (Bukkit.unloadWorld(world, false)));
                        }, 100);
                    }, i * 20);
                }
                return false;
            }
        });
        // io.papermc.testplugin.brigtests.Registration.registerViaOnEnable(this);
    }
}
