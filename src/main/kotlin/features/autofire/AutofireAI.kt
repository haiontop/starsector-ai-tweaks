package com.genir.aitweaks.features.autofire

import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.util.IntervalUtil
import com.genir.aitweaks.utils.div
import com.genir.aitweaks.utils.extensions.aimLocation
import com.genir.aitweaks.utils.extensions.hasBestTargetLeading
import com.genir.aitweaks.utils.extensions.maneuverTarget
import com.genir.aitweaks.utils.rotateAroundPivot
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

// TODO
// take high-tech station shield into account
// check normal station shield detection

// don't switch targets mid burst
// paladin ff
// track ship target for player

/** Low priority / won't do */
// fog
// target selection
// STRIKE never targets fighters ??

class AutofireAI(private val weapon: WeaponAPI) : AutofireAIPlugin {
    private var target: CombatEntityAPI? = null
    private var maneuverTarget: ShipAPI? = null
    private var prevTarget: CombatEntityAPI? = null

    private var attackTime: Float = 0f
    private var idleTime: Float = 0f

    private var selectTargetInterval = IntervalUtil(0.25F, 0.5F);

    override fun advance(timeDelta: Float) {
        trackManeuverTarget()
        trackTimes(timeDelta)
        selectTargetInterval.advance(timeDelta)

        if (selectTargetInterval.intervalElapsed()) target = selectTarget(weapon, target, maneuverTarget)
    }

    override fun shouldFire(): Boolean {
        if (target == null || Global.getCurrentState() != GameState.COMBAT) return holdFire

        // Fire only when the selected target is in range.
        val (range, willHitShield) = analyzeHit(weapon, target!!) ?: return holdFire

        return when {
            range > weapon.range -> holdFire
            !avoidPhased(weapon, target!!) -> holdFire
            !avoidShields(weapon, target!!, willHitShield) -> holdFire
            !avoidExposedHull(weapon, target!!, willHitShield) -> holdFire
            //!avoidWastingTorpedo(weapon, target!!, willHitShield) -> holdFire
            !avoidFriendlyFire(weapon, target!!, range) -> holdFire
            else -> fire
        }
    }

    override fun forceOff() {
        target = null
    }

    override fun getTarget(): Vector2f? {
        if (target == null) return null

        val offset = interceptOffset(weapon, target!!) ?: return null
        val intercept = target!!.aimLocation + offset / getAccuracy()

        return if (weapon.slot.isTurret) intercept
        else aimHardpoint(intercept)
    }

    override fun getTargetShip(): ShipAPI? = target as? ShipAPI
    override fun getWeapon(): WeaponAPI = weapon
    override fun getTargetMissile(): MissileAPI? = target as? MissileAPI

    private fun trackManeuverTarget() {
        val newTarget = weapon.ship.maneuverTarget
        if (newTarget != null || maneuverTarget?.isAlive != true) maneuverTarget = newTarget
    }

    private fun trackTimes(timeDelta: Float) {
        val currentTarget = target
        if (currentTarget != null && prevTarget != currentTarget) {
            prevTarget = currentTarget
            attackTime = 0f
        }

        if (weapon.isFiring) {
            attackTime += timeDelta
            idleTime = 0f
        } else idleTime += timeDelta

        if (idleTime >= 3f) attackTime = 0f
    }

    /**
     * getAccuracy returns current weapon accuracy.
     * The value is a number in range [1.0;2.0]. 1.0 is perfect accuracy, 2.0f is the worst accuracy.
     * For worst accuracy, the weapon should aim exactly in the middle point between the target actual
     * position and calculated intercept position.
     */
    private fun getAccuracy(): Float {
        if (weapon.hasBestTargetLeading) return 1f

        val accBase = weapon.ship.aimAccuracy
        val accBonus = weapon.spec.autofireAccBonus
        return (accBase - (accBonus + attackTime / 15f)).coerceAtLeast(1f)
    }

    /** predictive aiming for hardpoints */
    private fun aimHardpoint(intercept: Vector2f): Vector2f {
        val tgtLocation = target!!.aimLocation - weapon.ship.location
        val tgtFacing = VectorUtils.getFacing(tgtLocation)
        val angleToTarget = MathUtils.getShortestRotation(tgtFacing, weapon.ship.facing)

        // Ship is already facing the target. Return the
        // original target location to not overcompensate.
        if (abs(angleToTarget) < weapon.arc / 2f) {
            return intercept
        }

        return rotateAroundPivot(intercept, weapon.ship.location, angleToTarget)
    }
}

