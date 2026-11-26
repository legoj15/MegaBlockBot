# Body reference: client-less server-side player

Scope: AI-PLAYER-DESIGN.md §2.1 (Body row) and §3.1. Scout findings for implementers. Every fact carries a path:line into the source named by its prefix. Anything not checked is marked UNVERIFIED.

## 0. Sources, revisions, and how they were checked

| Prefix | Repo and revision | Notes |
|---|---|---|
| `carpet@9940feb7:` | E:/GitHub/fabric-carpet, commit 9940feb7 "update to 1.21.1" (tag 1.4.147) | Only commit where `gradle.properties` has `minecraft_version=1.21.1` (`git log -G`); the next change, d11586fd, is 24w33a. Read with `git show`. Build uses `loom.officialMojangMappings()` (Mojang names). |
| `pe@2fad4d3^:` | E:/GitHub/PlayerEngine, parent of 2fad4d3 | The HEAD of `1.21.1-arch` contains no fake-player class. The last one, `FakeServerPlayerEntity`, was deleted in 2fad4d3 (2025-07-22, "Removed fakeplayers, replaced with zombie entity"). That tree is Yarn/Quilt on MC 1.20.1 (`gradle.properties` at 2fad4d3^). |
| `aibot@a029fa6a:` | E:/GitHub/mc_aiplayer, HEAD a029fa6a (= working tree) | Targets MC 1.21.3, Yarn 1.21.3+build.2 (`gradle.properties`). Comments were checked against the code. Runtime behaviour on 1.21.3 is UNVERIFIED (no 1.21.3 jar in the Loom cache). |
| `vanilla-1.21.1` | `~/.gradle/caches/fabric-loom/1.21.1/` | Name map: `layered/loom.mappings-layered+hash.288375958.jar`, entry `mappings/mappings.tiny`, tiny v2 columns `intermediary, named(=Mojang), official`. Bytecode: `javap -p -c -cp minecraft-merged.jar <official>` (obfuscated names, mapped through the same file). Yarn cross-check: `net.fabricmc.yarn.1_21_1.1.21.1+build.3-v2/mappings.tiny`. |

Mapping caveat: the tiny file lists a method under the class that first declares it. Overrides carry the root name. So `ServerPlayer.tick()` is present in the bytecode but not in the name list; its name is the root `Entity.tick` (official `l`).

## 1. Files and symbols

### Carpet (`carpet@9940feb7:src/main/java/carpet/...`)

