package com.genir.aitweaks.core.features.shipai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.CombatAssignmentType.*
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.BACKING_OFF
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET
import com.fs.starfarer.api.combat.ShipwideAIFlags.FLAG_DURATION
import com.fs.starfarer.combat.entities.Ship
import com.genir.aitweaks.core.combat.combatState
import com.genir.aitweaks.core.features.shipai.systems.SystemAI
import com.genir.aitweaks.core.features.shipai.systems.SystemAIManager
import com.genir.aitweaks.core.features.shipai.vanilla.Vanilla
import com.genir.aitweaks.core.utils.*
import com.genir.aitweaks.core.utils.extensions.*
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.math.abs

// TODO move to interval update

@Suppress("MemberVisibilityCanBePrivate")
class CustomShipAI(val ship: ShipAPI) : ShipAIPlugin {
    // Subsystems.
    val movement: Movement = Movement(this)
    val systemAI: SystemAI? = SystemAIManager.overrideVanillaSystem(this)
    val vanilla: Vanilla = Vanilla(ship, systemAI != null)

    // Helper classes.
    private val damageTracker: DamageTracker = DamageTracker(ship)
    private val updateInterval: IntervalTracker = defaultAIInterval()

    // Standing orders.
    var assignment: CombatFleetManagerAPI.AssignmentInfo? = null
    var assignmentLocation: Vector2f? = null // Assignment takes priority over maneuver target.
    var maneuverTarget: ShipAPI? = null
    var attackTarget: ShipAPI? = null

    // Keep attacking the previous target for the
    // duration or already started weapon bursts.
    var finishBurstTarget: ShipAPI? = null
    var finishBurstWeaponGroup: WeaponGroup? = null

    // AI State.
    var stats: ShipStats = ShipStats(ship)
    var attackingGroup: WeaponGroup = stats.weaponGroups[0]
    var isBackingOff: Boolean = false
    var isHoldingFire: Boolean = false
    var isAvoidingBorder: Boolean = false
    var is1v1: Boolean = false
    var idleTime = 0f
    var threats: List<ShipAPI> = listOf()
    var threatVector = Vector2f()

    override fun advance(dt: Float) {
        debug()

        // Cede the control to vanilla AI when the ship is retreating.
        // This is irreversible, except on player ship.
        if (ship.assignment?.type == RETREAT) {
            ship.shipAI = vanilla.basicShipAI
            return
        }

        updateInterval.advance(dt)
        val interval: Boolean = updateInterval.intervalElapsed()
        if (interval) {
            updateInterval.reset()
            updateShipStats()
            ensureAutofire()
        }

        // Update state.
        damageTracker.advance()
        updateThreats()
        updateIdleTime(dt)
        updateBackoffStatus()
        update1v1Status()

        // Update targets.
        updateAssignment(interval)
        updateManeuverTarget(interval)
        updateAttackTarget(interval)
        updateFinishBurstTarget()

        ventIfNeeded()
        holdFireIfOverfluxed()

        // Advance subsystems.
        vanilla.advance(dt, attackTarget, movement.expectedVelocity, movement.expectedFacing)
        systemAI?.advance(dt)
        movement.advance(dt)

        vanilla.flags.setFlag(MANEUVER_TARGET, FLAG_DURATION, maneuverTarget)
    }

    override fun setDoNotFireDelay(amount: Float) = Unit

    override fun forceCircumstanceEvaluation() = Unit

    override fun needsRefit(): Boolean = false

    override fun getAIFlags(): ShipwideAIFlags = vanilla.flags

    override fun cancelCurrentManeuver() = Unit

    override fun getConfig(): ShipAIConfig = ShipAIConfig()

