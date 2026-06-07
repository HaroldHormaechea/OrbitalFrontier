package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import kotlin.random.Random

/**
 * Multi-layer parallax starfield (AC#1, AC#11).
 *
 * Each layer is a tile of pseudo-random stars repeated seamlessly across the viewport; layers
 * scroll at different fractions of the camera's world movement (`parallax`), so a near layer
 * shifts more than a far one and motion is perceptible on the otherwise-empty, unbounded sector.
 * Rendered in screen space with a [ShapeRenderer]; reads camera position only (simulation stays
 * out of rendering). Star layouts are seeded for deterministic, allocation-free per-frame draws.
 */
class StarfieldRenderer(
    seed: Long = DEFAULT_SEED,
    private val tileSize: Float = DEFAULT_TILE_SIZE,
) : Disposable {
    private class Layer(
        val parallax: Float,
        val stars: FloatArray,
        val color: Color,
        val size: Float,
    )

    private val shapeRenderer = ShapeRenderer()
    private val projection = Matrix4()
    private val layers: List<Layer>

    init {
        val random = Random(seed)
        layers =
            LAYER_SPECS.map { spec ->
                val coords = FloatArray(spec.count * 2)
                for (i in 0 until spec.count) {
                    coords[i * 2] = random.nextFloat() * tileSize
                    coords[i * 2 + 1] = random.nextFloat() * tileSize
                }
                Layer(spec.parallax, coords, spec.color, spec.size)
            }
    }

    fun render(
        cameraX: Float,
        cameraY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        shapeRenderer.projectionMatrix = projection
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        for (layer in layers) {
            shapeRenderer.color = layer.color
            val offsetX = wrap(-cameraX * layer.parallax, tileSize)
            val offsetY = wrap(-cameraY * layer.parallax, tileSize)
            var tileX = -tileSize
            while (tileX < viewportWidth) {
                var tileY = -tileSize
                while (tileY < viewportHeight) {
                    drawTile(layer, offsetX + tileX, offsetY + tileY, viewportWidth, viewportHeight)
                    tileY += tileSize
                }
                tileX += tileSize
            }
        }
        shapeRenderer.end()
    }

    private fun drawTile(
        layer: Layer,
        baseX: Float,
        baseY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        val stars = layer.stars
        val half = layer.size / 2f
        var i = 0
        while (i < stars.size) {
            val x = baseX + stars[i]
            val y = baseY + stars[i + 1]
            if (x >= -layer.size && x <= viewportWidth && y >= -layer.size && y <= viewportHeight) {
                shapeRenderer.rect(x - half, y - half, layer.size, layer.size)
            }
            i += 2
        }
    }

    /** Wrap [value] into [0, period). */
    private fun wrap(
        value: Float,
        period: Float,
    ): Float {
        val m = value % period
        return if (m < 0f) m + period else m
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    private class LayerSpec(
        val parallax: Float,
        val count: Int,
        val color: Color,
        val size: Float,
    )

    private companion object {
        const val DEFAULT_SEED = 1337L
        const val DEFAULT_TILE_SIZE = 1024f

        // Far (dim, slow) → near (bright, fast). [TUNE]
        val LAYER_SPECS =
            listOf(
                LayerSpec(parallax = 0.2f, count = 90, color = Color(0.45f, 0.45f, 0.55f, 1f), size = 2f),
                LayerSpec(parallax = 0.5f, count = 55, color = Color(0.75f, 0.78f, 0.9f, 1f), size = 3f),
                LayerSpec(parallax = 0.85f, count = 30, color = Color(1f, 1f, 1f, 1f), size = 4f),
            )
    }
}
