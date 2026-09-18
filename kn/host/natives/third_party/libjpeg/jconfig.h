/*
 * jconfig.h for the libjpeg that is ACTUALLY LINKED -- not the one whose
 * headers happen to be installed on the build machine.
 *
 * skiko.klib ships default/targets/<target>/included/libjpeg.a (libjpeg-turbo
 * 3.1.0), and Kotlin/Native links every one of those archives into the
 * executable.  Skia uses libjpeg itself, so those members are always pulled in
 * and their jpeg_* symbols define the ones this port calls -- a -ljpeg on the
 * link line can never win.  That build keeps the libjpeg 6b API, so
 * JPEG_LIB_VERSION is 62, while Ubuntu's jpeglib.h is libjpeg8's and says 80.
 * The two disagree about the middle of jpeg_compress_struct, and
 * jpeg_CreateCompress's version guard catches it: "Wrong JPEG library version:
 * library is 62, caller expects 80", every capture saved as a 0-byte file.
 *
 * The values below are the ones the 3.1.0 PUBLIC headers read, matched to that
 * archive.  Arithmetic coding is on because libjpeg.a carries jcarith.o and
 * jdarith.o, and jerror.h numbers its message codes from it.
 */

#ifndef JCONFIG_INCLUDED
#define JCONFIG_INCLUDED

#define JPEG_LIB_VERSION              62
#define LIBJPEG_TURBO_VERSION         3.1.0
#define LIBJPEG_TURBO_VERSION_NUMBER  3001000

#define C_ARITH_CODING_SUPPORTED      1
#define D_ARITH_CODING_SUPPORTED      1
#define MEM_SRCDST_SUPPORTED          1
#define BITS_IN_JSAMPLE               8

#define HAVE_STDDEF_H                 1
#define HAVE_STDLIB_H                 1
#define HAVE_UNSIGNED_CHAR            1
#define HAVE_UNSIGNED_SHORT           1

#define SIZEOF_SIZE_T                 8

#endif /* JCONFIG_INCLUDED */
