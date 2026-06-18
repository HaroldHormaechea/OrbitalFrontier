package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont

/**
 * The GL-thread loader for the bundled game font ([GameFont]), UC28.
 *
 * Each consumer (HUD, control skin, map labels) owns its **own** [BitmapFont] over the shared `.fnt`
 * asset — preserving the existing per-consumer ownership + dispose model (zero dispose-guard churn,
 * ADR 0017). The peak cost is a handful of small fonts sharing one ~35 KiB page; negligible beside the
 * design-system atlas.
 *
 * The page texture is set to **Linear** min/mag filtering so the [GameFont.BAKE_CAP_PX] master glyphs
 * downscale smoothly to their on-screen size; a freshly loaded [BitmapFont] otherwise defaults to Nearest
 * filtering, which would alias the minified text. GL-bound: call only on the render thread (a JVM test
 * thread has no GL context), exactly like [GameAssets.load].
 */
object GameFontLoader {
    /** Load a fresh [BitmapFont] from [GameFont.FONT_PATH] with Linear-filtered page(s). GL thread only. */
    fun load(): BitmapFont {
        val font = BitmapFont(Gdx.files.internal(GameFont.FONT_PATH))
        for (region in font.regions) {
            region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        return font
    }
}
