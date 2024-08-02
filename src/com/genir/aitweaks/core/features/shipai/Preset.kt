package com.genir.aitweaks.core.features.shipai

class Preset {
    @Suppress("ConstPropertyName")
    companion object {
        const val threatEvalRadius = 2500f
        const val aimOffsetSamples = 45

        const val effectiveDpsThreshold = 0.80f

        // Flux management
        const val backoffUpperThreshold = 0.75f
        const val backoffLowerThreshold = backoffUpperThreshold * 0.6f // will force vent
        const val holdFireThreshold = 0.9f
        const val damageHistoryDuration = 4f

        // Idle time calculation
        const val shieldDownVentTime = 2.0f
        const val shieldFlickerThreshold = 0.5f

        // Map movement calculation
        const val arrivedAtLocationRadius = 2000f
        const val borderCornerRadius = 4000f
        const val borderNoGoZone = 3000f
        const val borderHardNoGoZone = borderNoGoZone / 2f

        // Collision avoidance
        const val collisionBuffer = 30f

        // Broadside
        const val frontAttackMultiplier = 1.5f
        const val broadsideFacingPadding = 10f
        const val maxBroadsideFacing = 80f
        const val validBroadsideDPSThreshold = 0.9f

        const val weaponMaxReloadTime = 12f
    }
}
