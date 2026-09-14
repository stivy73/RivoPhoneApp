/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in app/src/main/assets/licenses/ShizuCallRecorder.txt.
 * Modified for Rivo Personal, 2026-09-14; derived only from v1.3.3 / dd940fe2caa8aa1b4c7143ad5123c9923b343abd.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.grinch.rivo4.controller.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.grinch.rivo4.IShellService
import kotlin.system.exitProcess

@Keep
class ShellService : IShellService.Stub {

    private val pipeline by lazy { ShellAudioPipeline() }

    @Keep
    constructor() : this(null)

    @Keep
    constructor(context: Context?)

    override fun startRecording(
        audioSource: String?,
        audioCodec: String?,
        audioBitRate: Int,
        serverPath: String?,
        debug: Boolean
    ): ParcelFileDescriptor? {
        val source = audioSource ?: "voice-call"
        val codec = audioCodec ?: "opus"
        val path = serverPath ?: return null

        return pipeline.startCapture(
            audioSource = source,
            audioCodec = codec,
            audioBitRate = audioBitRate,
            serverPath = path,
            isDebuggingModeEnabled = debug
        )
    }

    override fun stopRecording() {
        pipeline.stopCapture()
    }

    override fun destroy() {
        stopRecording()
        exitProcess(0)
    }
}
