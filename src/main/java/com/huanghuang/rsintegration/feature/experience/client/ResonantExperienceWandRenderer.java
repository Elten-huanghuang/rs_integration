package com.huanghuang.rsintegration.feature.experience.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.CorePixel;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.Orbit;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.Point;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.Quad;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.List;

/** 实体短弧配合无方向光的共面像素光核。 */
public final class ResonantExperienceWandRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ResourceLocation SURFACE = new ResourceLocation(RSIntegrationMod.MOD_ID,
            "textures/item/resonant_experience_wand_surface.png");
    private static final RenderType RENDER_TYPE = RenderType.entityCutoutNoCull(SURFACE);

    public ResonantExperienceWandRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poses,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        double ticks = minecraft.level == null
                ? (Util.getMillis() % (ExperienceWandGeometry.LOOP_TICKS * 50L)) / 50.0
                : (minecraft.level.getGameTime() % ExperienceWandGeometry.LOOP_TICKS)
                        + minecraft.getFrameTime();
        VertexConsumer vertices = ItemRenderer.getFoilBufferDirect(
                buffers, RENDER_TYPE, true, stack.hasFoil());
        poses.pushPose();
        poses.translate(0.5, 0.5, 0.5);
        poses.scale(1.0F / 16, 1.0F / 16, 1.0F / 16);
        poses.translate(0, -ExperienceWandGeometry.MODEL_CENTER_Y, 0);
        draw(ExperienceWandGeometry.BODY, poses.last(), vertices, packedLight, packedOverlay, false);
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            drawOrbit(orbit, ticks, poses, vertices, packedLight, packedOverlay);
        }
        drawCore(ticks, poses.last(), vertices, packedOverlay);
        poses.popPose();
    }

    private static void drawOrbit(Orbit orbit, double ticks, PoseStack poses, VertexConsumer vertices,
                                   int packedLight, int packedOverlay) {
        poses.pushPose();
        poses.translate(0, ExperienceWandGeometry.CORE_Y, 0);
        poses.mulPose(Axis.XP.rotationDegrees(orbit.tiltX()));
        poses.mulPose(Axis.YP.rotationDegrees(orbit.tiltY()));
        poses.mulPose(Axis.ZP.rotationDegrees(orbit.tiltZ()));
        poses.mulPose(Axis.ZP.rotationDegrees(orbit.angle(ticks)));
        draw(orbit.mesh(), poses.last(), vertices, packedLight, packedOverlay, true);
        poses.popPose();
    }

    private static void drawCore(double ticks, PoseStack.Pose pose, VertexConsumer vertices,
                                 int packedOverlay) {
        // 所有像素共面且不透明，透视变换后仍严丝合缝地拼成一整块。
        Matrix3f viewToModel = new Matrix3f(pose.pose()).invert();
        Point right = direction(viewToModel, 1, 0, 0);
        Point up = direction(viewToModel, 0, 1, 0);
        Point forward = direction(viewToModel, 0, 0, 1);
        double frame = ExperienceWandGeometry.coreFramePosition(ticks);
        int index = (int) frame;
        float blend = (float) (frame - index);
        List<CorePixel> from = ExperienceWandGeometry.LIGHT_CORES.get(index);
        List<CorePixel> to = ExperienceWandGeometry.LIGHT_CORES.get(
                (index + 1) % ExperienceWandGeometry.CORE_FRAME_COUNT);
        double half = ExperienceWandGeometry.CORE_PIXEL_SIZE / 2;
        for (int i = 0; i < from.size(); i++) {
            CorePixel a = from.get(i);
            CorePixel b = to.get(i);
            int red = (int) lerp((a.color() >> 16) & 255, (b.color() >> 16) & 255, blend);
            int green = (int) lerp((a.color() >> 8) & 255, (b.color() >> 8) & 255, blend);
            int blue = (int) lerp(a.color() & 255, b.color() & 255, blend);
            coreVertex(vertices, pose, a.corner(-half, -half, right, up, forward),
                    forward, red, green, blue, packedOverlay);
            coreVertex(vertices, pose, a.corner(half, -half, right, up, forward),
                    forward, red, green, blue, packedOverlay);
            coreVertex(vertices, pose, a.corner(half, half, right, up, forward),
                    forward, red, green, blue, packedOverlay);
            coreVertex(vertices, pose, a.corner(-half, half, right, up, forward),
                    forward, red, green, blue, packedOverlay);
        }
    }

    private static Point direction(Matrix3f matrix, float x, float y, float z) {
        Vector3f vector = matrix.transform(new Vector3f(x, y, z)).normalize();
        return new Point(vector.x(), vector.y(), vector.z());
    }

    private static void coreVertex(VertexConsumer vertices, PoseStack.Pose pose, Point point,
                                     Point normal, int red, int green, int blue, int overlay) {
        vertices.vertex(pose.pose(), (float) point.x(), (float) point.y(), (float) point.z())
                .color(red, green, blue, 255).uv(0.03125F, 0.03125F).overlayCoords(overlay)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), (float) normal.x(), (float) normal.y(),
                        (float) normal.z()).endVertex();
    }

    private static float lerp(double from, double to, float blend) {
        return (float) (from + (to - from) * blend);
    }

    private static void draw(List<Quad> mesh, PoseStack.Pose pose, VertexConsumer vertices,
                              int packedLight, int packedOverlay, boolean pixelated) {
        // 每个环带方格保持一整块颜色，不在单格内重复铺满细碎的表面噪点。
        float low = pixelated ? 0.03125F : 0;
        float high = pixelated ? 0.03125F : 1;
        for (Quad quad : mesh) {
            int light = quad.emissive() ? LightTexture.FULL_BRIGHT : packedLight;
            vertex(vertices, pose, quad.a(), quad, low, low, light, packedOverlay);
            vertex(vertices, pose, quad.b(), quad, high, low, light, packedOverlay);
            vertex(vertices, pose, quad.c(), quad, high, high, light, packedOverlay);
            vertex(vertices, pose, quad.d(), quad, low, high, light, packedOverlay);
        }
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, Point point,
                                Quad quad, float u, float v, int light, int overlay) {
        vertices.vertex(pose.pose(), (float) point.x(), (float) point.y(), (float) point.z())
                .color((quad.color() >> 16) & 255, (quad.color() >> 8) & 255,
                        quad.color() & 255, quad.alpha())
                .uv(u, v).overlayCoords(overlay).uv2(light)
                .normal(pose.normal(), (float) quad.normal().x(), (float) quad.normal().y(),
                        (float) quad.normal().z()).endVertex();
    }
}
