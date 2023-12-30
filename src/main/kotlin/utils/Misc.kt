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

fun shieldUptime(shield: ShieldAPI): Float {
    val r = shield.activeArc / shield.arc
    return if (r >= 1f) Float.MAX_VALUE
    else r * shield.unfoldTime
}

internal infix operator fun Vector2f.times(d: Float): Vector2f = Vector2f(x * d, y * d)
internal infix operator fun Vector2f.div(d: Float): Vector2f = Vector2f(x / d, y / d)

fun rotateAroundPivot(toRotate: Vector2f, pivot: Vector2f, angle: Float): Vector2f =
    VectorUtils.rotateAroundPivot(toRotate, pivot, angle, Vector2f())

fun rotate(toRotate: Vector2f, angle: Float): Vector2f = VectorUtils.rotate(toRotate, angle, Vector2f())

fun unitVector(angle: Float): Vector2f = VectorUtils.rotate(Vector2f(1f, 0f), angle)

fun atan(radians: Float): Float = Math.toDegrees(FastTrig.atan(radians.toDouble())).toFloat()

class Arc(val arc: Float, val facing: Float)

fun arcsOverlap(a: Arc, b: Arc): Boolean = abs(getShortestRotation(a.facing, b.facing)) <= (a.arc + b.arc) / 2f

class Log

fun log(message: Any) = Global.getLogger(Log().javaClass).info(message)
