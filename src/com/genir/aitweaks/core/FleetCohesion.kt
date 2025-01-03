package com.genir.aitweaks.core

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.AssignmentTargetAPI
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatAssignmentType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.combat.tasks.CombatTaskManager
import com.genir.aitweaks.core.extensions.*
import com.genir.aitweaks.core.shipai.Preset
import com.genir.aitweaks.core.state.State.Companion.state
import com.genir.aitweaks.core.utils.closestEntity
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

class FleetCohesion(private val side: Int) : BaseEveryFrameCombatPlugin() {
    private val enemy: Int = side xor 1

    private val cohesionAssignments: MutableSet<AssignmentKey> = mutableSetOf()
    private val cohesionWaypoints: MutableSet<AssignmentTargetAPI> = mutableSetOf()

    private var validGroups: List<Set<ShipAPI>> = listOf()
    private var primaryBigTargets: List<ShipAPI> = listOf()
    private var primaryTargets: List<ShipAPI> = listOf()
    private var allBigTargets: List<ShipAPI> = listOf()
    private var allTargets: List<ShipAPI> = listOf()

    private val advanceInterval = IntervalUtil(0.75f, 1f)

    private data class AssignmentKey(val ship: ShipAPI, val location: Vector2f?, val type: CombatAssignmentType)

    override fun advance(dt: Float, events: MutableList<InputEventAPI>?) {
        val engine = Global.getCombatEngine()
        when {
            !state.config.enableFleetCohesion -> return

            engine.isSimulation -> return

            engine.isPaused -> return
        }

        identifyBattleGroups()

        advanceInterval.advance(dt)
        if (!advanceInterval.intervalElapsed()) return

        // Cleanup of previous iteration assignments and waypoints.
        validGroups = listOf()
        primaryBigTargets = listOf()
        primaryTargets = listOf()
        allBigTargets = listOf()
        allTargets = listOf()
        clearAssignments()
        clearWaypoints()

        val taskManager = getTaskManager()
        when {
            taskManager.isInFullRetreat -> return

            // Giving assignments disrupts the full assault.
            taskManager.isFullAssault -> return

            // Do not force targets if there's an avoid assignment active.
            taskManager.allAssignments.firstOrNull { it.type == CombatAssignmentType.AVOID } != null -> return

            // Battle is already won.
            engine.getFleetManager(enemy).getTaskManager(false).isInFullRetreat -> return
        }

        // Don't give orders to enemy side
        // to not interfere with AdmiralAI.
        if (side == 1) return

        val ships = engine.ships.filter {
            when {
                it.owner != side -> false
                !it.isAlive -> false
                it.isExpired -> false
                !it.isBig -> false
                it.isAlly -> false
                it.isStation -> false
                it.isModule -> false
                it.basicShipAI == null -> false
                it.isUnderManualControl -> false
                else -> true
            }
        }

        // Assign targets.
        val channelWasOpen = taskManager.isCommChannelOpen
        ships.forEach { manageAssignments(it) }
        if (!channelWasOpen && taskManager.isCommChannelOpen) taskManager.closeCommChannel()
    }

    private fun identifyBattleGroups() {
        val engine = Global.getCombatEngine()

        val enemyFleet = engine.ships.filter { it.owner == enemy && it.isValidTarget && !it.isFighter }
        if (enemyFleet.isEmpty()) return
        val groups = segmentFleet(enemyFleet.toTypedArray())
        val groupsFromLargest = groups.sortedBy { it.dpSum }.reversed()
        validGroups = groupsFromLargest.filter { isValidGroup(it, groupsFromLargest.first().dpSum) }

        val fog = engine.getFogOfWar(side)
        primaryTargets = validGroups.first().filter { fog.isVisible(it) }
        primaryBigTargets = primaryTargets.filter { it.isBig }
        allTargets = validGroups.flatten().filter { fog.isVisible(it) }
        allBigTargets = allTargets.filter { it.isBig }
    }

    private fun findValidTarget(ship: ShipAPI, currentTarget: ShipAPI?): ShipAPI? {
        return when {
            validGroups.isEmpty() -> currentTarget

            // Ship is engaging or planning to engage the primary group.
            validGroups.first().contains(currentTarget) -> currentTarget

            // Ship is engaging a secondary group.
            currentTarget != null && validGroups.any { it.contains(currentTarget) } && closeToEnemy(ship, currentTarget) -> currentTarget

            // Ship has wrong target. Find the closest valid target in the main enemy battle group.
            else -> closestEntity(primaryBigTargets, ship.location) ?: currentTarget
        }
    }

