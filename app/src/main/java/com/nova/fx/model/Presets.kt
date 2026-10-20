package com.nova.fx.model

/**
 * Built-in animation transitions (Alight Motion / Keyflow / After Effects style)
 * and Cinematic LUT looks.
 */
object Presets {
    class Anim(val name: String, val desc: String, val fn: (Layer, Long, Project) -> Unit)
    class Look(val name: String, val desc: String, val fn: (Layer) -> Unit)

    private fun k(a: AnimatedFloat, t: Long, dt: Long, v: Float, ip: Interp = Interp.BEZIER) =
        a.set(Keyframe(t + dt, v, ip))

    private fun ix(n: String) = FX.indexOfFirst { it.name.startsWith(n, ignoreCase = true) }

    val anims = listOf(
        Anim("Pop In (Bounce)", "Smooth scale-in with energetic bounce") { l, t, _ ->
            val s = l.transform.scaleX.valueAt(t).coerceAtLeast(0.1f)
            k(l.opacity, t, 0, 0f, Interp.EASE_OUT)
            k(l.opacity, t, 200, 1f, Interp.LINEAR)
            for (a in arrayOf(l.transform.scaleX, l.transform.scaleY)) {
                k(a, t, 0, 0f, Interp.BOUNCE)
                k(a, t, 600, s, Interp.BOUNCE)
            }
        },
        Anim("Elastic Snap", "Keyflow-style snappy elastic arrival") { l, t, _ ->
            val s = l.transform.scaleX.valueAt(t).coerceAtLeast(0.1f)
            for (a in arrayOf(l.transform.scaleX, l.transform.scaleY)) {
                k(a, t, 0, 0.2f, Interp.ELASTIC)
                k(a, t, 700, s, Interp.ELASTIC)
            }
        },
        Anim("Fade In", "Smooth opacity fade over 0.5 s") { l, t, _ ->
            k(l.opacity, t, 0, 0f, Interp.EASE_OUT)
            k(l.opacity, t, 500, 1f, Interp.LINEAR)
        },
        Anim("Fade Out", "Smooth opacity fade to 0") { l, t, _ ->
            k(l.opacity, t, 0, 1f, Interp.EASE_IN)
            k(l.opacity, t, 500, 0f, Interp.LINEAR)
        },
        Anim("Slide In Left", "Enter smoothly from screen left edge") { l, t, p ->
            val x = l.transform.x.valueAt(t)
            k(l.transform.x, t, 0, x - p.width * 0.8f, Interp.EASE_OUT)
            k(l.transform.x, t, 500, x, Interp.EASE_OUT)
        },
        Anim("Slide In Right", "Enter smoothly from screen right edge") { l, t, p ->
            val x = l.transform.x.valueAt(t)
            k(l.transform.x, t, 0, x + p.width * 0.8f, Interp.EASE_OUT)
            k(l.transform.x, t, 500, x, Interp.EASE_OUT)
        },
        Anim("3D Spin Flip", "3D rotation flip with perspective depth") { l, t, _ ->
            l.threeD = true
            val s = l.transform.scaleX.valueAt(t)
            k(l.transform.rotY, t, 0, -90f, Interp.EASE_OUT)
            k(l.transform.rotY, t, 600, 0f, Interp.EASE_OUT)
            for (a in arrayOf(l.transform.scaleX, l.transform.scaleY)) {
                k(a, t, 0, s * 0.5f, Interp.EASE_OUT)
                k(a, t, 600, s, Interp.EASE_OUT)
            }
        },
        Anim("Slow Zoom (Ken Burns)", "Cinematic smooth push-in over 3 s") { l, t, _ ->
            val s = l.transform.scaleX.valueAt(t)
            for (a in arrayOf(l.transform.scaleX, l.transform.scaleY)) {
                k(a, t, 0, s, Interp.LINEAR)
                k(a, t, 3000, s * 1.25f, Interp.LINEAR)
            }
        },
        Anim("Camera Shake", "After Effects natural handheld camera wiggle") { l, t, _ ->
            val x = l.transform.x.valueAt(t)
            val y = l.transform.y.valueAt(t)
            for (j in 0..8) {
                val dx = if (j % 2 == 0) 16f else -16f
                val dy = if (j % 3 == 0) 10f else -10f
                k(l.transform.x, t, j * 60L, x + dx, Interp.LINEAR)
                k(l.transform.y, t, j * 60L, y + dy, Interp.LINEAR)
            }
            k(l.transform.x, t, 540, x)
            k(l.transform.y, t, 540, y)
        },
        Anim("Heartbeat Pulse", "Double energetic scale pulse") { l, t, _ ->
            val s = l.transform.scaleX.valueAt(t)
            for (a in arrayOf(l.transform.scaleX, l.transform.scaleY)) {
                k(a, t, 0, s, Interp.EASE_OUT)
                k(a, t, 200, s * 1.15f, Interp.EASE_OUT)
                k(a, t, 400, s, Interp.EASE_IN)
                k(a, t, 600, s * 1.15f, Interp.EASE_OUT)
                k(a, t, 800, s, Interp.EASE_IN)
            }
        }
    )

