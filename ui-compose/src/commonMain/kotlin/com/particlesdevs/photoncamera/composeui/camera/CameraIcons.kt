package com.particlesdevs.photoncamera.composeui.camera

import com.particlesdevs.photoncamera.composeui.resources.*
import com.particlesdevs.photoncamera.composeui.state.SettingType
import org.jetbrains.compose.resources.DrawableResource

/**
 * The selector drawables (ic_flash.xml, ic_timer.xml, quad_button.xml ...) are Android
 * state lists, which have no multiplatform equivalent. Their leaves are plain vectors,
 * so the selection happens here instead.
 */
object CameraIcons {
    fun flash(value: Int): DrawableResource = when (value) {
        0 -> Res.drawable.ic_torch
        1 -> Res.drawable.ic_flash_off
        2 -> Res.drawable.ic_flash_on
        else -> Res.drawable.ic_flash_auto
    }

    fun timer(index: Int): DrawableResource = when (index) {
        1 -> Res.drawable.ic_timer3s
        2 -> Res.drawable.ic_timer10s
        else -> Res.drawable.ic_timeroff
    }

    fun grid(value: Int): DrawableResource =
        if (value != 0) Res.drawable.ic_grid_on else Res.drawable.ic_grid_off

    fun quad(on: Boolean): DrawableResource =
        if (on) Res.drawable.ic_quad_on else Res.drawable.ic_quad_off

    fun eis(on: Boolean): DrawableResource =
        if (on) Res.drawable.ic_eis_on else Res.drawable.ic_eis_off

    fun hdrx(on: Boolean): DrawableResource =
        if (on) Res.drawable.ic_hdrx_on else Res.drawable.ic_hdrx_off

    fun fps(mode: Int): DrawableResource = when (mode) {
        1 -> Res.drawable.fps24_select_24px
        2 -> Res.drawable.fps30_select_24px
        3 -> Res.drawable.fps60_select_24px
        else -> Res.drawable.autofps_select_24px
    }

    fun raw(value: Int): DrawableResource =
        if (value == 0) Res.drawable.ic_raw_off else Res.drawable.ic_raw

    fun batterySaver(on: Boolean): DrawableResource =
        if (on) Res.drawable.leaf_icon_15 else Res.drawable.ic_round_battery_alert_24

    /** The icon a settings-bar option shows, matching SettingsBarEntryProvider. */
    fun forOption(type: SettingType, value: Int): DrawableResource = when (type) {
        SettingType.FLASH -> if (value == 0) Res.drawable.ic_torch else Res.drawable.ic_flash_off
        SettingType.TIMER -> timer(value)
        SettingType.GRID -> grid(value)
        SettingType.QUAD -> quad(value == 1)
        SettingType.EIS -> eis(value == 1)
        SettingType.HDRX -> hdrx(value == 1)
        SettingType.FPS_60 -> fps(value)
        SettingType.RAW -> raw(value)
        SettingType.BATTERY_SAVER -> batterySaver(value == 1)
        SettingType.BRACKETING, SettingType.AE_METERING_STD -> Res.drawable.ic_exposure
    }
}
