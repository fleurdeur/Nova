package com.nova.fx.model

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import com.nova.fx.ui.Ui

/**
 * Keyflow Motion Asset Store & Asset Library.
 * Provides curated motion graphics assets: Cinematic 3D LUTs, Particle Systems,
 * Kinetic Text Templates, Motion Backgrounds, and Velocity Transition Curves.
 */
object AssetStore {
    enum class Category {
        LUTS,
        PARTICLES,
        TITLES,
        CURVES,
        SFX,
        BACKGROUNDS
    }

    data class Asset(
        val id: String,
        val name: String,
        val category: Category,
        val description: String,
        val tag: String,
        val badge: String = "Free",
        val icon: String = "✦",
        val color: Int = Ui.ACCENT_CYAN,
        val applyToLayer: ((Layer, Project) -> Unit)? = null
    )

    val assets = listOf(
        // --- Cinematic 3D LUTs ---
        Asset(
            id = "lut_teal_orange",
            name = "Teal & Orange Pro",
            category = Category.LUTS,
            description = "Iconic Hollywood blockbuster color grade with rich cyan shadows and golden skin tones.",
            tag = "LUT 1",
            badge = "Popular",
            icon = "🎨",
            color = 0xFF00E5FF.toInt(),
            applyToLayer = { l, _ ->
                Presets.clearLook(l)
                l.lutId = 1
                l.lutIntensity = 1.0f
                l.contrast.base = 1.15f
                l.saturation.base = 1.1f
            }
        ),
        Asset(
            id = "lut_kodak_portra",
            name = "Kodak Portra 400",
            category = Category.LUTS,
            description = "Nostalgic 35mm warm film aesthetic with gentle contrast and creamy highlights.",
            tag = "LUT 2",
            badge = "Film",
            icon = "🎞",
            color = 0xFFFFB300.toInt(),
            applyToLayer = { l, _ ->
                Presets.clearLook(l)
                l.lutId = 2
                l.lutIntensity = 1.0f
                l.grain.base = 0.08f
            }
        ),
        Asset(
            id = "lut_cyberpunk",
            name = "Cyberpunk 2077",
            category = Category.LUTS,
            description = "Electric neon cyan highlights with deep violet and magenta shadows.",
            tag = "LUT 3",
            badge = "Neon",
            icon = "⚡",
            color = 0xFFE040FB.toInt(),
            applyToLayer = { l, _ ->
                Presets.clearLook(l)
                l.lutId = 3
                l.lutIntensity = 1.0f
                l.saturation.base = 1.35f
                l.contrast.base = 1.25f
            }
        ),
        Asset(
            id = "lut_noir_monolith",
            name = "Noir Monolith B&W",
            category = Category.LUTS,
            description = "High-contrast dramatic monochrome with crushed blacks and luminous whites.",
            tag = "LUT 5",
            badge = "Classic",
            icon = "🖤",
            color = 0xFFFFFFFF.toInt(),
            applyToLayer = { l, _ ->
                Presets.clearLook(l)
                l.lutId = 5
                l.lutIntensity = 1.0f
                l.saturation.base = 0f
                l.contrast.base = 1.4f
            }
        ),
        Asset(
            id = "lut_golden_hour",
            name = "Golden Hour Sunset",
            category = Category.LUTS,
            description = "Radiant amber sunset glow with soft highlights and warm film tones.",
            tag = "LUT 7",
            badge = "Warm",
            icon = "🌅",
            color = 0xFFFF6D00.toInt(),
            applyToLayer = { l, _ ->
                Presets.clearLook(l)
                l.lutId = 7
                l.lutIntensity = 1.0f
            }
        ),

        // --- Particle Systems ---
        Asset(
            id = "part_bokeh_dust",
            name = "Cinematic Floating Bokeh",
            category = Category.PARTICLES,
            description = "Soft organic out-of-focus light orbs and floating atmospheric dust motes.",
            tag = "GPU Emitter",
            badge = "Vulkan",
            icon = "✨",
            color = 0xFFFFD54F.toInt(),
            applyToLayer = { l, _ ->
                l.source = Source.Particles(type = 0, count = 120, speed = 0.8f, size = 1.2f)
                l.blend = BlendMode.ADD
                l.name = "Floating Bokeh"
            }
        ),
        Asset(
            id = "part_golden_sparks",
            name = "Golden Embers & Sparks",
            category = Category.PARTICLES,
            description = "Rising fiery embers with glowing heat halos for cinematic action sequences.",
            tag = "GPU Emitter",
            badge = "Vulkan",
            icon = "🔥",
            color = 0xFFFF5722.toInt(),
            applyToLayer = { l, _ ->
                l.source = Source.Particles(type = 1, count = 150, speed = 1.4f, size = 1.0f, colorR = 1f, colorG = 0.6f, colorB = 0.1f)
                l.blend = BlendMode.ADD
                l.name = "Golden Sparks"
            }
        ),
        Asset(
            id = "part_blizzard_snow",
            name = "Blizzard Snowstorm",
            category = Category.PARTICLES,
            description = "Realistic falling snowflakes with natural organic wind turbulence.",
            tag = "GPU Emitter",
            badge = "Vulkan",
            icon = "❄",
            color = 0xFF81D4FA.toInt(),
            applyToLayer = { l, _ ->
                l.source = Source.Particles(type = 3, count = 160, speed = 1.2f, size = 0.9f)
                l.blend = BlendMode.ADD
                l.name = "Blizzard Snow"
            }
        ),
        Asset(
            id = "part_starfield_warp",
            name = "Hyperdrive Starfield",
            category = Category.PARTICLES,
            description = "Fast radial star streaks expanding from center, simulating hyperspace warp.",
            tag = "GPU Emitter",
            badge = "Sci-Fi",
            icon = "🚀",
            color = 0xFF00E5FF.toInt(),
            applyToLayer = { l, _ ->
                l.source = Source.Particles(type = 5, count = 140, speed = 1.6f, size = 1.1f)
                l.blend = BlendMode.ADD
                l.name = "Starfield Warp"
            }
        ),

        // --- Keyflow Curve Transitions ---
        Asset(
            id = "curve_snappy",
            name = "Keyflow Snappy (0.05, 0.90, 0.10, 1.00)",
            category = Category.CURVES,
            description = "Signature Keyflow ultra-fast snappy arrival curve with zero overshoot.",
            tag = "Bezier",
            badge = "Keyflow",
            icon = "📈",
            color = 0xFF7C4DFF.toInt(),
            applyToLayer = { l, _ ->
                l.animatedProps().forEach { it.applyEase(0.05f, 0.90f, 0.10f, 1.00f) }
            }
        ),
        Asset(
            id = "curve_elastic",
            name = "Elastic Pop (0.34, 1.56, 0.64, 1.00)",
            category = Category.CURVES,
            description = "Playful elastic bounce curve with overshoot past destination.",
            tag = "Bezier",
            badge = "Motion",
            icon = "⚡",
            color = 0xFF00E676.toInt(),
            applyToLayer = { l, _ ->
                l.animatedProps().forEach { it.applyEase(0.34f, 1.56f, 0.64f, 1.00f) }
            }
        ),

        // --- Kinetic Text & Titles ---
        Asset(
            id = "title_cyber_glitch",
            name = "Cyber Glitch Title",
            category = Category.TITLES,
            description = "RGB split glitch text with animated horizontal slice artifacts.",
            tag = "Motion Graphic",
            badge = "Keyflow",
            icon = "✍",
            color = 0xFF00E5FF.toInt()
        ),
        Asset(
            id = "title_cinematic_minimal",
            name = "Cinematic Lower Third",
            category = Category.TITLES,
            description = "Sleek modern typography with clean fade and tracking expansion.",
            tag = "Motion Graphic",
            badge = "Pro",
            icon = "🎬",
            color = 0xFFFFFFFF.toInt()
        ),

        // --- Sound Effects SFX ---
        Asset(
            id = "sfx_cinematic_whoosh",
            name = "Cinematic Deep Whoosh",
            category = Category.SFX,
            description = "Deep sub-bass air rush perfect for dynamic cuts and fast pans.",
            tag = "WAV / AAC",
            badge = "SFX",
            icon = "🔊",
            color = 0xFF26A69A.toInt()
        ),
        Asset(
            id = "sfx_glitch_pop",
            name = "Digital Glitch Pop",
            category = Category.SFX,
            description = "Hi-tech digital artifact glitch sound with snappy transient.",
            tag = "WAV / AAC",
            badge = "SFX",
            icon = "⚡",
            color = 0xFFAB47BC.toInt()
        )
    )

    fun listByCategory(cat: Category): List<Asset> = assets.filter { it.category == cat }
}
