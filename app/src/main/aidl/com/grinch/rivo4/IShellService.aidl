// Derived from ShizuCallRecorder 1.3.3, copyright (C) 2026-present kitsumed (Med).
// GPLv3-or-later with Section 7 terms: assets/licenses/ShizuCallRecorder.txt.
// Modified 2026-09-14: recording-only interface; preserve transaction IDs.
package com.grinch.rivo4;
import android.os.ParcelFileDescriptor;
interface IShellService {
    ParcelFileDescriptor startRecording(String audioSource, String audioCodec, int audioBitRate,
        String serverPath, boolean isDebuggingModeEnabled) = 2;
    void stopRecording() = 3;
    void destroy() = 16777114;
}
