package net.citizensnpcs.nms.v1_16_R3.entity;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_16_R3.CraftServer;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCEnderTeleportEvent;
import net.citizensnpcs.api.event.NPCPushEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.nms.v1_16_R3.network.EmptyNetHandler;
import net.citizensnpcs.nms.v1_16_R3.network.EmptyNetworkManager;
import net.citizensnpcs.nms.v1_16_R3.network.EmptySocket;
import net.citizensnpcs.nms.v1_16_R3.util.EmptyAdvancementDataPlayer;
import net.citizensnpcs.nms.v1_16_R3.util.NMSImpl;
import net.citizensnpcs.nms.v1_16_R3.util.PlayerControllerJump;
import net.citizensnpcs.nms.v1_16_R3.util.PlayerControllerLook;
import net.citizensnpcs.nms.v1_16_R3.util.PlayerControllerMove;
import net.citizensnpcs.nms.v1_16_R3.util.PlayerNavigation;
import net.citizensnpcs.nms.v1_16_R3.util.PlayerlistTracker;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.citizensnpcs.npc.skin.SkinnableEntity;
import net.citizensnpcs.trait.Gravity;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.util.NMS;
import net.citizensnpcs.util.Util;
import net.minecraft.server.v1_16_R3.AttributeBase;
import net.minecraft.server.v1_16_R3.AttributeMapBase;
import net.minecraft.server.v1_16_R3.AttributeModifiable;
import net.minecraft.server.v1_16_R3.AttributeProvider;
import net.minecraft.server.v1_16_R3.BlockPosition;
import net.minecraft.server.v1_16_R3.ChatComponentText;
import net.minecraft.server.v1_16_R3.DamageSource;
import net.minecraft.server.v1_16_R3.Entity;
import net.minecraft.server.v1_16_R3.EntityHuman;
import net.minecraft.server.v1_16_R3.EntityPlayer;
import net.minecraft.server.v1_16_R3.EnumGamemode;
import net.minecraft.server.v1_16_R3.EnumItemSlot;
import net.minecraft.server.v1_16_R3.EnumProtocolDirection;
import net.minecraft.server.v1_16_R3.GenericAttributes;
import net.minecraft.server.v1_16_R3.IBlockData;
import net.minecraft.server.v1_16_R3.IChatBaseComponent;
import net.minecraft.server.v1_16_R3.ItemStack;
import net.minecraft.server.v1_16_R3.MinecraftServer;
import net.minecraft.server.v1_16_R3.NavigationAbstract;
import net.minecraft.server.v1_16_R3.NetworkManager;
import net.minecraft.server.v1_16_R3.Packet;
import net.minecraft.server.v1_16_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_16_R3.PathType;
import net.minecraft.server.v1_16_R3.PlayerInteractManager;
import net.minecraft.server.v1_16_R3.Vec3D;
import net.minecraft.server.v1_16_R3.WorldServer;

public class EntityHumanNPC extends EntityPlayer implements NPCHolder, SkinnableEntity {
    private final Map<PathType, Float> bz = Maps.newEnumMap(PathType.class);
    private PlayerControllerJump controllerJump;
    private PlayerControllerLook controllerLook;
    private PlayerControllerMove controllerMove;
    private int jumpTicks = 0;
    private PlayerNavigation navigation;
    private final CitizensNPC npc;
    private final Location packetLocationCache = new Location(null, 0, 0, 0);
    private PlayerlistTracker playerlistTracker;
    private final SkinPacketTracker skinTracker;
    private int updateCounter = 0;

    public EntityHumanNPC(MinecraftServer minecraftServer, WorldServer world, GameProfile gameProfile,
            PlayerInteractManager playerInteractManager, NPC npc) {
        super(minecraftServer, world, gameProfile, playerInteractManager);

        this.npc = (CitizensNPC) npc;
        if (npc != null) {
            skinTracker = new SkinPacketTracker(this);
            playerInteractManager.setGameMode(EnumGamemode.SURVIVAL);
            initialise(minecraftServer);
        } else {
            skinTracker = null;
        }
    }

