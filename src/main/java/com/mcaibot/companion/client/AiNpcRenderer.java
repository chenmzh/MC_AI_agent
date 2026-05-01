package com.mcaibot.companion.client;

import com.mcaibot.companion.AiNpcEntity;
import com.mcaibot.companion.McAiCompanion;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

public class AiNpcRenderer extends HumanoidMobRenderer<AiNpcEntity, PlayerModel<AiNpcEntity>> {
    private static final ResourceLocation CODEX_TEXTURE = ResourceLocation.fromNamespaceAndPath(McAiCompanion.MODID, "textures/entity/codexbot.png");
    private static final ResourceLocation FEMALE_TEXTURE = ResourceLocation.fromNamespaceAndPath(McAiCompanion.MODID, "textures/entity/female_companion.png");
    private static final ResourceLocation SCOUT_TEXTURE = ResourceLocation.fromNamespaceAndPath(McAiCompanion.MODID, "textures/entity/scout_companion.png");
    private static final ResourceLocation BUILDER_TEXTURE = ResourceLocation.fromNamespaceAndPath(McAiCompanion.MODID, "textures/entity/builder_companion.png");

    public AiNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_OUTER_ARMOR)),
                context.getModelManager()
        ));
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(AiNpcEntity entity) {
        String skin = entity.skin().toLowerCase(Locale.ROOT).trim();
        if (skin.contains("female") || skin.contains("woman") || skin.contains("girl")
                || skin.contains("pretty") || skin.contains("beautiful")
                || skin.equals("player") || skin.contains("女性") || skin.contains("女生") || skin.contains("女孩子") || skin.contains("漂亮")) {
            return FEMALE_TEXTURE;
        }
        if (skin.contains("scout") || skin.contains("lina") || skin.contains("探险") || skin.contains("侦察")) {
            return SCOUT_TEXTURE;
        }
        if (skin.contains("builder") || skin.contains("mason") || skin.contains("建筑") || skin.contains("工匠")) {
            return BUILDER_TEXTURE;
        }
        if (skin.contains("codex") || skin.contains("robot") || skin.contains("机器人")) {
            return CODEX_TEXTURE;
        }
        return FEMALE_TEXTURE;
    }
}
