package com.genir.aitweaks.launcher.loading

import com.fs.starfarer.api.combat.EveryFrameCombatPlugin
import com.fs.starfarer.campaign.ui.fleet.FleetMemberView
import com.fs.starfarer.combat.CombatEngine
import com.fs.starfarer.combat.ai.BasicShipAI
import com.fs.starfarer.combat.ai.attack.AttackAIModule
import com.fs.starfarer.combat.entities.Ship
import com.fs.starfarer.title.TitleScreenState
import com.genir.aitweaks.launcher.loading.Bytecode.classPath
import org.lwjgl.util.vector.Vector2f
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

@Suppress("PropertyName")
class Symbols {
    // Classes with un-obfuscated names.
    val ship: Class<*> = Ship::class.java
    val basicShipAI: Class<*> = BasicShipAI::class.java
    val attackAIModule: Class<*> = AttackAIModule::class.java
    val fleetMemberView: Class<*> = FleetMemberView::class.java
    val combatEngine: Class<*> = CombatEngine::class.java
    val titleScreenState: Class<*> = TitleScreenState::class.java

    // Classes and interfaces.
    val flockingAI: Class<*> = basicShipAI.getMethod("getFlockingAI").returnType
    val approachManeuver: Class<*> = findApproachManeuver()
    val autofireManager: Class<*> = attackAIModule.declaredFields.first { it.type.isInterface && it.type.methods.size == 1 }.type
    val maneuver: Class<*> = basicShipAI.getMethod("getCurrentManeuver").returnType
    val shipCommandWrapper: Class<*> = ship.getMethod("getCommands").genericReturnTypeArgument(0)
    val shipCommand: Class<*> = ship.getMethod("getBlockedCommands").genericReturnTypeArgument(0)
    val combatEntity: Class<*> = ship.getMethod("getEntity").returnType
    val weapon: Class<*> = ship.getMethod("getSelectedWeapon").returnType
    val aimTracker: Class<*> = weapon.getMethod("getAimTracker").returnType
    val button: Class<*> = fleetMemberView.getMethod("getButton").returnType
    val playerAction: Class<*> = button.getMethod("getShortcut").returnType
    val keymap: Class<*> = playerAction.enclosingClass
    val fighterPullbackModule: Class<*> = basicShipAI.getDeclaredField("fighterPullbackModule").type
    val systemAI: Class<*> = basicShipAI.getDeclaredField("systemAI").type
    val shieldAI: Class<*> = basicShipAI.getMethod("getShieldAI").returnType
    val ventModule: Class<*> = basicShipAI.getDeclaredField("ventModule").type
    val threatEvaluator: Class<*> = basicShipAI.getMethod("getThreatEvaluator").returnType
    val threatResponseManeuver: Class<*> = threatEvaluator.methods.first { it.hasParameters(Float::class.java) }.returnType
    val combatMap: Class<*> = combatEngine.getMethod("getCombatMap").returnType
    val missionDefinition: Class<*> = titleScreenState.getDeclaredField("nextMission").type
    val missionDefinitionPluginContainer: Class<*> = missionDefinition.classes.first()

