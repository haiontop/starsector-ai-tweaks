package com.genir.aitweaks.core.shipai.systems

import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.*
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.genir.aitweaks.core.extensions.*
import com.genir.aitweaks.core.shipai.AttackCoordinator
import com.genir.aitweaks.core.shipai.CustomShipAI
import com.genir.aitweaks.core.shipai.Preset.Companion.backoffUpperThreshold
import com.genir.aitweaks.core.utils.*
import org.lazywizard.lazylib.ext.combat.canUseSystemThisFrame
import org.lwjgl.util.vector.Vector2f
import kotlin.math.min

/** Burn Drive AI. It replaces the vanilla implementation for ships with custom AI. */
class BurnDriveToggle(ai: CustomShipAI) : SystemAI(ai), AttackCoordinator.Coordinable {
    private val updateInterval: IntervalUtil = defaultAIInterval()

    private var burnVector: Vector2f = Vector2f() // In ship frame of reference.
    private var targetOffset: Vector2f = Vector2f() // In target frame of reference.
    private var shouldInitBurn = false

    // Used for communication with attack coordinator.
    override var proposedHeadingPoint: Vector2f? = null
    override var reviewedHeadingPoint: Vector2f? = null

    // Hardcoded vanilla value.
    private val burnDriveFlatBonus: Float = 200f

    private var maxSpeed: Float = Float.MAX_VALUE
    private var maxBurnDist: Float = 0f

    companion object Preset {
        const val approachToMinRangeFraction = 0.875f
        const val maxAngleToTarget = 45f
        const val stopBeforeCollision = 0.2f // seconds
        const val ignoreMassFraction = 0.4f
        const val minBurnDistFraction = 0.33f
        const val maxFluxLevel = backoffUpperThreshold * 0.7f
    }

    override fun advance(dt: Float) {
        // Burn vector needs to be updated every frame to be
        // effectively coordinated by AttackCoordinator.
        updateBurnVector()

        updateInterval.advance(dt)
        if (updateInterval.intervalElapsed()) {
            updateMaxBurnDist()
            updateShouldInitBurn()
            triggerSystem()
        }
    }

    override fun overrideHeading(): Vector2f? {
        return if (shouldInitBurn) burnVector + ship.location
        else null
    }

    override fun overrideFacing(): Float? {
        return if (shouldInitBurn) burnVector.facing
        else null
    }

    private fun updateMaxBurnDist() {
        // Try to get the unmodified max speed, without burn drive bonus.
        maxSpeed = min(maxSpeed, ship.engineController.maxSpeedWithoutBoost)

        val effectiveBurnDuration = system.chargeActiveDur + (system.chargeUpDur + system.chargeDownDur) / 2f
        maxBurnDist = (maxSpeed + burnDriveFlatBonus) * effectiveBurnDuration
    }

    private fun updateBurnVector() {
        // Choose new burn destination.
        val maneuverTarget: ShipAPI? = ai.maneuverTarget
        val newDestination = when {
            ai.assignment.navigateTo != null -> {
                ai.assignment.navigateTo!!
            }

            // Charge straight at the maneuver target.
            maneuverTarget != null -> {
                calculateBurnToManeuverTarget(maneuverTarget)
            }

            else -> null
        }

        burnVector = newDestination?.let { it - ship.location } ?: Vector2f()
    }

    private fun calculateBurnToManeuverTarget(maneuverTarget: ShipAPI): Vector2f? {
        // When burn drive is active, try to maintain heading towards the target.
        // Otherwise, the attack coordinator may interrupt the burn by changing destination.
        if (system.state == IN || system.state == ACTIVE) {
            val burnLocation = targetOffset + maneuverTarget.location
            // Let attack coordinator know of ships' heading,
            // so other ships don't burn to same location.
            proposedHeadingPoint = burnLocation
            return burnLocation
        }

        val vectorToTarget = maneuverTarget.location - ship.location
        val rangeOverride = ai.vanilla.flags.get<Float>(AIFlags.MANEUVER_RANGE_FROM_TARGET)
        val expectedRange = rangeOverride ?: (ai.attackingGroup.minRange * approachToMinRangeFraction)
        val burnDist = vectorToTarget.length - expectedRange

        // Let the attack coordinator review the calculated heading point.
        val burnVector = vectorToTarget.resized(burnDist)
        proposedHeadingPoint = burnVector + ship.location

        // Save target offset for when burn drive is active.
        reviewedHeadingPoint?.let {
            targetOffset = reviewedHeadingPoint!! - maneuverTarget.location
        }

        return reviewedHeadingPoint
    }