    @Override
    protected void a(double d0, boolean flag, IBlockData block, BlockPosition blockposition) {
        if (npc == null || !npc.isFlyable()) {
            super.a(d0, flag, block, blockposition);
        }
    }

    @Override
    public boolean a(EntityPlayer entityplayer) {
        if (npc != null && playerlistTracker == null) {
            return false;
        }
        return super.a(entityplayer);
    }

    public float a(PathType pathtype) {
        return this.bz.containsKey(pathtype) ? this.bz.get(pathtype).floatValue() : pathtype.a();
    }

    public void a(PathType pathtype, float f) {
        this.bz.put(pathtype, Float.valueOf(f));
    }

    @Override
    public boolean b(float f, float f1) {
        if (npc == null || !npc.isFlyable()) {
            return super.b(f, f1);
        }
        return false;
    }

    public boolean b(PathType pathtype) {
        return (pathtype != PathType.DANGER_FIRE && pathtype != PathType.DANGER_CACTUS
                && pathtype != PathType.DANGER_OTHER);
    }

    @Override
    public void collide(net.minecraft.server.v1_16_R3.Entity entity) {
        // this method is called by both the entities involved - cancelling
        // it will not stop the NPC from moving.
        super.collide(entity);
        if (npc != null) {
            Util.callCollisionEvent(npc, entity.getBukkitEntity());
        }
    }

    @Override
    public boolean damageEntity(DamageSource damagesource, float f) {
        // knock back velocity is cancelled and sent to client for handling when
        // the entity is a player. there is no client so make this happen
        // manually.
        boolean damaged = super.damageEntity(damagesource, f);
        if (damaged && velocityChanged) {
            velocityChanged = false;
            Bukkit.getScheduler().runTask(CitizensAPI.getPlugin(), new Runnable() {
                @Override
                public void run() {
                    EntityHumanNPC.this.velocityChanged = true;
                }
            });
        }
        return damaged;
    }

    @Override
    public void die() {
        super.die();
        getAdvancementData().a();
    }

    @Override
    public void die(DamageSource damagesource) {
        // players that die are not normally removed from the world. when the
        // NPC dies, we are done with the instance and it should be removed.
        if (dead) {
            return;
        }
        super.die(damagesource);
        Bukkit.getScheduler().runTaskLater(CitizensAPI.getPlugin(), new Runnable() {
            @Override
            public void run() {
                ((WorldServer) world).removeEntity(EntityHumanNPC.this);
            }
        }, 35); // give enough time for death and smoke animation
    }

