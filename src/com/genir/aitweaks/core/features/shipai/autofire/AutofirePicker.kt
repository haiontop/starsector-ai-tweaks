package com.genir.aitweaks.core.features.shipai.autofire

import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.MissileSpecAPI

class AutofirePicker {
    private val autofireBlacklist = setOf(
        "cryoflux", // Cryoflamer has a custom script that makes the projectiles incompatible with ballistic calculations.
    )

    // List of weapons that are most effective when very trigger-happy.
    private val recklessWeapons = setOf(
        "TADA_plasma",
        "TS_plasma",
        "riftbeam"
    )

    fun pickWeaponAutofireAI(weapon: WeaponAPI): PluginPick<AutofireAIPlugin> {
        val ai = when {
            weapon.type == WeaponAPI.WeaponType.MISSILE -> null

            // Missile weapons pretending to be ballistics.
            weapon.spec.projectileSpec is MissileSpecAPI -> null

            autofireBlacklist.contains(weapon.id) -> null

            recklessWeapons.contains(weapon.id) -> RecklessAutofireAI(weapon)

            else -> AutofireAI(weapon)
        }

        return PluginPick(ai, CampaignPlugin.PickPriority.MOD_GENERAL)
    }
}
