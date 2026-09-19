# extra-config — metadata a trace cannot see

`../ni-config/` is the tracing agent's output over a real boot
(`linux-port/trace-metadata.sh`) and is never edited by hand. This directory is
the other half: entries an image **run** proved the trace could not see, each
with the failure that justifies it.

Two things make a traced call site incomplete, and both are structural rather
than accidental:

* **A traced call site is only registered for the arms the trace took.** atlas
  reaches the *concrete* activity class through `GetMethodID`, so a boot that
  never rotates, never opens the gallery and never takes a picture registers
  none of the methods those paths need. A JNI miss is a `NoSuchMethodError`,
  not a caught `NoSuchMethodException`, so it is fatal.
* **Registering a class loads it, and loading resolves every signature type.**
  A method whose signature names an `android.*` type atlas does not have
  degrades to a build warning and then fails the lookup at run time.

`../image/gen-reflect-config.py` covers the two mechanical families — the
classes Android instantiates by name, and the names the native libraries call
`FindClass` with. What is left goes here, as JSON in the usual
`reflect-config.json` / `jni-config.json` shape, with a line in
`../BRINGUP_NOTES.md` naming the run that demanded it.

Empty is the correct state until an image run says otherwise.
