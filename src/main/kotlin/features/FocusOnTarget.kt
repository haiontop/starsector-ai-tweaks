package com.genir.aitweaks.features

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.genir.aitweaks.extensions.firesForward
import com.genir.aitweaks.extensions.maneuverTarget
import org.lwjgl.util.vector.Vector2f

fun applyFocusOnTargetAI(ship: ShipAPI) {
    ship.weaponGroupsCopy.forEach { group ->
        val plugins = group.aiPlugins
        for (i in plugins.indices) {
            if (plugins[i].weapon.slot.isHardpoint && plugins[i].weapon.firesForward) {
                plugins[i] = FocusOnTargetAI(plugins[i])
            }
        }
    }
}

class FocusOnTargetAI(private val basePlugin: AutofireAIPlugin) : AutofireAIPlugin {
    private var target: ShipAPI? = null

    override fun advance(p0: Float) {
        basePlugin.advance(p0)

        when (val newTarget = basePlugin.weapon.ship.maneuverTarget) {
            target -> return
            null -> if (!isValidTarget(target)) target = null
            else -> target = newTarget
        }
    }

    override fun getTarget(): Vector2f? = if (target == null) basePlugin.target else target?.location
    override fun getTargetShip(): ShipAPI? = if (target == null) basePlugin.targetShip else target
    override fun getTargetMissile(): MissileAPI? = if (target == null) basePlugin.targetMissile else null

    override fun shouldFire(): Boolean = basePlugin.shouldFire()
    override fun forceOff() = basePlugin.forceOff()
    override fun getWeapon(): WeaponAPI = basePlugin.weapon

    private fun isValidTarget(ship: ShipAPI?) =
        ship != null && Global.getCombatEngine().isEntityInPlay(ship) && ship.isAlive
}


