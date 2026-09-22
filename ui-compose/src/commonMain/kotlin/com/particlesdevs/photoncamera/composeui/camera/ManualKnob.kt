package com.particlesdevs.photoncamera.composeui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.resources.Res
import com.particlesdevs.photoncamera.composeui.resources.manual_icon_focus_far
import com.particlesdevs.photoncamera.composeui.resources.manual_icon_focus_near
import com.particlesdevs.photoncamera.composeui.state.ManualKnobIcon
import com.particlesdevs.photoncamera.composeui.state.ManualKnobItem
import com.particlesdevs.photoncamera.composeui.state.ManualKnobState
import com.particlesdevs.photoncamera.composeui.theme.PhotonColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/** circularbarlib's res/values/dimens.xml, and manual_palette.xml's 96dp strip. */
private val KnobHeight = 96.dp
private val IconPadding = 10.dp
private val DashPadding = 11.dp
private val DashLength = 8.dp
private val KnobIconSize = 17.dp
private val KnobTextSize = 13.sp

/** KnobView.isTooCloseToCenter, in the raw pixels it used. */
private const val DEAD_ZONE_PX = 50.0

/**
 * KnobView, drawn instead of inflated.
 *
 * The wheel's centre is well BELOW the strip: the ticks ride the top of a circle
 * whose radius the box's own aspect fixes, which is what makes 96dp read as a big
 * dial. [ManualKnobState] carries the geometry KnobInfo held, and the tick maths
 * is KnobView's -- see its rotationOfTick/tickAtRotation.
 *
 * The widget owns only the angle the finger is holding. WHICH TICK IS SELECTED IS
 * THE MODEL'S: a turn reports through [onTick] and comes back as
 * [ManualKnobState.selectedTick], exactly as KnobView's listener did.
 */
@Composable
fun ManualKnob(
    knob: ManualKnobState,
    /** Device orientation in degrees; the glyphs turn back so they stay readable. */
    orientation: Int,
    onTick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val near = painterResource(Res.drawable.manual_icon_focus_near)
    val far = painterResource(Res.drawable.manual_icon_focus_far)

    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // THE GESTURE IS KEYED ON THE WHEEL, NOT ON THE STATE.  Every tick the
    // finger crosses comes back as a new ManualKnobState, and keying the
    // pointer input on that tears the drag down and starts it again mid-turn --
    // three of five moves reached the model.  The items are what identify the
    // wheel, and they compare equal until the console puts another one up.
    val live by rememberUpdatedState(knob)
    var dragging by remember { mutableStateOf(false) }
    // The tick the finger last reported. It comes back as selectedTick a moment
    // later, and re-animating to it would fight the drag it came from.
    var reported by remember { mutableStateOf<Int?>(null) }

    // A new model on the knob is a new wheel; no angle of the old one survives.
    LaunchedEffect(knob.items) { reported = null }
    LaunchedEffect(knob.items, knob.selectedTick, dragging) {
        if (dragging || reported == knob.selectedTick) return@LaunchedEffect
        // KnobView.setTickByValue's setKnobViewRotationSmooth.
        rotation.animateTo(knob.rotationOfTick(knob.selectedTick).toFloat(), tween(100))
    }

    val upright = uprightAngle(orientation)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(KnobHeight)
            .pointerInput(knob.items) {
                val centre = rotationCentre(size.width.toFloat(), size.height.toFloat())
                // KnobView's m_DrawableLastDegree/m_InitRadius.  The angle is kept
                // here and not read back off the Animatable: snapTo is a suspend
                // call and the next move must not compute its delta from a value
                // that has not landed yet.
                var held = 0.0
                var from = 0.0
                detectDragGestures(
                    onDragStart = { at ->
                        if (tooCloseToCentre(at, centre)) return@detectDragGestures
                        from = angleAt(at, centre)
                        held = rotation.value.toDouble()
                        dragging = true
                    },
                    onDragEnd = {
                        if (!dragging) return@detectDragGestures
                        dragging = false
                        // onRotationEndFromTouch: the wheel settles on the tick it
                        // stopped over, and does it without an animation.
                        val tick = live.tickAtRotation(held)
                        held = live.rotationOfTick(tick)
                        scope.launch { rotation.snapTo(held.toFloat()) }
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    if (!dragging) return@detectDragGestures
                    if (tooCloseToCentre(change.position, centre)) {
                        dragging = false
                        return@detectDragGestures
                    }
                    change.consume()
                    val now = angleAt(change.position, centre)
                    // mapToKnobRotationDegree's negation: the wheel turns AGAINST
                    // the finger, so dragging left brings the values on the right
                    // round to the middle.
                    held = live.validateRotation(held - degrees(now - from))
                    from = now
                    scope.launch { rotation.snapTo(held.toFloat()) }
                    val tick = live.tickAtRotation(held)
                    if (tick != reported) {
                        reported = tick
                        onTick(tick)
                    }
                }
            },
    ) {
        if (knob.items.isEmpty()) return@Canvas
        val centre = rotationCentre(size.width, size.height)
        val turned = rotation.value.toDouble()

        drawCircle(PhotonColors.PanelTransparency, radius = centre.y, center = centre)

        val labels = knob.items.filter { !it.label.isNullOrEmpty() }
        val glyphs = labels.associateWith { measurer.measure(it.label.orEmpty(), knobTextStyle(it, knob)) }
        drawDashRuler(labels, glyphs, knob, centre, turned)
        glyphs.forEach { (item, layout) -> drawKnobLabel(item, layout, knob, centre, turned, upright) }
        knob.items.forEach { item ->
            val painter = when (item.icon) {
                ManualKnobIcon.FOCUS_NEAR -> near
                ManualKnobIcon.FOCUS_FAR -> far
                ManualKnobIcon.NONE -> return@forEach
            }
            drawKnobIcon(item, knob, centre, turned, upright, painter)
        }
    }
}