- `patches/EntityPlayerMPFake.java:43` class `extends ServerPlayer`. `isAShadow` field at :46. `fixStartingPosition` Runnable field at :45.
- `patches/EntityPlayerMPFake.java:49` `static boolean createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying)`. Returns `true` before the spawn runs (see §2).
- `patches/EntityPlayerMPFake.java:98` `static createShadow(MinecraftServer, ServerPlayer)`. Replaces a real player's session with a fake one (duplicate-login path).
- `patches/EntityPlayerMPFake.java:124` `static respawnFake(MinecraftServer, ServerLevel, GameProfile, ClientInformation)`.
- `patches/EntityPlayerMPFake.java:129` private ctor `(MinecraftServer, ServerLevel, GameProfile, ClientInformation, boolean shadow)`; calls `super(server, worldIn, profile, cli)`.
- `patches/EntityPlayerMPFake.java:135` `onEquipItem(EquipmentSlot, ItemStack, ItemStack)`: skips `super` while `isUsingItem()`.
- `patches/EntityPlayerMPFake.java:141` `kill()` and :147 `kill(Component)`: `shakeOff()`, then `connection.onDisconnect(new DisconnectionDetails(reason))`, directly for duplicate_login, else scheduled via `server.tell(new TickTask(...))`.
- `patches/EntityPlayerMPFake.java:160` `tick()` (quoted in §2).
- `patches/EntityPlayerMPFake.java:182` `shakeOff()`: dismounts Player riders.
- `patches/EntityPlayerMPFake.java:191` `die(DamageSource)`: `shakeOff`, `super.die`, `setHealth(20)`, `foodData = new FoodData()`, `kill(combatTracker.getDeathMessage())`.
- `patches/EntityPlayerMPFake.java:201` `getIpAddress()` returns `"127.0.0.1"`.
- `patches/EntityPlayerMPFake.java:207` `allowsListing()` returns `CarpetSettings.allowListingFakePlayers`.
- `patches/EntityPlayerMPFake.java:212` `checkFallDamage(double y, boolean onGround, BlockState, BlockPos)` calls `doCheckFallDamage(0.0, y, 0.0, onGround)`.
- `patches/EntityPlayerMPFake.java:217` `changeDimension(DimensionTransition)`: calls super; if `wonGame`, performs `PERFORM_RESPAWN` through `connection.handleClientCommand`; returns `connection.player`.
- `patches/FakeClientConnection.java:10` `extends Connection`. :12 ctor `(PacketFlow)`. :17 `setChannel(new EmbeddedChannel())`. Overrides: :21 `setReadOnly()` (no-op), :26 `handleDisconnection()` (no-op), :31 `setListenerForServerboundHandshake` (no-op), :36 `setupInboundProtocol(ProtocolInfo, T)` (no-op). It does NOT override `send`.
- `patches/NetHandlerPlayServerFake.java:14` `extends ServerGamePacketListenerImpl`. :16 ctor `(MinecraftServer, Connection, ServerPlayer, CommonListenerCookie)`. :22 `send(Packet)` no-op. :27 `disconnect(Component)`: only for `multiplayer.disconnect.idling` and `duplicate_login`, calls `kill(message)`. :36 `teleport(...)`: `super.teleport`, then `resetPosition()` and `serverLevel().getChunkSource().move(player)` if the player is still registered.
- `helpers/EntityPlayerActionPack.java:35` class. :37 field `ServerPlayer player`. :53 ctor `(ServerPlayer)`, calls `stopAll()`. :74 `start(ActionType, Action)`. :86 `setSneaking(boolean)`. :94 `setSprinting(boolean)`. :103 `setForward(float)`, :108 `setStrafing(float)`. :113 `look(Direction)`. :130 `look(float yaw, float pitch)`. :138 `lookAt(Vec3)`. :154 `stopMovement()`. :164 `stopAll()`. :213 `onUpdate()` (quoted in §2). :288 `enum ActionType`: USE :290, ATTACK :369, JUMP :464, DROP_ITEM :486, DROP_STACK :496. :535 `static class Action`. :574 `Boolean tick(EntityPlayerActionPack, ActionType)`. :613 `retry`.
- `fakes/ServerPlayerInterface.java:5` `getActionPack()`, `invalidateEntityObjectReference()`, `isInvalidEntityObject()`.
- `mixins/ServerPlayer_actionPackMixin.java:16` `@Mixin(ServerPlayer)`, implements `ServerPlayerInterface`. :27 `@Inject(method="<init>", at=RETURN)` creates the action pack. :33 `@Inject(method="tick", at=HEAD)` calls `actionPack.onUpdate()`.
- `mixins/PlayerList_fakePlayersMixin.java:23` `@Mixin(PlayerList)`. :30 `@Inject(method="load", at=@At(RETURN, shift=BEFORE))` calls `fixStartingPosition.run()` for fakes. :39 `@Redirect(method="placeNewPlayer", at=@At(NEW, ...ServerGamePacketListenerImpl.<init>))` returns `NetHandlerPlayServerFake` for fakes. :52 `@Redirect(method="respawn", at=@At(NEW, ...ServerPlayer.<init>))` returns `EntityPlayerMPFake.respawnFake(...)` for fakes.
- `mixins/PlayerList_coreMixin.java:15` `@Mixin(PlayerList)`. :19 `placeNewPlayer` RETURN calls `CarpetServer.onPlayerLoggedIn`. :25 `sendLevelInfo` RETURN calls `ServerNetworkHandler.sendPlayerLevelData`.
- `mixins/ServerGamePacketListenerImpl_coreMixin.java:29` `@Inject(method="onDisconnect", at=HEAD)` calls `CarpetServer.onPlayerLoggedOut`.
- `mixins/Player_fakePlayersMixin.java:10` `@Mixin(Player)`. :16 `@Redirect` on `Player.attack`'s read of `Entity.hurtMarked` (:24): fakes are excluded from the velocity-modified check, so attacks knock them back.
- `mixins/EntityMixin.java:35` `@Inject(method="isControlledByLocalInstance", at=HEAD)`: returns `!level.isClientSide` when the controlling passenger is an `EntityPlayerMPFake`. Used for riding fakes.
- `mixins/ChunkMap_creativePlayersLoadChunksMixin.java:14` `@Inject(method="skipPlayer(ServerPlayer)Z", at=HEAD, cancellable)`: returns true (skip) for creative players unless `CarpetSettings.creativePlayersLoadChunks`.
- `mixins/Connection_packetCounterMixin.java:38` `@Accessor public abstract void setChannel(Channel)` on `Connection` (the adventure-platform compat). Packet counters at :23 and :30.
- `mixins/DistanceManager_spawnChunksMixin.java:17` `@Mixin(DistanceManager)`, :27 `changeSpawnChunks(ChunkPos, int)`. World spawn ticket only. Not body-specific.
- Client-only, not needed by the body: `mixins/PlayerTabOverlayMixin.java`, `mixins/Gui_tablistMixin.java`.
- Sleep: a grep of all of `carpet@9940feb7:src/main/java/carpet/mixins/` for sleep-related names finds no sleeping mixin (Player_parrotMixin, Villager_aiMixin and a scarpet mixin match only incidentally). Carpet does not touch sleep counting.

### PlayerEngine (`pe@2fad4d3^:`)

- `src/api/java/baritone/api/fakeplayer/FakeServerPlayerEntity.java:72` `class FakeServerPlayerEntity extends ServerPlayerEntity implements AutomatoneFakePlayer, IInventoryProvider, IInteractionManagerProvider` (Yarn names).
- Same file :85-91 ctor `(EntityType, ServerWorld, GameProfile)`: :88 `setStepHeight(0.6f)`, :90 `new ServerPlayNetworkHandler(world.getServer(), new ClientConnection(NetworkSide.S2C), this)`. The stub is a server-to-client `ClientConnection` and is not registered anywhere.
- Same file :146 `tick()`: `manager.update(); inventory.updateItems(); closeHandledScreen(); super.tick(); playerTick();`.
- :155 `tickMovement()` (sinks when sneaking in water). :186 `fall(...)` calls `handleFall(0, heightDifference, 0, onGround)`. :191 `canResetTimeBySleeping()` returns true, with comment "Fake players do not delay the sleep of other players". :201 `isPlayer()` returns false, with a comment about ticking and tracking.
- `src/main/java/baritone/utils/InputOverrideHandler.java:102-127` (same tree): sets `entity.sidewaysSpeed`, `entity.forwardSpeed` and `entity.setJumping(...)` each tick. This is how the Automatone body moved. Verified on 1.20.1 Yarn only.
- Current replacement (HEAD of `1.21.1-arch`): `libs/player2npc-neoforge-1.21.1-1.0.0.jar` class `com/goodbird/player2npc/companion/AutomatoneEntity` extends `net.minecraft.world.entity.LivingEntity` (javap). The jar is a binary dependency. Its source is not in the repo.
- Git note: `git ls-tree 1.21.1-arch` has no `AutomatoneEntity.java`.

