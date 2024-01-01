package com.genir.aitweaks.features.autofire

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.genir.aitweaks.utils.*
import com.genir.aitweaks.utils.extensions.absoluteArcFacing
import com.genir.aitweaks.utils.extensions.aimLocation
import com.genir.aitweaks.utils.extensions.radius
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

/**
 * All functions in this file take into account target velocity
 * when calculating hit location. Target acceleration is ignored.
 *
 * All functions in this file, except willHitCautious(), assume
 * a perfectly accurate weapon, shooting projectiles with zero
 * collision radius.
 */

const val cos90 = 0f
const val cos180 = -1f

/** Does the weapon have sufficient range and can rotate in its slot to aim at the target. */
fun canTrack(weapon: WeaponAPI, target: CombatEntityAPI): Boolean {
    val coords = targetCoords(weapon, target)
    val closestDistance = solve(coords, target.radius, 1f, cos180)

    return closestDistance <= weapon.range && arcsOverlap(
        Arc(weapon.arc, weapon.absoluteArcFacing), interceptArc(coords, target.radius)
    )
}

/** Weapon aim offset from the target position, required to hit centerpoint of a moving target.
 * Vector2f(NaN,NaN) if the target is faster than the projectile. */
fun interceptOffset(weapon: WeaponAPI, target: CombatEntityAPI): Vector2f =
    targetCoords(weapon, target).let { it.second * solve(it, 0f, 1f, 0f) }

/** Closest possible range at which the projectile can collide with the target circumference,
 * for any weapon facing. Nan if the target is faster than the projectile. */
fun closestHitRange(weapon: WeaponAPI, target: CombatEntityAPI): Float =
    solve(targetCoords(weapon, target), target.radius, 1f, cos180)

/** Range at which the projectile will collide with the target circumference,
 * given current weapon facing. NaN if no collision. */
fun hitRange(weapon: WeaponAPI, target: CombatEntityAPI): Float =
    solve(projectileCoords(weapon, target), target.radius, 0f, 0f)

/** Calculates if the projectile will collide with the target circumference,
 * given current weapon facing. Weapon range is ignored. */
fun willHit(weapon: WeaponAPI, target: CombatEntityAPI) = hitRange(weapon, target) > 0f

/** Calculates if an inaccurate projectile may collide with target circumference,
 * given current weapon facing. Weapon range is ignored. Assumes projectile has
 * 8.0f collision radius, which was determined experimentally in vanilla. */
fun willHitCautious(weapon: WeaponAPI, target: CombatEntityAPI): Boolean = arcsOverlap(
    Arc(weapon.currSpread, weapon.currAngle), interceptArc(targetCoords(weapon, target), target.radius + 8.0f)
)

/** Calculates if a perfectly accurate projectile will collide with target bounds,
 * given current weapon facing. Collision range is returned, null if no collision.*/
fun willHitBounds(weapon: WeaponAPI, target: CombatEntityAPI): Float? =
    willHitBounds(projectileCoords(weapon, target), target)

/** Custom implementation is roughly 5 times faster than CollisionUtils.getCollisionPoint */
private fun willHitBounds(coords: Pair<Vector2f, Vector2f>, target: CombatEntityAPI): Float? {
    val bounds = target.exactBounds ?: return null

    val cos: Float = cos(-target.facing)
    val sin: Float = sin(-target.facing)

    // Rotate weapon coordinates into target frame of reference.
    // That way the target bounds don't need to be transformed.
    // Rotation is implemented in place, as opposed to using library
    // call, for better execution time.
    fun rotate(v: Vector2f, sinA: Float, cosA: Float) = Vector2f(
        v.x * cosA - v.y * sinA,
        v.x * sinA + v.y * cosA,
    )

    val q1 = rotate(coords.first, sin, cos)
    val vr = rotate(coords.second, sin, cos)

    // Scale the velocity vector to ensure collision point falls on the vector.
    val scale = 10e5f
    val q2 = q1 + vr * scale

    return bounds.origSegments.fold(null, fun(closest: Float?, segment): Float? {
        // Both sides of the following equation set represent the collision point:
        // p1 + k(p2-p1) = q1 + t(q2-q1)
        // Solve if for k and t.
        val p1 = segment.p1
        val p2 = segment.p2

        val dp = p2 - p1
        val dq = q2 - q1

        val d = dp.x * dq.y - dp.y * dq.x

        val pqy = p1.y - q1.y
        val qpx = q1.x - p1.x

        val k = (pqy * dq.x + qpx * dq.y) / d
        if (k < 0f || k > 1f) return closest // no collision

        val t = scale * (pqy * dp.x + qpx * dp.y) / d
        return if (t > 0 && (closest == null || t < closest)) t
        else closest
    })
}

/** Target location and velocity in projectile frame of reference. */
private fun targetCoords(weapon: WeaponAPI, target: CombatEntityAPI) = Pair(
    target.aimLocation - weapon.location,
    (target.velocity - weapon.ship.velocity) / weapon.projectileSpeed,
)

/** Projectile location and velocity in target frame of reference. */
fun projectileCoords(weapon: WeaponAPI, target: CombatEntityAPI) = Pair(
    weapon.location - target.aimLocation,
    unitVector(weapon.currAngle) + (weapon.ship.velocity - target.velocity) / weapon.projectileSpeed,
)

private fun interceptArc(pv: Pair<Vector2f, Vector2f>, radius: Float): Arc {
    val tangentDistance = solve(pv, radius, 1f, cos90)

    // Target is directly over the weapon.
    if (tangentDistance.isNaN()) Arc(360f, 0f)

    val (p, v) = pv
    return Arc(
        arc = atan(radius / tangentDistance) * 2f,
        facing = VectorUtils.getFacing(p + v * tangentDistance),
    )
}

/**
 * Solve the following cosine law equation for t:
 *
 * a(t)^2 = b(t)^2 + r^2 - 2*b(t)*r*cosA
 *
 * where
 *
 * a(t) = |P + V * t|
 * b(t) = w * t
 *
 * The smaller positive solutions is returned.
 * If no positive solution exists, NaN is returned.
 *
 * Equation can be expanded the following way:
 * (|P + V * t|)^2 = (w * t)^2 + r^2 - 2(w * t * r * cosA)
 * (Px + Vx * t)^2 + (Py + Vy * t)^2 = = w^2 * t^2 + r^2 - 2(w * t * r * cosA)
 * (Vx^2 + Vy^2 - w^2)*t^2 + 2(Px*Vx + Py*Vy + r*w*cosA)*t + (Px^2 + Py^2 - r^2) = 0
 */
private fun solve(pv: Pair<Vector2f, Vector2f>, r: Float, w: Float, cosA: Float): Float {
    val (p, v) = pv
    val a = v.lengthSquared() - w * w
    val b = 2f * (p.x * v.x + p.y * v.y + r * w * cosA)
    val c = p.lengthSquared() - r * r

    val (t1, t2) = quad(a, b, c)
    return when {
        t1 > 0 && t1 < t2 -> t1
        t2 > 0 -> t2
        else -> Float.NaN
    }
}

/**
 * solve quadratic equation [ax^2 + bx + c = 0] for x.
 */
private fun quad(a: Float, b: Float, c: Float): Pair<Float, Float> {
    val d = b * b - 4f * a * c
    return when {
        d < 0 -> Pair(Float.NaN, Float.NaN)
        a == 0f -> (2 * c / -b).let { Pair(it, it) }
        else -> sqrt(d).let { Pair((-b + it) / (2 * a), (-b - it) / (2 * a)) }
    }
}
