package com.genir.aitweaks

import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.ShipAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType.MISSILE
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.genir.aitweaks.features.autofire.AutofireAI
import com.genir.aitweaks.features.shipai.newAssemblyAI
import com.genir.aitweaks.features.shipai.shouldHaveAssemblyAI

val autofireBlacklist = setOf(
    "fragbomb", // Stinger-class Proximity Mine is classified as a ballistic weapon, but works more like missile.
)

class AITweaksBaseModPlugin : MakeAITweaksRemovable() {
    override fun pickWeaponAutofireAI(weapon: WeaponAPI): PluginPick<AutofireAIPlugin> {
        val ai = if (weapon.type != MISSILE && !autofireBlacklist.contains(weapon.id)) AutofireAI(weapon)
        else null

        return PluginPick(ai, PickPriority.MOD_GENERAL)
    }

    override fun pickShipAI(member: FleetMemberAPI?, ship: ShipAPI): PluginPick<ShipAIPlugin> {
        val ai = if (shouldHaveAssemblyAI(ship)) newAssemblyAI(ship)
        else null

        return PluginPick(ai, PickPriority.MOD_GENERAL)
    }
}

