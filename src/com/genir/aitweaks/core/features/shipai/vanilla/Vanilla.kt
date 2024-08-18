package com.genir.aitweaks.core.features.shipai.vanilla

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.combat.ai.BasicShipAI
import org.lazywizard.lazylib.ext.getFacing
import org.lazywizard.lazylib.ext.isZeroVector
import org.lwjgl.util.vector.Vector2f
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/** AI modules carried over from vanilla ship AI. */
class Vanilla(val ship: ShipAPI, overrideVanillaSystem: Boolean) {
    val basicShipAI: BasicShipAI = Global.getSettings().createDefaultShipAI(ship, ShipAIConfig()) as BasicShipAI
    val flags: ShipwideAIFlags = basicShipAI.aiFlags

    private val flockingAI: FlockingAI = FlockingAI(basicShipAI)
    private val threatEvalAI: ThreatEvalAI = ThreatEvalAI(basicShipAI)
    private val ventModule: VentModule = VentModule(basicShipAI)
    private val shieldAI: ShieldAI? = ShieldAI.getIfExists(basicShipAI)
    private val systemAI: SystemAI? = if (overrideVanillaSystem) null else SystemAI.getIfExists(basicShipAI)
    private val fighterPullbackModule: FighterPullbackModule? = FighterPullbackModule.getIfExists(basicShipAI)

    private val avoidMissiles: MethodHandle

    init {
        val methods = basicShipAI::class.java.declaredMethods
        val avoidMissiles = methods.first { it.name == "avoidMissiles" }
        avoidMissiles.setAccessible(true)
        this.avoidMissiles = MethodHandles.lookup().unreflect(avoidMissiles)
    }

    fun advance(dt: Float, attackTarget: ShipAPI?, expectedVelocity: Vector2f, expectedFacing: Float) {
        flags.advance(dt)
        threatEvalAI.advance(dt)

        // Vanilla ship systems read maneuvers planned by ship AI through the flockingAI.
        flockingAI.setDesiredHeading(if (expectedVelocity.isZeroVector()) Float.MAX_VALUE else expectedVelocity.getFacing())
        flockingAI.setDesiredSpeed(expectedVelocity.length())
        flockingAI.setDesiredFacing(expectedFacing)

        avoidMissiles.invoke(basicShipAI)
        flockingAI.advanceCollisionAnalysisModule(dt)

        val missileDangerDir: Vector2f? = flockingAI.getMissileDangerDir()
        val collisionDangerDir: Vector2f? = null // TODO maybe implement?

        ventModule.advance(dt, attackTarget)
        fighterPullbackModule?.advance(dt, attackTarget)
        shieldAI?.advance(dt, threatEvalAI, missileDangerDir, collisionDangerDir, attackTarget)
        systemAI?.advance(dt, missileDangerDir, collisionDangerDir, attackTarget)
    }
}