private fun knobTextStyle(item: ManualKnobItem, knob: ManualKnobState) = TextStyle(
    // ManualModeKnobTextSelected differs from its parent in the colour alone.
    color = if (item.tick == knob.selectedTick) PhotonColors.Accent else Color.White,
    fontSize = KnobTextSize,
    fontWeight = FontWeight.Bold,
)

/**
 * KnobView.evaluateRotationCenter: the circle through the two bottom corners and
 * the midpoint of the top edge. Its centre is below the widget, and the y it sits
 * at IS the radius.
 */
private fun rotationCentre(width: Float, height: Float): Offset {
    if (height <= 0f) return Offset(width / 2f, 1f)
    val fanEdge = sqrt((width / 2f) * (width / 2f) + height * height)
    return Offset(width / 2f, (fanEdge * fanEdge) / (2f * height))
}

/** KnobView.evaluateRotation: radians, zero straight up, growing clockwise. */
private fun angleAt(at: Offset, centre: Offset): Double =
    atan2((at.x - centre.x).toDouble(), -(at.y - centre.y).toDouble())

private fun tooCloseToCentre(at: Offset, centre: Offset): Boolean {
    val dx = (at.x - centre.x).toDouble()
    val dy = (at.y - centre.y).toDouble()
    return sqrt(dx * dx + dy * dy) < DEAD_ZONE_PX
}

private fun degrees(radians: Double): Double = radians * 180.0 / PI

/** The angle a glyph turns back by to stay readable; common/Rotating's rule. */
private fun uprightAngle(orientation: Int): Float {
    val wrapped = ((orientation % 360) + 360) % 360
    return (if (wrapped > 180) wrapped - 360 else wrapped).toFloat()
}

/**
 * The ruler between two labelled ticks: one dash a degree, with the arc each label
 * covers left clear. The unlabelled ticks between them are not drawn -- KnobView
 * gave them an empty drawable, and this ruler is what the gap shows instead.
 */
private fun DrawScope.drawDashRuler(
    labels: List<ManualKnobItem>,
    glyphs: Map<ManualKnobItem, TextLayoutResult>,
    knob: ManualKnobState,
    centre: Offset,
    turned: Double,
) {
    if (labels.size < 2) return
    val top = DashPadding.toPx()
    val bottom = top + DashLength.toPx()
    val x = size.width / 2f
    val radius = (centre.y - top).toDouble()
    // updateKnobItemsBounds' drawableAngleHalf, plus the 2 degrees of air the
    // dash loop left on each side of a glyph.
    fun clearance(item: ManualKnobItem): Double {
        val half = (glyphs[item]?.size?.width ?: 0) / 2.0
        return degrees(atan2(half, radius)) + 2.0
    }
    for (i in 0 until labels.size - 1) {
        val from = knob.rotationOfTick(labels[i].tick) + clearance(labels[i])
        val to = knob.rotationOfTick(labels[i + 1].tick) - clearance(labels[i + 1])
        var angle = from
        while (angle < to) {
            rotate((angle - turned).toFloat(), centre) {
                drawLine(Color.White, Offset(x, top), Offset(x, bottom), strokeWidth = 2f)
            }
            angle += 1.0
        }
    }
}

private fun DrawScope.drawKnobLabel(
    item: ManualKnobItem,
    layout: TextLayoutResult,
    knob: ManualKnobState,
    centre: Offset,
    turned: Double,
    upright: Float,
) {
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val left = size.width / 2f - w / 2f
    val top = IconPadding.toPx()
    rotate((knob.rotationOfTick(item.tick) - turned).toFloat(), centre) {
        // setKnobItemsRotation: the glyph rides the arc, then turns back on its
        // own centre so a phone held sideways still reads it.
        rotate(upright, Offset(left + w / 2f, top + h / 2f)) {
            drawText(layout, topLeft = Offset(left, top))
        }
    }
}

private fun DrawScope.drawKnobIcon(
    item: ManualKnobItem,
    knob: ManualKnobState,
    centre: Offset,
    turned: Double,
    upright: Float,
    painter: Painter,
) {
    val side = KnobIconSize.toPx()
    val left = size.width / 2f - side / 2f
    val top = IconPadding.toPx()
    val tint = if (item.tick == knob.selectedTick) PhotonColors.Accent else Color.White
    rotate((knob.rotationOfTick(item.tick) - turned).toFloat(), centre) {
        rotate(upright, Offset(left + side / 2f, top + side / 2f)) {
            translate(left, top) {
                with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(tint)) }
            }
        }
    }
}
