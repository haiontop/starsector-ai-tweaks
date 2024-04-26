package com.genir.aitweaks.features.maneuver

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.*
import com.fs.starfarer.api.combat.ShipwideAIFlags.FLAG_DURATION
import com.genir.aitweaks.asm.combat.ai.AssemblyShipAI
import com.genir.aitweaks.debug.Line
import com.genir.aitweaks.debug.debugPlugin
import com.genir.aitweaks.debug.debugVertices
import com.genir.aitweaks.utils.*
import com.genir.aitweaks.utils.ShipSystemAiType.BURN_DRIVE
import com.genir.aitweaks.utils.ShipSystemAiType.MANEUVERING_JETS
import com.genir.aitweaks.utils.extensions.*
import org.lazywizard.lazylib.ext.combat.canUseSystemThisFrame
import org.lazywizard.lazylib.ext.getFacing
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

const val threatEvalRadius = 2500f
const val aimOffsetSamples = 45

const val effectiveDpsThreshold = 0.66f

// Flux management
const val backoffUpperThreshold = 0.75f
const val backoffLowerThreshold = backoffUpperThreshold / 2f // will force vent
const val holdFireThreshold = 0.9f

// Idle time calculation
const val shieldDownVentTime = 2.0f
const val shieldFlickerThreshold = 0.5f

// Map movement calculation
const val arrivedAtLocationRadius = 2000f
const val borderCornerRadius = 4000f
const val borderNoGoZone = 2500f
const val borderHardNoGoZone = 1250f

@Suppress("MemberVisibilityCanBePrivate")
class Maneuver(val ship: ShipAPI, val maneuverTarget: ShipAPI?, private val targetLocation: Vector2f?) {
    private val engineController = ship.AITStash.engineController
    private val shipAI = ship.ai as AssemblyShipAI
    private val targetFinder = TargetFinder()

    var desiredHeading: Float = ship.facing
    var desiredFacing: Float = ship.facing

    var attackTarget: ShipAPI? = maneuverTarget
    var isBackingOff: Boolean = false
    var aimPoint: Vector2f? = null

    //    private var range: Float = 0f
    private var averageOffset = RollingAverageFloat(aimOffsetSamples)
    private var idleTime = 0f

    // Make strafe rotation direction random, but consistent for a given ship.
    private val strafeRotation = Rotation(if (ship.id.hashCode() % 2 == 0) 10f else -10f)
    private val systemAIType = ship.system?.specAPI?.AIType

    private var threatVector = calculateThreatDirection(ship.location)

    fun advance(dt: Float) {
        ship.AITStash.maneuverAI = this

        if (shouldEndManeuver()) {
            shipAI.cancelCurrentManeuver()
            ship.AITStash.maneuverAI = null
        }

        // Update state.
        threatVector = calculateThreatDirection(ship.location)

        updateAttackTarget()
        updateBackoffStatus()
        updateIdleTime(dt)

        ventIfNeeded()
        holdFireIfOverfluxed()
        manageMobilitySystems()

        setFacing()
        setHeading()

        debugPlugin["backoff"] = if (isBackingOff) "is backing off" else ""

        ship.aiFlags.setFlag(MANEUVER_RANGE_FROM_TARGET, ship.minRange)
        ship.aiFlags.setFlag(MANEUVER_TARGET, FLAG_DURATION, maneuverTarget)
    }

    /** Method called by ShipAI to set ship heading. It is not called when ShipAI
     * is avoiding collision. But since ShipAI collision avoidance is overriden,
     * setting heading by Maneuver needs to be done each frame, in advance method. */
    fun doManeuver() = Unit

    private fun shouldEndManeuver(): Boolean {
        return when {
            // Target ship was destroyed.
            maneuverTarget != null && (maneuverTarget.isExpired || !maneuverTarget.isAlive) -> {
                true
            }

            // Arrived at location.
            targetLocation != null && (ship.location - targetLocation).length() <= arrivedAtLocationRadius -> {
                true
            }

            else -> false
        }
    }

