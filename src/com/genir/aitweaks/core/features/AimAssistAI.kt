package com.genir.aitweaks.core.features

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.loading.WeaponGroupType
import com.fs.starfarer.campaign.CampaignEngine
import com.genir.aitweaks.core.Obfuscated
import com.genir.aitweaks.core.debug.debugPrint
import com.genir.aitweaks.core.features.shipai.BaseShipAIPlugin
import com.genir.aitweaks.core.features.shipai.BasicEngineController
import com.genir.aitweaks.core.features.shipai.WeaponGroup
import com.genir.aitweaks.core.features.shipai.autofire.BallisticTarget
import com.genir.aitweaks.core.features.shipai.autofire.defaultBallisticParams
import com.genir.aitweaks.core.features.shipai.autofire.intercept
import com.genir.aitweaks.core.utils.*
import com.genir.aitweaks.core.utils.extensions.*
import org.lazywizard.lazylib.CollisionUtils
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f

// TODO missiles miss
// TODO 0.96
// TODO setting to disable hardpoint aiming
// TODO test turrets

class AimAssistAI : BaseShipAIPlugin() {
    private val keymap = VanillaKeymap()

    private var prevPlayerShip: ShipAPI? = null
    private var engineController: BasicEngineController? = null

    private var isFiring: Boolean = false
    private var isStrafeMode: Boolean = false

    override fun advance(dt: Float) {
        // Decide if aim assist should run.
        val engine = Global.getCombatEngine()
        val ship = engine.playerShip
        when {
            engine.isPaused -> return

            ship == null -> return
            !ship.isAlive -> return
            !ship.isUnderManualControl -> return

            // Is aim assist enabled.
            !campaignMemory.getBoolean("\$aitweaks_enableAimBot") -> return
        }

        // Read player input.
        isFiring = keymap.isKeyDown(VanillaKeymap.Action.SHIP_FIRE)
        isStrafeMode = keymap.isKeyDown(VanillaKeymap.Action.SHIP_STRAFE_KEY)

        // Select target.
        val target: CombatEntityAPI = selectTarget() ?: return
        val ballisticTarget = BallisticTarget(target.timeAdjustedVelocity, mousePosition(), 0f)

        aimShip(dt, ship, ballisticTarget)
        aimWeapons(ship, ballisticTarget)
    }

    private val campaignMemory: MemoryAPI
        get() = CampaignEngine.getInstance().memoryWithoutUpdate


    private fun aimShip(dt: Float, ship: ShipAPI, ballisticTarget: BallisticTarget) {
        if (!isStrafeMode) return

        val selectedWeapons = ship.selectedWeapons.ifEmpty { return }

        // Update engine controller if player ship changed.
        if (ship != prevPlayerShip) {
            prevPlayerShip = ship
            engineController = BasicEngineController(ship)
        }

        // Remove vanilla turn commands.
        clearVanillaCommands(ship, "TURN_LEFT", "TURN_RIGHT")

        // Control the ship rotation.
        val weaponGroup = WeaponGroup(ship, selectedWeapons.toList())
        val expectedFacing = weaponGroup.attackFacing(ballisticTarget)
        engineController!!.facing(dt, expectedFacing)
    }

    private fun aimWeapons(ship: ShipAPI, ballisticTarget: BallisticTarget) {
        val selectedWeapons: Set<WeaponAPI> = ship.selectedWeapons
        val manualWeapons: List<WeaponAPI> = ship.manualWeapons

        manualWeapons.forEach { weapon ->
            when {
                // Vanilla hardpoints obey player fire command regardless of arc,
                // so there's no need to override their fire command.
                weapon.slot.isHardpoint -> {
                    // It's not possible to aim missile hardpoints.
                    if (!weapon.isUnguidedMissile) {
                        aimWeapon(weapon, ballisticTarget)
                    }
                }

                // Override aim for all non-autofire turrets.
                weapon.slot.isTurret -> {
                    val intercept: Vector2f = aimWeapon(weapon, ballisticTarget)
                    // Override fire command for manually operated turrets.
                    if (selectedWeapons.contains(weapon)) {
                        fireWeapon(weapon, intercept)
                    }
                }
            }
        }
    }

