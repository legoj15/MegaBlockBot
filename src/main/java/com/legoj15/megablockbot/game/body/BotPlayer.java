package com.legoj15.megablockbot.game.body;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.nav.BodyHunger;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BodyInventory;
import com.legoj15.megablockbot.nav.engine.api.entity.IEntityInventory;
import com.legoj15.megablockbot.nav.engine.api.entity.IHungerManager;
import com.legoj15.megablockbot.nav.engine.api.entity.IHungerManagerProvider;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManagerProvider;
import com.legoj15.megablockbot.nav.engine.api.entity.IInventoryProvider;
import com.legoj15.megablockbot.nav.engine.api.entity.IMovementInputReceiver;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;

/**
 * A real {@link ServerPlayer} with no client behind it (design 3.1). Its connection and packet listener swallow
 * everything ({@link BodyConnection}, {@link BodyListener}); it is moved only by its own {@link BodyInput}.
 *
 * <p>Vanilla runs a player's entity tick ({@code doTick}, which is where gravity, {@code aiStep} and {@code travel}
 * live) from the player's connection tick, and then snaps the player back to where the client said it was. A body's
 * connection is never ticked, so {@link #tick()} (which the level calls once per server tick for every player in it)
 * writes the input and calls {@code doTick()} itself.
 */