    /** Select which enemy ship to attack. This may be different
     * from the maneuver target provided by the ShipAI. */
    private fun updateAttackTarget() {
        // Attack target is stored in a flag, so it carries over between Maneuver instances.
        val currentTarget: ShipAPI? = ship.AITStash.attackTarget

        targetFinder.target

        val updateTarget = when {
            currentTarget == null -> true
            !currentTarget.isValidTarget -> true

            // Do not interrupt bursts.
            ship.primaryWeapons.firstOrNull { it.trueIsInBurst } != null -> false

            // Finish helpless target.
            currentTarget.fluxTracker.isOverloadedOrVenting -> false

            else -> true
        }

        val updatedTarget = if (updateTarget) targetFinder.target ?: maneuverTarget
        else currentTarget

        ship.AITStash.attackTarget = updatedTarget
        ship.shipTarget = updatedTarget
        attackTarget = updatedTarget
    }

    /** Decide if ships needs to back off due to high flux level */
    // TODO shieldless ships
    private fun updateBackoffStatus() {
        val backingOffFlag = ship.aiFlags.hasFlag(BACKING_OFF)
        val fluxLevel = ship.fluxTracker.fluxLevel

        isBackingOff = when {
            Global.getCombatEngine().isEnemyInFullRetreat -> false

            // Start backing off.
            fluxLevel > backoffUpperThreshold -> true

            // Stop backing off.
            backingOffFlag && fluxLevel <= 0.01f -> false

            // Continue backing off.
            else -> backingOffFlag
        }

        if (isBackingOff)
            ship.aiFlags.setFlag(BACKING_OFF)
        else if (backingOffFlag)
            ship.aiFlags.unsetFlag(BACKING_OFF)
    }

    private fun updateIdleTime(dt: Float) {
        val shieldIsUp = ship.shield?.isOn == true && shieldUptime(ship.shield) > shieldFlickerThreshold
        val isFiring = ship.allWeapons.firstOrNull { it.isFiring } != null

        idleTime = if (shieldIsUp || isFiring) 0f
        else idleTime + dt
    }

    private fun holdFireIfOverfluxed() {
        val tracker = ship.fluxTracker

        debugPlugin["hold fire"] = ""

        if (tracker.fluxLevel < holdFireThreshold) return

        debugPlugin["hold fire"] = "hold fire"

        ship.allWeapons.filter { !it.isPD && it.fluxCostToFire != 0f }.mapNotNull { it.autofirePlugin }.forEach { it.forceOff() }
    }

    /** Force vent when the ship is backing off,
     * not shooting and with shields down. */
    private fun ventIfNeeded() {
        val shouldVent = when {
            !isBackingOff -> false
            ship.fluxTracker.isVenting -> false
            ship.fluxLevel < 0.01f -> false
            ship.fluxLevel < backoffLowerThreshold -> true
            idleTime < shieldDownVentTime -> false
            ship.allWeapons.firstOrNull { it.autofireAI?.shouldFire() == true } != null -> false
            else -> true
        }

        if (shouldVent) ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
    }

    private fun manageMobilitySystems() {
        when (systemAIType) {
            // Use MANEUVERING_JETS to back off, if possible. Vanilla AI
            // does this already, but is not determined enough.
            MANEUVERING_JETS -> {
                if (isBackingOff && ship.canUseSystemThisFrame()) {
                    ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
                }
            }

            // Prevent vanilla AI from jumping closer to target with
            // BURN_DRIVE, if the target is already within weapons range.
            BURN_DRIVE -> {
                if (attackTarget != null && engagementRange(attackTarget!!) < ship.effectiveRange(effectiveDpsThreshold)) {
                    ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
                }
            }

            // TODO BURN_DRIVE_TOGGLE

            else -> Unit
        }
    }

