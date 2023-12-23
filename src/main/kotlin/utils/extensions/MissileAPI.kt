package com.genir.aitweaks.utils.extensions

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MissileAPI

val MissileAPI.isValidTarget: Boolean
    get() = Global.getCombatEngine().isEntityInPlay(this)