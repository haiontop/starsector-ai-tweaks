package com.genir.aitweaks.features.autoshield;

import com.fs.starfarer.combat.CombatState;

public class StateAccess {
    public static boolean getAutoOmni() {
        return CombatState.AUTO_OMNI_SHIELDS;
    }

    public static void setAutoOmni(boolean val) {
        CombatState.AUTO_OMNI_SHIELDS = val;
    }
}
