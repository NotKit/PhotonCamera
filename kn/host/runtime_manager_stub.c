/* Aurora's runtime-manager-qt5 has no counterpart off Aurora, and ak-window /
 * ak-uri-launcher reference nine of its symbols. This stubs them so the probe
 * links and runs elsewhere: lifecycle notifications and Aurora "intents" do
 * nothing. Names below are the C++ mangled ones.
 *
 * The two QMetaObjects cannot be zero blobs: ak-window's LifecycleClient does
 * QObject::connect(Lifecycle::instance(), &Lifecycle::stateChanged, ...), and
 * Qt walks the sender's metaobject chain. So instance() hands back a real,
 * default-constructed QObject and the metaobjects are copies of QObject's --
 * connect then fails cleanly with "signal not found" instead of segfaulting. */

#include <string.h>

extern char qobject_static_meta_object[] __asm__("_ZN7QObject16staticMetaObjectE");
extern void qobject_ctor(void *self, void *parent) __asm__("_ZN7QObjectC1EPS_");

/* Room for QObject (vptr + d_ptr); over-sized on purpose. */
static long long rm_obj_storage[8];
static int rm_obj_built;

void *rm_lifecycle_instance(void) __asm__("_ZN14RuntimeManager9Lifecycle8instanceEv");
void *rm_lifecycle_instance(void)
{
    if (!rm_obj_built) { qobject_ctor(rm_obj_storage, 0); rm_obj_built = 1; }
    return rm_obj_storage;
}

void rm_lifecycle_state_changed(void *a, void *b, void *c)
    __asm__("_ZN14RuntimeManager9Lifecycle12stateChangedERK7QStringRKNS_16ApplicationStateE");
void rm_lifecycle_state_changed(void *a, void *b, void *c) { (void)a; (void)b; (void)c; }

void rm_ii_ctor(void *self, void *parent) __asm__("_ZN14RuntimeManager14IntentsInvokerC1EP7QObject");
void rm_ii_ctor(void *self, void *parent) { qobject_ctor(self, parent); }

void rm_ii_dtor(void *self) __asm__("_ZN14RuntimeManager14IntentsInvokerD1Ev");
void rm_ii_dtor(void *self) { (void)self; }

void rm_ii_invoke(void *self, void *a, void *b, void *c)
    __asm__("_ZN14RuntimeManager14IntentsInvoker6invokeERK7QStringRK11QJsonObjectS6_");
void rm_ii_invoke(void *self, void *a, void *b, void *c) { (void)self; (void)a; (void)b; (void)c; }

void rm_ii_reply(void *self, void *a, void *b)
    __asm__("_ZN14RuntimeManager14IntentsInvoker13replyReceivedERK11QJsonObjectRKNS_5ErrorE");
void rm_ii_reply(void *self, void *a, void *b) { (void)self; (void)a; (void)b; }

int rm_error_code(void *self) __asm__("_ZNK14RuntimeManager5Error4codeEv");
int rm_error_code(void *self) { (void)self; return 0; }

char rm_lifecycle_mo[256] __asm__("_ZN14RuntimeManager9Lifecycle16staticMetaObjectE");
char rm_lifecycle_mo[256];
char rm_ii_mo[256] __asm__("_ZN14RuntimeManager14IntentsInvoker16staticMetaObjectE");
char rm_ii_mo[256];

/* QMetaObject is six pointers; copy generously. */
__attribute__((constructor)) static void rm_init(void)
{
    memcpy(rm_lifecycle_mo, qobject_static_meta_object, 64);
    memcpy(rm_ii_mo, qobject_static_meta_object, 64);
}
