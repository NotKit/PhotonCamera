/* android.text: the InputType bit constants (an ABI) and TextUtils. */
package android.text

object InputType {
    const val TYPE_NULL: Int = 0x00000000
    const val TYPE_MASK_CLASS: Int = 0x0000000f
    const val TYPE_MASK_VARIATION: Int = 0x00000ff0
    const val TYPE_MASK_FLAGS: Int = 0x00fff000
    const val TYPE_CLASS_TEXT: Int = 0x00000001
    const val TYPE_CLASS_NUMBER: Int = 0x00000002
    const val TYPE_CLASS_PHONE: Int = 0x00000003
    const val TYPE_CLASS_DATETIME: Int = 0x00000004
    const val TYPE_NUMBER_FLAG_SIGNED: Int = 0x00001000
    const val TYPE_NUMBER_FLAG_DECIMAL: Int = 0x00002000
    const val TYPE_TEXT_FLAG_MULTI_LINE: Int = 0x00020000
    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080
}

object TextUtils {
    fun isEmpty(s: CharSequence?): Boolean = s == null || s.isEmpty()
    fun equals(a: CharSequence?, b: CharSequence?): Boolean = a?.toString() == b?.toString()
    fun join(delimiter: CharSequence, tokens: Iterable<Any?>): String =
        tokens.joinToString(delimiter) { it.toString() }
    fun split(text: String, expression: String): Array<String> =
        if (text.isEmpty()) emptyArray() else text.split(Regex(expression)).toTypedArray()
}
