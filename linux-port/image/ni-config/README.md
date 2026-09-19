# ni-config — the traced metadata

GraalVM's tracing agent writes this directory; `linux-port/trace-metadata.sh`
runs the trace and `linux-port/build-image.sh` reads it.

**It is tracked, and it is not edited by hand.** Every entry has to come from a
run: the agent records what the app actually looked up by name, and an entry
nobody traced is an image root the build then has to honour for ever. Re-trace
instead of patching, and review the result as a diff — a trace that suddenly
loses a hundred classes means a check stopped reaching its path, not that the
app got simpler.

This is the one generated tree the port commits. It has to be, because
native-image cannot cross-compile: the arm64 image is built on a runner that
cannot run the app, so the metadata has to travel with the source rather than
be produced beside the build.

Two things a trace structurally cannot see, so do not go looking for them here:

* the classes Android instantiates **by name** (every View in a layout, every
  Fragment, every ViewModel) and the names the native libraries call
  `FindClass` with — `../gen-reflect-config.py` generates those at build time;
* what an image **run** proves is missing — that goes in `../extra-config/`,
  with the failure that justifies it.

Until this directory holds a trace, `build-image.sh` refuses to run and the CI
workflow publishes only the HotSpot click, saying so in its run summary.
