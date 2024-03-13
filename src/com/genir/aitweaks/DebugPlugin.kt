package com.genir.aitweaks

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipCommand.*
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.combat.entities.Ship
import com.genir.aitweaks.features.autofire.AutofireAI
import com.genir.aitweaks.utils.Controller
import com.genir.aitweaks.utils.times
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ui.LazyFont
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.magiclib.subsystems.drones.PIDController
import java.awt.Color
import java.util.*

private const val ID = "com.genir.aitweaks.DebugPlugin"

var debugPlugin: DebugPlugin = DebugPlugin()
var debugVertices: MutableList<Line> = mutableListOf()

val pid = PIDController(10f, 3f, 4f, 2f)

data class Line(val a: Vector2f, val b: Vector2f, val color: Color)

// DebugPlugin is used to render debug information during combat.
class DebugPlugin : BaseEveryFrameCombatPlugin() {
    private var font: LazyFont? = null
    private var logs: MutableMap<String, LazyFont.DrawableString> = TreeMap()

    operator fun set(index: Any, value: Any?) {
        if (font == null) return

        if (value == null) logs.remove("$index")
        else logs["$index"] = font!!.createText("$value", baseColor = Color.ORANGE)
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (font == null) {
            font = LazyFont.loadFont("graphics/fonts/insignia15LTaa.fnt")
            debugPlugin = this
        }

        // Initialize debug renderer.
        val engine = Global.getCombatEngine()
        if (!engine.customData.containsKey(ID)) {
            engine.addLayeredRenderingPlugin(RenderDebugLines())
            engine.customData[ID] = true
        }

        debug(amount)
//        speedupAsteroids()
    }

    override fun renderInUICoords(viewport: ViewportAPI?) {
        super.renderInUICoords(viewport)

        for ((i, v) in logs.entries.withIndex()) {
            v.value.draw(500f, 500f + (logs.count() / 2 - i) * 16f)
        }
    }

    var stopped = false

    private fun debug(dt: Float) {
//        if (Global.getCombatEngine().isPaused) return

        val ship = Global.getCombatEngine().ships.firstOrNull { it.variant.hasHullMod(HullMods.AUTOMATED) } ?: return

        debugPlugin[STRAFE_RIGHT] = " "
        debugPlugin[STRAFE_LEFT] = " "
        debugPlugin[TURN_LEFT] = " "
        debugPlugin[TURN_RIGHT] = " "
        debugPlugin[ACCELERATE] = " "
        debugPlugin[ACCELERATE_BACKWARDS] = " "
        debugPlugin[DECELERATE] = " "

        (ship as Ship).ai = null


        val position = Vector2f(Global.getCombatEngine().viewport.convertScreenXToWorldX(Global.getSettings().mouseX.toFloat()), Global.getCombatEngine().viewport.convertScreenYToWorldY(Global.getSettings().mouseY.toFloat())

        )

//        pid.move(position, ship)
//        pid.rotate(VectorUtils.getFacing(position - ship.location), ship)

//        val target = Global.getCombatEngine().playerShip?.location ?: return

        val con = Controller()
        con.facing(ship, position, dt)
        con.heading(ship, position, dt)
    }

    private fun speedupAsteroids() {
        val asteroids = Global.getCombatEngine().asteroids
        for (i in asteroids.indices) {
            val a = asteroids[i]
            a.mass = 0f
            a.velocity.set(VectorUtils.getDirectionalVector(Vector2f(), a.velocity) * 1200f)
        }
    }

    inner class RenderDebugLines : BaseCombatLayeredRenderingPlugin() {

        private fun getVertices(): List<Line> {
            val ships = Global.getCombatEngine().ships.filter { it != Global.getCombatEngine().playerShip }
            val ais = ships.flatMap { it.weaponGroupsCopy }.flatMap { it.aiPlugins }.filterIsInstance<AutofireAI>()
            val hardpoints = ais.filter { it.weapon.slot.isHardpoint && it.target != null }

            return hardpoints.map { Line(it.weapon.location, it.target!!, Color.RED) }
        }

        override fun render(layer: CombatEngineLayers?, viewport: ViewportAPI?) {
            if (debugVertices.isEmpty()) {
                return
            }

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)

            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

            GL11.glLineWidth(2f / Global.getCombatEngine().viewport.viewMult)

            debugVertices.forEach {
                Misc.setColor(it.color)
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2f(it.a.x, it.a.y);
                GL11.glVertex2f(it.b.x, it.b.y);
                GL11.glEnd();
            }

            GL11.glPopAttrib()

            debugVertices.clear()
        }

        override fun getRenderRadius(): Float = 1e6f

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.of(CombatEngineLayers.JUST_BELOW_WIDGETS)
    }
}

class DtTracker(private val span: Float) {
    private var tSum = 0f
    private val dts: MutableList<Float> = mutableListOf()

    fun advance(dt: Float) {
        dts.add(dt)
        tSum += dt

        while (dts.isNotEmpty() && tSum > span) {
            tSum -= dts.removeFirst()
        }
    }

    fun dt() = tSum / dts.count()
}