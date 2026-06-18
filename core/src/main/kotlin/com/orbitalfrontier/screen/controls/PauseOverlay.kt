package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable

/**
 * Full-screen modal **pause overlay** (UC32): a dimming backdrop that swallows every tap (so no flight
 * control underneath can fire while the game is frozen) plus a centred Resume / Settings / Quit-to-main-menu
 * button column. Tapping Settings swaps the column for a single Back button and leaves the (separately
 * owned) in-flight settings panel visible on top so the player can adjust controls/audio while paused; Back
 * returns to the pause column.
 *
 * Pure Scene2D view: it owns its actors and forwards taps to the [onResume]/[onSettings]/[onQuit]/[onBack]
 * callbacks the screen wires; all pause/resume state lives in [com.orbitalfrontier.render.PauseState] on the
 * screen. The backing [actor] is added to the stage **last** (top z-order) so the backdrop covers the HUD,
 * the minimap and its tap target. The backdrop is drawn from a generated 1×1 translucent texture this widget
 * owns and releases in [dispose] (no atlas art — mirrors the runtime-pixmap chrome in [OrbitalUiSkin]).
 */
class PauseOverlay(skin: OrbitalUiSkin) : Disposable {
    /** The full-screen group (backdrop + button column + back button); positioned/sized by the screen. */
    val actor: Group = Group()

    // Edge-triggered callbacks — defaulted to no-ops; the screen wires them to its pause transitions.
    var onResume: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onQuit: () -> Unit = {}
    var onBack: () -> Unit = {}

    private val backdropTexture: Texture
    private val backdrop: Image
    private val menuColumn = Table()
    private val backColumn = Table()

    private val resumeButton = TextButton("RESUME", skin.settingsButtonStyle)
    private val settingsButton = TextButton("SETTINGS", skin.settingsButtonStyle)
    private val quitButton = TextButton("QUIT TO MAIN MENU", skin.settingsButtonStyle)
    private val backButton = TextButton("BACK", skin.settingsButtonStyle)

    init {
        // Generated translucent-black fill for the dim backdrop (a single texel stretched to fill the
        // stage); owned + disposed here, never an atlas region.
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(0f, 0f, 0f, BACKDROP_ALPHA)
            pixmap.fill()
            backdropTexture = Texture(pixmap)
        } finally {
            pixmap.dispose()
        }
        backdrop = Image(TextureRegionDrawable(backdropTexture))
        // Swallow every tap so the modal backdrop blocks the flight controls + minimap underneath while
        // paused (a ClickListener consumes the touchDown by default). Resume/Settings/Quit sit above it.
        backdrop.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) = Unit
            },
        )

        resumeButton.addListener(clickListener { onResume() })
        settingsButton.addListener(clickListener { onSettings() })
        quitButton.addListener(clickListener { onQuit() })
        backButton.addListener(clickListener { onBack() })

        for (button in listOf(resumeButton, settingsButton, quitButton)) {
            menuColumn.add(button).width(BUTTON_WIDTH).height(BUTTON_HEIGHT).padBottom(BUTTON_GAP)
            menuColumn.row()
        }
        backColumn.add(backButton).width(BUTTON_WIDTH).height(BUTTON_HEIGHT)

        actor.addActor(backdrop)
        actor.addActor(menuColumn)
        actor.addActor(backColumn)
        showSettingsSubView(false)
    }

    /**
     * Toggle between the pause menu (Resume/Settings/Quit) and the settings sub-view (just Back). When in
     * the sub-view the screen also shows the in-flight settings panel on top of the backdrop (UC32 AC#3).
     */
    fun showSettingsSubView(show: Boolean) {
        menuColumn.isVisible = !show
        backColumn.isVisible = show
    }

    /** Resize to fill the stage and re-centre the button columns. Called from the screen's layout pass. */
    fun resize(
        width: Float,
        height: Float,
    ) {
        actor.setBounds(0f, 0f, width, height)
        backdrop.setBounds(0f, 0f, width, height)
        menuColumn.pack()
        menuColumn.setPosition((width - menuColumn.width) / 2f, (height - menuColumn.height) / 2f)
        backColumn.pack()
        backColumn.setPosition((width - backColumn.width) / 2f, (height - backColumn.height) / 2f)
    }

    private fun clickListener(onClick: () -> Unit): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                onClick()
            }
        }

    override fun dispose() {
        backdropTexture.dispose()
    }

    private companion object {
        const val BACKDROP_ALPHA = 0.7f
        const val BUTTON_WIDTH = 320f
        const val BUTTON_HEIGHT = 56f
        const val BUTTON_GAP = 16f
    }
}
