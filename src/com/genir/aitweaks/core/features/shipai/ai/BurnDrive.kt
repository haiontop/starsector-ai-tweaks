package com.genir.aitweaks.core.features.shipai.ai

import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.ACTIVE
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.IDLE
import com.genir.aitweaks.core.debug.debugPlugin
import com.genir.aitweaks.core.debug.drawLine
import com.genir.aitweaks.core.utils.*
import com.genir.aitweaks.core.utils.extensions.addLength
import com.genir.aitweaks.core.utils.extensions.isModule
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.combat.canUseSystemThisFrame
import org.lazywizard.lazylib.ext.getFacing
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs
import kotlin.math.min

class BurnDrive(val ship: ShipAPI, val ai: Maneuver) {
    var headingPoint: Vector2f? = ai.stash.burnDriveHeading
    var shouldBurn = false

    private var vectorToTarget: Vector2f = Vector2f()
    private var distToTarget: Float = 0f
    private var angleToTarget: Float = 0f

    private var maxSpeed = Float.MAX_VALUE
    private var maxBurnDist: Float = 0f

    fun advance(dt: Float) {
        updateMaxBurnDist()
        updateHeadingPoint()
        updateShouldBurn()

        if (headingPoint == null) {
            drawLine(ship.location, ship.location + unitVector(ship.facing) * 200f, Color.RED)
        } else {
            drawLine(ship.location, headingPoint!!, Color.GREEN)
        }
    }

    private fun updateMaxBurnDist() {
        // Try to get the unmodified max speed, without burn drive bonus.
        maxSpeed = min(maxSpeed, ship.engineController.maxSpeedWithoutBoost)

        val burnDriveFlatBonus = 200f
        val duration = ship.system.chargeActiveDur + (ship.system.chargeUpDur + ship.system.chargeDownDur) / 2f
        maxBurnDist = (maxSpeed + burnDriveFlatBonus) * duration
    }

    private fun updateHeadingPoint() {
        val newHeadingPoint = when {
            // Freeze the heading point when ship is burning.
            ship.system.isActive -> headingPoint

            ai.moveOrderLocation != null -> ai.moveOrderLocation

            // Charge straight at the maneuver target, disregard fleet coordination.
            ai.maneuverTarget != null -> {
                val vectorToTarget = ai.maneuverTarget.location - ship.location
                vectorToTarget.addLength(-ai.minRange * Preset.approachToRangeFraction) + ship.location
            }

            else -> null
        }

        if (newHeadingPoint != null) {
            vectorToTarget = newHeadingPoint - ship.location
            headingPoint = vectorToTarget + ship.location
            distToTarget = vectorToTarget.length()
            angleToTarget = abs(MathUtils.getShortestRotation(vectorToTarget.getFacing(), ship.facing))
        } else {
            headingPoint = null
            vectorToTarget = Vector2f()
            distToTarget = 0f
            angleToTarget = 0f
        }

        // Heading point target is stored in stash,
        // so it carries over between BurnDrive instances.
        ai.stash.burnDriveHeading = this.headingPoint
    }

    private fun updateShouldBurn() {
        shouldBurn = when {
            ship.system.state != IDLE -> false

            !ship.canUseSystemThisFrame() -> false

            headingPoint == null -> false

            ai.isBackingOff -> false

            angleToTarget > 15f -> false

            distToTarget < maxBurnDist / 2f -> false

            !routeIsClear() -> false

            else -> true
        }
    }

    private var d = 0

    fun shouldTrigger(dt: Float): Boolean {
        debugPlugin[ship] = "${ship.name} ${ship.system.state} $d"
        d++

        return when {
            // Launch.
            shouldBurn && angleToTarget < 0.1f -> {
                debugPlugin[ship] = "${ship.name} ${ship.system.state} $d burn"
                true
            }

            ship.system.state != ACTIVE -> false

            // Stop to not overshoot target.
            vMax(dt, distToTarget, 600f) < ship.velocity.length() -> {
                debugPlugin[ship] = "${ship.name} ${ship.system.state} $d dist"
                true
            }

            // Veered off course, stop.
            angleToTarget > 15f -> {
                debugPlugin[ship] = "${ship.name} ${ship.system.state} $d angle $angleToTarget"
                true
            }

            else -> false
        }
    }

    private fun routeIsClear(): Boolean {
        val p = ship.location + vectorToTarget / 2f
        val r = distToTarget.coerceAtMost(maxBurnDist)

        val obstacles = shipSequence(p, r).filter {
            when {
                it == ship -> false
                it.collisionClass != CollisionClass.SHIP -> false

                // Ram enemy frigates.
                it.owner != ship.owner && it.isFrigate && !it.isModule -> false
                else -> true
            }
        }.toList()

        return obstacles.none { obstacle ->
            val t = timeToOrigin(ship.location - obstacle.location, vectorToTarget)

            if (t < 0f || t > 1f) false
            else {
                val closestPoint = ship.location + vectorToTarget * t
                val dist = (closestPoint - obstacle.location).length()

                dist < ship.totalCollisionRadius + obstacle.totalCollisionRadius + Preset.collisionBuffer
            }
        }
    }
}
