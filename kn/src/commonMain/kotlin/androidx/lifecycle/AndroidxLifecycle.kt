/* androidx.lifecycle: ViewModel/LiveData without a Lifecycle.
 *
 * There is no Activity here, so an observer is never auto-removed: a LiveData
 * observer added with observeForever's semantics stays until removed.  That is
 * the only divergence.
 *
 * NOT declared here, on purpose: Lifecycle, LifecycleOwner, LifecycleObserver
 * and ViewModelProvider.  Those ARE in androidx's linuxArm64/linuxX64 klibs,
 * which Compose pulls in, and a source class with a klib class's package and
 * name REPLACES it for every consumer -- including the klib's own compiled
 * code.  An empty `interface LifecycleOwner` here cost a whole phone run:
 * androidx.savedstate's SavedStateRegistryImpl reads `owner.lifecycle`, found
 * our member-less twin instead, and the first LazyList frame died with
 * IrLinkageError "No property accessor found for
 * androidx.savedstate/SavedStateRegistryOwner.lifecycle".  LiveData, Observer
 * and AndroidViewModel are Android-only in androidx and have no klib twin. */
package androidx.lifecycle

open class AndroidViewModel(private val application: android.app.Application) : ViewModel() {
    /** Not generic: the JVM's `<T extends Application> T getApplication()` needs
     *  a call-site type argument Kotlin cannot infer here. */
    open fun getApplication(): android.app.Application = application
}

fun interface Observer<T> {
    fun onChanged(value: T)
}

open class LiveData<T> {
    private var valueField: T? = null
    private val observers = ArrayList<Observer<T>>()

    constructor()
    constructor(value: T) { valueField = value }

    open fun getValue(): T? = valueField
    fun hasObservers(): Boolean = observers.isNotEmpty()

    fun observeForever(observer: Observer<T>) {
        if (observer !in observers) {
            observers.add(observer)
            valueField?.let { observer.onChanged(it) }
        }
    }

    fun removeObserver(observer: Observer<T>) { observers.remove(observer) }

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
