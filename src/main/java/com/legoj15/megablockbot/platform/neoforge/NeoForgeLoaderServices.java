package com.legoj15.megablockbot.platform.neoforge;

//? if neoforge {
import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.platform.BlockChangeListener;
import com.legoj15.megablockbot.platform.LoaderServices;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
// Used by the bot-only hooks below; kept out of the other nodes' compile so a NeoForge that renames one cannot break them.
//? if bot {
/*import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
*///?}

/**
 * NeoForge's loader services. The server-data and block-change hooks name NeoForge events whose shape changes between
 * Minecraft versions (26.x split TagsUpdatedEvent and replaced BlockEvent.BreakEvent), so they are written and
 * verified for the bot node's version only: every other node gets a stub that throws, which nothing calls because the
 * code that registers listeners lives in the {@code game} package those nodes do not build. When another node becomes a
 * bot node, its build fails here until the hooks are ported.
 */
public final class NeoForgeLoaderServices implements LoaderServices {
    /** The mod's event bus, handed over by {@link NeoForgeEntrypoint} before {@link MegaBlockBot#init()} runs. */
    private IEventBus modBus;

    //? if bot {
    /*// Block changes seen this tick, delivered at its end. Server thread only.
    private final ArrayDeque<Runnable> pendingBlockChanges = new ArrayDeque<>();
    private boolean blockChangeDeliveryRegistered;
    *///?}

    void bindModBus(IEventBus modBus) {
        this.modBus = modBus;
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> listener.accept(event.getServer()));
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> listener.accept(event.getServer()));
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> listener.accept(event.getServer()));
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> listener.accept(event.getDispatcher()));
    }

    @Override
    public void onServerDataLoaded(Consumer<MinecraftServer> listener) {
        //? if bot {
        /*// Two moments, because the data loads at two different times relative to the server object.
        // Start: the data is loaded before the server exists (WorldLoader), so TagsUpdatedEvent cannot hand over a
        // server. ServerStartingEvent is the first moment after that load with a server and its levels.
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> listener.accept(event.getServer()));
        // /reload: MinecraftServer.reloadResources installs the new resources, then posts TagsUpdatedEvent
        // (SERVER_DATA_LOAD) with the server already running. NeoForge's VillagerTradingManager rebuilds the trade
        // lists in a NORMAL-priority listener of that same event, so LOWEST runs after it.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TagsUpdatedEvent.class, event -> {
            if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) listener.accept(server);
        });
        *///?} else {
        throw new UnsupportedOperationException("onServerDataLoaded is implemented for bot nodes only");
        //?}
    }

    // NeoForge 21.1 has no after-the-fact block events: BreakEvent fires before the block goes, EntityPlaceEvent while
    // the placement can still be reverted. Both are taken at LOWEST priority, which skips anything another listener
    // cancelled, and delivered at the end of the tick, when the change has happened.
    @Override
    public void onBlockBroken(BlockChangeListener listener) {
        //? if bot {
        /*registerBlockChangeDelivery();
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BlockEvent.BreakEvent.class, event -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            BlockPos pos = event.getPos().immutable();
            BlockState broken = event.getState();
            pendingBlockChanges.add(() -> listener.changed(level, pos, broken, event.getPlayer()));
        });
        *///?} else {
        throw new UnsupportedOperationException("onBlockBroken is implemented for bot nodes only");
        //?}
    }

    @Override
    public void onBlockPlaced(BlockChangeListener listener) {
        //? if bot {
        /*registerBlockChangeDelivery();
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BlockEvent.EntityPlaceEvent.class, event -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            BlockPos pos = event.getPos().immutable();
            pendingBlockChanges.add(() -> listener.changed(level, pos, level.getBlockState(pos), event.getEntity()));
        });
        *///?} else {
        throw new UnsupportedOperationException("onBlockPlaced is implemented for bot nodes only");
        //?}
    }

    //? if bot {
    /*private void registerBlockChangeDelivery() {
        if (blockChangeDeliveryRegistered) return;
        blockChangeDeliveryRegistered = true;
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            Runnable delivery;
            while ((delivery = pendingBlockChanges.poll()) != null) {
                try {
                    delivery.run();
                } catch (RuntimeException e) {
                    // An observer's bug must not starve the others or take the tick down.
                    MegaBlockBot.LOGGER.error("Block change listener failed", e);
                }
            }
        });
        // Changes queued in the last tick of a server must not leak into the next one.
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> pendingBlockChanges.clear());
    }
    *///?}

    @Override
    public void registerGameTests(Class<?>... holders) {
        if (modBus == null) {
            throw new IllegalStateException("registerGameTests called before NeoForgeEntrypoint bound the mod bus");
        }
        NeoForgeGameTests.register(modBus, holders);
    }
}
//?}
