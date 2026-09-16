/* androidx.databinding.BaseObservable: the observer half of data binding,
 * without the generated binding classes.  notifyPropertyChanged carries the
 * BR id, which the Compose UI maps to its own state. */
package androidx.databinding

interface Observable {
    fun addOnPropertyChangedCallback(callback: OnPropertyChangedCallback)
    fun removeOnPropertyChangedCallback(callback: OnPropertyChangedCallback)

    abstract class OnPropertyChangedCallback {
        abstract fun onPropertyChanged(sender: Observable?, propertyId: Int)
    }
}

open class BaseObservable : Observable {
    private val callbacks = ArrayList<Observable.OnPropertyChangedCallback>()

    override fun addOnPropertyChangedCallback(callback: Observable.OnPropertyChangedCallback) {
        if (callback !in callbacks) callbacks.add(callback)
    }

    override fun removeOnPropertyChangedCallback(callback: Observable.OnPropertyChangedCallback) {
        callbacks.remove(callback)
    }

    fun notifyChange() = notifyPropertyChanged(0)

    fun notifyPropertyChanged(fieldId: Int) {
        for (c in ArrayList(callbacks)) c.onPropertyChanged(this, fieldId)
    }
}

@Retention(AnnotationRetention.SOURCE)
annotation class Bindable

@Retention(AnnotationRetention.SOURCE)
annotation class BindingAdapter(vararg val value: String)
