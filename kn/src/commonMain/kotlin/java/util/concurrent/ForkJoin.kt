package java.util.concurrent

/* ForkJoin, run in the caller.  A ForkJoin task is a promise that the work is
 * done when invoke returns, not that it ran in parallel, so executing the
 * subtasks one after another is a valid pool -- a single-threaded one.  The one
 * user is GainMapComputer's UltraHDR gain map, which then costs its serial
 * time here. */
abstract class RecursiveAction {
    protected abstract fun compute()

    fun invoke() = compute()

    companion object {
        fun invokeAll(vararg tasks: RecursiveAction) = tasks.forEach { it.invoke() }
    }
}

class ForkJoinPool private constructor() {
    fun invoke(task: RecursiveAction) = task.invoke()

    companion object {
        private val common = ForkJoinPool()
        fun commonPool(): ForkJoinPool = common
    }
}