### AIBot (`aibot@a029fa6a:src/main/java/io/github/zoyluo/aibot/...`)

- `entity/AIPlayerEntity.java:11` `class AIPlayerEntity extends ServerPlayerEntity`. :12 `private final ActionPack actionPack = new ActionPack(this)`. :14-19 ctor `(MinecraftServer, ServerWorld, GameProfile, SyncedClientOptions)`, calls `super`.
- :21-35 `tick()`. Order: :23-26 every 10 ticks `networkHandler.syncWithPlayerPosition()` and `getServerWorld().getChunkManager().updatePosition(this)`; :29 `super.tick()`; :30 `playerTick()`; :31 `actionPack.onUpdate()`; :32-34 `catch (NullPointerException)` logs and swallows.
- :38-40 `getIp()` returns `"127.0.0.1"`. :42-44 `reviveForAIBotSpawn()` calls `unsetRemoved()`. :46 `getActionPack()`.
- `manager/AIPlayerManager.java:126-142` spawn sequence: :126 `new AIPlayerEntity(...)`; :127 `new FakeClientConnection(NetworkSide.SERVERBOUND)`; :128 `new ConnectedClientData(profile, 0, options, false)`; :131 `server.getPlayerManager().onPlayerConnect(connection, player, clientData)`; :132 `player.teleport(world, x, y, z, Collections.emptySet(), yaw, pitch, true)`; :133 `setHealth(20.0F)`; :134 `reviveForAIBotSpawn()`; :135-137 `STEP_HEIGHT` base value 0.6; :142 `interactionManager.changeGameMode(SURVIVAL)`. Teleport happens after `onPlayerConnect`, as in Carpet.
- `network/FakeClientConnection.java:16` `extends ClientConnection`. :19-22 ctor calls `super(side)` then `aibot$setChannel(new EmbeddedChannel())` (:21). Overrides: :25 `transitionInbound` (no-op), :29 `transitionOutbound` (no-op), :33 `setInitialPacketListener` (no-op), :37 `send(Packet)` (no-op), :41 `send(Packet, PacketCallbacks)` (calls `onSuccess`), :48 `send(Packet, PacketCallbacks, boolean)` (calls `onSuccess`), :55 and :59 `disconnect(...)` (no-op), :63 `handleDisconnection()` (no-op), :67 `getAddress()` returns a loopback `InetSocketAddress`.
- `network/AINetworkHandler.java:13` `extends ServerPlayNetworkHandler`. :22 `sendPacket(Packet)` (no-op). :26 `send(Packet, PacketCallbacks)` (calls `onSuccess`). :33 and :37 `disconnect(...)` (no-op).
- `mixin/PlayerManagerFakePlayerMixin.java:17` `@Mixin(PlayerManager)`. :23 `@Redirect` on `onPlayerConnect`, NEW `ServerPlayNetworkHandler.<init>`, returns `AINetworkHandler` when the player is an `AIPlayerEntity`.
- `mixin/ClientConnectionAccessorMixin.java:9` `@Mixin(ClientConnection)`. :12 `@Accessor("channel") aibot$setChannel(Channel)`.
- `mixin/ServerTickProbeMixin.java:14` `@Mixin(MinecraftServer)`. :19 `@Inject tick(BooleanSupplier) HEAD`. Logs every 1200 ticks. Not body-related.
- `mixin/MerchantEntityInvokerMixin.java:8` `@Mixin(MerchantEntity)`. :10 `@Invoker("afterUsing")`. Trading only.
- `mixin/ServerWorldEntityManagerAccessorMixin.java:10` `@Mixin(ServerWorld)`. :12 `@Accessor("entityManager")`. Collision checks, not body-related.
- `mixin/ServerEntityManagerCacheAccessorMixin.java:10` `@Mixin(ServerEntityManager)`. :12 `@Accessor("cache")`. Not body-related.
- `resources/aibot.mixins.json`: lists the six mixins above.
- `action/ActionPack.java:430-437` javadoc of `descendInto(BlockPos)` (quoted in §5). :502 `onUpdate()`, :515-518 writes `player.forwardSpeed = forward * velocity`, `player.sidewaysSpeed = strafing * velocity`, then `player.setJumping(jumping || jumpTicks > 0)`. Other reset paths (:447-448, :460-466, :556-559, :580-583) zero the same fields.
- `mode/FakePlayerMotion.java`: :41 `stepTo` (teleport :74); :81 `stepToStandable`; :105 `swimStepTo` (comment :101-104 and :116-117; teleport :120); :137 `shiftToSupportEdge` (teleport :171); :182 `returnToBlockCenter` (teleport :212); :243 `nudgeWithinBlockToward` (teleport :277); :291 `jumpTo` (comment :298-299; teleport :355).
- `action/HarvestCore.java:275` comment "Server-side fake players receive no client gravity."
- `task/NavSafetyNet.java:175-177` comment "Server-side fake players do not execute client-authored travel, so merely setting forward/jump leaves them motionless."
- `pathfinding/Standability.java:120` comment "Clientless fake players cannot execute normal swimming travel."

