package photoncam.camera

import kotlin.concurrent.AtomicInt

/**
 * Kotlin/Native's stdlib has no synchronized().  The camera's queues are held
 * for a few instructions at a time between the backend's frame thread and the
 * app's, which is what a spin lock is for.
 */
class SpinLock {
	private val held = AtomicInt(0)

	fun <T> withLock(block: () -> T): T {
		while (!held.compareAndSet(0, 1)) {
		}
		try {
			return block()
		} finally {
			held.value = 0
		}
	}
}