    fun clearLook(l: Layer) {
        l.brightness.base = 0f
        l.contrast.base = 1f
        l.saturation.base = 1f
        l.vignette.base = 0f
        l.grain.base = 0f
        l.posterize.base = 0f
        l.rgbSplit.base = 0f
        l.lutId = 0
        l.lutIntensity = 1f
        l.lift.base = 0f
        l.gamma.base = 1f
        l.gain.base = 1f
        for (a in l.e) a.base = 0f
    }

    private fun setNamed(l: Layer, n: String, v: Float) {
        when {
            n.equals("Brightness", true) -> l.brightness.base = v
            n.equals("Contrast", true) -> l.contrast.base = v
            n.equals("Saturation", true) -> l.saturation.base = v
            n.equals("Vignette", true) -> l.vignette.base = v
            n.equals("Grain", true) -> l.grain.base = v
            n.equals("Posterize", true) -> l.posterize.base = v
            n.equals("Lift", true) -> l.lift.base = v
            n.equals("Gamma", true) -> l.gamma.base = v
            n.equals("Gain", true) -> l.gain.base = v
            else -> {
                val i = ix(n)
                if (i >= 0) l.e[i].base = v
            }
        }
    }

    val looks = listOf(
        Look("Teal & Orange", "Hollywood blockbuster color grading (LUT 1)", { l ->
            clearLook(l)
            l.lutId = 1
            l.contrast.base = 1.15f
            l.saturation.base = 1.1f
            l.vignette.base = 0.35f
        }),
        Look("Kodak Portra 400", "Warm nostalgic golden film aesthetic (LUT 2)", { l ->
            clearLook(l)
            l.lutId = 2
            l.contrast.base = 1.08f
            l.grain.base = 0.08f
            l.vignette.base = 0.25f
        }),
        Look("Cyberpunk Neon", "High-saturation violet shadows & cyan highlights (LUT 3)", { l ->
            clearLook(l)
            l.lutId = 3
            l.contrast.base = 1.25f
            l.saturation.base = 1.35f
            setNamed(l, "Glow", 0.6f)
            setNamed(l, "Chromatic", 0.008f)
        }),
        Look("70s Vintage Film", "Warm faded shadows, subtle grain & amber tone (LUT 4)", { l ->
            clearLook(l)
            l.lutId = 4
            l.grain.base = 0.15f
            l.vignette.base = 0.45f
            setNamed(l, "Temperature", 0.35f)
        }),
        Look("Noir Monolith", "High-contrast dramatic monochrome (LUT 5)", { l ->
            clearLook(l)
            l.lutId = 5
            l.saturation.base = 0f
            l.contrast.base = 1.4f
            l.vignette.base = 0.65f
            l.grain.base = 0.12f
        }),
        Look("Commercial Clean", "Bright punchy Apple commercial look (LUT 6)", { l ->
            clearLook(l)
            l.lutId = 6
            l.contrast.base = 1.1f
            l.brightness.base = 0.03f
            l.saturation.base = 1.12f
            setNamed(l, "Sharpen", 0.4f)
        }),
        Look("Golden Hour", "Rich warm sunset glow & deep amber highlights (LUT 7)", { l ->
            clearLook(l)
            l.lutId = 7
            setNamed(l, "Temperature", 0.5f)
            l.contrast.base = 1.12f
            setNamed(l, "Glow", 0.5f)
        }),
        Look("Urban Moody", "Desaturated foliage with crushed rich shadows (LUT 8)", { l ->
            clearLook(l)
            l.lutId = 8
            l.contrast.base = 1.2f
            l.saturation.base = 0.85f
            l.vignette.base = 0.4f
        }),
        Look("VHS Retro 90s", "Chromatic aberration, scanlines & tape jitter", { l ->
            clearLook(l)
            setNamed(l, "Chromatic", 0.015f)
            setNamed(l, "Scanlines", 0.5f)
            setNamed(l, "Glitch", 0.3f)
            l.grain.base = 0.18f
        }),
        Look("Dreamy Glow", "Soft diffused highlights with pastel tone", { l ->
            clearLook(l)
            setNamed(l, "Glow", 1.2f)
            setNamed(l, "Blur", 1.0f)
            l.brightness.base = 0.04f
            l.saturation.base = 1.15f
        }),
        Look("Reset to Natural", "Clear all color grades and effects", { l -> clearLook(l) })
    )
}
