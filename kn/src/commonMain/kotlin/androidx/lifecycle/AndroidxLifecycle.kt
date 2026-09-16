/* androidx.lifecycle: ViewModel/LiveData without a Lifecycle.
 *
 * There is no Activity here, so an observer is never auto-removed: a LiveData
 * observer added with observeForever's semantics stays until removed.  That is
 * the only divergence, and it is why observe(owner, ...) ignores its owner. */
package androidx.lifecycle

open class ViewModel {
    protected open fun onCleared() {}
    fun clear() = onCleared()
}

open class AndroidViewModel(private val application: android.app.Application) : ViewModel() {
    /** Not generic: the JVM's `<T extends Application> T getApplication()` needs
     *  a call-site type argument Kotlin cannot infer here. */
    open fun getApplication(): android.app.Application = application
}

fun interface Observer<T> {
    fun onChanged(value: T)
}

interface LifecycleOwner

open class LiveData<T> {
    private var valueField: T? = null
    private val observers = ArrayList<Observer<T>>()

    constructor()
    constructor(value: T) { valueField = value }

    open fun getValue(): T? = valueField
    fun hasObservers(): Boolean = observers.isNotEmpty()

    fun observe(owner: LifecycleOwner?, observer: Observer<T>) = observeForever(observer)

    fun observeForever(observer: Observer<T>) {
        if (observer !in observers) {
            observers.add(observer)
            valueField?.let { observer.onChanged(it) }
        }
    }

    fun removeObserver(observer: Observer<T>) { observers.remove(observer) }
    fun removeObservers(owner: LifecycleOwner?) = observers.clear()

    protected open fun setValueInternal(value: T) {
        valueField = value
        for (o in ArrayList(observers)) o.onChanged(value)
    }
}

class MutableLiveData<T> : LiveData<T> {
    constructor() : super()
    constructor(value: T) : super(value)

    fun setValue(value: T) = setValueInternal(value)

    /** No main thread to hop to here, so postValue is setValue. */
    fun postValue(value: T) = setValueInternal(value)
}

class ViewModelProvider(private val store: Any?) {
    private val cache = LinkedHashMap<String, ViewModel>()
    fun <T : ViewModel> get(key: String, factory: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return cache.getOrPut(key, factory) as T
    }
}