    private fun debug() {
//        debugPrint.clear()

//        stats.significantWeapons.filter { it.isInFiringSequence }.forEach {
//            debugPrint[it] = it.id
//        }

//        drawTurnLines(ship)

//        drawLine(ship.location, attackTarget?.location ?: ship.location, Color.RED)
//        drawLine(ship.location, finishBurstTarget?.location ?: ship.location, Color.YELLOW)

//        drawLine(ship.location, maneuverTarget?.location ?: ship.location, Color.BLUE)
//        drawLine(ship.location, ship.location + (maneuverTarget?.velocity ?: ship.location), Color.GREEN)
//        drawLine(ship.location, movement.headingPoint, Color.YELLOW)

//        drawLine(ship.location, ship.location + unitVector(ship.facing) * 600f, Color.GREEN)
//        drawLine(ship.location, ship.location + unitVector(movement.expectedFacing) * 600f, Color.YELLOW)

//        drawLine(ship.location, ship.location + unitVector(ship.facing + attackingGroup.facing) * 600f, Color.BLUE)
//        drawLine(ship.location, ship.location + (movement.expectedVelocity).resized(300f), Color.GREEN)
//        drawLine(ship.location, ship.location + (ship.velocity).resized(300f), Color.BLUE)
//        drawLine(ship.location, ship.location + threatVector.resized(600f), Color.PINK)
    }

    private fun updateAssignment(interval: Boolean) {
        // Update assignment location only when assignment
        // was changed or when interval has elapsed.
        if (ship.assignment == assignment && !interval) return

        assignment = ship.assignment
        assignmentLocation = null

        if (assignment == null) return

        val assignment = assignment!!

        when (assignment.type) {
            RECON, AVOID, RETREAT, REPAIR_AND_REFIT, SEARCH_AND_DESTROY -> return

            DEFEND, RALLY_TASK_FORCE, RALLY_CARRIER, RALLY_CIVILIAN, RALLY_STRIKE_FORCE -> Unit
            RALLY_FIGHTERS, STRIKE, INTERCEPT, HARASS, LIGHT_ESCORT, MEDIUM_ESCORT -> Unit
            HEAVY_ESCORT, CAPTURE, CONTROL, ASSAULT, ENGAGE -> Unit

            else -> return
        }

        assignmentLocation = assignment.target?.location
    }

    private fun updateManeuverTarget(interval: Boolean) {
        val needsUpdate = when {
            // Current target is no longer valid.
            maneuverTarget?.isValidTarget == false -> true

            // Don't change target when movement system is on.
            systemAI?.holdTargets() == true -> false

            else -> interval
        }

        if (!needsUpdate) return

        // Try cohesion AI first.
        val cohesionAI = combatState().fleetCohesion?.get(ship.owner)
        cohesionAI?.findClosestTarget(ship)?.let {
            maneuverTarget = it
            return
        }

        // Fall back to the closest target.
        val targets = Global.getCombatEngine().ships.filter {
            when {
                it.owner == ship.owner -> false

                !it.isValidTarget -> false

                it.isFighter -> false

                else -> true
            }
        }

        maneuverTarget = targets.minByOrNull { (it.location - ship.location).lengthSquared() }
    }

    /** Select which enemy ship to attack. This may be different
     * from the maneuver target provided by the ShipAI. */
    private fun updateAttackTarget(interval: Boolean) {
        val currentTarget = attackTarget

        val updateTarget = when {
            currentTarget?.isValidTarget != true -> true

            // Don't change target when movement system is on.
            systemAI?.holdTargets() == true -> false

            // Target is out of range.
            range(currentTarget) > attackingGroup.maxRange -> true

            else -> interval
        }

        if (updateTarget) {
            val (newWeaponGroup, newTarget) = findNewAttackTarget()

            // Keep track of previous target until weapon bursts subside.
            if (newTarget != attackTarget && attackTarget?.isValidTarget == true) {
                finishBurstTarget = attackTarget
                finishBurstWeaponGroup = attackingGroup
            }

            ship.shipTarget = newTarget
            attackTarget = newTarget
            attackingGroup = newWeaponGroup
        }
    }

