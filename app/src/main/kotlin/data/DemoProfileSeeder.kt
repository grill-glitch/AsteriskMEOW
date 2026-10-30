// Copyright 2026, AsteriskMEOW contributors
// SPDX-License-Identifier: GPL-3.0

package data

import android.content.Context
import app.AppState
import app.MihomoProfileState
import app.MihomoProfileType
import java.io.File
import java.security.MessageDigest

/**
 * Seeds a bundled DIRECT profile on a fresh install so the VPN can start
 * without importing a subscription first. Once the user edits or deletes it,
 * the flag keeps it from coming back.
 */
internal object DemoProfileSeeder {
    private const val PrefName = "asterisk_meow_demo"
    private const val SeededKey = "demo_profile_seeded"
    private const val DemoAsset = "demo-profile.yaml"
    private const val DemoProfileId = 1

    fun seedIfNeeded(context: Context, state: AppState): AppState {
        if (state.mihomoProfiles.isNotEmpty()) return state
        val prefs = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SeededKey, false)) return state

        val file = File(context.filesDir, "mihomo-profiles/demo.yaml")
        file.parentFile?.mkdirs()
        runCatching {
            context.assets.open(DemoAsset).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { return state }

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return state
        if (bytes.isEmpty()) return state
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val profile = MihomoProfileState(
            id = DemoProfileId,
            name = "AsteriskMEOW Demo",
            type = MihomoProfileType.File,
            builtIn = true,
            contentPath = file.absolutePath,
            contentSha256 = sha,
            contentSizeBytes = bytes.size.toLong(),
            lastUpdatedAtMillis = System.currentTimeMillis(),
        )
        prefs.edit().putBoolean(SeededKey, true).apply()
        return state.copy(
            mihomoProfiles = listOf(profile),
            selectedMihomoProfileId = DemoProfileId,
            pendingMihomoRestartProfileId = DemoProfileId,
        )
    }
}
