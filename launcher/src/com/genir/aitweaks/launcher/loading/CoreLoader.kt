package com.genir.aitweaks.launcher.loading

import com.fs.starfarer.api.Global
import com.genir.aitweaks.launcher.loading.Bytecode.classPath
import java.net.URL
import java.net.URLClassLoader

class CoreLoader(coreURL: URL) : URLClassLoader(arrayOf(coreURL)) {
    private val cache: MutableMap<String, Class<*>> = mutableMapOf()
    private val symbols = Symbols()
    private val core = "com/genir/aitweaks/core/Obfuscated"

    private val obfuscator = Transformer(listOf(
        // Classes.
        Transformer.newTransform("$core\$AimTracker", symbols.aimTracker.classPath),
        Transformer.newTransform("$core\$ApproachManeuver", symbols.approachManeuver.classPath),
        Transformer.newTransform("$core\$AutofireManager", symbols.autofireManager.classPath),
        Transformer.newTransform("$core\$AttackAIModule", symbols.attackAIModule.classPath),
        Transformer.newTransform("$core\$BasicShipAI", symbols.basicShipAI.classPath),
        Transformer.newTransform("$core\$CombatEntity", symbols.combatEntity.classPath),
        Transformer.newTransform("$core\$FighterPullbackModule", symbols.fighterPullbackModule.classPath),
        Transformer.newTransform("$core\$FlockingAI", symbols.flockingAI.classPath),
        Transformer.newTransform("$core\$Keymap", symbols.keymap.classPath),
        Transformer.newTransform("$core\$Maneuver", symbols.maneuver.classPath),
        Transformer.newTransform("$core\$PlayerAction", symbols.playerAction.classPath),
        Transformer.newTransform("$core\$ShipCommandWrapper", symbols.shipCommandWrapper.classPath),
        Transformer.newTransform("$core\$ShipCommand", symbols.shipCommand.classPath),
        Transformer.newTransform("$core\$Ship", symbols.ship.classPath),
        Transformer.newTransform("$core\$ShieldAI", symbols.shieldAI.classPath),
        Transformer.newTransform("$core\$SystemAI", symbols.systemAI.classPath),
        Transformer.newTransform("$core\$ThreatEvaluator", symbols.threatEvaluator.classPath),
        Transformer.newTransform("$core\$ThreatResponseManeuver", symbols.threatResponseManeuver.classPath),
        Transformer.newTransform("$core\$VentModule", symbols.ventModule.classPath),
        Transformer.newTransform("$core\$Weapon", symbols.weapon.classPath),

        // Fields and methods.
        Transformer.newTransform("autofireManager_advance", symbols.autofireManager_advance.name),
        Transformer.newTransform("shipCommandWrapper_getCommand", symbols.shipCommandWrapper_getCommand.name),
        Transformer.newTransform("maneuver_getTarget", symbols.maneuver_getTarget.name),
        Transformer.newTransform("aimTracker_setTargetOverride", symbols.aimTracker_setTargetOverride.name),
        Transformer.newTransform("keymap_isKeyDown", symbols.keymap_isKeyDown.name),
        Transformer.newTransform("attackAIModule_advance", symbols.attackAIModule_advance.name),
        Transformer.newTransform("fighterPullbackModule_advance", symbols.fighterPullbackModule_advance.name),
        Transformer.newTransform("systemAI_advance", symbols.systemAI_advance.name),
        Transformer.newTransform("shieldAI_advance", symbols.shieldAI_advance.name),
        Transformer.newTransform("ventModule_advance", symbols.ventModule_advance.name),
        Transformer.newTransform("threatEvaluator_advance", symbols.threatEvaluator_advance.name),
        Transformer.newTransform("flockingAI_setDesiredHeading", symbols.flockingAI_setDesiredHeading.name),
        Transformer.newTransform("flockingAI_setDesiredFacing", symbols.flockingAI_setDesiredFacing.name),
        Transformer.newTransform("flockingAI_setDesiredSpeed", symbols.flockingAI_setDesiredSpeed.name),
        Transformer.newTransform("flockingAI_advanceCollisionAnalysisModule", symbols.flockingAI_advanceCollisionAnalysisModule.name),
        Transformer.newTransform("flockingAI_getMissileDangerDir", symbols.flockingAI_getMissileDangerDir.name),
    ))

    override fun loadClass(name: String): Class<*> {
        cache[name]?.let { return it }

        var c: Class<*>?

        try {
            // Try to load the class using default Starsector script loader.
            // This ensures that AI Tweaks' core logic uses the same class
            // definitions as the rest of the application, including AI Tweaks
            // launcher. Using the same class definitions is important when
            // sharing state through static fields, as in the case of LunaLib
            // settings.
            c = Global.getSettings().scriptClassLoader.loadClass(name)
        } catch (_: SecurityException) {
            // Load classes restricted by Starsector reflect/IO ban.
            c = ClassLoader.getSystemClassLoader().loadClass(name)
        } catch (_: ClassNotFoundException) {
            // Load and transform AI Tweaks core logic classes.
            val classBuffer = Bytecode.readClassBuffer(this, name)
            val obfuscated = obfuscator.apply(obfuscator.apply(classBuffer))
            c = defineClass(name, obfuscated, 0, obfuscated.size)
        }

        cache[name] = c!!
        return c
    }
}
