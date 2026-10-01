package com.github.noamm9.utils.render

import com.github.noamm9.mixin.IRenderType
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

object NoammRenderLayers {
    // line width is set per vertex since 1.21.11 (VertexConsumer#setLineWidth)
    val LINES = create("lines", RenderPipelines.LINES)
    val LINES_THROUGH_WALLS = create("lines_through_walls", NoammRenderPipelines.LINES_THROUGH_WALLS)
    val FILLED = create("filled", RenderPipelines.DEBUG_FILLED_BOX, sortOnUpload = true)
    val FILLED_THROUGH_WALLS = create("filled_through_walls", NoammRenderPipelines.FILLED_THROUGH_WALLS, sortOnUpload = true)

    private fun create(name: String, pipeline: RenderPipeline, sortOnUpload: Boolean = false): RenderType {
        val setup = RenderSetup.builder(pipeline)
            .bufferSize(RenderType.TRANSIENT_BUFFER_SIZE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
        if (sortOnUpload) setup.sortOnUpload()
        return IRenderType.create(name, setup.createRenderSetup())
    }
}
