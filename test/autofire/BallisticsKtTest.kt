package autofire

import com.fs.starfarer.api.combat.MutableStat
import com.genir.aitweaks.core.shipai.autofire.*
import com.genir.aitweaks.core.utils.Arc
import com.genir.aitweaks.core.extensions.length
import mocks.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.lwjgl.util.vector.Vector2f


class BallisticsKtTest {
    @Test
    fun testWillHitBounds() {
        val bounds = MockBoundsAPI(
            "getOrigSegments" to listOf(
                MockSegmentAPI(Vector2f(4.0f, 13.0f), Vector2f(3.333332f, -5.666666f)),
                MockSegmentAPI(Vector2f(3.333332f, -5.666666f), Vector2f(0.6940994f, -3.4000683f)),
                MockSegmentAPI(Vector2f(0.6940994f, -3.4000683f), Vector2f(2.5799294f, 2.8077145f)),
                MockSegmentAPI(Vector2f(2.5799294f, 2.8077145f), Vector2f(-1.5446529f, 3.7806206f)),
                MockSegmentAPI(Vector2f(-1.5446529f, 3.7806206f), Vector2f(-2.002161f, 7.9474297f)),
                MockSegmentAPI(Vector2f(-2.002161f, 7.9474297f), Vector2f(-7.5947266f, 7.6417694f)),
                MockSegmentAPI(Vector2f(-7.5947266f, 7.6417694f), Vector2f(-12.8621025f, 7.619339f)),
                MockSegmentAPI(Vector2f(-12.8621025f, 7.619339f), Vector2f(-12.500004f, 12.5f)),
                MockSegmentAPI(Vector2f(-12.500004f, 12.5f), Vector2f(4.0f, 13.0f)),
            ), "getSegments" to listOf(
            MockSegmentAPI(Vector2f(1664.6791f, -462.06982f), Vector2f(1653.2975f, -447.25946f)),
            MockSegmentAPI(Vector2f(1653.2975f, -447.25946f), Vector2f(1656.7758f, -447.32437f)),
            MockSegmentAPI(Vector2f(1656.7758f, -447.32437f), Vector2f(1659.2784f, -453.31018f)),
            MockSegmentAPI(Vector2f(1659.2784f, -453.31018f), Vector2f(1663.077f, -451.43152f)),
            MockSegmentAPI(Vector2f(1663.077f, -451.43152f), Vector2f(1666.085f, -454.35114f)),
            MockSegmentAPI(Vector2f(1666.085f, -454.35114f), Vector2f(1670.2001f, -450.5517f)),
            MockSegmentAPI(Vector2f(1670.2001f, -450.5517f), Vector2f(1674.2451f, -447.17773f)),
            MockSegmentAPI(Vector2f(1674.2451f, -447.17773f), Vector2f(1677.0763f, -451.16977f)), // HIT
            MockSegmentAPI(Vector2f(1677.0763f, -451.16977f), Vector2f(1664.6791f, -462.06982f)),
        )
        )

        val weapon = MockWeaponAPI(
            "getLocation" to Vector2f(2011.343f, -1750.4396f),
            "getCurrAngle" to 104.474915f,
            "getProjectileSpeed" to 3.4028236E36f,
            "getShip" to MockShipAPI("getVelocity" to Vector2f(2.2171297f, 65.96275f)),
            "getSlot" to MockWeaponSlotAPI("isHardpoint" to false),
            "getSpec" to MockWeaponSpecAPI("getTurretFireOffsets" to listOf(Vector2f())),
            "isBeam" to false,
        )

        val target = MockShipAPI(
            "getLocation" to Vector2f(1659.4774f, -449.50232f),
            "getVelocity" to Vector2f(52.902084f, -105.472f),
            "getCollisionRadius" to 35.755074f,
            "getFacing" to 219.58746f,
            "getExactBounds" to bounds,
            "getMutableStats" to MockMutableShipStatsAPI("getTimeMult" to MutableStat(1f))
        )

        val actual = willHitBounds(weapon, target, BallisticParams(1f, 0f))
        assertEquals(1340.9568f, actual)
    }

    @Test
    fun testTargetFasterThanProjectile() {
        val weapon = MockWeaponAPI(
            "getLocation" to Vector2f(0f, 0f),
            "getProjectileSpeed" to 1f,
            "getCurrAngle" to 90f,
            "getShip" to MockShipAPI("getVelocity" to Vector2f(0f, 0f)),
            "getSlot" to MockWeaponSlotAPI("isHardpoint" to false),
            "getSpec" to MockWeaponSpecAPI("getTurretFireOffsets" to listOf(Vector2f())),
            "isBeam" to false,
        )

        val target = BallisticTarget(
            velocity = Vector2f(0f, 10f),
            location = Vector2f(0f, 10f),
            radius = 3f,
        )

        val approachesInfinity = 1e7f

        assertTrue(intercept(weapon, target, BallisticParams(1f, 0f)).length >= approachesInfinity)
        assertEquals(Arc(0f, weapon.currAngle), interceptArc(weapon, target, BallisticParams(1f, 0f)))
        assertTrue(closestHitRange(weapon, target, BallisticParams(1f, 0f)) >= approachesInfinity)
        assertNull(willHitCircumference(weapon, target, BallisticParams(1f, 0f)))
    }

    @Test
    fun testWeaponInsideTargetRadius() {
        val weapon = MockWeaponAPI(
            "getLocation" to Vector2f(0f, 0f),
            "getProjectileSpeed" to 100f,
            "getRange" to 1000f,
            "getProjectileFadeRange" to 200f,
            "getArc" to 30f,
            "getArcFacing" to 0f,
            "getCurrAngle" to 90f,
            "getShip" to MockShipAPI(
                "getVelocity" to Vector2f(0f, 0f),
                "getFacing" to 90f,
            ),
            "getSlot" to MockWeaponSlotAPI("isHardpoint" to false),
            "getSpec" to MockWeaponSpecAPI("getTurretFireOffsets" to listOf(Vector2f())),
            "isBeam" to false,
        )

        val target = BallisticTarget(
            velocity = Vector2f(0f, 10f),
            location = Vector2f(0f, 10f),
            radius = 30f,
        )

        assertTrue(canTrack(weapon, target, BallisticParams(1f, 0f)))
        assertNotNull(intercept(weapon, target, BallisticParams(1f, 0f)))
        assertEquals(Arc(360f, 0f), interceptArc(weapon, target, BallisticParams(1f, 0f)))
        assertEquals(0f, closestHitRange(weapon, target, BallisticParams(1f, 0f)))
        assertNotNull(willHitCircumference(weapon, target, BallisticParams(1f, 0f)))
    }
}