    /** Should the ship position itself to begin burn? */
    private fun updateShouldInitBurn() {
        shouldInitBurn = when {
            system.state != IDLE -> false

            !ship.canUseSystemThisFrame() -> false

            burnVector.isZero -> false

            ai.backoff.isBackingOff -> false

            // Don't burn if not facing the burn destination, as this may lead to interrupting an attack.
            angleToDestination() > maxAngleToTarget -> false

            // Don't burn to destination if it's too close.
            burnVector.length < maxBurnDist * minBurnDistFraction -> false

            // Don't burn to maneuver target when high on flux.
            ai.assignment.navigateTo == null && ship.fluxLevel > maxFluxLevel -> false

            // Don't burn to maneuver target when it's already in effective weapons range.
            ai.assignment.navigateTo == null && (ai.maneuverTarget!!.location - ship.location).length <= ai.attackingGroup.effectiveRange -> false

            !isRouteClear() -> false

            else -> true
        }
    }

    private fun triggerSystem() {
        val shouldTrigger: Boolean = when (system.state) {
            // Should begin burn?
            IDLE -> {
                shouldInitBurn && angleToDestination() < 0.1f
            }

            // Should interrupt burn?
            IN, ACTIVE -> when {
                // Lost burn vector.
                burnVector.isZero -> true

                // Veered off course, stop.
                angleToDestination() > maxAngleToTarget -> true

                // Avoid collisions.
                isCollisionImminent() -> true

                else -> false
            }

            else -> false
        }

        if (shouldTrigger) ship.command(ShipCommand.USE_SYSTEM)
    }

    private fun angleToDestination(): Float {

        ai.movement.expectedFacing

        return if (burnVector.isZero) 0f
        else absShortestRotation(burnVector.facing, ship.facing)
    }

    private fun findObstacles(center: Vector2f, radius: Float): Sequence<ShipAPI> {
        return Grid.ships(center, radius).filter {
            when {
                // Self
                it == ship -> false

                // Fighters
                it.collisionClass != CollisionClass.SHIP -> false

                // Allies
                it.owner == ship.owner -> true

                // Equal or larger hulls. Hitting smaller hulls will not cause flameout.
                it.root.hullSize.ordinal >= ship.hullSize.ordinal -> true

                // Heavy obstacles. Hitting heavy hulls will deflect the ships path.
                it.mass >= ship.mass * ignoreMassFraction -> true

                else -> false
            }
        }
    }

    private fun isRouteClear(): Boolean {
        val dist = burnVector.length.coerceAtMost(maxBurnDist)
        val position = ship.location + burnVector.resized(dist / 2)
        val obstacles = findObstacles(position, dist / 2)

        val maxBurnDuration = system.chargeActiveDur + system.chargeUpDur + system.chargeDownDur
        val timeToTarget = maxBurnDuration * (dist / maxBurnDist)
        val effectiveSpeed = maxBurnDist / maxBurnDuration

        return timeToTarget < timeToCollision(obstacles, burnVector.resized(effectiveSpeed))
    }

    private fun isCollisionImminent(): Boolean {
        val radius = maxBurnDist / 2
        val position = ship.location + unitVector(ship.facing) * radius
        val obstacles = findObstacles(position, radius)

        return timeToCollision(obstacles, ship.velocity) <= stopBeforeCollision
    }

    private fun timeToCollision(obstacles: Sequence<ShipAPI>, shipVelocity: Vector2f): Float {
        return obstacles.mapNotNull { obstacle ->
            val p = obstacle.location - ship.location
            val v = obstacle.velocity - shipVelocity
            val r = ship.totalCollisionRadius + obstacle.totalCollisionRadius

            // Calculate time to collision.
            if (p.lengthSquared <= r * r) 0f
            else solve(Pair(p, v), r)
        }.minOrNull() ?: Float.MAX_VALUE
    }
}