    private fun setFacing() {
        this.aimPoint = null

        val aimPoint: Vector2f = when {
            attackTarget != null -> {
                val target = attackTarget!!
                val farOutOfRange = engagementRange(target) > ship.maxRange + 1000f

                when {
                    // Face threat direction when backing off.
                    isBackingOff && farOutOfRange -> {
                        ship.location + threatVector
                    }

                    // Face heading direction when chasing target.
                    farOutOfRange -> {
                        ship.location + unitVector(desiredHeading) * 1000f
                    }

                    // Average aim offset to avoid ship wobbling.
                    else -> {
                        val aimPointThisFrame = calculateOffsetAimPoint(target)
                        val aimOffsetThisFrame = getShortestRotation(target.location, ship.location, aimPointThisFrame)
                        val aimOffset = averageOffset.update(aimOffsetThisFrame)

                        target.location.rotatedAroundPivot(Rotation(aimOffset), ship.location)
                    }
                }
            }

            targetLocation != null -> {
                // Move to location, if no attack target.
                targetLocation
            }

            // Nothing to do.
            else -> return
        }

        this.aimPoint = aimPoint
        desiredFacing = engineController.facing(aimPoint)
    }

    private fun setHeading() {
        val heading: Vector2f? = when {
            // Move opposite to threat direction when backing off.
            // If there's no threat, the ship will coast with const velocity.
            isBackingOff -> {
                ship.location - threatVector.resized(1000f)
            }

            // Move directly to ordered location.
            targetLocation != null -> {
                targetLocation
            }

            // Orbit target at effective weapon range. Rotate away from threat,
            // or just strafe randomly if no threat.
            maneuverTarget != null -> {
                // TODO will need syncing when interval tracking is introduced.
                val angleFromTargetToThreat = abs(getShortestRotation(maneuverTarget.location - ship.location, threatVector))
                val offset = if (angleFromTargetToThreat > 1f) threatVector
                else threatVector.rotated(strafeRotation)

                maneuverTarget.location - offset.resized(ship.minRange)
            }

            // Nothing to do, stop the ship.
            else -> null
        }

        val censoredHeading = heading?.let { avoidBorder(heading) }
        desiredHeading = engineController.heading(censoredHeading)
    }

    /** Make the ship avoid map border. The ship will attempt to move
     * inside a rectangle with rounded corners placed `borderNoGoZone`
     * units from map border.*/
    private fun avoidBorder(heading: Vector2f): Vector2f {
        val mapH = Global.getCombatEngine().mapHeight / 2f
        val mapW = Global.getCombatEngine().mapWidth / 2f
        val borderZone = borderNoGoZone + borderCornerRadius

        val l = ship.location

        // Translate ship coordinates so that it appears to be always
        // near a map corner. That way we can use a circle calculations
        // to approximate a rectangle with rounded corners.
        val c = Vector2f()
        c.x = if (l.x > 0) (l.x - mapW + borderZone).coerceAtLeast(0f)
        else (l.x + mapW - borderZone).coerceAtMost(0f)
        c.y = if (l.y > 0) (l.y - mapH + borderZone).coerceAtLeast(0f)
        else (l.y + mapH - borderZone).coerceAtMost(0f)

        // Distance into the border zone.
        val d = (c.length() - borderCornerRadius).coerceAtLeast(0f)

        // Ship is far from border, no avoidance required.
        if (d == 0f) return heading

        // Translate to frame of reference, where up
        // corresponds to direction towards border.
        val r = Rotation(90f - c.getFacing())
        val hr = (heading - ship.location).rotated(r)

        // Ship attempts to move away from the border on its own.
        if (hr.y < 0) return heading

        // The closer the ship is to map edge, the stronger
        // the heading transformation away from the border.
        val avoidForce = (d / (borderNoGoZone - borderHardNoGoZone)).coerceAtMost(1f)
        val allowedHeading = when {
            hr.x >= 0f -> Vector2f(hr.length(), 0f)
            else -> Vector2f(-hr.length(), 0f)
        }
        val censoredHeading = (allowedHeading * avoidForce + hr * (1f - avoidForce)).rotatedReverse(r) * 0.5f

        debugVertices.add(Line(ship.location, ship.location + hr.rotatedReverse(r) * (1f - avoidForce), Color.YELLOW))
        debugVertices.add(Line(ship.location, ship.location + allowedHeading.rotatedReverse(r) * avoidForce, Color.BLUE))
        debugVertices.add(Line(ship.location, ship.location + censoredHeading, Color.CYAN))

        return censoredHeading + ship.location
    }

