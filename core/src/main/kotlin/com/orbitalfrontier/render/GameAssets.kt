package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

/**
 * The single owner of the design-system art atlas (`orbital.atlas`), UC27 (AC#1).
 *
 * The whole game shares **one** [TextureAtlas] (one backing texture page), [load]ed once on the GL thread
 * in [com.orbitalfrontier.app.OrbitalFrontierGame.create] and [dispose]d exactly once on shutdown. Screens,
 * renderers and the control skin receive a **borrowed** reference and must never dispose it — disposing the
 * texture out from under a live screen would blank every sprite (UC27 pitfall: single owner, single dispose).
 *
 * [region] is the only access path. Lookups are memoised so the per-POI, per-frame hot path (e.g.
 * [WorldObjectRenderer]) does an O(1) map read after the first frame rather than re-scanning the atlas's
 * region list, protecting the 60 FPS budget; callers may still cache the returned [TextureRegion] in a
 * field. A missing region fails fast with a clear error (a typo in [AtlasRegions]) instead of drawing blank.
 *
 * GL-bound: never construct on a JVM test thread (no GL context). Headless tests assert region coverage by
 * parsing the `.atlas` text, not by loading this (UC27 pitfall: headless tests can't create GL textures).
 */
class GameAssets private constructor(
    private val atlas: TextureAtlas,
) : Disposable {
    private val cache = HashMap<String, TextureRegion>()

    /**
     * The atlas region named [name] (use an [AtlasRegions] constant). Memoised after first lookup. Throws
     * if the region is absent so a name mismatch surfaces immediately rather than as a silent blank sprite.
     */
    fun region(name: String): TextureRegion =
        cache.getOrPut(name) {
            atlas.findRegion(name)
                ?: error("Atlas region '$name' not found in $ATLAS_PATH — check AtlasRegions vs. the packed atlas")
        }

    override fun dispose() {
        cache.clear()
        atlas.dispose()
    }

    companion object {
        /** Internal (packaged-asset) path to the atlas descriptor; its page PNG sits beside it. */
        const val ATLAS_PATH = "orbital.atlas"

        /** Load the shared atlas from the internal asset path. GL thread only. */
        fun load(): GameAssets = GameAssets(TextureAtlas(Gdx.files.internal(ATLAS_PATH)))
    }
}
