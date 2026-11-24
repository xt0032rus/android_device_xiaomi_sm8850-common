/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceManager
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.DEFAULT_3D_SURROUND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.DEFAULT_ENABLED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.DEFAULT_MODE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EFFECT_PRIORITY
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.MISOUND_PARAM_3DSURROUND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.MODE_OFF
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.MODE_SETTING_KEY
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.PREF_ENABLE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_3D_SURROUND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.dlog

/**
 * Singleton controller that owns the global [MiSoundAudioEffect] instance
 * (audioSession = 0, the global output mix) and re-applies the surround
 * profile on every music playback start and on every audio device change.
 *
 * Modeled on [co.aospa.dolby.xiaomi.DolbyController]; trimmed down because
 * the MiSound effect is parameter-only (no preset/IEQ/HP virt/... sub-params
 * that the user would tweak through a UI).
 */
internal class MiSoundController private constructor(
    private val context: Context
) {
    private var miSoundEffect: MiSoundAudioEffect? = null
    private val managesBoseSound = Build.DEVICE == "myron"
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val handler = Handler(context.mainLooper)
    private var settingsObserver: ContentObserver? = null
    private var mediaOutput: Pair<Int, String>? = null

    // Most recent mode applied to the effect. The playback/device callbacks
    // re-apply this on every session change so a runtime change of mode or
    // device doesn't leave the effect on a stale profile.
    @Volatile
    private var currentMode: Int = DEFAULT_MODE

    // Most recent value (0/1) of the 3D surround toggle. Pushed to the
    // vendor AIDL effect on every profile apply via the MISOUND_PARAM_* id
    // decoded from libmisoundfx_aosp_aidl_ext.so (see MiSoundConstants).
    @Volatile
    private var current3dSurround: Int = DEFAULT_3D_SURROUND

    // What was last pushed to the live effect instance. A playback event
    // alone must not rewrite identical parameters into the HAL.
    private var lastPushedEffect: MiSoundAudioEffect? = null
    private var lastPushedMode = -1
    private var lastPushed3d = -1

    // Re-apply the surround profile on every media session.
    private val playbackCallback = object : AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            if (!registerCallbacks) return
            val isPlaying = configs.any {
                it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED &&
                    (it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA ||
                        it.audioAttributes.usage == AudioAttributes.USAGE_GAME)
            }
            dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
            if (isPlaying) {
                refreshMediaOutput(checkEffect = true)
            }
        }
    }

    // Re-apply the surround profile whenever a speaker/headphone comes or goes
    // so the AIDL effect re-binds to the new output device. Without this, the
    // effect can remain attached to the previous device and stop processing
    // audio on the new one (e.g. after a wired headset plug)
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (addedDevices.any { it.isMediaOutput() }) refreshMediaOutput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { it.isMediaOutput() }) refreshMediaOutput()
        }
    }

    private fun AudioDeviceInfo.isMediaOutput(): Boolean =
        isSink && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun refreshMediaOutput(checkEffect: Boolean = false) {
        if (!registerCallbacks || audioManager.mode != AudioManager.MODE_NORMAL) return
        val routed = try {
            audioManager.getDevicesForAttributes(MEDIA_ATTRIBUTES)
                .firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read media output", e)
            null
        } ?: return
        val output = routed.type to routed.address
        val changed = mediaOutput != output
        if (changed || checkEffect) applyCurrentProfile(currentMode, force = changed)
        mediaOutput = output
    }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            } else {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }

    init {
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")

        val enabled = readEnabled()
        val mode = readMode()
        current3dSurround = readToggle(SETTING_KEY_3D_SURROUND, DEFAULT_3D_SURROUND)
        dlog(
            TAG,
            "onBootCompleted: enabled=$enabled, mode=$mode, 3d=$current3dSurround",
        )
        applyState(enabled, mode)
    }

    /**
     * Registers [ContentObserver]s for the master on/off key, the mode key,
     * and the 3D surround toggle key so the controller reacts to runtime changes
     * (e.g. from the XiaomiParts sub-page or `adb shell settings put system
     * ...`). Safe to call multiple times — observers are only registered once
     * per process.
     */
    fun registerSettingsObserver() {
        if (settingsObserver != null) return
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                dlog(TAG, "settings observer: onChange")
                current3dSurround = readToggle(SETTING_KEY_3D_SURROUND, DEFAULT_3D_SURROUND)
                applyState(readEnabled(), readMode())
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_KEY),
            false,
            observer,
            UserHandle.USER_CURRENT,
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(MODE_SETTING_KEY),
            false,
            observer,
            UserHandle.USER_CURRENT,
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_KEY_3D_SURROUND),
            false,
            observer,
            UserHandle.USER_CURRENT,
        )
        if (managesBoseSound) {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(BOSE_SOUND_SETTING),
                false,
                observer,
            )
        }
        settingsObserver = observer
        dlog(TAG, "registered Settings.System observers for all misound keys")
    }

    private fun applyState(enabled: Boolean, mode: Int) {
        currentMode = mode
        // "Effectively on" means both the master toggle is on AND the chosen
        // mode isn't 0 (off). Selecting Off in the picker turns the effect
        // off without flipping the master switch.
        val boseSelected = managesBoseSound &&
            Settings.Global.getInt(context.contentResolver, BOSE_SOUND_SETTING, 0) == 1
        val effective = enabled && mode != MODE_OFF && (!managesBoseSound || boseSelected)
        if (effective) {
            applyCurrentProfile(mode)
            registerCallbacks = true
        } else {
            mediaOutput = null
            registerCallbacks = false
            release()
        }
    }

    /**
     * Reads the current master on/off state. The canonical source of truth
     * is the [MiSoundConstants.SETTING_KEY] entry in Settings.System. For
     * users who had explicitly disabled the effect via the legacy
     * SharedPreferences path (in builds prior to the toggle being added),
     * that boolean value is honored as a one-shot fallback on first boot
     * after this code lands.
     */
    private fun readEnabled(): Boolean {
        val sysValue = Settings.System.getIntForUser(
            context.contentResolver,
            SETTING_KEY,
            DEFAULT_ENABLED,
            UserHandle.USER_CURRENT,
        )
        if (sysValue != DEFAULT_ENABLED) {
            return sysValue == 1
        }
        // Credential-encrypted storage is not available before the first
        // unlock (Direct Boot); fall back to the default then.
        return try {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLE, true)
        } catch (t: IllegalStateException) {
            true
        }
    }

    /**
     * Reads the current super-effect profile (0 = off, 1 = intelligent,
     * 2 = standard). Out-of-range values are clamped to the default.
     */
    private fun readMode(): Int {
        val raw = Settings.System.getIntForUser(
            context.contentResolver,
            MODE_SETTING_KEY,
            DEFAULT_MODE,
            UserHandle.USER_CURRENT,
        )
        return if (raw in 0..2) raw else DEFAULT_MODE
    }

    /**
     * Reads a 0/1 MiSound sub-toggle from Settings.System. Any non-1 value
     * (including the absence of the key, which returns the default) is
     * coerced to 0.
     */
    private fun readToggle(key: String, default: Int): Int {
        val raw = Settings.System.getIntForUser(
            context.contentResolver,
            key,
            default,
            UserHandle.USER_CURRENT,
        )
        return if (raw == 1) 1 else 0
    }

    /**
     * Ensures [miSoundEffect] is a live, controlled effect instance. If the
     * previous one was released (e.g. after a disable) or we lost control of
     * it (another higher-priority app or the system re-claimed the session),
     * this builds a fresh one.
     */
    private fun requireEffect(): MiSoundAudioEffect {
        val existingEffect = miSoundEffect
        val alive = try {
            existingEffect?.hasControl() == true
        } catch (t: IllegalStateException) {
            // hasControl() throws if the native handle was already released
            // (e.g. a previous disable path called release()).
            false
        }
        if (alive && existingEffect != null) return existingEffect

        release()
        return MiSoundAudioEffect(EFFECT_PRIORITY, audioSession = 0).also {
            miSoundEffect = it
        }
    }

    private fun applyCurrentProfile(mode: Int, force: Boolean = true) {
        if (audioManager.mode != AudioManager.MODE_NORMAL) return
        dlog(TAG, "applyCurrentProfile(mode=$mode, force=$force)")
        try {
            // A callback queued before unregistering can arrive after switching to Dolby.
            if (managesBoseSound &&
                Settings.Global.getInt(context.contentResolver, BOSE_SOUND_SETTING, 0) != 1
            ) return

            val effect = requireEffect()
            if (!force && effect === lastPushedEffect &&
                mode == lastPushedMode && current3dSurround == lastPushed3d
            ) return

            // In HOC4 the Bose choice enables this effect on the output mix.
            effect.superEffectProfile = mode
            if (managesBoseSound && !effect.enabled) {
                check(effect.setEnabled(true) == AudioEffect.SUCCESS)
            }
            try {
                effect.setBooleanParam(MISOUND_PARAM_3DSURROUND, current3dSurround == 1)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not apply MiSound surround setting", e)
            }
            lastPushedEffect = effect
            lastPushedMode = mode
            lastPushed3d = current3dSurround
        } catch (e: RuntimeException) {
            Log.e(TAG, "applyCurrentProfile failed", e)
        }
    }

    fun setEnabledAndPersist(enabled: Boolean) {
        dlog(TAG, "setEnabledAndPersist($enabled)")
        Settings.System.putIntForUser(
            context.contentResolver,
            SETTING_KEY,
            if (enabled) 1 else 0,
            UserHandle.USER_CURRENT,
        )
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_ENABLE, enabled)
            .apply()
        applyState(enabled, readMode())
    }

    fun setModeAndPersist(mode: Int) {
        dlog(TAG, "setModeAndPersist($mode)")
        val clamped = if (mode in 0..2) mode else DEFAULT_MODE
        Settings.System.putIntForUser(
            context.contentResolver,
            MODE_SETTING_KEY,
            clamped,
            UserHandle.USER_CURRENT,
        )
        applyState(readEnabled(), clamped)
    }

    private fun release() {
        val effect = miSoundEffect ?: return
        miSoundEffect = null
        lastPushedEffect = null
        try {
            if (managesBoseSound && effect.hasControl()) effect.setEnabled(false)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MiSound effect is no longer active", e)
        } finally {
            effect.release()
        }
    }

    companion object {
        private const val TAG = "MiSoundController"
        private val MEDIA_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // Also written by LunarisDolby on Myron.
        private const val BOSE_SOUND_SETTING = "myron_bose_sound"

        @Volatile
        private var instance: MiSoundController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: MiSoundController(context).also { instance = it }
            }
    }
}
