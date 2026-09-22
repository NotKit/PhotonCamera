package com.particlesdevs.photoncamera.composeui.state

import androidx.compose.runtime.Immutable

/** circularbarlib's ManualParam: the five knobs of the manual bar. */
enum class ManualParam { ISO, EXPOSURE, EV, FOCUS, WB }

/** circularbarlib's KnobIcon: what a tick draws instead of a label. */
enum class ManualKnobIcon { NONE, FOCUS_NEAR, FOCUS_FAR }

/**
 * One tick of the knob. KnobItemInfo without the Drawable the View cached on it,
 * and without the three rotation fields, which the widget works out from [tick].
 */
@Immutable
data class ManualKnobItem(
    val tick: Int,
    val value: Double,
    /** The value text the manual bar shows; always set. */
    val text: String,
    /** Drawn at this tick, or null for a bare tick. */
    val label: String? = null,
    val icon: ManualKnobIcon = ManualKnobIcon.NONE,
)

/**
 * KnobInfo, the items and the tick under the pointer: everything the widget needs
 * to place a tick, and nothing about the model behind it.
 */
@Immutable
data class ManualKnobState(
    val angleMin: Int = 0,
    val angleMax: Int = 0,
    val tickMin: Int = 0,
    val tickMax: Int = 0,
    val autoAngle: Int = 0,
    val items: List<ManualKnobItem> = emptyList(),
    val selectedTick: Int = 0,
) {
    /** Degrees between two neighbouring ticks; KnobView's includedAngle. */
    val includedAngle: Double
        get() = if (tickMax == tickMin) 0.0
        else ((angleMax - angleMin - autoAngle).toDouble()) / (tickMax - tickMin)

    /** KnobView.mapTickToRotation: where a tick sits, before the knob is turned. */
    fun rotationOfTick(tick: Int): Double =
        validateRotation(tick * includedAngle + (sign(tick) * autoAngle) / 2)

    /** KnobView.mapRotationToTick: the tick a turn of [rotation] degrees lands on. */
    fun tickAtRotation(rotation: Double): Int {
        var previous = Double.MAX_VALUE
        for (i in tickMin..tickMax) {
            val diff = kotlin.math.abs(i * includedAngle + (sign(i) * autoAngle) / 2 - rotation)
            if (diff < previous) previous = diff else return validateTick(i - 1)
        }
        return tickMax
    }

    fun validateRotation(rotation: Double): Double = rotation.coerceIn(
        angleMin.toDouble(), angleMax.toDouble(),
    )

    fun validateTick(tick: Int): Int = tick.coerceIn(tickMin, tickMax)

    fun itemAtTick(tick: Int): ManualKnobItem? = items.firstOrNull { it.tick == tick }

    // Integer.signum, and the /2 below it is Java's integer division: an odd
    // autoAngle leaves the auto gap one degree narrow on each side, as it did.
    private fun sign(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0
}

/**
 * What the manual-mode console shows. ManualModeModel's texts and selection, plus
 * KnobModel's visibility and the model it put on the knob.
 */
@Immutable
data class ManualBarState(
    /** ManualModeModel.manualPanelVisible: the panel as a whole. */
    val visible: Boolean = false,
    /** KnobModel.knobVisible: the dial above the five tabs. */
    val knobVisible: Boolean = false,
    val knob: ManualKnobState = ManualKnobState(),
    val selected: ManualParam? = null,
    /** ManualModeModel's five value texts, by the knob each belongs to. */
    val values: Map<ManualParam, String> = emptyMap(),
    /** The controls counter-rotate with the device, as the View palette did. */
    val orientation: Int = 0,
)
