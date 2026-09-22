/* THE MANUAL-MODE CONSOLE'S SURFACE.  -PwithApp only.
 *
 * circularbarlib/ui/ is in drop.txt: KnobView, ViewObserver and Binding are
 * Views and a View data-binding, and there is no View layer here.  What they
 * DID is not View glue, and this file is that:
 *
 *   ui/ViewObserver.java   -> ManualUiKn.update, field for field
 *   ui/Binding.java        -> the four private functions at the foot of it
 *   ui/views/knobview/     -> composeui.camera.ManualKnob, which draws the dial
 *
 * The seams were cut in the Java for exactly this: `api/ManualUi` says
 * "ViewObserver on Android; the Compose screen on the port", and
 * `control/knob/KnobHost` says "the KnobView on Android, the Compose knob on
 * the port".  Everything below either side of those two is the app's own and
 * unchanged -- ManualModeConsoleImpl, the five ManualModels, ManualParamModel.
 */
package photoncam.screen

import android.app.Activity
import com.particlesdevs.photoncamera.circularbarlib.api.ManualUi
import com.particlesdevs.photoncamera.circularbarlib.api.ManualUiFactory
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobChangedListener
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobHost
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobIcon
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo
import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel
import com.particlesdevs.photoncamera.circularbarlib.model.ParamClickListener
import com.particlesdevs.photoncamera.composeui.state.ManualKnobIcon
import com.particlesdevs.photoncamera.composeui.state.ManualKnobItem
import com.particlesdevs.photoncamera.composeui.state.ManualKnobState
import com.particlesdevs.photoncamera.ui.camera.compose.CameraScreenHost
import com.particlesdevs.photoncamera.circularbarlib.model.ManualParam as ConsoleParam
import com.particlesdevs.photoncamera.composeui.state.ManualParam as UiParam
import java.util.Observable
import java.util.Observer
import kotlin.math.abs

/**
 * ViewObserver and KnobView's state, without either View.
 *
 * It is BOTH ends of the console's UI seam: the [ManualUi] the models notify,
 * and the [KnobHost] the selected model is attached to.  KnobView was the same
 * two things -- it implemented KnobHost and took the model as its listener.
 */
internal class ManualUiKn(private val host: CameraScreenHost) : ManualUi, KnobHost {

	/** The ManualModel on the dial; KnobView's m_KnobViewChangedListener. */
	private var listener: KnobChangedListener? = null
	private var info: KnobInfo? = null
	private var items: List<KnobItemInfo> = emptyList()
	/** KnobView's m_Value and m_Tick, which outlive a model swap as they did. */
	private var current: KnobItemInfo? = null
	private var tick: Int = 0

	/** ManualModeModel's, so a tap on a tab reaches the console's own listener. */
	var clicks: ParamClickListener? = null
		private set

	// -- ManualUi -----------------------------------------------------------

	// The View pair drove an OrientationEventListener; here OrientationWatcher
	// already feeds CameraScreenHost.setOrientation, and the palette reads it.
	override fun onResume() {}

	override fun onPause() {}

	// -- KnobHost: Binding.setModelToKnob's calls land here ------------------

	override fun setKnobInfo(info: KnobInfo?) {
		this.info = info
		publish()
	}

	override fun setKnobItems(items: List<KnobItemInfo>?) {
		this.items = items.orEmpty()
		// updateKnobItemSelection: the tick survives, the item it names does not.
		current = this.items.firstOrNull { it.tick == tick }
		this.items.forEach { it.isSelected = it.tick == tick }
		publish()
	}

	override fun setTickByValue(value: Double) {
		val item = items.firstOrNull { abs(it.value - value) < 1.0E-4 } ?: return
		setTick(item.tick)
	}

	override fun getCurrentKnobItem(): KnobItemInfo? = current

	/** KnobView.setTick, and the only way a turn of the dial reaches a model. */
	fun setTick(tick: Int) {
		if (this.tick == tick) return
		val old = items.firstOrNull { it.tick == this.tick }
		val new = items.firstOrNull { it.tick == tick } ?: return
		this.tick = tick
		current = new
		old?.isSelected = false
		new.isSelected = true
		listener?.onSelectedKnobItemChanged(this, old, new)
		publish()
	}