### Design doc (`E:/GitHub/MegaBlockBot/AI-PLAYER-DESIGN.md`)

- §2 table row for PlayerEngine says Architectury `1.21.1-arch` branch; the fake-player class it cites is gone at HEAD (see PlayerEngine above).
- §2.1 Body row and §3.1 are consistent with the code on the points listed in §5 below.

## 2. Carpet, quoted

`createFake` (`carpet@9940feb7:src/main/java/carpet/patches/EntityPlayerMPFake.java:49-91`):

```
ServerLevel worldIn = server.getLevel(dimensionId);            // :52
GameProfileCache.setUsesAuthentication(false);                 // :53
... gameprofile = server.getProfileCache().get(username).orElse(null) ...   // :55-60 (restores auth in finally)
if (gameprofile == null) { if (!CarpetSettings.allowSpawningOfflinePlayers) return false;   // :61-69
    gameprofile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(username), username); }
GameProfile finalGP = gameprofile;
fetchGameProfile(gameprofile.getName()).thenAcceptAsync(p -> {  // :71  SkullBlockEntity.fetchGameProfile, async
    GameProfile current = finalGP; if (p.isPresent()) current = p.get();         // :72-76
    EntityPlayerMPFake instance = new EntityPlayerMPFake(server, worldIn, current, ClientInformation.createDefault(), false);  // :77
    instance.fixStartingPosition = () -> instance.moveTo(pos.x, pos.y, pos.z, (float) yaw, (float) pitch);  // :78
    server.getPlayerList().placeNewPlayer(new FakeClientConnection(PacketFlow.SERVERBOUND), instance,
        new CommonListenerCookie(current, 0, instance.clientInformation(), false));  // :79
    instance.teleportTo(worldIn, pos.x, pos.y, pos.z, (float) yaw, (float) pitch);   // :80
    instance.setHealth(20.0F);                                                         // :81
    instance.unsetRemoved();                                                           // :82
    instance.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);                 // :83
    instance.gameMode.changeGameModeForPlayer(gamemode);                              // :84
    server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)), dimensionId); // :85
    server.getPlayerList().broadcastAll(new ClientboundTeleportEntityPacket(instance), dimensionId);   // :86
    instance.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);             // :88 all skin layers
    instance.getAbilities().flying = flying;                                          // :89
}, server);                                                                           // :90
return true;                                                                          // :91
```

Consequences: `createFake` returns `true` before the spawn happens. Any failure inside the async block is silent. The `fixStartingPosition` runnable (`PlayerList.load` RETURN-BEFORE inject, `PlayerList_fakePlayersMixin.java:30`) re-applies the requested position after NBT load, so saved coordinates do not win on login.

`tick` (`carpet@9940feb7:src/main/java/carpet/patches/EntityPlayerMPFake.java:160-180`):

```
@Override
public void tick()
{
    if (this.getServer().getTickCount() % 10 == 0)
    {
        this.connection.resetPosition();                 // :165  ServerGamePacketListenerImpl.resetPosition
        this.serverLevel().getChunkSource().move(this);  // :166  ServerChunkCache.move(player): chunk tickets
    }
    try
    {
        super.tick();    // :170  ServerPlayer.tick: bookkeeping; ActionPack.onUpdate runs first (mixin HEAD inject)
        this.doTick();   // :171  ServerPlayer.doTick: the player entity tick (Player.tick -> LivingEntity.tick -> aiStep -> travel) + client sync
    }
    catch (NullPointerException ignored)
    {
        // happens with that paper port thingy - not sure what that would fix, but hey
        // the game not gonna crash violently.
    }
}
```

Why `doTick()` must be called by hand: `ServerGamePacketListenerImpl.tick` calls `player.doTick()` (vanilla bytecode, `aru.d`, offset 78), and the server's connection list only ticks registered connections (`ServerConnectionListener.tick`, bytecode loops over field `connections` and calls `Connection.tick`). `placeNewPlayer` never adds the fake's connection to that list. So nothing else runs the player's entity tick for a fake. Vanilla behaviour in §4 has the details.

`EntityPlayerActionPack.onUpdate` movement part (`carpet@9940feb7:src/main/java/carpet/helpers/EntityPlayerActionPack.java:241-248`):

```
float vel = sneaking?0.3F:1.0F;
// The != 0.0F checks are needed given else real players can't control minecarts, however it works with fakes and else they don't stop immediately
if (forward != 0.0F || player instanceof EntityPlayerMPFake) {
    player.zza = forward * vel;
}
if (strafing != 0.0F || player instanceof EntityPlayerMPFake) {
    player.xxa = strafing * vel;
}
```

`onUpdate` runs per tick from the action pack's `ActionType` loop (:217-240). It is injected at `ServerPlayer.tick` HEAD by `ServerPlayer_actionPackMixin.java:33-37`, so it runs before `doTick()` each tick. Other per-tick writes:

- `setSneaking` (:86-93): `player.setShiftKeyDown(doSneak)`, and clears sprint if both are set.
- `setSprinting` (:94-101): `player.setSprinting(doSprint)`.
- JUMP (:464-485): `limit == 1` calls `player.jumpFromGround()` when `onGround()` (:471). Otherwise `player.setJumping(true)` (:475). `inactiveTick` calls `setJumping(false)` (:483).
- `look(yaw,pitch)` (:130-136): `setYRot(yaw % 360)`, `setXRot(clamp(pitch))`. `lookAt(Vec3)` (:138-142): `player.lookAt(EntityAnchorArgument.Anchor.EYES, position)`.

