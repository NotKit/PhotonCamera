package java.io

open class File {
    constructor(path: String)
    constructor(parent: String?, child: String)
    open fun getPath(): String = TODO()
    open fun getAbsolutePath(): String = TODO()
}

open class FileDescriptor
