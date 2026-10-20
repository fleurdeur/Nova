package com.nova.fx

import android.content.Context
import android.content.SharedPreferences

/**
 * App preferences and hardware performance profiles.
 * Includes dedicated configurations for MediaTek Helio G88 / Mali-G52 GPU,
 * 4K UHD / 2K QHD rendering and export settings, and watermark-free configuration.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) {
            sp = c.applicationContext.getSharedPreferences("novafx_prefs", Context.MODE_PRIVATE)
        }
    }

    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // Canvas / Project defaults
    var defW: Int
        get() = i("defW", 1920)
        set(v) = put("defW", v)
    var defH: Int
        get() = i("defH", 1080)
        set(v) = put("defH", v)
    var defFps: Int
        get() = i("defFps", 30)
        set(v) = put("defFps", v)

    // Preview / Performance Tuning for Mali-G52
    var previewRes: Int // short-side cap in px (0 = full canvas)
        get() = i("previewRes", 540)
        set(v) = put("previewRes", v)
    var previewFpsCap: Int // 0 = match project fps
        get() = i("previewFpsCap", 0)
        set(v) = put("previewFpsCap", v)
    var liteEffects: Boolean
        get() = b("lite", false)
        set(v) = put("lite", v)
    var thermalGuard: Boolean
        get() = b("thermal", true)
        set(v) = put("thermal", v)
    var flowRes: Int
        get() = i("flowRes", 192)
        set(v) = put("flowRes", v)

    // Export Options (Includes 4K UHD, 2K QHD, 1080p, HEVC, High Bitrate)
    var exportQuality: Int // 0=Balanced, 1=High, 2=Ultra 4K, 3=Custom
        get() = i("expQ", 1)
        set(v) = put("expQ", v)
    var exportCustomMbps: Int
        get() = i("expMbps", 35)
        set(v) = put("expMbps", v)
    var exportCodec: Int // 0=H.264 (AVC), 1=HEVC (H.265 recommended for 4K)
        get() = i("expCodec", 1)
        set(v) = put("expCodec", v)
    var exportScale: Int // 100=Original, 200=4K Upscale, 50=Half
        get() = i("expScale", 100)
        set(v) = put("expScale", v)

    fun exportMbps(): Int = when (exportQuality) {
        0 -> 12
        1 -> 24
        2 -> 45 // 45 Mbps for pristine 4K video
        else -> exportCustomMbps.coerceIn(4, 80)
    }

    // General
    var autosaveMin: Int
        get() = i("autosave", 2)
        set(v) = put("autosave", v)
    var keepScreenOn: Boolean
        get() = b("keepOn", true)
        set(v) = put("keepOn", v)
    var accent: Int
        get() = i("accent", 0)
        set(v) = put("accent", v)
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = put("haptics", v)

    fun favs(): Set<String> = sp.getStringSet("favs", emptySet()) ?: emptySet()
    fun toggleFav(n: String) {
        val s = favs().toMutableSet()
        if (!s.add(n)) s.remove(n)
        sp.edit().putStringSet("favs", s).apply()
    }

    /**
     * Hardware Optimization Profiles:
     * 0 = Battery Saver
     * 1 = Balanced (Optimized for MediaTek Helio G88 / Mali-G52 MC2)
     * 2 = Ultra Quality / 4K Production
     */
    fun applyProfile(p: Int) {
        when (p) {
            0 -> {
                previewRes = 360
                previewFpsCap = 30
                liteEffects = true
                thermalGuard = true
                flowRes = 128
                exportQuality = 0
            }
            1 -> {
                // MediaTek G88 / Mali-G52 sweet spot: crisp 540p preview, full fps, HEVC 4K export capability
                previewRes = 540
                previewFpsCap = 0
                liteEffects = false
                thermalGuard = true
                flowRes = 192
                exportQuality = 1
            }
            else -> {
                previewRes = 0 // Full resolution
                previewFpsCap = 60
                liteEffects = false
                thermalGuard = true
                flowRes = 256
                exportQuality = 2 // 4K Ultra
            }
        }
    }

    fun reset() {
        sp.edit().clear().apply()
    }
}