Attack and use, per tick (`carpet@9940feb7:src/main/java/carpet/helpers/EntityPlayerActionPack.java`):

- USE (:290-368): with cooldown `itemUseCooldown` (set to 3 after a successful action, :323/:339/:345/:354). Block target: `pos.getY() < level.getMaxBuildHeight() - ...` and `world.mayInteract(player,pos)`, then `player.gameMode.useItemOn(player, world, player.getItemInHand(hand), hand, blockHit)` (:319). Entity target: `entity.interactAt(...)` then `player.interactOn(entity, hand)` (:337, :343). Air: `player.gameMode.useItem(...)` (:352). `inactiveTick` calls `player.releaseUsingItem()` (:366).
- ATTACK, entity (:374-384): `player.attack(entity)` and `swing` when not continuous; `resetAttackStrengthTicker()`.
- ATTACK, block (:385-449). Creative (:403-408): `gameMode.handleBlockBreakAction(pos, START_DESTROY_BLOCK, side, maxBuildHeight, -1)`, `blockHitDelay = 5`, broken. Survival (:409-431): if the target changed, ABORT the old block and START the new one (:413, :415). When `curBlockDamageMP == 0`, call `state.attack(level, pos, player)` (:419). If `state.getDestroyProgress(player, level, pos) >= 1`, the block is instant (:421-426). Otherwise `currentBlock = pos` and progress starts at 0. Continuing on the same block (:433-445): `curBlockDamageMP += state.getDestroyProgress(...)` (:435). At `>= 1`, call STOP_DESTROY_BLOCK (:438), clear `currentBlock`, set `blockHitDelay = 5`, mark broken. Otherwise `level.destroyBlockProgress(-1, pos, (int)(curBlockDamageMP*10))` (:443). Each attack tick also calls `resetLastActionTime()` and `swing(MAIN_HAND)`.
- ATTACK `inactiveTick` (:455-462): when the action stops, `destroyBlockProgress(-1, ...)` and ABORT_DESTROY_BLOCK.

`NetHandlerPlayServerFake.send` is a no-op (:22-24). `FakeClientConnection` does not override any `send` method, so a packet that reaches `Connection.sendPacket` goes to the `EmbeddedChannel`. That channel is never read. See the adjacent issues in §5.

## 3. Recipe for NeoForge 1.21.1 (Mojang names)

Each step is marked PLAIN (subclass or override only) or MIXIN/AT (needs a mixin or access transformer). Steps are ordered as they must run.

