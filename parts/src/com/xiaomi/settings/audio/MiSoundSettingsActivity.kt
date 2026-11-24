/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.os.Build
import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.collapsingtoolbar.R

class MiSoundSettingsActivity : CollapsingToolbarBaseActivity() {

    companion object {
        private const val TAG = "MiSoundSettingsActivity"
        internal const val AUDIO_PAGE = "com.xiaomi.settings.extra.AUDIO_PAGE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = if (Build.DEVICE == "myron") intent.getStringExtra(AUDIO_PAGE) else null
        when (page) {
            "bose" -> setTitle(com.xiaomi.settings.R.string.bose_settings_title)
            "calibration" -> setTitle(com.xiaomi.settings.R.string.audio_output_settings_title)
        }
        if (savedInstanceState == null) {
            val fragment = MiSoundSettingsFragment().apply {
                arguments = Bundle().apply { putString(AUDIO_PAGE, page) }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_frame, fragment, TAG)
                .commit()
        }
    }
}
