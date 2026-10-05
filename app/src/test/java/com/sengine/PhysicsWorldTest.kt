package com.sengine

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Joint2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.physics.PhysicsWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsWorldTest {
    private fun body(scene: Scene, name: String, x: Float, y: Float, dynamic: Boolean = false): Pair<com.sengine.engine.core.GameObject, Collider2D> {
        val go = scene.create(name).also { it.x = x; it.y = y }
        val collider = go.add(Collider2D())
        if (dynamic) go.add(Rigidbody2D())
        return go to collider
    }

    @Test
    fun collisionLayersRequireMutualMaskAgreement() {
        val scene = Scene("filters").also { it.gravityY = 0f }
        val (a, ac) = body(scene, "layer-one", 0f, 0f, dynamic = true)
        val (b, bc) = body(scene, "layer-two", 0.25f, 0f)
        ac.collisionLayer = 1 shl 0
        ac.collisionMask = 1 shl 0
        bc.collisionLayer = 1 shl 1
        bc.collisionMask = -1

        val physics = PhysicsWorld()
        physics.step(scene, 1f / 60f)
        assertFalse("layer mismatch should suppress contact", physics.areOverlapping(a, b))
        assertEquals(0, physics.statistics.contacts)

        ac.collisionMask = 1 shl 1
        physics.reset()
        physics.step(scene, 1f / 60f)
        assertTrue(physics.areOverlapping(a, b))
        assertEquals(1, physics.statistics.contacts)
    }

    @Test
    fun accumulatedForceImpulseAndTorqueAffectDynamicBody() {
        val scene = Scene("forces").also { it.gravityY = 0f }
        val (go, _) = body(scene, "crate", 0f, 0f, dynamic = true)
        val rb = go.getAny<Rigidbody2D>()!!
        rb.applyForce(12f, 0f)
        val physics = PhysicsWorld()
        physics.step(scene, 1f / 60f)
        assertEquals(0.2f, rb.vx, 0.001f)
        physics.step(scene, 1f / 60f)
        assertEquals("force should be consumed once", 0.2f, rb.vx, 0.001f)

        rb.applyImpulse(2f, 0f)
        assertEquals(2.2f, rb.vx, 0.001f)
        rb.applyTorque(1f)
        physics.step(scene, 1f / 60f)
        assertTrue("torque should rotate the body", go.rotation > 0f)
        assertTrue(rb.angularVelocity > 0f)
    }

    @Test
    fun raycastReturnsNearestHitAndHonorsMask() {
        val scene = Scene("rays").also { it.gravityY = 0f }
        val (far, farCol) = body(scene, "far", 6f, 0f)
        val (near, nearCol) = body(scene, "near", 3f, 0f)
        farCol.collisionLayer = 1 shl 1
        nearCol.collisionLayer = 1 shl 2
        val physics = PhysicsWorld()

        val hit = physics.raycast(scene, 0f, 0f, 10f, 0f, 10f, 1 shl 2)
        assertNotNull(hit)
        assertEquals(near.id, hit!!.gameObject.id)
        assertEquals(2.5f, hit.distance, 0.01f)
        assertNull(physics.raycast(scene, 0f, 0f, 1f, 0f, 10f, 1 shl 3))

        val cast = physics.circleCast(scene, 0f, 0f, 1f, 0f, 10f, 1f, 1 shl 2)
        assertNotNull(cast)
        assertEquals(near.id, cast!!.gameObject.id)
        assertTrue(cast.distance < hit.distance)
        assertTrue(far.id != near.id)
    }

    @Test
    fun triggerEntersAndExitsWithoutResolvingMotion() {
        val scene = Scene("triggers").also { it.gravityY = 0f }
        val (_, trigger) = body(scene, "sensor", 0f, 0f)
        trigger.isTrigger = true
        val (mover, _) = body(scene, "mover", 0f, 0f, dynamic = true)
        val events = mutableListOf<String>()
        val physics = PhysicsWorld()
        physics.listener = object : PhysicsWorld.Listener {
            override fun onCollisionEnter(a: com.sengine.engine.core.GameObject, b: com.sengine.engine.core.GameObject) {}
            override fun onTriggerEnter(a: com.sengine.engine.core.GameObject, b: com.sengine.engine.core.GameObject) { events += "enter" }
            override fun onTriggerExit(a: com.sengine.engine.core.GameObject, b: com.sengine.engine.core.GameObject) { events += "exit" }
        }
        physics.step(scene, 1f / 60f)
        assertEquals(listOf("enter"), events)
        mover.x = 5f
        physics.step(scene, 1f / 60f)
        assertEquals(listOf("enter", "exit"), events)
        assertEquals(0f, mover.x, 0f)
    }

    @Test
    fun pinJointConstrainsBodyToWorldAnchor() {
        val scene = Scene("pin-joint").also { it.gravityY = -10f }
        val bob = scene.create("bob").also { it.x = 2f; it.y = 1f }
        bob.add(Rigidbody2D())
        bob.add(Joint2D().also {
            it.jointType = 1
            it.connectedAnchorX = 0f
            it.connectedAnchorY = 1f
        })
        val physics = PhysicsWorld()

        repeat(90) { physics.step(scene, 1f / 60f) }

        assertEquals(0f, bob.x, 0.02f)
        assertEquals(1f, bob.y, 0.02f)
        assertEquals(1, physics.statistics.joints)
    }

    @Test
    fun distanceJointMaintainsLengthWhileBodyFalls() {
        val scene = Scene("distance-joint").also { it.gravityY = -8f }
        val bob = scene.create("bob").also { it.x = 1.5f; it.y = 2f }
        bob.add(Rigidbody2D())
        bob.add(Joint2D().also {
            it.jointType = 0
            it.connectedAnchorX = 0f
            it.connectedAnchorY = 2f
            it.length = 1.5f
        })
        val physics = PhysicsWorld()

        repeat(120) { physics.step(scene, 1f / 60f) }

        val dx = bob.x
        val dy = bob.y - 2f
        assertEquals(1.5f, kotlin.math.sqrt(dx * dx + dy * dy), 0.06f)
    }

    @Test
    fun fastContinuousCircleDoesNotTunnelThroughThinStaticWall() {
        val scene = Scene("ccd").also { it.gravityY = 0f }
        val wall = scene.create("wall").also { it.x = 0f; it.scaleX = 0.1f; it.scaleY = 4f }
        wall.add(Collider2D())
        val bullet = scene.create("bullet").also { it.x = -0.6f }
        bullet.add(Collider2D().also { it.shape = 1; it.radius = 0.1f })
        bullet.add(Rigidbody2D().also { it.vx = 60f; it.continuous = true })
        val physics = PhysicsWorld()

        physics.step(scene, 1f / 60f)

        assertTrue("CCD should keep the body from crossing the wall", bullet.x < 0.2f)
        assertTrue(physics.statistics.fixedSteps > 0)
    }
}
