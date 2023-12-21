package com.genir.aitweaks.features.autofire

import com.fs.starfarer.api.combat.*
import com.genir.aitweaks.debugValue
import com.genir.aitweaks.utils.extensions.maneuverTarget
import com.genir.aitweaks.utils.extensions.targetEntity
import com.genir.aitweaks.utils.*
import com.genir.aitweaks.utils.extensions.isValidTarget
import org.lwjgl.util.vector.Vector2f

var count = 0

fun applyTurretAI(ship: ShipAPI) {
    ship.weaponGroupsCopy.forEach { group ->
        val plugins = group.aiPlugins
        for (i in plugins.indices) {
            val weapon = plugins[i].weapon
            if (weapon.slot.isTurret && weapon.type != WeaponAPI.WeaponType.MISSILE && weapon.ship.owner == 0) {
                plugins[i] = TurretAI(plugins[i])
                count++
                debugValue = count
            }
        }
    }
}

// TODO
// accuracy
// fire on shields
// ff
// target selection

class TurretAI(private val basePlugin: AutofireAIPlugin) : AutofireAIPlugin {
    private val maneuverTargetTracker = ManeuverTargetTracker(basePlugin.weapon.ship)
    private var solution: FiringSolution? = null

    override fun advance(timeDelta: Float) {
        basePlugin.advance(timeDelta)
        maneuverTargetTracker.advance(timeDelta)

        val maneuverSolution = calculateFiringSolution(weapon, maneuverTargetTracker.target)
        val baseSolution = calculateFiringSolution(weapon, basePlugin.targetEntity)

        solution = when {
            weapon.hasAIHint(WeaponAPI.AIHints.PD) -> baseSolution
//            baseSolution?.target is MissileAPI -> baseSolution
            maneuverSolution?.canTrack == true -> maneuverSolution
            baseSolution?.canTrack == true -> baseSolution
            maneuverSolution != null -> maneuverSolution
            else -> null
        }
    }

    override fun getTarget(): Vector2f? = solution?.intercept
    override fun shouldFire(): Boolean = basePlugin.shouldFire() && solution?.willHit == true
    override fun forceOff() = basePlugin.forceOff()
    override fun getTargetShip(): ShipAPI? = solution?.target as? ShipAPI
    override fun getWeapon(): WeaponAPI = basePlugin.weapon
    override fun getTargetMissile(): MissileAPI? = solution?.target as? MissileAPI
}


class ManeuverTargetTracker(private val ship: ShipAPI) {
    var target: ShipAPI? = null

    fun advance(p0: Float) {
        when (val newTarget = ship.maneuverTarget) {
            target -> return
            null -> if (!target.isValidTarget) target = null
            else -> target = newTarget
        }
    }

}