    @Override
    public void enderTeleportTo(double d0, double d1, double d2) {
        if (npc == null) {
            super.enderTeleportTo(d0, d1, d2);
            return;
        }
        NPCEnderTeleportEvent event = new NPCEnderTeleportEvent(npc);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            super.enderTeleportTo(d0, d1, d2);
        }
    }

    @Override
    public void g(Vec3D vec3d) {
        if (npc == null || !npc.isFlyable()) {
            super.g(vec3d);
        } else {
            NMSImpl.flyingMoveLogic(this, vec3d);
        }
    }

    @Override
    public CraftPlayer getBukkitEntity() {
        if (npc != null && !(super.getBukkitEntity() instanceof NPCHolder)) {
            NMSImpl.setBukkitEntity(this, new PlayerNPC(this));
        }
        return super.getBukkitEntity();
    }

    public PlayerControllerJump getControllerJump() {
        return controllerJump;
    }

    public PlayerControllerMove getControllerMove() {
        return controllerMove;
    }

    public NavigationAbstract getNavigation() {
        return navigation;
    }

    @Override
    public NPC getNPC() {
        return npc;
    }

    @Override
    public IChatBaseComponent getPlayerListName() {
        if (Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.asBoolean()) {
            return new ChatComponentText("");
        }
        return super.getPlayerListName();
    }

    @Override
    public String getSkinName() {
        String skinName = npc.getOrAddTrait(SkinTrait.class).getSkinName();
        if (skinName == null) {
            skinName = npc.getName();
        }
        return skinName.toLowerCase();
    }

    @Override
    public SkinPacketTracker getSkinTracker() {
        return skinTracker;
    }

    @Override
    public void i(double x, double y, double z) {
        if (npc == null) {
            super.i(x, y, z);
            return;
        }
        if (NPCPushEvent.getHandlerList().getRegisteredListeners().length == 0) {
            if (!npc.data().get(NPC.DEFAULT_PROTECTED_METADATA, true)) {
                super.i(x, y, z);
            }
            return;
        }
        Vector vector = new Vector(x, y, z);
        NPCPushEvent event = Util.callPushEvent(npc, vector);
        if (!event.isCancelled()) {
            vector = event.getCollisionVector();
            super.i(vector.getX(), vector.getY(), vector.getZ());
        }
        // when another entity collides, this method is called to push the
        // NPC so we prevent it from doing anything if the event is
        // cancelled.
    }

    private void initialise(MinecraftServer minecraftServer) {
        Socket socket = new EmptySocket();
        NetworkManager conn = null;
        try {
            conn = new EmptyNetworkManager(EnumProtocolDirection.CLIENTBOUND);
            playerConnection = new EmptyNetHandler(minecraftServer, conn, this);
            conn.setPacketListener(playerConnection);
            socket.close();
        } catch (IOException e) {
            // swallow
        }

        AttributeModifiable range = getAttributeInstance(GenericAttributes.FOLLOW_RANGE);
        if (range == null) {
            try {
                AttributeProvider provider = (AttributeProvider) ATTRIBUTE_MAP.invoke(getAttributeMap());
                Map<AttributeBase, AttributeModifiable> all = Maps
                        .newHashMap((Map<AttributeBase, AttributeModifiable>) ATTRIBUTE_PROVIDER_MAP.invoke(provider));
                all.put(GenericAttributes.FOLLOW_RANGE,
                        new AttributeModifiable(GenericAttributes.FOLLOW_RANGE, new Consumer<AttributeModifiable>() {
                            @Override
                            public void accept(AttributeModifiable att) {
                                throw new UnsupportedOperationException(
                                        "Tried to change value for default attribute instance FOLLOW_RANGE");
                            }
                        }));
                ATTRIBUTE_PROVIDER_MAP_SETTER.invoke(provider, ImmutableMap.copyOf(all));
            } catch (Throwable e) {
                e.printStackTrace();
            }
            range = getAttributeMap().a(GenericAttributes.FOLLOW_RANGE);
        }
        range.setValue(Setting.DEFAULT_PATHFINDING_RANGE.asDouble());

        controllerJump = new PlayerControllerJump(this);
        controllerLook = new PlayerControllerLook(this);
        controllerMove = new PlayerControllerMove(this);
        navigation = new PlayerNavigation(this, world);
        invulnerableTicks = 0;
        NMS.setStepHeight(getBukkitEntity(), 1); // the default (0) breaks step climbing
        setSkinFlags((byte) 0xFF);

        EmptyAdvancementDataPlayer.clear(this.getAdvancementData());
        NMSImpl.setAdvancement(this.getBukkitEntity(),
                new EmptyAdvancementDataPlayer(minecraftServer.getDataFixer(), minecraftServer.getPlayerList(),
                        minecraftServer.getAdvancementData(), CitizensAPI.getDataFolder().getParentFile(), this));
    }

    @Override
    public boolean isClimbing() {
        if (npc == null || !npc.isFlyable()) {
            return super.isClimbing();
        } else {
            return false;
        }
    }

    @Override
    public boolean isCollidable() {
        return npc == null ? super.isCollidable() : npc.data().get(NPC.COLLIDABLE_METADATA, true);
    }

    public boolean isNavigating() {
        return npc.getNavigator().isNavigating();
    }

    private void moveOnCurrentHeading() {
        if (jumping) {
            if (onGround && jumpTicks == 0) {
                jump();
                jumpTicks = 10;
            }
        } else {
            jumpTicks = 0;
        }
        aR *= 0.98F;
        aT *= 0.98F;
        g(new Vec3D(this.aR, this.aS, this.aT)); // movement method
        NMS.setHeadYaw(getBukkitEntity(), yaw);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    @Override
    public Packet<?> P() {
        if (playerlistTracker != null) {
            playerlistTracker.updateLastPlayer();
        }
        return super.P();
    }

    @Override
    public void playerTick() {
        if (npc == null) {
            super.playerTick();
            return;
        }
        entityBaseTick();
        boolean navigating = npc.getNavigator().isNavigating();
        if (!navigating && getBukkitEntity() != null
                && (!npc.hasTrait(Gravity.class) || npc.getOrAddTrait(Gravity.class).hasGravity())
                && Util.isLoaded(getBukkitEntity().getLocation(LOADED_LOCATION))) {
            g(new Vec3D(0, 0, 0));
        }
        Vec3D mot = getMot();
        if (Math.abs(mot.getX()) < EPSILON && Math.abs(mot.getY()) < EPSILON && Math.abs(mot.getZ()) < EPSILON) {
            setMot(new Vec3D(0, 0, 0));
        }
        if (navigating) {
            if (!NMSImpl.isNavigationFinished(navigation)) {
                NMSImpl.updateNavigation(navigation);
            }
            moveOnCurrentHeading();
        }
        NMSImpl.updateAI(this);

        this.ar = this.as;
        if (this.hurtTicks > 0) {
            this.hurtTicks -= 1;
        }
        tickPotionEffects();
        this.aM = this.aL;
        this.aB = this.aA;
        this.aD = this.aC;
        this.lastYaw = this.yaw;
        this.lastPitch = this.pitch;
    }

    public void setMoveDestination(double x, double y, double z, double speed) {
        controllerMove.a(x, y, z, speed);
    }

    public void setShouldJump() {
        controllerJump.jump();
    }

    @Override
    public void setSkinFlags(byte flags) {
        // set skin flag byte
        getDataWatcher().set(EntityHuman.bi, flags);
    }

    @Override
    public void setSkinName(String name) {
        npc.getOrAddTrait(SkinTrait.class).setSkinName(name);
    }

    @Override
    public void setSkinName(String name, boolean forceUpdate) {
        npc.getOrAddTrait(SkinTrait.class).setSkinName(name, forceUpdate);
    }

    @Override
    public void setSkinPersistent(String skinName, String signature, String data) {
        npc.getOrAddTrait(SkinTrait.class).setSkinPersistent(skinName, signature, data);
    }

    public void setTargetLook(Entity target, float yawOffset, float renderOffset) {
        controllerLook.a(target, yawOffset, renderOffset);
    }

    public void setTargetLook(Location target) {
        controllerLook.a(target.getX(), target.getY(), target.getZ());
    }

    public void setTracked(PlayerlistTracker tracker) {
        this.playerlistTracker = tracker;
    }

    @Override
    public void tick() {
        super.tick();
        if (npc == null || !npc.shouldTick())
            return;
        noclip = isSpectator();
        if (updateCounter + 1 > Setting.PACKET_UPDATE_DELAY.asInt()) {
            updateEffects = true;
        }
        Bukkit.getServer().getPluginManager().unsubscribeFromPermission("bukkit.broadcast.user", getBukkitEntity());

        boolean navigating = npc.getNavigator().isNavigating();
        updatePackets(navigating);

        if (noDamageTicks > 0) {
            --noDamageTicks;
        }

        npc.update();
        /*
         double diff = this.yaw - this.aK;
         if (diff != 40 && diff != -40) {
         ++this.yawUpdateRequiredTicks;
         }
         if (this.yawUpdateRequiredTicks > 5) {
         this.yaw = (diff > -40 && diff < 0) || (diff > 0 && diff > 40) ? this.aK - 40 : this.aK + 40;
         this.yawUpdateRequiredTicks = 0;
         }
         */
    }

    public void updateAI() {
        controllerMove.a();
        controllerLook.a();
        controllerJump.b();
    }

    private void updatePackets(boolean navigating) {
        if (updateCounter++ <= Setting.PACKET_UPDATE_DELAY.asInt())
            return;

        updateCounter = 0;
        Location current = getBukkitEntity().getLocation(packetLocationCache);
        Packet<?>[] packets = new Packet[1];
        List<Pair<EnumItemSlot, ItemStack>> vals = Lists.newArrayList();
        for (EnumItemSlot slot : EnumItemSlot.values()) {
            vals.add(new Pair<EnumItemSlot, ItemStack>(slot, getEquipment(slot)));
        }
        packets[0] = new PacketPlayOutEntityEquipment(getId(), vals);
        NMSImpl.sendPacketsNearby(getBukkitEntity(), current, packets);
    }

    public void updatePathfindingRange(float pathfindingRange) {
        this.navigation.setRange(pathfindingRange);
    }

    public static class PlayerNPC extends CraftPlayer implements NPCHolder, SkinnableEntity {
        private final CraftServer cserver;
        private final CitizensNPC npc;

        private PlayerNPC(EntityHumanNPC entity) {
            super((CraftServer) Bukkit.getServer(), entity);
            this.npc = entity.npc;
            this.cserver = (CraftServer) Bukkit.getServer();
            npc.getOrAddTrait(Inventory.class);
        }

        @Override
        public Player getBukkitEntity() {
            return this;
        }

        @Override
        public EntityHumanNPC getHandle() {
            return (EntityHumanNPC) this.entity;
        }

        @Override
        public List<MetadataValue> getMetadata(String metadataKey) {
            return cserver.getEntityMetadata().getMetadata(this, metadataKey);
        }

        @Override
        public NPC getNPC() {
            return npc;
        }

        @Override
        public String getSkinName() {
            return ((SkinnableEntity) this.entity).getSkinName();
        }

        @Override
        public SkinPacketTracker getSkinTracker() {
            return ((SkinnableEntity) this.entity).getSkinTracker();
        }

        @Override
        public boolean hasMetadata(String metadataKey) {
            return cserver.getEntityMetadata().hasMetadata(this, metadataKey);
        }

        @Override
        public void removeMetadata(String metadataKey, Plugin owningPlugin) {
            cserver.getEntityMetadata().removeMetadata(this, metadataKey, owningPlugin);
        }

        @Override
        public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
            cserver.getEntityMetadata().setMetadata(this, metadataKey, newMetadataValue);
        }

        @Override
        public void setSkinFlags(byte flags) {
            ((SkinnableEntity) this.entity).setSkinFlags(flags);
        }

        @Override
        public void setSkinName(String name) {
            ((SkinnableEntity) this.entity).setSkinName(name);
        }

        @Override
        public void setSkinName(String skinName, boolean forceUpdate) {
            ((SkinnableEntity) this.entity).setSkinName(skinName, forceUpdate);
        }

        @Override
        public void setSkinPersistent(String skinName, String signature, String data) {
            ((SkinnableEntity) this.entity).setSkinPersistent(skinName, signature, data);
        }
    }

    private static final MethodHandle ATTRIBUTE_MAP = NMS.getGetter(AttributeMapBase.class, "d");
    private static final MethodHandle ATTRIBUTE_PROVIDER_MAP = NMS.getGetter(AttributeProvider.class, "a");
    private static final MethodHandle ATTRIBUTE_PROVIDER_MAP_SETTER = NMS.getFinalSetter(AttributeProvider.class, "a");
    private static final float EPSILON = 0.005F;
    private static final Location LOADED_LOCATION = new Location(null, 0, 0, 0);
}