    private fun engagementRange(target: ShipAPI): Float {
        return (target.location - ship.location).length() - target.collisionRadius
    }

    /** Aim hardpoint weapons with entire ship, if possible. */
    private fun calculateOffsetAimPoint(attackTarget: ShipAPI): Vector2f {
        // Find intercept points of all hardpoints attacking the current target.
        val hardpoints = ship.allWeapons.filter { it.slot.isHardpoint }.mapNotNull { it.autofireAI }
        val aimedHardpoints = hardpoints.filter { it.targetShip != null && it.targetShip == attackTarget }
        val interceptPoints = aimedHardpoints.mapNotNull { it.intercept }

        if (interceptPoints.isEmpty()) return attackTarget.location

        // Average the intercept points. This may cause poor aim if different hardpoints
        // have weapons with significantly different projectile velocities.
        val interceptSum = interceptPoints.fold(Vector2f()) { sum, intercept -> sum + intercept }
        val aimPoint = interceptSum / interceptPoints.size.toFloat()

        return aimPoint
    }

    private fun calculateThreatDirection(location: Vector2f): Vector2f {
        val radius = min(threatEvalRadius, ship.maxRange)
        val ships = shipsInRadius(location, radius)
        val threats = ships.filter { it.owner != ship.owner && it.isValidTarget && it.isShip }

        val threatSum = threats.fold(Vector2f()) { sum, it ->
            val dp = it.deploymentPoints
            val dir = (it.location - ship.location).resized(1f)
            sum + dir * dp * dp
        }

        return threatSum
    }

    private fun shipsInRadius(location: Vector2f, radius: Float): Sequence<ShipAPI> {
        val r = radius * 2f
        return shipGrid().getCheckIterator(location, r, r).asSequence().filterIsInstance<ShipAPI>()
    }

    inner class TargetFinder {
        val target: ShipAPI?
            get() {
                val opportunities = findTargetOpportunities().toList()
                return opportunities.minWithOrNull { o1, o2 -> (evaluateTarget(o1) - evaluateTarget(o2)).sign.toInt() }
            }

        /** Evaluate if target is worth attacking. The lower the score, the better the target. */
        private fun evaluateTarget(target: ShipAPI): Float {
            // Prioritize targets closer to ship facing.
            val angle = ship.shortestRotationToTarget(target.location) * PI.toFloat() / 180.0f
            val angleWeight = 0.5f
            val evalAngle = abs(angle) * angleWeight

            // Prioritize closer targets. Avoid attacking targets out of effective weapons range.
            val dist = engagementRange(target)
            val distWeight = if (dist > ship.effectiveRange(effectiveDpsThreshold)) 2f else 1f
            val evalDist = (dist / ship.maxRange) * distWeight

            // Prioritize targets high on flux. Avoid hunting low flux phase ships.
            val fluxLeft = (1f - target.fluxLevel)
            val fluxFactor = if (target.phaseCloak?.specAPI?.isPhaseCloak == true) 1.5f else 0.5f
            val evalFlux = fluxLeft * fluxFactor

            // Avoid attacking bricks.
            val evalDamper = if (target.system?.id == "damper" && !ship.isFrigate) 2f else 0f
            val evalShunt = if (target.variant.hasHullMod("fluxshunt")) 4f else 0f

            return evalAngle + evalDist + evalFlux + evalDamper + evalShunt
        }

        /** Find all potential enemy targets in or close to ship weapon range. */
        private fun findTargetOpportunities(): Sequence<ShipAPI> {
            val rangeEnvelope = 500f
            val allShips = shipsInRadius(ship.location, ship.maxRange + rangeEnvelope)
            val opportunities = allShips.filter {
                when {
                    it.owner == ship.owner -> false
                    !it.isValidTarget -> false
                    !it.isShip -> false
                    engagementRange(it) > ship.maxRange + rangeEnvelope -> false
                    else -> true
                }
            }

            return opportunities
        }
    }
}