    private fun aimWeapon(weapon: WeaponAPI, ballisticTarget: BallisticTarget): Vector2f {
        val intercept: Vector2f = intercept(weapon, ballisticTarget, defaultBallisticParams)

        // Override vanilla-computed weapon facing.
        val aimTracker: Obfuscated.AimTracker = (weapon as Obfuscated.Weapon).aimTracker
        aimTracker.aimTracker_setTargetOverride(intercept + weapon.location)

        return intercept
    }

    private fun fireWeapon(weapon: WeaponAPI, intercept: Vector2f) {
        val interceptFacing = intercept.facing - weapon.ship.facing
        val group: WeaponGroupAPI = weapon.group ?: return
        val isFiring = keymap.isKeyDown(VanillaKeymap.Action.SHIP_FIRE)

        debugPrint[weapon] = weapon.spec.weaponId

        val shouldFire: Boolean = when {
            !isFiring -> false

            // Fire active alternating group weapon. Same behavior as vanilla.
            group.type == WeaponGroupType.ALTERNATING && weapon == group.activeWeapon -> true

            // Fire linked weapons if target is in arc, regardless if weapon
            // is actually pointed at the target. Same behavior as vanilla.
            group.type == WeaponGroupType.LINKED && weapon.isAngleInArc(interceptFacing) -> true

            else -> false
        }

        // Override the vanilla-computed should-fire decision.
        if (shouldFire) weapon.setForceFireOneFrame(true)
        else weapon.setForceNoFireOneFrame(true)
    }

    private fun selectTarget(): CombatEntityAPI? {
        val searchRadius = 500f

        val ships: Sequence<ShipAPI> = shipGrid().get<ShipAPI>(mousePosition(), searchRadius).filter {
            when {
                !it.isValidTarget -> false
                it.owner == 0 -> false
                it.isFighter -> false

                else -> true
            }
        }

        closestTarget(ships)?.let { return it }

        val hulks = shipGrid().get<ShipAPI>(mousePosition(), searchRadius).filter {
            when {
                it.isExpired -> false
                it.owner != 100 -> false
                it.isFighter -> false

                else -> true
            }
        }

        closestTarget(hulks)?.let { return it }

        val asteroids: Sequence<CombatAsteroidAPI> = asteroidGrid().get<CombatAsteroidAPI>(mousePosition(), searchRadius).filter {
            when {
                it.isExpired -> false

                else -> true
            }
        }

        return closestTarget(asteroids)
    }

    private fun closestTarget(entities: Sequence<CombatEntityAPI>): CombatEntityAPI? {
        val targetEnvelope = 150f
        val closeEntities: Sequence<CombatEntityAPI> = entities.filter {
            (it.location - mousePosition()).length <= (it.collisionRadius + targetEnvelope)
        }

        var closestEntity: CombatEntityAPI? = null
        var closestDist = Float.MAX_VALUE

        closeEntities.forEach {
            // If mouse is over ship bounds, consider it the target.
            if (CollisionUtils.isPointWithinBounds(mousePosition(), it)) return it

            val dist = (it.location - mousePosition()).lengthSquared
            if (dist < closestDist) {
                closestDist = dist
                closestEntity = it
            }
        }

        return closestEntity
    }

    private val WeaponAPI.shouldAim: Boolean
        get() = type != WeaponAPI.WeaponType.MISSILE || isUnguidedMissile

    private val ShipAPI.manualWeapons: List<WeaponAPI>
        get() = weaponGroupsCopy.filter { !it.isAutofiring }.flatMap { it.weaponsCopy }.filter { it.shouldAim }

    private val ShipAPI.selectedWeapons: Set<WeaponAPI>
        get() = selectedGroupAPI?.weaponsCopy?.filter { it.shouldAim }?.toSet() ?: setOf()
}