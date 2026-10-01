package com.github.noamm9.utils.render

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.ChatUtils.addColor
import com.github.noamm9.utils.NumbersUtils.minus
import com.github.noamm9.utils.NumbersUtils.plus
import com.github.noamm9.utils.NumbersUtils.times
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Matrix4f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

object Render3D {
    fun renderBlock(
        ctx: RenderContext,
        pos: BlockPos,
        outlineColor: Color,
        fillColor: Color = outlineColor,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) {
        if (! outline && ! fill) return

        val state = mc.level?.getBlockState(pos) ?: return
        val mstack = ctx.matrixStack ?: return
        val consumers = ctx.consumers ?: return
        val camPos = ctx.camera.position()
        val shape = if (state.block != Blocks.AIR) state.getShape(mc.level !!, pos) else Shapes.block()

        val outlineR = outlineColor.red / 255f
        val outlineG = outlineColor.green / 255f
        val outlineB = outlineColor.blue / 255f

        val fillR = fillColor.red / 255f
        val fillG = fillColor.green / 255f
        val fillB = fillColor.blue / 255f
        val fillA = fillColor.alpha / 255f

        val minX = pos.x + shape.min(Direction.Axis.X) - 0.002
        val minY = pos.y + shape.min(Direction.Axis.Y) - 0.002
        val minZ = pos.z + shape.min(Direction.Axis.Z) - 0.002
        val maxX = pos.x + shape.max(Direction.Axis.X) + 0.002
        val maxY = pos.y + shape.max(Direction.Axis.Y) + 0.002
        val maxZ = pos.z + shape.max(Direction.Axis.Z) + 0.002

        val x1 = minX - camPos.x
        val y1 = minY - camPos.y
        val z1 = minZ - camPos.z
        val x2 = maxX - camPos.x
        val y2 = maxY - camPos.y
        val z2 = maxZ - camPos.z

        if (fill) filledBox(
            mstack,
            consumers.getBuffer(if (phase) NoammRenderLayers.FILLED_THROUGH_WALLS else NoammRenderLayers.FILLED),
            x1, y1, z1,
            x2, y2, z2,
            fillR, fillG, fillB, fillA
        )

        if (outline) lineBox(
            mstack.last(),
            consumers.getBuffer(if (phase) NoammRenderLayers.LINES_THROUGH_WALLS else NoammRenderLayers.LINES),
            x1, y1, z1,
            x2, y2, z2,
            outlineR, outlineG, outlineB, 1f,
            lineWidth.toFloat()
        )
    }

    fun renderBlock(
        ctx: RenderContext,
        pos: BlockPos,
        color: Color,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) = renderBlock(ctx, pos, color, color, outline, fill, phase, lineWidth)

    fun renderCircle(
        ctx: RenderContext,
        center: Vec3,
        radius: Number,
        color: Color,
        thickness: Number = 2,
        phase: Boolean = false
    ) {
        val matrices = ctx.matrixStack ?: return
        val cameraPos = mc.gameRenderer.mainCamera.position()
        val segments = (36 * radius).toInt()

        matrices.pushPose()
        matrices.translate(- cameraPos.x, - cameraPos.y, - cameraPos.z)

        val buffer = ctx.consumers !!.getBuffer(if (phase) NoammRenderLayers.FILLED_THROUGH_WALLS else NoammRenderLayers.FILLED)

        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        val a = color.alpha / 255f
        val entry = matrices.last().pose()

        val size = thickness.toDouble() / 40.0
        val innerR = radius - size
        val outerR = radius + size
        val bottomY = (center.y - size).toFloat()
        val topY = (center.y + size).toFloat()

        for (i in 0 until segments) {
            val angle1 = i * (2.0 * Math.PI / segments)
            val angle2 = (i + 1) * (2.0 * Math.PI / segments)

            val c1 = cos(angle1).toFloat()
            val s1 = sin(angle1).toFloat()
            val c2 = cos(angle2).toFloat()
            val s2 = sin(angle2).toFloat()

            val x1Inner = (center.x + innerR * c1).toFloat()
            val z1Inner = (center.z + innerR * s1).toFloat()
            val x1Outer = (center.x + outerR * c1).toFloat()
            val z1Outer = (center.z + outerR * s1).toFloat()

            val x2Inner = (center.x + innerR * c2).toFloat()
            val z2Inner = (center.z + innerR * s2).toFloat()
            val x2Outer = (center.x + outerR * c2).toFloat()
            val z2Outer = (center.z + outerR * s2).toFloat()

            buffer.addVertex(entry, x1Inner, topY, z1Inner).setColor(r, g, b, a)
            buffer.addVertex(entry, x1Outer, topY, z1Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Outer, topY, z2Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Inner, topY, z2Inner).setColor(r, g, b, a)

            buffer.addVertex(entry, x1Outer, bottomY, z1Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x1Outer, topY, z1Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Outer, topY, z2Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Outer, bottomY, z2Outer).setColor(r, g, b, a)

            buffer.addVertex(entry, x1Inner, bottomY, z1Inner).setColor(r, g, b, a)
            buffer.addVertex(entry, x1Inner, topY, z1Inner).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Inner, topY, z2Inner).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Inner, bottomY, z2Inner).setColor(r, g, b, a)

