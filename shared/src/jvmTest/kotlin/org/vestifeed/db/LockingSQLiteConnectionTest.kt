package org.vestifeed.db

import kotlinx.coroutines.runBlocking
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LockingSQLiteConnectionTest {

    private val driver = BundledSQLiteDriver()

    /**
     * A statement's lock must be held from [LockingSQLiteConnection.prepare]
     * until the statement is closed, not just for the duration of a single call.
     * Otherwise a write could land between two `step()` calls that refill a read
     * cursor's window, which is exactly the crash this class exists to prevent.
     */
    @Test
    fun prepareHoldsTheConnectionUntilTheStatementIsClosed() {
        val conn = LockingSQLiteConnection(driver.open(":memory:"))
        conn.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY);")
        conn.execSQL("INSERT INTO t (id) VALUES (1);")

        val held = conn.prepare("SELECT id FROM t;")
        Assert.assertTrue(held.step())

        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        Thread {
            entered.countDown()
            try {
                conn.prepare("SELECT id FROM t;").use { it.step() }
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                finished.countDown()
            }
        }.start()

        Assert.assertTrue("second thread never reached the barrier", entered.await(5, TimeUnit.SECONDS))
        Assert.assertFalse(
            "second statement ran while the first was still open",
            finished.await(200, TimeUnit.MILLISECONDS),
        )

        held.close()

        Assert.assertTrue(
            "second statement did not run after the first closed",
            finished.await(5, TimeUnit.SECONDS),
        )
        Assert.assertNull(error.get())
        conn.close()
    }

    @Test
    fun transactionIsReentrantAndCommits() = runBlocking<Unit> {
        val conn = LockingSQLiteConnection(driver.open(":memory:"))
        conn.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY);")

        conn.transaction {
            conn.prepare("INSERT INTO t (id) VALUES (?);").use { stmt ->
                stmt.bindLong(1, 1)
                stmt.step()
            }
        }

        conn.prepare("SELECT COUNT(*) FROM t;").use { stmt ->
            Assert.assertTrue(stmt.step())
            Assert.assertEquals(1L, stmt.getLong(0))
        }
        conn.close()
    }

    @Test
    fun concurrentStatementsAreSerializedWithoutDeadlock() {
        val conn = LockingSQLiteConnection(driver.open(":memory:"))
        conn.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, value INTEGER NOT NULL);")
        conn.execSQL("INSERT INTO t (id, value) VALUES (1, 0);")

        val threads = 8
        val iterations = 200
        val start = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        val workers = (0 until threads).map { worker ->
            Thread {
                start.await()
                try {
                    repeat(iterations) { i ->
                        if ((worker + i) % 3 == 0) {
                            conn.prepare("UPDATE t SET value = value + 1 WHERE id = 1;").use {
                                it.step()
                            }
                        } else {
                            conn.prepare("SELECT value FROM t WHERE id = 1;").use { stmt ->
                                Assert.assertTrue(stmt.step())
                                stmt.getLong(0)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(10_000) }
        workers.forEach { Assert.assertFalse("worker did not finish", it.isAlive) }

        Assert.assertNull(error.get())
        conn.close()
    }
}