	private fun publish() {
		val i = info
		host.setManualKnob(
			ManualKnobState(
				angleMin = i?.angleMin ?: 0,
				angleMax = i?.angleMax ?: 0,
				tickMin = i?.tickMin ?: 0,
				tickMax = i?.tickMax ?: 0,
				autoAngle = i?.autoAngle ?: 0,
				items = items.map { it.toUi() },
				selectedTick = tick,
			)
		)
	}

	// -- ViewObserver.update, field for field --------------------------------

	override fun update(o: Observable?, arg: Any?) {
		if (o == null || arg == null) return
		if (o is KnobModel) {
			when (arg as KnobModel.KnobModelFields) {
				KnobModel.KnobModelFields.RESET -> resetKnob(o.isKnobResetCalled())
				KnobModel.KnobModelFields.VISIBILITY ->
					host.setManualKnobVisible(o.isKnobVisible())
				KnobModel.KnobModelFields.MANUAL_MODEL -> setModelToKnob(o.getManualModel())
			}
		}
		if (o is ManualModeModel) {
			when (arg as ManualModeModel.ManualModelFields) {
				ManualModeModel.ManualModelFields.EV_TEXT ->
					host.setManualValueText(UiParam.EV, o.getEvText())
				ManualModeModel.ManualModelFields.EXP_TEXT ->
					host.setManualValueText(UiParam.EXPOSURE, o.getExposureText())
				ManualModeModel.ManualModelFields.ISO_TEXT ->
					host.setManualValueText(UiParam.ISO, o.getIsoText())
				ManualModeModel.ManualModelFields.FOCUS_TEXT ->
					host.setManualValueText(UiParam.FOCUS, o.getFocusText())
				ManualModeModel.ManualModelFields.WB_TEXT ->
					host.setManualValueText(UiParam.WB, o.getWbText())
				ManualModeModel.ManualModelFields.CLICK_LISTENER ->
					clicks = o.getParamClickListener()
				ManualModeModel.ManualModelFields.SELECTED_PARAM ->
					host.setManualSelectedParam(o.getSelectedParam()?.toUi())
				ManualModeModel.ManualModelFields.PANEL_VISIBILITY ->
					host.setManualPanelVisible(o.isManualPanelVisible())
			}
		}
	}

	// -- Binding.java --------------------------------------------------------

	/** Binding.setModelToKnob. */
	private fun setModelToKnob(model: ManualModel<*>?) {
		listener = model
		if (model == null) return
		setKnobInfo(model.getKnobInfo())
		setKnobItems(model.getKnobInfoList())
		model.getCurrentInfo()?.let { setTickByValue(it.value) }
	}

	/** Binding.resetKnob, which is KnobView.resetKnob: back to the auto tick. */
	private fun resetKnob(toReset: Boolean) {
		if (!toReset) return
		val auto = items.firstOrNull { it.tick == 0 } ?: return
		setTickByValue(auto.value)
	}
}

private fun KnobItemInfo.toUi() = ManualKnobItem(
	tick = tick,
	value = value,
	text = text.orEmpty(),
	label = label,
	icon = when (icon) {
		KnobIcon.FOCUS_NEAR -> ManualKnobIcon.FOCUS_NEAR
		KnobIcon.FOCUS_FAR -> ManualKnobIcon.FOCUS_FAR
		else -> ManualKnobIcon.NONE
	},
)

private fun ConsoleParam.toUi() = when (this) {
	ConsoleParam.ISO -> UiParam.ISO
	ConsoleParam.EXPOSURE -> UiParam.EXPOSURE
	ConsoleParam.EV -> UiParam.EV
	ConsoleParam.FOCUS -> UiParam.FOCUS
	ConsoleParam.WB -> UiParam.WB
}

internal fun UiParam.toConsole() = when (this) {
	UiParam.ISO -> ConsoleParam.ISO
	UiParam.EXPOSURE -> ConsoleParam.EXPOSURE
	UiParam.EV -> ConsoleParam.EV
	UiParam.FOCUS -> ConsoleParam.FOCUS
	UiParam.WB -> ConsoleParam.WB
}

/** ManualInstanceProvider's one job: it handed each console its surface. */
internal class ManualUiKnFactory(private val ui: ManualUiKn) : ManualUiFactory {
	override fun create(activity: Activity?): ManualUi? = ui
}
