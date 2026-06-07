package com.orbitalfrontier.ship

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.FixtureDef
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.common.Vec2

/**
 * Box2D spatial body for the player ship — the **integrator of record** (AC#10).
 *
 * This is the on-device integration boundary (it uses libGDX/Box2D types and is therefore *not*
 * unit-tested; the pure velocity math lives in [ShipMovementModel]). See
 * docs/adr/0005-movement-integration.md for the binding per-frame contract. In short:
 *
 *  1. [readKinematics] — read the body transform/velocity back (for the model, render, HUD).
 *  2. [ShipMovementModel.update] computes the next **velocity** only.
 *  3. [applyKinematics] writes that linear+angular velocity to the body — **never the transform**.
 *  4. [step] lets Box2D integrate position/rotation from the velocity.
 *
 * The transform is set directly only at spawn/reset ([resetTo]). Box2D therefore owns
 * position/rotation integration while the model owns velocity — no double integration.
 *
 * Units: the model works in *world-units*; Box2D works in metres. [pixelsPerMeter] (PPM) is the
 * world-units-per-metre scale. Velocities/positions are divided by PPM going into Box2D and
 * multiplied coming back, keeping body sizes and per-step translations within Box2D's stable
 * range (avoids the b2_maxTranslation clamp at high speed).
 */
class ShipPhysics(
    private val pixelsPerMeter: Float = DEFAULT_PIXELS_PER_METER,
    spawn: ShipKinematics = ShipKinematics(),
    bodyRadiusWorldUnits: Float = DEFAULT_BODY_RADIUS_WORLD_UNITS,
) : Disposable {
    // Top-down space: no gravity. doSleep=false so the body always honours the velocity we set
    // each frame (a sleeping body would ignore setLinearVelocity until re-awoken).
    private val world = World(Vector2(0f, 0f), false)
    private val body: Body

    init {
        val bodyDef =
            BodyDef().apply {
                type = BodyDef.BodyType.DynamicBody
                position.set(toMeters(spawn.position.x), toMeters(spawn.position.y))
                angle = spawn.headingRadians
                linearDamping = 0f
                angularDamping = 0f
                fixedRotation = false
            }
        body = world.createBody(bodyDef)

        val shape = CircleShape().apply { radius = toMeters(bodyRadiusWorldUnits) }
        try {
            val fixtureDef =
                FixtureDef().apply {
                    this.shape = shape
                    density = 1f
                    friction = 0f
                    restitution = 0f
                }
            body.createFixture(fixtureDef)
        } finally {
            shape.dispose()
        }

        body.setLinearVelocity(toMeters(spawn.velocity.x), toMeters(spawn.velocity.y))
        body.angularVelocity = spawn.angularVelocity
    }

    /**
     * Per-frame velocity write (step 3 of the contract). Sets linear + angular velocity only;
     * Box2D integrates the resulting position/rotation in [step]. Never sets the transform.
     */
    fun applyKinematics(kinematics: ShipKinematics) {
        body.setLinearVelocity(toMeters(kinematics.velocity.x), toMeters(kinematics.velocity.y))
        body.angularVelocity = kinematics.angularVelocity
    }

    /** Advance the Box2D world by [dt] seconds (step 4 of the contract). */
    fun step(dt: Float) {
        world.step(dt, VELOCITY_ITERATIONS, POSITION_ITERATIONS)
    }

    /** Read the body's current transform + velocity back into the pure model's value type. */
    fun readKinematics(): ShipKinematics {
        val position = body.position
        val velocity = body.linearVelocity
        return ShipKinematics(
            position = Vec2(toWorldUnits(position.x), toWorldUnits(position.y)),
            velocity = Vec2(toWorldUnits(velocity.x), toWorldUnits(velocity.y)),
            headingRadians = body.angle,
            angularVelocity = body.angularVelocity,
        )
    }

    /**
     * Spawn/reset only: the single place allowed to set the body transform directly. Use for
     * initial placement, respawn, or teleport — never on the per-frame path.
     */
    fun resetTo(kinematics: ShipKinematics) {
        body.setTransform(
            toMeters(kinematics.position.x),
            toMeters(kinematics.position.y),
            kinematics.headingRadians,
        )
        body.setLinearVelocity(toMeters(kinematics.velocity.x), toMeters(kinematics.velocity.y))
        body.angularVelocity = kinematics.angularVelocity
        body.isAwake = true
    }

    override fun dispose() {
        world.dispose()
    }

    private fun toMeters(worldUnits: Float): Float = worldUnits / pixelsPerMeter

    private fun toWorldUnits(meters: Float): Float = meters * pixelsPerMeter

    private companion object {
        const val DEFAULT_PIXELS_PER_METER = 32f
        const val DEFAULT_BODY_RADIUS_WORLD_UNITS = 16f
        const val VELOCITY_ITERATIONS = 6
        const val POSITION_ITERATIONS = 2
    }
}
