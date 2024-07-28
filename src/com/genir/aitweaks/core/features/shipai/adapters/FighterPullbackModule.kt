package com.genir.aitweaks.core.features.shipai.adapters

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.combat.ai.BasicShipAI
import com.fs.starfarer.combat.entities.Ship
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Field

class FighterPullbackModule(private val fighterPullbackModule: Any) {
    private val advance: MethodHandle

    init {
        val methods = fighterPullbackModule::class.java.methods
        val advanceParams = arrayOf(Float::class.java, Ship::class.java)
        val advance = methods.first { it.parameterTypes.contentEquals(advanceParams) }
        this.advance = MethodHandles.lookup().unreflect(advance)
    }

    fun advance(dt: Float, attackTarget: ShipAPI?) {
        advance.invoke(fighterPullbackModule, dt, attackTarget)
    }

    companion object {
        fun getIfExists(vanillaAI: BasicShipAI): FighterPullbackModule? {
            val field: Field = BasicShipAI::class.java.getDeclaredField("fighterPullbackModule").also { it.setAccessible(true) }
            val fighterPullbackModule: Any? = field.get(vanillaAI)

            return fighterPullbackModule?.let { FighterPullbackModule(it) }
        }
    }
}