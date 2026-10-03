package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RobohashTest {
    /** The color and pictures e1ven/Robohash (robohash.py) chooses for these addresses; two checked pixel for pixel on robohash.idena.io. */
    private val reference = mapOf(
        "0x0000000000000000000000000000000000000000" to Robot("pink", listOf(5, 6, 4, 7, 1)),
        "0x34004a3e1d2c3b4a59687766554433221100b850" to Robot("orange", listOf(6, 5, 1, 8, 3)),
        "0x9f1c2e7d5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d" to Robot("white", listOf(5, 8, 1, 1, 0)),
        "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd" to Robot("purple", listOf(8, 9, 1, 9, 8)),
        "0x1111111111111111111111111111111111111111" to Robot("white", listOf(1, 5, 0, 7, 4)),
        "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef" to Robot("brown", listOf(7, 2, 4, 7, 4)),
    )

    @Test
    fun choosesAsRoboHash() {
        for ((address, robot) in reference) assertEquals(address, robot, Robohash.robot(address))
    }

    @Test
    fun drawsBodyFirstAndMouthLast() {
        assertEquals(
            listOf("robohash/brown/3/7.png", "robohash/brown/4/4.png", "robohash/brown/2/4.png", "robohash/brown/1/2.png", "robohash/brown/0/7.png"),
            Robot("brown", listOf(7, 2, 4, 7, 4)).layers(),
        )
    }

    @Test
    fun everyPictureIsInTheAssets() {
        val assets = File("src/main/assets")
        for (color in Robohash.COLORS) for (part in 0 until Robohash.PARTS) for (n in 0 until Robohash.VARIANTS) {
            assertTrue("$color/$part/$n", File(assets, "robohash/$color/$part/$n.png").isFile)
        }
    }
}
