package com.genir.aitweaks.utils

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.FastTrig
import org.lazywizard.lazylib.MathUtils.getShortestRotation
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.getFacing
import org.lazywizard.lazylib.ext.minus
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

fun willHitShield(weapon: WeaponAPI, target: ShipAPI?) = when {
    target == null -> false
    target.shield == null -> false
    target.shield.isOff -> false
    else -> willHitActiveShieldArc(weapon, target.shield)
}

fun willHitActiveShieldArc(weapon: WeaponAPI, shield: ShieldAPI): Boolean {
    val tgtFacing = (weapon.location - shield.location).getFacing()
    val attackAngle = getShortestRotation(tgtFacing, shield.facing)
    return abs(attackAngle) < (shield.activeArc / 2)
}

internal infix operator fun Vector2f.times(d: Float): Vector2f = Vector2f(x * d, y * d)
internal infix operator fun Vector2f.div(d: Float): Vector2f = Vector2f(x / d, y / d)

fun rotateAroundPivot(toRotate: Vector2f, pivot: Vector2f, angle: Float): Vector2f =
    VectorUtils.rotateAroundPivot(toRotate, pivot, angle, Vector2f())

fun rotate(toRotate: Vector2f, angle: Float): Vector2f = VectorUtils.rotate(toRotate, angle, Vector2f())

fun unitVector(angle: Float): Vector2f = VectorUtils.rotate(Vector2f(1f, 0f), angle)

fun atan(radians: Float): Float = Math.toDegrees(FastTrig.atan(radians.toDouble())).toFloat()

fun arcsOverlap(facing0: Float, arc0: Float, facing1: Float, arc1: Float): Boolean =
    abs(getShortestRotation(facing0, facing1)) <= (arc0 + arc1) / 2f

//fun shipsWithinRange(location: Vector2f, range: Float): Iterator<Any> {
//    val searchRange = range * 2.0f + 50.0f // Magic numbers based on vanilla autofire AI.
//    val grid = Global.getCombatEngine().shipGrid
//    return grid.getCheckIterator(location, searchRange, searchRange)
//}

fun closestShipFilter(location: Vector2f, range: Float, filter: (ShipAPI) -> Boolean): ShipAPI? {
    var closestShip: ShipAPI? = null
    var closestRange = Float.MAX_VALUE

    val evaluateShip = fun(ship: ShipAPI) {
        val currentRange = (location - ship.location).lengthSquared()
        if (currentRange < closestRange && filter(ship)) {
            closestShip = ship
            closestRange = currentRange
        }
    }

    val searchRange = range * 2.0f + 50.0f // Magic numbers based on vanilla autofire AI.
    val grid = Global.getCombatEngine().shipGrid
    val shipIterator = grid.getCheckIterator(location, searchRange, searchRange)
    shipIterator.forEach { evaluateShip(it as ShipAPI) }

    return closestShip
}