    fun findClosestTarget(ship: ShipAPI): ShipAPI? {
        val allTargets = if (ship.isFast) allTargets else allBigTargets
        val closestTarget: ShipAPI? = closestEntity(allTargets, ship.location)
        if (closestTarget != null && closeToEnemy(ship, closestTarget))
            return closestTarget

        val primaryTargets = if (ship.isFast) primaryTargets else primaryBigTargets
        val primaryTarget: ShipAPI? = closestEntity(primaryTargets, ship.location)
        return primaryTarget
    }

    private fun manageAssignments(ship: ShipAPI) {
        // Ship has foreign assignment.
        if (ship.assignment != null) {
            return
        }

        val target = ship.attackTarget ?: return
        val validTarget = findValidTarget(ship, target)
        if (validTarget == null || validTarget == target) return

        // Create waypoint on the target ship. Make sure it follows the target even on the map edge.
        val fleetManager = Global.getCombatEngine().getFleetManager(ship.owner)
        val waypoint = fleetManager.createWaypoint(Vector2f(), true)
        waypoint.location.set(validTarget.location)
        cohesionWaypoints.add(waypoint)

        // Assign target to ship.
        val taskManager = getTaskManager()
        val doNotRefundCP = taskManager.isCommChannelOpen
        val assignment = taskManager.createAssignment(CombatAssignmentType.RALLY_TASK_FORCE, waypoint, doNotRefundCP)
        taskManager.giveAssignment(ship.deployedFleetMember, assignment, false)

        if (ship.assignment != null) {
            val key = AssignmentKey(ship, ship.assignment!!.target.location, ship.assignment!!.type)
            cohesionAssignments.add(key)
        }
    }

    private fun clearAssignments() {
        // Assignments given be fleet cohesion AI may have
        // already expired. Remove only the non-expired ones.
        val taskManager = getTaskManager()
        cohesionAssignments.forEach { assignment ->
            val ship = assignment.ship
            when {
                ship.isExpired -> Unit
                !ship.isAlive -> Unit
                ship.assignment == null -> Unit
                assignment != AssignmentKey(ship, ship.assignment!!.target?.location, ship.assignment!!.type) -> Unit

                else -> taskManager.removeAssignment(ship.assignment)
            }
        }

        cohesionAssignments.clear()
    }

    private fun clearWaypoints() {
        cohesionWaypoints.forEach {
            Global.getCombatEngine().removeObject(it)
        }

        cohesionWaypoints.clear()
    }

    // Divide fleet into separate battle groups.
    private fun segmentFleet(fleet: Array<ShipAPI>): List<Set<ShipAPI>> {
        val maxRange = 2000f

        // Assign targets to battle groups.
        val groups = IntArray(fleet.size) { it }
        for (i in fleet.indices) {
            for (j in fleet.indices) {
                when {
                    // Cannot attach to battle group via frigate.
                    fleet[j].root.isFrigate -> continue

                    // Frigate already attached to battle group.
                    fleet[i].root.isFrigate && groups[i] != i -> continue

                    // Both targets already in same battle group.
                    groups[i] == groups[j] -> continue

                    // Too large distance between targets to connect.
                    (fleet[i].location - fleet[j].location).lengthSquared() > maxRange * maxRange -> continue
                }

                // Merge battle groups.
                val toMerge = groups[i]
                for (k in groups.indices) {
                    if (groups[k] == toMerge) groups[k] = groups[j]
                }
            }
        }

        // Build battle groups.
        val sets: MutableMap<Int, MutableSet<ShipAPI>> = mutableMapOf()
        for (i in fleet.indices) {
            if (!sets.contains(groups[i])) sets[groups[i]] = mutableSetOf()

            sets[groups[i]]!!.add(fleet[i])
        }

        return sets.values.toList()
    }

    private fun closeToEnemy(ship: ShipAPI, target: ShipAPI): Boolean {
        val maxRange = max(max(Preset.threatSearchRange, ship.maxRange * 2f), target.maxRange)
        return (ship.location - target.location).lengthSquared() <= maxRange * maxRange
    }

    private fun isValidGroup(group: Set<ShipAPI>, largestGroupDP: Float): Boolean {
        return group.any { ship: ShipAPI -> ship.root.isCapital } || (group.dpSum * 4f >= largestGroupDP)
    }

    private fun getTaskManager(): CombatTaskManager {
        return Global.getCombatEngine().getFleetManager(side).getTaskManager(false) as CombatTaskManager
    }

    private val Set<ShipAPI>.dpSum: Float
        get() = sumOf { it.deploymentPoints }

    private val ShipAPI.maxRange: Float
        get() = allWeapons.maxOfOrNull { it.slotRange } ?: 0f
}
