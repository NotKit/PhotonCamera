package androidx.fragment.app

/* There are no Fragments on this port: the Compose scene has no fragment back
 * stack, so predictive back has nothing to animate and the switch has no
 * effect.  PhotonCamera.onCreate flips it before any FragmentManager exists. */
class FragmentManager private constructor() {
    companion object {
        fun enablePredictiveBack(enabled: Boolean) {}
    }
}
