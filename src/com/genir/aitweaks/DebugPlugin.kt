package com.genir.aitweaks

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipCommand.*
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.combat.entities.Ship
import com.genir.aitweaks.utils.DtTracker
import com.genir.aitweaks.utils.setFacing
import com.genir.aitweaks.utils.setHeading
import com.genir.aitweaks.utils.times
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.isZeroVector
import org.lazywizard.lazylib.ui.LazyFont
import org.lwjgl.util.vector.Vector2f
import org.magiclib.subsystems.drones.PIDController
import java.awt.Color
import java.util.*

var debugPlugin: DebugPlugin = DebugPlugin()

val pid = PIDController(10f, 3f, 1f, 1f)

// DebugPlugin is used to render debug information during combat.
class DebugPlugin : BaseEveryFrameCombatPlugin() {
    private var font: LazyFont? = null
    private var logs: MutableMap<String, LazyFont.DrawableString> = TreeMap()

    var dtTracker = DtTracker(2f)

    operator fun set(index: Any, value: Any?) {
        if (font == null) return

        if (value == null) logs.remove("$index")
        else logs["$index"] = font!!.createText("${if (index is String) index else ""} $value", baseColor = Color.ORANGE)
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (font == null) {
            font = LazyFont.loadFont("graphics/fonts/insignia15LTaa.fnt")
            debugPlugin = this
        }

        dtTracker.advance(amount)

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

        if (!ship.velocity.isZeroVector()) {
            if (!stopped) {
                ship.giveCommand(ShipCommand.DECELERATE, null, 0)
                return
            }
        } else {
            stopped = true
        }

        val position = Vector2f(Global.getCombatEngine().viewport.convertScreenXToWorldX(Global.getSettings().mouseX.toFloat()), Global.getCombatEngine().viewport.convertScreenYToWorldY(Global.getSettings().mouseY.toFloat())

        )

//        pid.move(target, ship)

        val target = Global.getCombatEngine().playerShip?.location ?: return

        setFacing(ship, target)
        setHeading(ship, position)
    }

    private fun speedupAsteroids() {
        val asteroids = Global.getCombatEngine().asteroids
        for (i in asteroids.indices) {
            val a = asteroids[i]
            a.mass = 0f
            a.velocity.set(VectorUtils.getDirectionalVector(Vector2f(), a.velocity) * 1200f)
        }
    }
}
