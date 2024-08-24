package com.genir.aitweaks.core.features

import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.combat.systems.thissuper
import com.genir.aitweaks.core.features.shipai.autofire.*
import com.genir.aitweaks.core.utils.extensions.facing
import com.genir.aitweaks.core.utils.extensions.isAngleInArc
import com.genir.aitweaks.core.utils.extensions.length
import com.genir.aitweaks.core.utils.mousePosition
import org.lazywizard.lazylib.ext.minus
import org.lwjgl.util.vector.Vector2f

class AimBot : BaseEveryFrameCombatPlugin() {
    private var isFiring: Boolean = false

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


//        debugPrint["0"] = "0"

        if (!Global.getCombatEngine().isUIAutopilotOn) return

        val group: WeaponGroupAPI = ship.selectedGroupAPI ?: return
//        if (group.isAutofiring) return

//        debugPrint["1"] = "1"

        val weapons: List<WeaponAPI> = group.weaponsCopy
        if (weapons.isEmpty()) return
//        if (weapons.any { it.type == WeaponAPI.WeaponType.MISSILE }) return

//        debugPrint["2"] = "2"
        // Assist with aiming only when there's an R-selected target.
//        val target: CombatEntityAPI = ship.shipTarget ?: return

//        debugPrint["3"] = "3"
        // Prevent vanilla from deciding when to fire.
//        ship.blockCommandForOneFrame(ShipCommand.FIRE)
//        debugPrint.clear()

        val mousePosition: Vector2f = mousePosition()
        var closestAsteroid: CombatEntityAPI = Global.getCombatEngine().asteroids.firstOrNull() ?: return
        Global.getCombatEngine().asteroids.forEach {
            if ((it.location - mousePosition).length < (closestAsteroid.location - mousePosition).length) {
                closestAsteroid = it
            }
        }

//        drawCollisionRadius(closestAsteroid)

        weapons.forEach { weapon ->
//            debugPrint[weapon] = weapon.id


//            val intercept: Vector2f = aimWeapon(dt, weapon, target, mousePosition)
            val intercept: Vector2f = aimWeapon(dt, weapon, closestAsteroid, mousePosition)
            fireWeapon(weapon, closestAsteroid, intercept)

//            drawLine(weapon.location, intercept, Color.RED)
        }

//
//
//        val groups = ship.weaponGroupsCopy
//        var groupIdx = -1
//
//        groups.forEachIndexed { idx, it ->
//            if (it == group) groupIdx = idx
//        }
//
//        debugPrint["group"] = "group $groupIdx"
//        debugPrint["is"] = isFiring
//        debugPrint["c"] = commandMethod


//        if (groupIdx != -1)
//            ship.giveCommand(ShipCommand.FIRE, position, groupIdx)


//        ship.selectedGroupAPI?.weaponsCopy?.forEach { weapon ->
//            debugPrint[weapon] = "${weapon.id} ${weapon.location}"
//
//
//
//            drawLine(weapon.location, weapon.location + Vector2f(0f, 100f), RED)
//        }
    }

    private fun aimWeapon(dt: Float, weapon: WeaponAPI, target: CombatEntityAPI, mousePosition: Vector2f): Vector2f {
//        val ballisticTarget = BallisticTarget(target.velocity, mousePosition, 0f)
        val ballisticTarget = BallisticTarget(target.velocity, target.location, 0f)

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
            weapon.isAngleInArc(interceptFacing) -> true

            // Fire at target if hit is predicted, even if intercept point
            // is outside weapon firing arc.
            analyzeHit(weapon, target, defaultBallisticParams()) != null -> true

            else -> false
        }

        if (shouldFire) weapon.setForceFireOneFrame(true)
        else weapon.setForceNoFireOneFrame(true)
    }
}