    /** Decide if ships needs to back off due to high flux level */
    private fun updateBackoffStatus() {
        val fluxLevel = ship.fluxTracker.fluxLevel
        val underFire = damageTracker.damage / ship.maxFlux > 0.2f

        isBackingOff = when {
            // Enemy is routing, keep the pressure.
            Global.getCombatEngine().isEnemyInFullRetreat -> false

            // Ship with no shield backs off when it can't fire anymore.
            ship.shield == null && ship.allWeapons.any { !it.isInFiringSequence && it.fluxCostToFire >= ship.fluxLeft } -> true

            // High flux.
            ship.shield != null && fluxLevel > Preset.backoffUpperThreshold -> true

            // Shields down and received damage.
            underFire && ship.shield != null && ship.shield.isOff -> true

            // Started venting under fire.
            underFire && ship.fluxTracker.isVenting -> true

            // Stop backing off.
            fluxLevel <= 0.01f -> false

            // Continue current backoff status.
            else -> isBackingOff
        }

        if (isBackingOff) vanilla.flags.setFlag(BACKING_OFF)
        else vanilla.flags.unsetFlag(BACKING_OFF)
    }

    private fun updateShipStats() {
        stats = ShipStats(ship)

        // Find the most similar weapon group to the current one after ship stats have been updated.
        attackingGroup = stats.weaponGroups.minWithOrNull(compareBy { abs(MathUtils.getShortestRotation(it.facing, attackingGroup.facing)) })!!
    }

    private fun updateIdleTime(dt: Float) {
        val shieldIsUp = ship.shield?.isOn == true && shieldUptime(ship.shield) > Preset.shieldFlickerThreshold
        val pdIsFiring = ship.allWeapons.firstOrNull { it.isPD && it.isFiring } != null

        idleTime = if (shieldIsUp || pdIsFiring) 0f
        else idleTime + dt
    }

    /** Is ship engaged in 1v1 duel with the target. */
    private fun update1v1Status() {
        is1v1 = when {
            isAvoidingBorder -> false
            isBackingOff -> false
            attackTarget == null -> false
            attackTarget != maneuverTarget -> false
            attackTarget!!.isFrigate != ship.isFrigate -> false
            threats.size > 1 -> false
            else -> true
        }
    }

    private fun updateThreats() {
        threats = shipSequence(ship.location, stats.threatSearchRange).filter { isThreat(it) }.toList()

        threatVector = threats.fold(Vector2f()) { sum, it ->
            val dp = it.deploymentPoints
            val dir = (it.location - ship.location).resized(1f)
            sum + dir * dp * dp
        }
    }

    // Keep track of previous target until weapon bursts subside.
    private fun updateFinishBurstTarget() {
        if (finishBurstTarget?.isValidTarget != true) {
            finishBurstTarget = null
            finishBurstWeaponGroup = null
            return
        }

        val continueBurst = finishBurstWeaponGroup?.weapons?.any { it.isInFiringSequence && it.target == finishBurstTarget }
        if (continueBurst != true) finishBurstTarget = null
    }

    private fun ensureAutofire() {
        (ship as Ship).setNoWeaponSelected()
        ship.weaponGroupsCopy.forEach { it.toggleOn() }
    }

    private fun holdFireIfOverfluxed() {
        isHoldingFire = when {
            // Ships with no shields don't need to preserve flux.
            ship.shield == null -> false

            // Ship is overfluxed.
            ship.fluxTracker.fluxLevel > Preset.holdFireThreshold -> true

            else -> false
        }

        if (isHoldingFire) {
            val fluxWeapons = ship.allWeapons.filter { !it.isPD && it.fluxCostToFire != 0f }
            fluxWeapons.mapNotNull { it.autofirePlugin }.forEach { it.forceOff() }
        }
    }

    /** Force vent when the ship is backing off,
     * not shooting and with shields down. */
    private fun ventIfNeeded() {
        val shouldVent = when {
            // Already venting.
            ship.fluxTracker.isVenting -> false

            // No need to vent.
            ship.fluxLevel < 0.01f -> false

            // Don't interrupt the ship system.
            ship.system?.isOn == true -> false

            !isBackingOff -> false

            // Vent regardless of situation when already passively
            // dissipated below Preset.backoffLowerThreshold
            ship.fluxLevel < Preset.backoffLowerThreshold -> true

            idleTime < Preset.shieldDownVentTime -> false

            // Don't vent when defending from missiles.
            ship.allWeapons.any { it.autofirePlugin?.targetMissile != null } -> false

            else -> true
        }

        if (shouldVent) ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
    }

