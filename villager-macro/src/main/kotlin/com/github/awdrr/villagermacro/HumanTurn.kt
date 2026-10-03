package com.github.awdrr.villagermacro

import net.minecraft.util.Mth
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One turn of the camera that looks like a hand on a mouse rather than a straight line:
 * - bigger turns take longer, small corrections are quick, with some randomness on top
 * - the path bows out to one side instead of going straight
 * - it speeds up quickly and slows down gently at the end
 * - bigger turns sometimes go a little past the target and settle back
 * - a slight wobble along the way that fades out, so it still ends exactly on the target
 */
class HumanTurn(private val fromYaw: Float, private val fromPitch: Float, targetYaw: Float, targetPitch: Float, baseDurationMs: Int) {
    val toYaw = fromYaw + Mth.wrapDegrees(targetYaw - fromYaw)
    val toPitch = targetPitch.coerceIn(- 90f, 90f)

    private val startedAt = System.currentTimeMillis()
    private val durationMs: Long

    /** Control point of the curve: where the path bends towards. */
    private val bendYaw: Float
    private val bendPitch: Float

    /** Where the main move ends: the target, or a bit past it when overshooting. */
    private val endYaw: Float
    private val endPitch: Float

    /** Share of the time spent on the main move; the rest settles back onto the target after an overshoot. */
    private val mainShare: Float
    private val skew = rand(0.7f, 0.9f)

    private val wobbleSize: Float
    private val wobbleSpeed = rand(1.5f, 3.5f)
    private val wobblePhase = rand(0f, 2 * PI.toFloat())

    var finished = false

    init {
        val dYaw = toYaw - fromYaw
        val dPitch = toPitch - fromPitch
        val distance = sqrt(dYaw * dYaw + dPitch * dPitch)
        durationMs = (baseDurationMs * (0.55f + 0.45f * min(1f, distance / 90f)) * rand(0.85f, 1.15f)).toLong()

        if (distance < 0.01f) {
            bendYaw = toYaw
            bendPitch = toPitch
            endYaw = toYaw
            endPitch = toPitch
            mainShare = 1f
            wobbleSize = 0f
        }
        else {
            val dirYaw = dYaw / distance
            val dirPitch = dPitch / distance

            val sideways = rand(0.08f, 0.22f) * distance * (if (Random.nextBoolean()) 1 else - 1)
            val along = rand(0.35f, 0.6f)
            bendYaw = fromYaw + dYaw * along - dirPitch * sideways
            bendPitch = fromPitch + dPitch * along + dirYaw * sideways

            val overshoot = if (distance > 8f && Random.nextFloat() < 0.45f) min(3f, rand(0.02f, 0.06f) * distance) else 0f
            endYaw = toYaw + dirYaw * overshoot
            endPitch = toPitch + dirPitch * overshoot
            mainShare = if (overshoot > 0f) rand(0.78f, 0.88f) else 1f

            wobbleSize = min(0.6f, distance * 0.012f) * rand(0.5f, 1f)
        }
    }

    /** 0 at the start, 1 once the turn is done. */
    fun progress(now: Long = System.currentTimeMillis()) = if (durationMs <= 0) 1f else min(1f, (now - startedAt) / durationMs.toFloat())

    /** Yaw and pitch the camera should have at [t] (0..1); exactly the target at 1. */
    fun angleAt(t: Float): Pair<Float, Float> {
        var yaw: Float
        var pitch: Float

        if (t < mainShare || mainShare >= 1f) {
            val u = smoothstep((t / mainShare).coerceIn(0f, 1f).pow(skew))
            yaw = bezier(fromYaw, bendYaw, endYaw, u)
            pitch = bezier(fromPitch, bendPitch, endPitch, u)
        }
        else {
            val u = smoothstep((t - mainShare) / (1f - mainShare))
            yaw = endYaw + (toYaw - endYaw) * u
            pitch = endPitch + (toPitch - endPitch) * u
        }

        val fade = sin(PI.toFloat() * t)
        yaw += wobbleSize * fade * sin(2 * PI.toFloat() * wobbleSpeed * t + wobblePhase)
        pitch += wobbleSize * 0.6f * fade * sin(2 * PI.toFloat() * wobbleSpeed * 1.3f * t + wobblePhase * 1.7f)
        return yaw to pitch.coerceIn(- 90f, 90f)
    }

    private fun bezier(a: Float, control: Float, b: Float, u: Float) = (1 - u) * (1 - u) * a + 2 * (1 - u) * u * control + u * u * b

    private fun smoothstep(x: Float) = x * x * (3 - 2 * x)

    private fun rand(from: Float, to: Float) = from + Random.nextFloat() * (to - from)
}