            buffer.addVertex(entry, x1Inner, bottomY, z1Inner).setColor(r, g, b, a)
            buffer.addVertex(entry, x1Outer, bottomY, z1Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Outer, bottomY, z2Outer).setColor(r, g, b, a)
            buffer.addVertex(entry, x2Inner, bottomY, z2Inner).setColor(r, g, b, a)
        }

        matrices.popPose()
    }

    fun renderBox(
        ctx: RenderContext,
        x: Number,
        y: Number,
        z: Number,
        width: Number,
        height: Number,
        outlineColor: Color,
        fillColor: Color = outlineColor,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) {
        if (! outline && ! fill) return

        val consumers = ctx.consumers ?: return
        val matrices = ctx.matrixStack ?: return
        val cam = ctx.camera.position().reverse()

        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()
        val hw = width.toDouble() / 2.0
        val hd = height.toDouble()

        matrices.pushPose()
        matrices.translate(cam.x, cam.y, cam.z)

        if (fill) filledBox(
            matrices,
            consumers.getBuffer(if (phase) NoammRenderLayers.FILLED_THROUGH_WALLS else NoammRenderLayers.FILLED),
            xd - hw, yd, zd - hw,
            xd + hw, yd + hd, zd + hw,
            fillColor.red / 255f, fillColor.green / 255f, fillColor.blue / 255f, fillColor.alpha / 255f
        )

        if (outline) lineBox(
            matrices.last(),
            consumers.getBuffer(if (phase) NoammRenderLayers.LINES_THROUGH_WALLS else NoammRenderLayers.LINES),
            xd - hw, yd, zd - hw,
            xd + hw, yd + hd, zd + hw,
            outlineColor.red / 255f, outlineColor.green / 255f, outlineColor.blue / 255f, 1f,
            lineWidth.toFloat()
        )

        matrices.popPose()
    }

    fun renderBox(
        ctx: RenderContext,
        x: Number,
        y: Number,
        z: Number,
        width: Number,
        height: Number,
        color: Color = Color.CYAN,
        outline: Boolean = true,
        fill: Boolean = true,
        phase: Boolean = false,
        lineWidth: Number = 2.5
    ) = renderBox(ctx, x, y, z, width, height, color, color, outline, fill, phase, lineWidth)

    fun renderString(
        text: String,
        x: Number, y: Number, z: Number,
        color: Color = Color.WHITE,
        scale: Number = 1f,
        phase: Boolean = false
    ) {
        val toScale = (scale.toFloat() * 0.025f)
        val matrices = Matrix4f()
        val textRenderer = mc.font
        val camera = mc.gameRenderer.mainCamera
        val dx = (x.toDouble() - camera.position().x).toFloat()
        val dy = (y.toDouble() - camera.position().y).toFloat()
        val dz = (z.toDouble() - camera.position().z).toFloat()

        matrices.translate(dx, dy, dz).rotate(camera.rotation()).scale(toScale, - toScale, toScale)

        val consumer = mc.renderBuffers().bufferSource()
        val textLayer = if (phase) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL
        val lines = text.addColor().split("\n")

        for ((i, line) in lines.withIndex()) {
            textRenderer.drawInBatch(
                line,
                - textRenderer.width(line) / 2f,
                i * 9f,
                color.rgb,
                true,
                matrices,
                consumer,
                textLayer,
                0,
                LightTexture.FULL_BLOCK
            )
        }

        consumer.endBatch()
    }

    fun renderString(
        text: String,
        pos: Vec3,
        color: Color = Color.WHITE,
        scale: Number = 1f,
        phase: Boolean = false
    ) = renderString(text, pos.x, pos.y, pos.z, color, scale, phase)


    fun renderLine(ctx: RenderContext, start: Vec3, finish: Vec3, color: Color, thickness: Number = 2) {
        val matrices = ctx.matrixStack ?: return
        val cameraPos = mc.gameRenderer.mainCamera.position()
        matrices.pushPose()
        matrices.translate(- cameraPos.x, - cameraPos.y, - cameraPos.z)

        val buffer = (ctx.consumers as MultiBufferSource.BufferSource).getBuffer(RenderTypes.lines())
        val width = thickness.toFloat()

        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        val a = color.alpha / 255f
        val direction = finish.subtract(start).normalize().toVector3f()
        val entry = matrices.last()

        buffer.addVertex(entry, start.x.toFloat(), start.y.toFloat(), start.z.toFloat()).setColor(r, g, b, a)
            .setNormal(entry, direction).setLineWidth(width)
        buffer.addVertex(entry, finish.x.toFloat(), finish.y.toFloat(), finish.z.toFloat()).setColor(r, g, b, a)
            .setNormal(entry, direction).setLineWidth(width)

        ctx.consumers.endBatch(RenderTypes.lines())
        matrices.popPose()
    }

    fun renderLine(ctx: RenderContext, start: BlockPos, end: BlockPos, thickness: Number, color: Color) {
        renderLine(ctx, Vec3.atCenterOf(start), Vec3.atCenterOf(end), color, thickness)
    }

    fun renderTracer(ctx: RenderContext, point: Vec3, color: Color, thickness: Number = 2) {
        val camera = ctx.camera
        val matrixStack = ctx.matrixStack ?: return
        val consumers = ctx.consumers
        val cameraPos = camera.position()

        matrixStack.pushPose()
        matrixStack.translate(- cameraPos.x, - cameraPos.y, - cameraPos.z)

        val buffer =
            (consumers as MultiBufferSource.BufferSource).getBuffer(NoammRenderLayers.LINES_THROUGH_WALLS)
        val cameraPoint = cameraPos.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()))
        val normal = point.toVector3f().sub(cameraPoint.x.toFloat(), cameraPoint.y.toFloat(), cameraPoint.z.toFloat())
            .normalize()
        val entry = matrixStack.last() ?: return

        val width = thickness.toFloat()

        buffer.addVertex(entry, cameraPoint.x.toFloat(), cameraPoint.y.toFloat(), cameraPoint.z.toFloat())
            .setColor(color.red / 255f, color.green / 255f, color.blue / 255f, 1f)
            .setNormal(entry, normal).setLineWidth(width)
        buffer.addVertex(entry, point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
            .setColor(color.red / 255f, color.green / 255f, color.blue / 255f, 1f).setNormal(entry, normal).setLineWidth(width)

        consumers.endBatch(NoammRenderLayers.LINES_THROUGH_WALLS)
        matrixStack.popPose()
    }

    fun renderTracer(ctx: RenderContext, point: BlockPos, color: Color, thickness: Number) {
        renderTracer(ctx, Vec3.atCenterOf(point), color, thickness)
    }

    /** Outline of a box as 12 line segments; replaces ShapeRenderer.renderLineBox removed in 1.21.11. */
    fun lineBox(
        pose: PoseStack.Pose, consumer: VertexConsumer,
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double,
        r: Float, g: Float, b: Float, a: Float, width: Float
    ) {
        val x1 = minX.toFloat(); val y1 = minY.toFloat(); val z1 = minZ.toFloat()
        val x2 = maxX.toFloat(); val y2 = maxY.toFloat(); val z2 = maxZ.toFloat()

        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, nx: Float, ny: Float, nz: Float) {
            consumer.addVertex(pose, ax, ay, az).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width)
            consumer.addVertex(pose, bx, by, bz).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width)
        }

        for (y in floatArrayOf(y1, y2)) for (z in floatArrayOf(z1, z2)) edge(x1, y, z, x2, y, z, 1f, 0f, 0f)
        for (x in floatArrayOf(x1, x2)) for (z in floatArrayOf(z1, z2)) edge(x, y1, z, x, y2, z, 0f, 1f, 0f)
        for (x in floatArrayOf(x1, x2)) for (y in floatArrayOf(y1, y2)) edge(x, y, z1, x, y, z2, 0f, 0f, 1f)
    }

    /** Filled box as a triangle strip; same vertex order as the removed ShapeRenderer.addChainedFilledBoxVertices. */
    fun filledBox(
        poseStack: PoseStack, consumer: VertexConsumer,
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val matrix = poseStack.last().pose()
        val x1 = minX.toFloat(); val y1 = minY.toFloat(); val z1 = minZ.toFloat()
        val x2 = maxX.toFloat(); val y2 = maxY.toFloat(); val z2 = maxZ.toFloat()
        val xs = floatArrayOf(x1, x2); val ys = floatArrayOf(y1, y2); val zs = floatArrayOf(z1, z2)

        for (corner in FILLED_BOX_STRIP) {
            consumer.addVertex(matrix, xs[corner[0] - '0'], ys[corner[1] - '0'], zs[corner[2] - '0']).setColor(r, g, b, a)
        }
    }

    // corners as xyz with 0 = min, 1 = max
    private val FILLED_BOX_STRIP = listOf(
        "000", "000", "000", "001", "010", "011", "011", "001", "111", "101",
        "101", "100", "111", "110", "110", "100", "010", "000", "000", "100",
        "001", "101", "101", "010", "010", "011", "110", "111", "111", "111"
    )
}