    private fun findNewAttackTarget(): Pair<WeaponGroup, ShipAPI?> {
        // Find best attack opportunity for each weapon group.
        val weaponGroupTargets: Map<WeaponGroup, ShipAPI> = stats.weaponGroups.associateWith { weaponGroup ->
            val opportunities = threats.filter { range(it) < weaponGroup.maxRange }

            val foldInit: Pair<ShipAPI?, Float> = Pair(null, Float.MAX_VALUE)
            opportunities.fold(foldInit) { best, it ->
                val eval = evaluateTarget(it, weaponGroup)
                if (eval < best.second) Pair(it, eval)
                else best
            }.first
        }.filterValues { it != null }.mapValues { it.value!! }

        val bestTarget = weaponGroupTargets.minOfWithOrNull(compareBy { evaluateTarget(it.value, it.key) }) { it }
        if (bestTarget != null) return bestTarget.toPair()

        // No good attack target found. Try alternatives.
        val altTarget: ShipAPI? = when {
            maneuverTarget != null -> maneuverTarget

            // Try to find a target near move location.
            assignmentLocation != null -> {
                shipSequence(assignmentLocation!!, 200f).firstOrNull { isThreat(it) }
            }

            else -> null
        }
        return Pair(stats.weaponGroups[0], altTarget)
    }

    /** Evaluate if target is worth attacking. The lower the score, the better the target. */
    private fun evaluateTarget(target: ShipAPI, weaponGroup: WeaponGroup): Float {
        // Prioritize targets closer to ship facing.
        val angle = ship.shortestRotationToTarget(target.location, weaponGroup.facing) * PI.toFloat() / 180.0f
        val angleWeight = 0.75f
        val evalAngle = abs(angle) * angleWeight

        // Prioritize closer targets. Avoid attacking targets out of effective weapons range.
        val dist = range(target)
        val distWeight = 1f / weaponGroup.dpsFractionAtRange(dist)
        val evalDist = (dist / weaponGroup.maxRange) * distWeight

        // Prioritize targets high on flux. Avoid hunting low flux phase ships.
        val fluxLeft = (1f - target.fluxLevel)
        val fluxFactor = if (target.phaseCloak?.specAPI?.isPhaseCloak == true) 2f else 0.5f
        val evalFlux = fluxLeft * fluxFactor

        // Avoid attacking bricks, especially Monitors.
        val evalDamper = if (target.system?.id == "damper" && !target.isFrigate) 1f else 0f
        val evalShunt = if (target.variant.hasHullMod("fluxshunt") && target.isFrigate) 256f else 0f

        // Assign lower priority to frigates.
        val evalType = if (target.isSmall) 1f else 0f

        // Finish helpless target.
        val evalVent = if (target.fluxTracker.isOverloadedOrVenting) -2f else 0f

        // Try to stay on target.
        val evalCurrentTarget = if (target == attackTarget && range(target) <= weaponGroup.effectiveRange) -2f else 0f

        // TODO avoid wrecks

        return evalAngle + evalDist + evalFlux + evalDamper + evalShunt + evalType + evalVent + evalCurrentTarget
    }

    /** Range from which ship should attack its target. */
    fun calculateAttackRange(): Float {
        val flag = vanilla.flags.get<Float>(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET)

        return when {
            // Range overriden by ai flag.
            flag != null -> flag

            // Default all-weapons attack range.
            attackingGroup.dps > 0f -> attackingGroup.minRange

            // Range for ships with no weapons.
            else -> Preset.noWeaponsAttackRange
        }
    }

    private fun isThreat(target: ShipAPI): Boolean {
        return target.owner != ship.owner && target.isAlive && !target.isExpired && target.isShip
    }

    internal fun range(target: ShipAPI): Float {
        return (target.location - ship.location).length() - target.collisionRadius / 2f
    }
}