1. PLAIN. Subclass `ServerPlayer` with ctor `(MinecraftServer, ServerLevel, GameProfile, ClientInformation)`. This is the same signature `PlayerList.respawn` calls. Carpet uses a private ctor with a `shadow` flag, which is optional.
2. AT or MIXIN. Make `Connection.channel` reachable, or add an `@Accessor` like `Connection_packetCounterMixin.java:38`. Carpet needs it for `new EmbeddedChannel()`, which makes `Connection.isConnected()` true so ender pearls work. Without it the connection has no channel.
3. PLAIN. Subclass `Connection(PacketFlow.SERVERBOUND)`. Override `setReadOnly()`, `handleDisconnection()`, `setListenerForServerboundHandshake(PacketListener)` and `setupInboundProtocol(ProtocolInfo, T)` as no-ops (Carpet's `FakeClientConnection.java:21-38`). Also consider overriding `send(Packet)`, the three-argument send with callbacks and `flush` (Mojang signature not checked), and `disconnect(...)` on the connection, as AIBot does (`FakeClientConnection.java:37-63`), so nothing reaches the channel.
4. MIXIN (required). `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` constructs `ServerGamePacketListenerImpl` internally. Redirect that `NEW` to a stub listener for your type (Carpet `PlayerList_fakePlayersMixin.java:39`, AIBot `PlayerManagerFakePlayerMixin.java:23`). A plain override cannot reach it. The alternative is to swap `player.connection` after the call, which leaves the vanilla join packets sent through the real listener. That option is UNVERIFIED.
5. PLAIN. Stub listener subclass of `ServerGamePacketListenerImpl(MinecraftServer, Connection, ServerPlayer, CommonListenerCookie)`. Override `send(Packet)` as a no-op (Carpet `NetHandlerPlayServerFake.java:22`). Override `disconnect(Component)` to call your own `kill` for idle and duplicate-login reasons (:27). Override `teleport(...)` to call `resetPosition()` and `getChunkSource().move(player)` (:36).
6. MIXIN (required). `PlayerList.load(ServerPlayer)` returns the NBT. Inject RETURN-BEFORE (Carpet `PlayerList_fakePlayersMixin.java:30`) to re-apply your spawn position, or the saved one wins.
7. PLAIN. In your spawn method (`createFake` equivalent): resolve the profile. Carpet uses `GameProfileCache` with authentication off, then `SkullBlockEntity.fetchGameProfile` async, then `thenAcceptAsync(..., server)` (`EntityPlayerMPFake.java:52-90`). Offline UUIDs use `UUIDUtil.createOfflinePlayerUUID(name)` (:67). Then build the subclass with `ClientInformation.createDefault()` (:77).
8. PLAIN. Call `placeNewPlayer(new YourConnection(PacketFlow.SERVERBOUND), player, new CommonListenerCookie(profile, 0, player.clientInformation(), false))` (:79). Then `teleportTo(level, x, y, z, yaw, pitch)` (:80), `setHealth(20)` (:81), `unsetRemoved()` (:82), `getAttribute(STEP_HEIGHT).setBaseValue(0.6)` (:83), `gameMode.changeGameModeForPlayer(mode)` (:84). Broadcast `ClientboundRotateHeadPacket` and `ClientboundTeleportEntityPacket` via `broadcastAll(packet, dimension)` (:85-86). Optionally set `DATA_PLAYER_MODE_CUSTOMISATION` to 0x7f (:88).
9. PLAIN. Override `tick()`: every 10 ticks call `connection.resetPosition()` and `serverLevel().getChunkSource().move(this)`. Then `super.tick()`, then `doTick()` (§2). Input must be applied before `doTick()`, because `doTick` runs the entity tick and aiStep reads `zza`/`xxa`. Carpet does this in a HEAD mixin on `ServerPlayer.tick` (`ServerPlayer_actionPackMixin.java:33`). In a subclass, call your input step first, then `doTick()` yourself. AIBot calls it after (see §5).
10. PLAIN. Input: write `zza` (forward) and `xxa` (strafe) directly, as Carpet does (`EntityPlayerActionPack.java:241-248`). Call `setJumping(boolean)`, `setShiftKeyDown(boolean)`, `setSprinting(boolean)`, `lookAt(...)`, `setYRot`/`setXRot` as needed. Do NOT use `ServerPlayer.setPlayerInput(float,float,boolean,boolean)` for walking: its bytecode only writes the fields when the player is a passenger (§4).
11. PLAIN. Fall damage: override `checkFallDamage(double, boolean, BlockState, BlockPos)` and call `doCheckFallDamage(0.0, y, 0.0, onGround)` (Carpet `EntityPlayerMPFake.java:212-215`). Fall damage then runs through normal `hurt()`.
12. PLAIN. Death: override `die(DamageSource)` to dismount riders, call super, `setHealth(20)`, reset food, then call your `kill` with the death message (Carpet `:191-199`). `kill` schedules `connection.onDisconnect(new DisconnectionDetails(reason))` on a `TickTask` (`:147-158`). Vanilla `PlayerList.respawn` builds the new player and reuses the old connection (§4). Respawn therefore needs a MIXIN: redirect `PlayerList.respawn`'s `NEW ServerPlayer.<init>` to return your subclass (Carpet `PlayerList_fakePlayersMixin.java:52`).
13. PLAIN. Dimension change: override `changeDimension(DimensionTransition)` and call super (Carpet `:217-232`). Carpet also checks `isChangingDimension()`.
14. PLAIN. Optional overrides: `getIpAddress()` (`:201`), `allowsListing()` (`:207`), `onEquipItem(...)` (`:135`).
15. MIXIN (optional). Knockback of a fake attacked by a player: redirect `Player.attack`'s read of `Entity.hurtMarked` (Carpet `Player_fakePlayersMixin.java:16`). Riding a fake: inject `Entity.isControlledByLocalInstance` HEAD (Carpet `EntityMixin.java:35`). Creative-only chunk loading: inject `ChunkMap.skipPlayer` (Carpet `ChunkMap_creativePlayersLoadChunksMixin.java:14`).
16. Save: `PlayerList.remove(ServerPlayer)` calls `save(player)` (§4). A logout by `connection.onDisconnect` gets there. Server stop calls `PlayerList.saveAll()` (name only; body UNVERIFIED). Nothing else is needed for player data, as the body is a normal `ServerPlayer`.

Summary: MIXIN or AT needed for steps 2, 4, 6, 12. Optional mixins: 15. Everything else is a plain subclass or override.

## 4. Vanilla behaviour the design must decide (verified in 1.21.1 bytecode unless marked)

Names are Mojang with the official (obfuscated) name in brackets, so the bytecode can be checked.

- Entity tick split. `ServerPlayer.tick()` [`l`] does bookkeeping only: among other things `gameMode.tick` [`cpu.d`] and the advancements flush [`akz.b`]. It has no `super.tick()` (no `invokespecial`). `ServerPlayer.doTick()` [`m`] calls `Player.tick()` [`cmx.l` via `invokespecial`] when `!isSpectator() || !touchingUnloadedChunk()`, then client sync (health, food, XP, inventory). `doTick` is called only from `ServerGamePacketListenerImpl.tick()` [`aru.d`, offset 78] for real players. Consequence: a fake's entity tick (gravity, aiStep, travel) runs only if something calls `doTick()`. Both Carpet and AIBot do.
- Travel. `LivingEntity.tick()` [`btn.l`] calls `aiStep()` [`m_`] unless `isRemoved()`. `LivingEntity.aiStep()` ends by calling `travel(Vec3(xxa, yya, zza))` [`a(exc)`] unless the entity is a controlled passenger (`cQ` / `bE` branch). `Player.aiStep` [`cmx.m_`] calls `LivingEntity.aiStep` via `invokespecial` (offset 165). So for a non-riding ServerPlayer, `doTick` → `Player.tick` → `aiStep` → `travel` runs every tick. `travel` applies gravity and collision. The position is then moved by `move(...)`. This is the basis for §5.
- Tab list. `PlayerList.placeNewPlayer` [`aur.a(vt, aqv, arj)`]: sends the joiner the full list (`ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players)`, bytecode `aea.a(Collection)`), then `broadcastAll` the joiner as ADD. So a fake appears in everyone's tab list by default. Whether `allowsListing()` [`aqv.allowsListing`] controls the `listed` flag in that packet is UNVERIFIED (Carpet overrides it; the reader was not traced).
- Sleep percentage. `ServerLevel` [`aqu.e()`] calls `SleepStatus.update(this.players)` [`auu.a(List)`]. For each `ServerPlayer` with `!isSpectator()`, it counts an active player, and for each with `isSleeping()` (LivingEntity `fH`) a sleeping one (bytecode, `auu.a(java.util.List)`). So an awake fake counts against the percentage. Night skip also uses `areEnoughDeepSleeping(int, List)` [`auu.a(int, List)`], which filters with `Player::isSleepingLongEnough` [`cmx.gb`] (verified via the bootstrap method handle). PlayerEngine's 1.20.1 class overrode `canResetTimeBySleeping` (Yarn method_7276, which is `isSleepingLongEnough` in 1.21.1) to return true, so a fake did not block the night skip. In 1.21.1 that override would be on `Player.isSleepingLongEnough`.
- Advancements and stats. `PlayerList.save(ServerPlayer)` [`aur.b`] calls `PlayerDataStorage.save(player)` (`eri.a`), then `ServerStatsCounter.save()` for the player's entry in `stats` (field `r`), then `PlayerAdvancements.save()` for `advancements` (field `s`). So a fake gets `.dat`, stats and advancements written like any player. Exact file paths (`playerdata/`, `stats/`, `advancements/` with UUID names) are UNVERIFIED (not traced into `PlayerDataStorage`).
- Save triggers. `PlayerList.remove(ServerPlayer)` [`aur.c`] calls `save(player)` (offset 14). `saveAll()` [`aur.h`] exists; its body was not traced (UNVERIFIED).
- Chunk loading. `ChunkMap.skipPlayer(ServerPlayer)` [`aqb.c`] returns `isSpectator() && !gameRules.getBoolean(SPECTATORS_GENERATE_CHUNKS)` (bytecode). Any other player (including a survival fake) creates chunk tickets around itself. `ServerChunkCache.move(player)` [`aqs.a(aqv)`] is what updates the tickets; it is called by Carpet every 10 ticks and in `NetHandlerPlayServerFake.teleport`, and by AIBot every 10 ticks as `getChunkManager().updatePosition(this)`.
- Mob spawning around the body. UNVERIFIED. Expected to follow `ServerPlayer`s that are non-spectators, but the spawn code was not read.
- Packets broadcast to all players. `PlayerList.broadcastAll(Packet)` [`aur.a(zg)`] and `broadcastAll(Packet, ResourceKey)` [`aur.a(zg, akq)`] exist (signatures verified). Their loop body was not read (UNVERIFIED). Carpet uses `broadcastAll` for head rotate and teleport in its spawn. The stub listener's `send(Packet)` no-op (Carpet) and the stub connection's sends (AIBot) absorb any packet sent to the body itself.
- Respawn. `PlayerList.respawn(ServerPlayer, boolean, RemovalReason)` [`aur.a(aqv, boolean, bsr$c)`] does `new ServerPlayer(server, level, profile, cli)` (`aqv.<init>`), then `newPlayer.connection = oldPlayer.connection` (`aqv.c` put, bytecode offset 63-66). The connection is reused. Carpet's comment in `changeDimension` agrees (`EntityPlayerMPFake.java:226-230`). How `ServerGamePacketListenerImpl.player` is re-pointed to the new instance was not traced (UNVERIFIED).
- Connection tick. `ServerConnectionListener.tick()` [`art.c`] loops over `connections` [`art.g`] and calls `Connection.tick()` [`vt.b`]. A fake's connection is never added there, so it is never ticked. This is the reason for the manual `doTick()` and `resetPosition()` (§2).
- Ticked by the level. Whether `ServerLevel` calls `ServerPlayer.tick()` for players (the Carpet override relies on this) is UNVERIFIED.
- `ServerPlayer.setPlayerInput(float, float, boolean, boolean)` [`aqv.a(FFZZ)`]. The bytecode is gated on `isPassenger()` [`bS`] (offset 1-4). When not riding, nothing is written. Use direct field writes for walking (§3 step 10).

## 5. Behaviour the design must decide: where the comments disagree with the code

The §2.1 row says AIBot's comments disagree with each other. Here they are, with the code.

- `aibot@a029fa6a:src/main/java/io/github/zoyluo/aibot/action/ActionPack.java:430-437`, javadoc of `descendInto`: "关键:bot 是 ServerPlayerEntity,服务端**不跑 travel()**(真实玩家的移动/重力由客户端驱动, fake player 没有客户端),因此**没有被动重力**——挖空脚下不会自动下落。" Translation: the server does not run travel(); real players' movement and gravity come from the client; a fake has no client; so there is no passive gravity, and digging the floor does not make the bot fall.
- Contradicted for 1.21.1 by the code path: `AIPlayerEntity.java:29-30` calls `super.tick()` and then `playerTick()`. `playerTick` is Yarn for `ServerPlayer.doTick` [`method_14226`, cross-checked in the Yarn tiny file]. `doTick` calls `Player.tick` and so `LivingEntity.aiStep` → `travel` (§4). So on 1.21.1 vanilla, the body does get travel and gravity whenever `AIPlayerEntity.tick` runs. Carpet's comment-free design relies on the same thing.
- Consistent with that code: `aibot@a029fa6a:src/main/java/io/github/zoyluo/aibot/mode/FakePlayerMotion.java:101-104` ("Landing exactly on the integer Y boundary lets gravity floor the clientless player back into the previous block ... producing a permanent two-cell oscillation") and :116-117 ("A 0.125-block inset is enough to survive one gravity tick"). These describe gravity acting.
- Consistent with the first comment: `action/HarvestCore.java:275` ("Server-side fake players receive no client gravity."); `task/NavSafetyNet.java:175-177` ("do not execute client-authored travel, so merely setting forward/jump leaves them motionless."); `pathfinding/Standability.java:120`.
- AIBot's own `FakePlayerMotion.java:298-299`: "ServerPlayerEntity normally refreshes onGround from client movement packets. A clientless fake player can therefore report false on the tick after a verified landing." This is a reason the code re-checks `onGround` with a support probe. It is not a contradiction by itself, but it is only needed if movement is not applied to the body by `travel`.
- Verdict for the design: on 1.21.1 the premise "the server never runs travel() for its body" is false for any ServerPlayer whose `doTick()` runs. The code paths that matter are `ServerPlayer.doTick` (`carpet@...:EntityPlayerMPFake.java:171`, `aibot:AIPlayerEntity.java:30`). The AIBot teleports are a separate choice. This verdict is for 1.21.1. AIBot targets 1.21.3, where it is UNVERIFIED.
- Ordering: AIBot's `AIPlayerEntity.tick` calls `actionPack.onUpdate()` (:31) after `super.tick()` and `playerTick()`. The inputs it writes (`forwardSpeed`, `sidewaysSpeed`, `setJumping`, `ActionPack.java:515-518`) therefore take effect on the next tick. Carpet writes them before `doTick()`. The one-tick lag is a fact of the code, not a measured bug.
- §2.1 says "Carpet and Automatone fake players move through inputs". Carpet verified (§2). Automatone verified only on the old 1.20.1 Yarn tree (`InputOverrideHandler.java:102-127`), not on 1.21.1.

## 6. Adjacent issues

- PlayerEngine: the fake-player source the design §2 table points at is gone from `1.21.1-arch`. Its successor is a `LivingEntity` (bundled jar), not a `ServerPlayer`. Any plan that reuses PlayerEngine's body must reckon with that.
- Carpet `createFake` returns `true` before the async spawn runs (`EntityPlayerMPFake.java:71`, :91). Callers cannot tell that a spawn failed after the lookup.
- Carpet's `FakeClientConnection` leaves `Connection.sendPacket` live (it only overrides the four setup methods). Packets that go straight to the connection reach `EmbeddedChannel`, which queues outbound messages that nothing reads. Carpet's listener-level `send` no-op (`NetHandlerPlayServerFake.java:22`) hides this for listener-routed packets. AIBot overrides the connection-level `send` methods (`network/FakeClientConnection.java:37-48`), which is the safer shape. Whether memory grows in practice is UNVERIFIED.
- AIBot `ServerTickProbeMixin` (`mixin/ServerTickProbeMixin.java:19`) is a per-tick counter injected into `MinecraftServer.tick`. It is not body-related, but it sits in the six-mixin count the design quotes.
- AIBot's catch block (`AIPlayerEntity.java:32-34`) swallows `NullPointerException` from the whole tick and logs it. Carpet does the same (`EntityPlayerMPFake.java:173-177`, comment says "happens with that paper port thingy"). Neither is evidence about the body. Both hide bugs.
- Carpet `EntityPlayerMPFake.tick` calls `doTick()` even though nothing else does this for fakes; if a future vanilla change ticks connections, the fake's entity tick would run twice. Not a current bug (UNVERIFIED for NeoForge).
- Carpet's `NetHandlerPlayServerFake.teleport` and `EntityPlayerMPFake.tick` both call `chunkSource.move`; the 10-tick cadence in `tick` is separate from the teleport path. Harmless, but two sources of ticket updates.
- §2.1 of the design says PlayerEngine is "Fabric + NeoForge via Architectury on branches `1.21.1-arch`". The NeoForge side is a binary jar in `libs/`. Not checked further.

## 7. Open questions (not answerable from the repos)

1. Does AIBot's body behave as §5 describes on 1.21.3? A 1.21.3 vanilla jar would settle `ServerPlayer.doTick` and `aiStep`. None is in the Loom cache.
2. Does NeoForge 1.21.1 change `PlayerList.placeNewPlayer`, `respawn`, `load` or `Connection` relative to vanilla? Not checked (no NeoForge jar read).
3. Does `ServerLevel` call `ServerPlayer.tick()` for players? Carpet's override relies on it.
4. Mob spawning and mob targeting around a non-spectator fake: not traced.
5. Whether `allowsListing()` gates the tab-list `listed` flag in `createPlayerInitializing`: not traced.
6. Exact save paths for `playerdata`, `stats` and `advancements`, and whether `saveAll()` calls `save` for each player: not traced.
7. How `ServerGamePacketListenerImpl.player` is re-pointed on respawn and dimension change: not traced.
8. Whether any real path sends packets straight to `Connection` (bypassing the listener) for a fake, which would reach `EmbeddedChannel`: not traced.
9. For the sleep percentage, which of `SleepStatus.areEnoughSleeping` and `areEnoughDeepSleeping` decides the night skip in `ServerLevel` (both are called; the branch order was not traced).
10. Whether the Yarn-to-Mojang names used in the AIBot section (`syncWithPlayerPosition` → `resetPosition`, `getChunkManager().updatePosition` → `ServerChunkCache.move`) are exact on 1.21.1. They are used by analogy with Carpet (the same concept); not confirmed by a mapping lookup.
