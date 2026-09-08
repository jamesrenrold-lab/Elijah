package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.UndeadCrewmate;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/** Renders the crewmate with the static classic-arm pirate-captain skin. */
public final class UndeadCrewmateRenderer extends HumanoidMobRenderer<UndeadCrewmate, ZombieModel<UndeadCrewmate>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(
            "elijah", "textures/entity/undead_pirate_captain.png");

    public UndeadCrewmateRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(UndeadCrewmate entity) {
        return TEXTURE;
    }
}
