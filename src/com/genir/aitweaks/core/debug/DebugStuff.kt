package com.genir.aitweaks.core.debug

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.combat.entities.Ship.ShipAIWrapper
import com.genir.aitweaks.core.features.shipai.EngineController
import com.genir.aitweaks.core.features.shipai.autofire.SimulateMissile
import com.genir.aitweaks.core.state.State.Companion.state
import com.genir.aitweaks.core.utils.*
import com.genir.aitweaks.core.utils.Rotation.Companion.rotated
import com.genir.aitweaks.core.utils.extensions.*
import org.lwjgl.util.vector.Vector2f
import java.awt.Color.BLUE
import java.awt.Color.GREEN

/**
 *
 * FRAME UPDATE ORDER
 *
 * ship movement
 * AI
 * ship advance:
 *   engine controller process commands
 *   weapons:
 *      fire projectile
 *      update aim
 * (ship movement, AI, ship advance LOOP for fast time ships)
 *
 * EFSs
 *
 */

internal fun debug(dt: Float) {
//    val ship = Global.getCombatEngine().playerShip ?: return
//    val ships = Global.getCombatEngine().ships.filter { !it.isFighter }
//
//    ships.forEach { obstacle ->
//
//        Debug.drawBounds(obstacle)
//
//        if (state.bounds.isPointWithin(mousePosition(), obstacle)) {
//            Debug.drawBounds(obstacle, BLUE)
//        }
//
//
////        Debug.drawLine(state.bounds.closestPoint(mousePosition(), obstacle), mousePosition())
//    }

//    val v = unitVector((mousePosition() - ship.location).facing) * 10f
//
//    val collisions = ships.mapNotNull { obstacle ->
//        if (obstacle == ship) return@mapNotNull null
//
//        Debug.drawBounds(obstacle, YELLOW)
//
//        val p = ship.location - obstacle.location
//        val collision = state.bounds.collision(p, v, obstacle) ?: return@mapNotNull null
//
//        Pair(obstacle, collision)
//    }
//
//    collisions.forEach {
//        Debug.print[it.first] = "${it.first.hullSpec.hullId} ${it.second}"
//    }
//
//    if (collisions.isEmpty()) {
//        Debug.drawLine(ship.location, mousePosition())
//    } else {
//        val (obstacle, dist) = collisions.minWithOrNull(compareBy { it.second })!!
//        val collision = ship.location + v * dist
//
//        if ((collision - ship.location).length <= (mousePosition() - ship.location).length) {
//            Debug.drawBounds(obstacle, BLUE)
//            Debug.drawLine(ship.location, ship.location + v * dist)
//        }
//    }
}

var expectedFacing = 90f
const val df = -1f * 60f

class RotateEngineControllerAI(val ship: ShipAPI) : BaseEngineControllerAI() {
    private val controller = EngineController(ship)

    override fun advance(dt: Float) {
        expectedFacing += df * dt

        Debug.drawLine(ship.location, ship.location + unitVector(expectedFacing) * 400f, GREEN)
        Debug.drawLine(ship.location, ship.location + unitVector(ship.facing) * 400f, BLUE)
        Debug.print["f"] = absShortestRotation(ship.facing, expectedFacing)

        controller.facing(dt, expectedFacing)
    }
}

class FollowMouseAI(val ship: ShipAPI) : BaseEngineControllerAI() {
    private val controller = EngineController(ship)

    override fun advance(dt: Float) {
        val toMouse = mousePosition() - ship.location

        val facing = if (toMouse.length > ship.collisionRadius / 2f) toMouse.facing
        else controller.rotationStop

        controller.facing(dt, facing)
        controller.heading(dt, mousePosition())

        Debug.drawEngineLines(ship)
    }

    companion object {
        fun install(ship: ShipAPI) {
            if (((ship.ai as? ShipAIWrapper)?.ai !is FollowMouseAI)) {
                ship.shipAI = FollowMouseAI(ship)
            }
        }
    }
}

var trail: Sequence<SimulateMissile.Frame>? = null

fun debugMissilePath(dt: Float) {
    val ship = Global.getCombatEngine().playerShip ?: return
    val weapon = ship.allWeapons.firstOrNull() ?: return

    if (trail != null) {
        var prev = trail!!.firstOrNull()!!.location
        trail?.forEach { frame ->
            Debug.drawLine(prev, frame.location, BLUE)
            prev = frame.location
        }
    }

    if (Global.getCombatEngine().missiles.isNotEmpty())
        return

    trail = SimulateMissile.missilePath(weapon)
}

class DroneFormationAI(private val drone: ShipAPI, val ship: ShipAPI, private val offset: Vector2f) : BaseEngineControllerAI() {
    private val controller = EngineController(drone)

    override fun advance(dt: Float) {
        val currentOffset = offset.rotated(Rotation(ship.facing))

        controller.heading(dt, ship.location + currentOffset)
        controller.facing(dt, currentOffset.facing)
    }
}

private fun makeDroneFormation() {
    if (Global.getCombatEngine().getTotalElapsedTime(false) < 8f) return

    val ship = Global.getCombatEngine().playerShip ?: return
    val drones = Global.getCombatEngine().ships.filter { it.isFighter }

    val angle = 360f / drones.size

    for (i in drones.indices) {
        val drone = drones[i]

        if (((drone.ai as? ShipAIWrapper)?.ai !is DroneFormationAI)) {
            val offset = Vector2f(0f, 500f).rotated(Rotation(angle * i))
            drone.shipAI = DroneFormationAI(drone, ship, offset)
        }

        Debug.drawEngineLines(drone)
    }
}

fun removeAsteroids() {
    val engine = Global.getCombatEngine()
    engine.asteroids.forEach {
        engine.removeEntity(it)
    }
}

private fun speedupAsteroids() {
    val asteroids = Global.getCombatEngine().asteroids
    for (i in asteroids.indices) {
        val a = asteroids[i]
        a.mass = 0f
        a.velocity.set(a.velocity.resized(1200f))
    }
}

abstract class BaseEngineControllerAI : ShipAIPlugin {
    override fun setDoNotFireDelay(amount: Float) = Unit

    override fun forceCircumstanceEvaluation() = Unit

    override fun needsRefit(): Boolean = false

    override fun getAIFlags(): ShipwideAIFlags = ShipwideAIFlags()

    override fun cancelCurrentManeuver() = Unit

    override fun getConfig(): ShipAIConfig = ShipAIConfig()
}

inline fun <reified T : ShipAIPlugin> installAI(ship: ShipAPI, aiFactory: () -> T) {
    if (((ship.ai as? ShipAIWrapper)?.ai !is T)) {
        ship.shipAI = aiFactory()
    }
}