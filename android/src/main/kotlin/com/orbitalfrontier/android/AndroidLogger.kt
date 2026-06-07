package com.orbitalfrontier.android

import android.util.Log
import com.orbitalfrontier.platform.Logger

/**
 * Android-backed [Logger] (the platform side of the DIP logging port). Routes to logcat with a
 * shared app prefix on the per-system tag, so `core` never touches the Android SDK (ADR 0001).
 */
class AndroidLogger : Logger {
    override fun debug(
        tag: String,
        message: String,
    ) {
        Log.d(prefix(tag), message)
    }

    override fun info(
        tag: String,
        message: String,
    ) {
        Log.i(prefix(tag), message)
    }

    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (throwable != null) Log.w(prefix(tag), message, throwable) else Log.w(prefix(tag), message)
    }

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (throwable != null) Log.e(prefix(tag), message, throwable) else Log.e(prefix(tag), message)
    }

    private fun prefix(tag: String): String = "$APP_TAG/$tag"

    private companion object {
        const val APP_TAG = "OrbitalFrontier"
    }
}
