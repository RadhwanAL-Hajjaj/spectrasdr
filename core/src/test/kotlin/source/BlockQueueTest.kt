// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BlockQueueTest {
    @Test
    fun deliversInFifoOrder() {
        val pool = BlockPool(4)
        val q = BlockQueue(3)
        q.offer(pool.obtain().apply { seq = 1 })
        q.offer(pool.obtain().apply { seq = 2 })
        assertEquals(1L, q.take(10)!!.seq)
        assertEquals(2L, q.take(10)!!.seq)
        assertNull(q.take(10))
    }

    @Test
    fun dropsOldestWhenFullAndReturnsItToThePool() {
        val pool = BlockPool(4)
        val q = BlockQueue(2)
        for (i in 0 until 3) q.offer(pool.obtain().apply { seq = i.toLong() })
        assertEquals(1L, q.overruns)
        assertEquals(1, pool.freeCount)
        assertEquals(1L, q.take(10)!!.seq)
        assertEquals(2L, q.take(10)!!.seq)
    }

    @Test
    fun takeWaitsForTheProducer() {
        val pool = BlockPool(4)
        val q = BlockQueue(2)
        val producer = Thread { Thread.sleep(50); q.offer(pool.obtain().apply { seq = 7 }) }
        producer.start()
        val got = q.take(2_000)
        producer.join()
        assertEquals(7L, got!!.seq)
    }

    @Test
    fun poolReusesReleasedBlocks() {
        val pool = BlockPool(8, preallocate = 1)
        val b = pool.obtain()
        b.length = 5
        b.release()
        val again = pool.obtain()
        assertSame(b, again)
        assertEquals(0, again.length)
        assertEquals(1, pool.allocated)
    }

    @Test(expected = IllegalStateException::class)
    fun releasingTwiceIsAnError() {
        val b = BlockPool(8).obtain()
        b.release()
        b.release()
    }

    @Test
    fun clearReleasesEverything() {
        val pool = BlockPool(4)
        val q = BlockQueue(4)
        repeat(3) { q.offer(pool.obtain()) }
        q.clear()
        assertEquals(0, q.size)
        assertEquals(3, pool.freeCount)
    }
}
