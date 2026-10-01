package com.github.noamm9.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface IRenderType {
    @Invoker("create")
    static RenderType create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
