#!/usr/bin/env python3
"""Remove the unused libcrypt dependency from the staged Sailfish binary."""
import subprocess
import sys


def readelf(option, path):
    return subprocess.check_output(["readelf", option, "-W", path], text=True)


def symbols(path, undefined):
    names = set()
    for line in readelf("--dyn-syms", path).splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[0].rstrip(":").isdigit():
            if (fields[6] == "UND") == undefined:
                names.add(fields[7].split("@")[0])
    return names


binary, library = sys.argv[1:]
if "[libcrypt.so.1]" in readelf("--dynamic", binary):
    used = symbols(binary, True) & symbols(library, False)
    if used:
        sys.exit("cannot remove libcrypt: imported symbols: " + ", ".join(sorted(used)))
    versions = readelf("--version-info", binary)
    if "File: libcrypt.so.1" in versions:
        sys.exit("cannot remove libcrypt: the binary requires its symbol versions")
    subprocess.run(["patchelf", "--remove-needed", "libcrypt.so.1", binary], check=True)
    if "[libcrypt.so.1]" in readelf("--dynamic", binary):
        sys.exit("libcrypt dependency removal failed")
    print("removed unused libcrypt.so.1 dependency")
