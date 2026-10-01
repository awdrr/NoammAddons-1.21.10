package com.github.noamm9.mixin;

import com.github.noamm9.features.impl.misc.ScrollableTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = GuiGraphics.class)
public abstract class MixinGuiGraphics {
    @Shadow @Final private Matrix3x2fStack pose;

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void onRenderTooltipPre(Font font, List<ClientTooltipComponent> list, int i, int j, ClientTooltipPositioner clientTooltipPositioner, @Nullable Identifier resourceLocation, CallbackInfo ci) {
        if (list.isEmpty()) {
            ci.cancel();
            return;
        }

        if (ScrollableTooltip.INSTANCE.enabled) {
            float scale = (1 * (ScrollableTooltip.INSTANCE.getScale().getValue().floatValue() / 100f)) + ScrollableTooltip.scaleOverride / 10;
            pose.pushMatrix();
            pose.translate(i, j);
            pose.scale(scale);
            pose.translate(ScrollableTooltip.scrollAmountX, ScrollableTooltip.scrollAmountY);
            pose.translate(-i, -j);
        }
    }

    @Inject(method = "renderTooltip", at = @At("TAIL"))
    private void onRenderTooltipPost(CallbackInfo ci) {
        if (ScrollableTooltip.INSTANCE.enabled) {
            pose.popMatrix();
        }
    }
}