package com.genir.aitweaks.core.features

import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.combat.systems.thissuper
import com.genir.aitweaks.core.features.shipai.CustomShipAI
import com.genir.aitweaks.core.features.shipai.autofire.BallisticTarget
import com.genir.aitweaks.core.features.shipai.autofire.SimulateMissile
import com.genir.aitweaks.core.features.shipai.autofire.defaultBallisticParams
import com.genir.aitweaks.core.features.shipai.autofire.intercept
import com.genir.aitweaks.core.utils.asteroidGrid
import com.genir.aitweaks.core.utils.extensions.*
import com.genir.aitweaks.core.utils.mousePosition
import com.genir.aitweaks.core.utils.shipGrid
import org.lazywizard.lazylib.CollisionUtils
import org.lazywizard.lazylib.ext.minus
import org.lwjgl.util.vector.Vector2f

class AimBot : BaseEveryFrameCombatPlugin() {
    private var isFiring: Boolean = false
    private var shipAI: CustomShipAI? = null
    var target: CombatEntityAPI? = null

    override fun advance(dt: Float, events: MutableList<InputEventAPI>?) {
        if (Global.getCurrentState() != GameState.COMBAT) return

        val ship: ShipAPI = Global.getCombatEngine().playerShip ?: return

        // Handle input.
        events?.forEach {
            when {
                it.isConsumed -> Unit

                it.isLMBDownEvent -> isFiring = true

                it.isLMBUpEvent -> isFiring = false
            }
        }

        // TODO remove
        when (ship.isUnderManualControl) {
            true -> {
                val ai = shipAI ?: CustomShipAI(ship)
                ai.advance(dt)
                shipAI = ai
            }

            false -> {
                shipAI = null
            }
        }

        val mouse: Vector2f = mousePosition()
        val target: CombatEntityAPI = selectTarget(mouse) ?: return
        this.target = target

        // Aim all non-autofire weapons.
        val manualWeapons: List<WeaponAPI> = ship.weaponGroupsCopy.filter { !it.isAutofiring }.flatMap { it.weaponsCopy }
        manualWeapons.forEach { weapon ->
            aimWeapon(weapon, target, mouse)
        }

        // Aim and fire weapons in selected group.
        val selectedWeapons: List<WeaponAPI> = ship.selectedGroupAPI?.weaponsCopy ?: listOf()
        selectedWeapons.forEach { weapon ->
            val intercept: Vector2f = aimWeapon(weapon, target, mouse)
            fireWeapon(weapon, target, intercept)
        }
    }

    private fun aimWeapon(weapon: WeaponAPI, target: CombatEntityAPI, mouse: Vector2f): Vector2f {
        val ballisticTarget = BallisticTarget(target.velocity, mouse, 0f)

        val intercept: Vector2f = when {
            weapon.type == WeaponType.MISSILE -> {
                SimulateMissile.missileIntercept(weapon, ballisticTarget)
            }

            else -> {
                intercept(weapon, ballisticTarget, defaultBallisticParams()) ?: target.location
            }
        }

        val obfWeapon = weapon as thissuper
        obfWeapon.aimTracker.`new`(intercept)

        return intercept
    }

    private fun fireWeapon(weapon: WeaponAPI, target: CombatEntityAPI, intercept: Vector2f) {
        val interceptFacing = (intercept - weapon.location).facing - weapon.ship.facing
        val shouldFire: Boolean = when {
            !isFiring -> false

            // Fire if target is in arc, regardless if weapon is actually
            // pointed at the target. Same behavior as vanilla.
            else -> weapon.isAngleInArc(interceptFacing)
        }

        if (shouldFire) weapon.setForceFireOneFrame(true)
        else weapon.setForceNoFireOneFrame(true)
    }

    private fun selectTarget(mouse: Vector2f): CombatEntityAPI? {
        val searchRadius = 500f

        val ships: Sequence<ShipAPI> = shipGrid().get<ShipAPI>(mouse, searchRadius).filter {
            when {
                !it.isValidTarget -> false
                it.owner == 0 -> false
                it.isFighter -> false

                else -> true
            }
        }

        closestTarget(mouse, ships)?.let { return it }

        val hulks = shipGrid().get<ShipAPI>(mouse, searchRadius).filter {
            when {
                it.isExpired -> false
                it.owner != 100 -> false
                it.isFighter -> false

                else -> true
            }
        }

        closestTarget(mouse, hulks)?.let { return it }

        val asteroids: Sequence<CombatAsteroidAPI> = asteroidGrid().get<CombatAsteroidAPI>(mouse, searchRadius).filter {
            when {
                it.isExpired -> false

                else -> true
            }
        }

        return closestTarget(mouse, asteroids)
    }

    private fun closestTarget(mouse: Vector2f, entities: Sequence<CombatEntityAPI>): CombatEntityAPI? {
        val targetEnvelope = 150f
        val closeEntities: Sequence<CombatEntityAPI> = entities.filter {
            (it.location - mouse).length <= (it.collisionRadius + targetEnvelope)
        }

        var closestEntity: CombatEntityAPI? = null
        var closestDist = Float.MAX_VALUE

        closeEntities.forEach {
            // If mouse is over ship bounds, consider it the target.
            if (CollisionUtils.isPointWithinBounds(mouse, it)) return it

            val dist = (it.location - mouse).lengthSquared
            if (dist < closestDist) {
                closestDist = dist
                closestEntity = it
            }
        }

        return closestEntity
    }
}