package com.jamesrenrold.elijah.client;

import com.jamesrenrold.elijah.FlintlockBall;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** A small, genuinely round black shot, using Minecraft's existing black-concrete texture. */
public final class FlintlockRenderer extends EntityRenderer<FlintlockBall> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/block/black_concrete.png");
    private static final int RINGS = 6, SLICES = 10;

    public FlintlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override
    public void render(FlintlockBall entity, float yaw, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int light) {
        poses.pushPose();
        poses.translate(0, 0.09, 0);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for (int ring = 0; ring < RINGS; ring++) {
            double a = Math.PI * ring / RINGS;
            double b = Math.PI * (ring + 1) / RINGS;
            for (int slice = 0; slice < SLICES; slice++) {
                double c = 2 * Math.PI * slice / SLICES;
                double d = 2 * Math.PI * (slice + 1) / SLICES;
                vertex(vertices, poses.last(), a, c, light);
                vertex(vertices, poses.last(), b, c, light);
                vertex(vertices, poses.last(), b, d, light);
                vertex(vertices, poses.last(), a, d, light);
            }
        }
        poses.popPose();
        super.render(entity, yaw, partialTick, poses, buffers, light);
    }

    private void vertex(VertexConsumer vertices, PoseStack.Pose pose, double latitude, double longitude, int light) {
        float x = (float) (Math.sin(latitude) * Math.cos(longitude));
        float y = (float) Math.cos(latitude);
        float z = (float) (Math.sin(latitude) * Math.sin(longitude));
        vertices.vertex(pose.pose(), x * 0.09F, y * 0.09F, z * 0.09F)
                .color(255, 255, 255, 255).uv((float) (longitude / (2 * Math.PI)), (float) (latitude / Math.PI))
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.normal(), x, y, z).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(FlintlockBall entity) { return TEXTURE; }
}
