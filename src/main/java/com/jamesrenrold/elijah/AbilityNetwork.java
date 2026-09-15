package com.jamesrenrold.elijah;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

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
    }

    public static void send(int ability) {
        CHANNEL.sendToServer(new AbilityPacket(ability));
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

    private static void dispatch(ServerPlayer player, int ability) {
        if (!ElijahPirate.isPirate(player)) return;
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
