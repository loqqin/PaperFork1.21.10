package io.papermc.paper.console;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@Plugin(name = "CurrentTickConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"tick", "currentTick"})
public final class CurrentTickConverter extends LogEventPatternConverter {
    private static final CurrentTickConverter INSTANCE = new CurrentTickConverter();
    private static volatile VarHandle TICK_HANDLE;

    private CurrentTickConverter() {
        super("currentTick", "currentTick");
    }

    public static CurrentTickConverter newInstance(final String[] options) {
        return INSTANCE;
    }

    private static int getCurrentTick() {
        if (TICK_HANDLE == null) {
            try {
                Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
                TICK_HANDLE = MethodHandles.publicLookup().findStaticVarHandle(serverClass, "currentTick", int.class);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        try {
            return (int) TICK_HANDLE.get();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        toAppendTo.append(getCurrentTick());
    }
}
