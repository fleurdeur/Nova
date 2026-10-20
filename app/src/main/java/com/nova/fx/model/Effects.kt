package com.nova.fx.model

/**
 * FX effect slot definition for shader uniforms.
 * Mapped to uE[i] in the GPU compositor fragment shaders.
 */
class Fx(val name: String, val min: Float, val max: Float, val default: Float = 0f, val category: String = "Color")

val FX = listOf(
    Fx("Invert", 0f, 1f, category = "Color"),
    Fx("Sepia", 0f, 1f, category = "Color"),
    Fx("Hue shift", -0.5f, 0.5f, category = "Color"),
    Fx("Temperature", -1f, 1f, category = "Color Grading"),
    Fx("Threshold (B/W)", 0f, 1f, category = "Color"),
    Fx("Duotone", 0f, 1f, category = "Color Grading"),
    Fx("Sharpen", 0f, 2f, category = "Blur & Sharpen"),
    Fx("Blur", 0f, 8f, category = "Blur & Sharpen"),
    Fx("Glow / Bloom", 0f, 2.5f, category = "Light & Glow"),
    Fx("Pixelate", 0f, 1f, category = "Stylize"),
    Fx("Wave warp", 0f, 1f, category = "Distortion"),
    Fx("Swirl", -1f, 1f, category = "Distortion"),
    Fx("Bulge / Fisheye", -1f, 1f, category = "Distortion"),
    Fx("Mirror", 0f, 1f, category = "Distortion"),
    Fx("Scanlines CRT", 0f, 1f, category = "Retro & VHS"),
    Fx("Glitch artifacts", 0f, 1f, category = "Retro & VHS"),
    Fx("Edge detect (Neon)", 0f, 1f, category = "Stylize"),
    Fx("Halftone dots", 0f, 1f, category = "Stylize"),
    Fx("Chromatic aberration", 0f, 0.04f, category = "Lens & Optics"),
    Fx("Flicker / Strobe", 0f, 1f, category = "Light & Glow"),
    Fx("Zoom blur", 0f, 0.4f, category = "Blur & Sharpen"),
    Fx("Exposure EV", -2f, 2f, category = "Color Grading"),
    Fx("Chroma key (Green screen)", 0f, 1f, category = "Matte & Keying"),
    Fx("Directional motion blur", 0f, 1f, category = "Motion & AE"),
    Fx("Camera shake (Wiggle)", 0f, 1f, category = "Motion & AE"),
    Fx("Tint (Green-Magenta)", -1f, 1f, category = "Color Grading"),
    Fx("Highlights recovery", -1f, 1f, category = "Color Grading"),
    Fx("Shadows lift", -1f, 1f, category = "Color Grading")
)