public class BotPlayer extends ServerPlayer
    implements IMovementInputReceiver, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider {
    /** The compound inside the body's player data that holds everything of ours. */
    static final String SAVE_KEY = "megablockbot";
    private static final int SAVE_SCHEMA = 1;

    private final BodyInput input = new BodyInput();
    private Supplier<BotBrain> brainFactory;
    private BotBrain brain;
    @Nullable
    private UUID ownerId;
    @Nullable
    private String ownerName;
    // Set on the replacement body of a respawn; the brain is told on the replacement's first tick.
    private boolean respawnNoticePending;
    // Non-null only while die() runs: the item entities the death creates, in the order they were made.
    @Nullable
    private List<ItemEntity> deathDrops;
    // The navigation engine's views of this instance's own inventory, food and game mode (see the nav section below).
    private final BodyInventory navInventory = new BodyInventory(getInventory());
    private final BodyHunger navHunger = new BodyHunger(this);
    private final BodyInteractionManager navInteraction = new BodyInteractionManager(this);

    /** The constructor {@code PlayerList.respawn} is redirected to; bodies spawned by the manager use the other one. */
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        this(server, level, profile, info, BotBrains.factory());
    }

    BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info, Supplier<BotBrain> brainFactory) {
        super(server, level, profile, info);
        this.brainFactory = brainFactory;
        this.brain = brainFactory.get();
    }

    public BodyInput input() {
        return input;
    }

    public BotBrain brain() {
        return brain;
    }

    public void setBrain(BotBrain brain) {
        this.brain = java.util.Objects.requireNonNull(brain);
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    @Nullable
    public String ownerName() {
        return ownerName;
    }

    void setOwner(@Nullable UUID id, @Nullable String name) {
        this.ownerId = id;
        this.ownerName = name;
    }

    /** True for the owner, for a permission-level-2 source (ops, command blocks) and for the console (level 4). */
    public boolean mayCommand(CommandSourceStack source) {
        return BodyManager.mayCommand(ownerId, source);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Called by {@code ServerLevel} once per server tick for every player in the level (also through {@code rideTick}
     * for a rider), so the body is ticked exactly once. The order is the contract: the brain writes the input after
     * the vanilla bookkeeping and the input is applied before {@code doTick}, because {@code doTick} is what reads
     * it; applied after, it would act a tick late. Nothing is caught here: an exception is a bug and must show.
     */
    @Override
    public void tick() {
        if (tickCount % BodyConstants.CHUNK_REFRESH_TICKS == 0) {
            // The connection tick would do the first; the chunk tickets of a body that walks only follow it this way.
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
        super.tick();
        if (respawnNoticePending) {
            respawnNoticePending = false;
            brain.onRespawn(this);
        }
        if (!isDeadOrDying()) {
            brain.tick(this);
            input.tickHold();
            input.applyTo(this);
        }
        doTick();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Navigation engine (game.nav.BotNavigator drives it): what the engine reads and how it moves the body
    // ---------------------------------------------------------------------------------------------------------

    /** The engine's movement keys become this body's input, the only way a body moves. */
    @Override
    public void setMovementInput(float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
        input.forward = forward;
        input.strafe = strafe;
        input.jump = jump;
        input.sneak = sneak;
        input.sprint = sprint;
    }

    @Override
    public void setLook(float yaw, float pitch) {
        input.lookAngles(yaw, pitch);
    }

    @Override
    public IEntityInventory getLivingInventory() {
        return navInventory;
    }

    @Override
    public IInteractionManager getInteractionManager() {
        return navInteraction;
    }

    @Override
    public IHungerManager getHungerManager() {
        return navHunger;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fall damage, death, respawn, dimension change
    // ---------------------------------------------------------------------------------------------------------

    /**
     * {@code ServerPlayer} leaves fall damage to the client's movement packets and does nothing here, so a body would
     * fall without ever being hurt. {@code doCheckFallDamage} is the packet path's entry into the real logic.
     */
    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }

    /**
     * Records the death before vanilla drops the inventory, lets vanilla do its death, adds the positions of the items
     * it made, tells the brain and queues the respawn. The body stays logged in: {@code PlayerList.respawn} (with the
     * {@code new ServerPlayer} in it redirected to {@link BotPlayer}) replaces it, as a client's respawn button would.
     */
    @Override
    public void die(DamageSource source) {
        String dimension = level().dimension().location().toString();
        BlockPos where = blockPosition();
        long when = level().getGameTime();
        deathDrops = new ArrayList<>();
        List<ItemEntity> drops;
        try {
            super.die(source);
        } finally {
            drops = deathDrops;
            deathDrops = null;
        }
        // A death listener may veto the death (and heal the body): ServerPlayer.die then returns early and nobody died,
        // so there is nothing to record, nobody to tell and no respawn to queue.
        if (!isDeadOrDying()) return;

        List<BlockPos> dropPositions = new ArrayList<>();
        for (ItemEntity drop : drops) {
            // A mod may veto the drops after they were made (LivingDropsEvent): only count items that reached the level.
            if (serverLevel().getEntity(drop.getUUID()) == null) continue;
            BlockPos at = drop.blockPosition();
            if (!dropPositions.contains(at)) dropPositions.add(at);
        }
        DeathRecord record = new DeathRecord(dimension, where, when, dropPositions);
        brain.onDeath(this, record);
        BodyManager.of(getServer()).onDeath(this, record);
    }

    /** Collects the item entities of a death for the record; vanilla's own behaviour is untouched. */
    @Override
    public ItemEntity drop(ItemStack stack, boolean throwRandomly, boolean retainOwnership) {
        ItemEntity entity = super.drop(stack, throwRandomly, retainOwnership);
        if (entity != null && deathDrops != null) deathDrops.add(entity);
        return entity;
    }

    /**
     * True while this body is dead (or has won the game, which takes it out of the world the same way) and nothing has
     * replaced it yet. {@code isRemoved} covers a respawn that failed halfway: vanilla has by then taken the body out
     * of the level and cleared {@code wonGame}, so the other two no longer say it is waiting.
     */
    boolean isAwaitingRespawn() {
        return connection.player == this && (wonGame || getHealth() <= 0.0F || isRemoved());
    }

    /**
     * Respawns this body through the code path a client's respawn button takes, which also points the listener at the
     * replacement. Run by the manager at the end of a tick, never from inside the damage that caused the death.
     *
     * @return true when nothing is left to do: the body was replaced, or it was not waiting to be. False when the
     *         respawn did not happen, which the manager counts as a failure (a throw from vanilla or a mod's event
     *         handler is the other kind).
     */
    boolean performRespawn() {
        if (!isAwaitingRespawn()) return true;
        connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return connection.player != this;
    }

    /** The new instance inherits the old one's owner and brain; the brain hears about it on the new body's first tick. */
    @Override
    public void restoreFrom(ServerPlayer old, boolean keepEverything) {
        super.restoreFrom(old, keepEverything);
        if (old instanceof BotPlayer previous) {
            ownerId = previous.ownerId;
            ownerName = previous.ownerName;
            brainFactory = previous.brainFactory;
            brain = previous.brain;
            previous.brain = BotBrain.NONE;
            respawnNoticePending = true;
        }
    }

    /**
     * Leaving the End for the first time: vanilla removes the player and waits for the client to ask to respawn,
     * which a body never will, so the manager is asked to do it at the end of the tick.
     */
    @Override
    public void showEndCredits() {
        super.showEndCredits();
        if (wonGame) BodyManager.of(getServer()).queueRespawn(this);
    }

    /**
     * Vanilla marks a player as changing dimension until the client's next movement packet confirms it, and keeps
     * it invulnerable and its portal cooldown frozen meanwhile. No packet will come, so the mark is cleared here.
     */
    @Override
    public Entity changeDimension(DimensionTransition transition) {
        Entity result = super.changeDimension(transition);
        if (isChangingDimension()) hasChangedDimension();
        return result;
    }

    /** Logs the body out through vanilla's normal player removal, which saves it. */
    void logOut(Component reason) {
        connection.disconnect(reason);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag ours = new CompoundTag();
        ours.putInt("schema", SAVE_SCHEMA);
        if (ownerId != null) {
            ours.putUUID("ownerUuid", ownerId);
            if (ownerName != null) ours.putString("ownerName", ownerName);
        }
        CompoundTag brainTag = new CompoundTag();
        try {
            brain.save(brainTag, registryAccess());
            ours.put("brain", brainTag);
        } catch (RuntimeException e) {
            // The rest of the body (inventory, position) is still worth saving.
            MegaBlockBot.LOGGER.error("Brain of body {} failed to save; its state is not written", getGameProfile().getName(), e);
        }
        tag.put(SAVE_KEY, ours);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (!tag.contains(SAVE_KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag ours = tag.getCompound(SAVE_KEY);
        if (ours.hasUUID("ownerUuid")) {
            ownerId = ours.getUUID("ownerUuid");
            ownerName = ours.contains("ownerName", Tag.TAG_STRING) ? ours.getString("ownerName") : null;
        }
        if (!ours.contains("brain", Tag.TAG_COMPOUND)) return;
        try {
            brain.load(ours.getCompound("brain"), registryAccess());
        } catch (RuntimeException e) {
            // A bad save must not stop this body or any other from starting (design 3.4, Persistence).
            MegaBlockBot.LOGGER.error("Brain of body {} could not load its saved state; starting it fresh", getGameProfile().getName(), e);
            brain = brainFactory.get();
        }
    }
}
