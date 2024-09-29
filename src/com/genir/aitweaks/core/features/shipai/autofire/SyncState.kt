package com.genir.aitweaks.core.features.shipai.autofire

/** SyncState is used to synchronise weapons that are to fire in staggered mode. */
data class SyncState(var weapons: Int, var lastAttack: Float)