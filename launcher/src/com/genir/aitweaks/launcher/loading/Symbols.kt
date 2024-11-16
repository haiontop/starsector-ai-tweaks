package com.genir.aitweaks.launcher.loading

import com.fs.starfarer.combat.ai.BasicShipAI
import com.fs.starfarer.combat.ai.attack.AttackAIModule
import com.fs.starfarer.combat.entities.Ship
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
    private val ship: Class<*> = Ship::class.java
    private val basicShipAI: Class<*> = BasicShipAI::class.java
    private val attackAIModule: Class<*> = AttackAIModule::class.java

    // Classes and interfaces.
    val flockingAI: Class<*> = basicShipAI.getMethod("getFlockingAI").returnType
    val approachManeuver: Class<*> = findApproachManeuver()
    val autofireManager: Class<*> = attackAIModule.declaredFields.first { it.type.isInterface && it.type.methods.size == 1 }.type
    val maneuver: Class<*> = basicShipAI.getMethod("getCurrentManeuver").returnType
    val shipCommandWrapper: Class<*> = ship.getMethod("getCommands").genericReturnTypeArgument(0)
    val shipCommand: Class<*> = ship.getMethod("getBlockedCommands").genericReturnTypeArgument(0)
    val threatEvalAI: Class<*> = basicShipAI.getMethod("getThreatEvaluator").returnType
    val combatEntity: Class<*> = ship.getMethod("getEntity").returnType
    val weapon: Class<*> = ship.getMethod("getSelectedWeapon").returnType
    val aimTracker: Class<*> = weapon.getMethod("getAimTracker").returnType

    // Methods and fields.
    val autofireManager_advance: Method = autofireManager.methods.first { it.name != "<init>" }
    val shipCommandWrapper_getCommand: Field = shipCommandWrapper.fields.first { it.type.isEnum }
    val maneuver_getTarget: Method = maneuver.methods.first { it.returnType == combatEntity }
    val aimTracker_setTargetOverride: Method = aimTracker.methods.first { it.returnType == Void.TYPE && it.hasParameters(Vector2f::class.java) }

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
}