    // Methods and fields.
    val autofireManager_advance: Method = autofireManager.methods.first { it.name != "<init>" }
    val shipCommandWrapper_getCommand: Field = shipCommandWrapper.fields.first { it.type.isEnum }
    val maneuver_getTarget: Method = maneuver.methods.first { it.returnType == combatEntity }
    val aimTracker_setTargetOverride: Method = aimTracker.methods.first { it.returnType == Void.TYPE && it.hasParameters(Vector2f::class.java) }
    val keymap_isKeyDown: Method = findKeymapIsKeyDown()
    val attackAIModule_advance: Method = attackAIModule.methods.first { it.hasParameters(Float::class.java, threatEvaluator, Vector2f::class.java) }
    val fighterPullbackModule_advance: Method = fighterPullbackModule.methods.first { it.hasParameters(Float::class.java, Ship::class.java) }
    val systemAI_advance: Method = systemAI.methods.first { it.hasParameters(Float::class.java, Vector2f::class.java, Vector2f::class.java, Ship::class.java) }
    val shieldAI_advance: Method = shieldAI.methods.first { it.parameterTypes.firstOrNull() == Float::class.java }
    val ventModule_advance: Method = ventModule.methods.first { it.hasParameters(Float::class.java, Ship::class.java) }
    val threatEvaluator_advance: Method = threatEvaluator.methods.first { it.hasParameters(Float::class.java) }
    val flockingAI_setDesiredHeading: Method = flockingAI.methods.first { it.name == flockingAISetterNames(1) && it.hasParameters(Float::class.java) }
    val flockingAI_setDesiredFacing: Method = flockingAI.methods.first { it.name == flockingAISetterNames(2) && it.hasParameters(Float::class.java) }
    val flockingAI_setDesiredSpeed: Method = flockingAI.methods.first { it.name == flockingAISetterNames(4) && it.hasParameters(Float::class.java) }
    val flockingAI_advanceCollisionAnalysisModule: Method = flockingAI.methods.first { it.name == flockingAISetterNames(3) && it.hasParameters(Float::class.java) }
    val flockingAI_getMissileDangerDir: Method = flockingAI.methods.first { it.name == Bytecode.getMethodsInOrder(flockingAI).first { it.desc == "()Lorg/lwjgl/util/vector/Vector2f;" }.name && it.returnType == Vector2f::class.java && it.hasParameters() }
    val combatMap_getPluginContainers: Method = combatMap.methods.first { it.hasParameters() && it.returnType == List::class.java }
    val missionDefinitionPluginContainer_getEveryFrameCombatPlugin: Method = missionDefinitionPluginContainer.methods.first { it.returnType == EveryFrameCombatPlugin::class.java }

    private fun Method.genericReturnTypeArgument(idx: Int): Class<*> {
        return (genericReturnType as ParameterizedType).actualTypeArguments[idx] as Class<*>
    }

    private fun Method.hasParameters(vararg params: Class<*>): Boolean {
        return parameterTypes.contentEquals(arrayOf(*params))
    }

    private fun findApproachManeuver(): Class<*> {
        val aiReader = ClassReader(Bytecode.readClassBuffer(this::class.java.classLoader, basicShipAI.classPath))
        val maneuvers = mutableListOf<String>()

        // Find all maneuver classes used by ship AI.
        aiReader.accept(object : ClassVisitor(Opcodes.ASM4) {
            override fun visitMethod(access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                return object : MethodVisitor(Opcodes.ASM4) {
                    override fun visitTypeInsn(opcode: Int, type: String?) {
                        if (type?.startsWith("com/fs/starfarer/combat/ai/movement/maneuvers/") == true) {
                            maneuvers.add(type)
                        }
                    }
                }
            }
        }, 0)

        // Gather all possible candidate classes for approach maneuver.
        val candidates = mutableSetOf<String>()
        maneuvers.forEach { className ->
            val reader = ClassReader(Bytecode.readClassBuffer(this::class.java.classLoader, className))

            reader.accept(object : ClassVisitor(Opcodes.ASM4) {
                override fun visitMethod(access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (name == "<init>" && desc?.startsWith("(L${ship.classPath};L${ship.classPath};FL${flockingAI.classPath};") == true) {
                        candidates.add(className)
                    }

                    return null
                }
            }, 0)
        }

        // Identify approach maneuver by number of methods.
        // The expected maneuver has the higher number of methods
        val candidateClasses: List<Class<*>> = candidates.map { this::class.java.classLoader.loadClass(it.replace("/", ".")) }
        return candidateClasses.maxWithOrNull { a, b -> a.declaredMethods.size - b.declaredMethods.size }!!
    }

    private fun findKeymapIsKeyDown(): Method {
        val isKeyDownName = Bytecode.getMethodsInOrder(keymap).first { it.desc == "(L${playerAction.classPath};)Z" }
        return keymap.methods.first { it.name == isKeyDownName.name && it.parameterTypes.contentEquals(arrayOf(playerAction)) }
    }

    private fun flockingAISetterNames(idx: Int): String {
        val bytecodeMethods: List<Bytecode.Method> = Bytecode.getMethodsInOrder(flockingAI)
        return bytecodeMethods.filter { it.desc == "(F)V" }[idx].name
    }
}
