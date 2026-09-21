package com.jamesrenrold.elijah;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** The client-to-server bridge for Java-owned pirate abilities. */
public final class AbilityNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ElijahPirate.MOD_ID, "abilities"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static int nextId;

    public static void register() {
        CHANNEL.registerMessage(nextId++, AbilityPacket.class,
                AbilityPacket::encode, AbilityPacket::decode, AbilityPacket::handle);
        CHANNEL.registerMessage(nextId++, ClientStatePacket.class,
                ClientStatePacket::encode, ClientStatePacket::decode, ClientStatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(int ability) {
        CHANNEL.sendToServer(new AbilityPacket(ability));
    }

    /** Sends display-only state. The server remains the only authority for abilities. */
    public static void syncState(ServerPlayer player) {
        PowderPouch state = ElijahPirate.state(player);
        boolean pirate = ElijahPirate.isPirate(player);
        long now = player.getServer() == null
                ? player.serverLevel().getGameTime()
                : player.getServer().overworld().getGameTime();

        // Always send a packet, including the false state. Previously this
        // method returned without sending anything after an origin change,
        // leaving the client rendering the last Elijah HUD forever.
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClientStatePacket(
                pirate,
                pirate ? state.bloodResource : 0,
                pirate ? state.crewResource : 0,
                pirate && state.cursedFormActive,
                pirate && state.bloodHuntUntil > now,
                pirate && state.bloodFlightUntil > now,
                pirate && state.bloodOverdriveUntil > now,
                pirate && state.bloodExhaustedUntil > now,
                pirate && state.dirtyTacticsArmed,
                pirate ? remaining(state.dirtyTacticsReadyAt, now) : 0,
                pirate ? remaining(state.nextShotTick, now) : 0,
                pirate ? remaining(state.bloodCooldownUntil, now) : 0,
                pirate ? remaining(state.bloodHuntUntil, now) : 0,
                pirate ? remaining(state.bloodHuntCooldownUntil, now) : 0,
                pirate ? remaining(state.bloodFlightUntil, now) : 0,
                pirate ? remaining(state.bloodFlightCooldownUntil, now) : 0,
                pirate ? remaining(state.bloodOverdriveUntil, now) : 0,
                pirate ? remaining(state.bloodExhaustedUntil, now) : 0,
                pirate ? DomainAbilities.activeRemaining(player) : 0,
                pirate ? DomainAbilities.cooldownRemaining(player) : 0,
                pirate ? remaining(state.nextCrewRechargeTick, now) : 0));
    }

    private static int remaining(long until, long now) {
        long value = until - now;
        return Math.max(0, (int) Math.min(Integer.MAX_VALUE, value));
    }

    private record AbilityPacket(int ability) {
        private void encode(FriendlyByteBuf buffer) {
            buffer.writeByte(ability);
        }

        private static AbilityPacket decode(FriendlyByteBuf buffer) {
            return new AbilityPacket(buffer.readUnsignedByte());
        }

        private static void handle(AbilityPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) dispatch(player, packet.ability);
            });
            context.setPacketHandled(true);
        }
    }

    private record ClientStatePacket(
            boolean pirate,
            int bloodResource,
            int crewResource,
            boolean cursedFormActive,
            boolean huntActive,
            boolean flightActive,
            boolean overdriveActive,
            boolean exhaustedActive,
            boolean dirtyTacticsArmed,
            int dirtyCooldown,
            int shotCooldown,
            int cursedCooldown,
            int huntRemaining,
            int huntCooldown,
            int flightRemaining,
            int flightCooldown,
            int overdriveRemaining,
            int exhaustedRemaining,
            int domainRemaining,
            int domainCooldown,
            int crewRecharge) {
        private void encode(FriendlyByteBuf buffer) {
            buffer.writeBoolean(pirate);
            buffer.writeVarInt(bloodResource);
            buffer.writeVarInt(crewResource);
            buffer.writeBoolean(cursedFormActive);
            buffer.writeBoolean(huntActive);
            buffer.writeBoolean(flightActive);
            buffer.writeBoolean(overdriveActive);
            buffer.writeBoolean(exhaustedActive);
            buffer.writeBoolean(dirtyTacticsArmed);
            buffer.writeVarInt(dirtyCooldown);
            buffer.writeVarInt(shotCooldown);
            buffer.writeVarInt(cursedCooldown);
            buffer.writeVarInt(huntRemaining);
            buffer.writeVarInt(huntCooldown);
            buffer.writeVarInt(flightRemaining);
            buffer.writeVarInt(flightCooldown);
            buffer.writeVarInt(overdriveRemaining);
            buffer.writeVarInt(exhaustedRemaining);
            buffer.writeVarInt(domainRemaining);
            buffer.writeVarInt(domainCooldown);
            buffer.writeVarInt(crewRecharge);
        }

        private static ClientStatePacket decode(FriendlyByteBuf buffer) {
            return new ClientStatePacket(
                    buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        }

        private static void handle(ClientStatePacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () ->
                    () -> com.jamesrenrold.elijah.client.ClientHudState.apply(
                            packet.pirate, packet.bloodResource, packet.crewResource,
                            packet.cursedFormActive, packet.huntActive, packet.flightActive,
                            packet.overdriveActive, packet.exhaustedActive,
                            packet.dirtyTacticsArmed, packet.dirtyCooldown, packet.shotCooldown,
                            packet.cursedCooldown, packet.huntRemaining, packet.huntCooldown,
                            packet.flightRemaining, packet.flightCooldown, packet.overdriveRemaining,
                            packet.exhaustedRemaining, packet.domainRemaining,
                            packet.domainCooldown, packet.crewRecharge)));
            context.setPacketHandled(true);
        }
    }

    private static void dispatch(ServerPlayer player, int ability) {
        if (ability < 0 || ability > 7) return;
        if (!ElijahPirate.isPirate(player)) return;
        PowderPouch state = ElijahPirate.state(player);
        long now = player.getServer() == null
                ? player.serverLevel().getGameTime()
                : player.getServer().overworld().getGameTime();
        // One physical key press can be observed by both Connector/Origins
        // and this bridge, or arrive as two queued packets. Collapse only
        // the same ability within three server ticks.
        if (state.lastAbilityPacket == ability && state.lastAbilityPacketTick > now - 3L) return;
        state.lastAbilityPacket = ability;
        state.lastAbilityPacketTick = now;
        switch (ability) {
            case 0 -> PirateAbilities.armDirtyTactics(player);
            case 1 -> ElijahPirate.fire(player);
            case 2 -> ElijahPirate.openPouch(player);
            case 3 -> ElijahPirate.summonCrew(player);
            case 4 -> BloodAbilities.activateBloodRush(player);
            case 5 -> BloodAbilities.activateHunt(player);
            case 6 -> BloodAbilities.activateWings(player);
            case 7 -> DomainAbilities.activate(player);
            default -> { }
        }
    }

    private AbilityNetwork() {}
}
