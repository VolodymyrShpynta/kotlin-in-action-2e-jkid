package kia.jkid

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Appends the elements of this [Iterable] to the provided [stringBuilder], using the same semantics as [joinTo],
 * but allows an optional [callback] to be invoked for each element instead of using the element's string
 * representation.
 *
 * Behavior:
 * - If [callback] is null (default): behaves like a normal join; each element's `toString()` is appended.
 * - If [callback] is non-null: the callback is invoked for every element to allow custom side‑effects or manual
 *   appending to the same [stringBuilder]. In this case the element's textual form is NOT appended automatically
 *   (an empty string is returned to the underlying join logic) so you must write any desired text inside the
 *   callback.
 *
 * This design lets you keep the delimiter / prefix / postfix logic of [joinTo] while taking full control over
 * how each element is rendered (e.g., pretty printing, multi‑line formatting, conditional skipping, etc.).
 *
 * Parameters mirror [joinTo]:
 * @param stringBuilder Destination builder that receives the joined text.
 * @param separator Text inserted between elements (default: ", ").
 * @param prefix Text inserted at the very beginning (default: empty).
 * @param postfix Text inserted at the very end (default: empty).
 * @param limit Maximum number of elements to append; if non‑negative and the collection has more elements,
 *              the remainder are replaced with [truncated]. (Default: -1 meaning no limit.)
 * @param truncated Marker text used when the output is truncated (default: "...").
 * @param callback Optional hook invoked for each element instead of automatic `toString()` insertion.
 *                 Use it to append custom formatted content directly to [stringBuilder].
 *
 * @return The same [StringBuilder] instance for fluent usage.
 *
 * Example (custom formatting):
 * ```kotlin
 * val sb = StringBuilder()
 * listOf(1, 2, 3).joinToStringBuilder(sb, prefix = "[", postfix = "]") { value ->
 *     sb.append("<#").append(value * 10).append(">")
 * }
 * println(sb) // Output: [<#10>, <#20>, <#30>]
 * ```
 */
fun <T> Iterable<T>.joinToStringBuilder(
    stringBuilder: StringBuilder,
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    callback: ((T) -> Unit)? = null
): StringBuilder {
    return joinTo(stringBuilder, separator, prefix, postfix, limit, truncated) {
        if (callback == null) return@joinTo it.toString()
        callback(it)
        return@joinTo ""
    }
}

/**
 * Returns true if this Kotlin reflection [KType] represents one of the primitive numeric types, [Boolean], or
 * [String] (including nullable `String?`).
 *
 * The recognized set is: Byte, Short, Int, Long, Float, Double, Boolean, String, and String?.
 * This utility can be useful in serialization / deserialization logic to distinguish between simple scalar
 * leaf values and more complex object / collection types.
 *
 * Note: `Char` is intentionally excluded here; add it if your use‑case treats `Char` as a primitive.
 */
fun KType.isPrimitiveOrString(): Boolean {
    val types = setOf(
        typeOf<Byte>(),
        typeOf<Short>(),
        typeOf<Int>(),
        typeOf<Long>(),
        typeOf<Float>(),
        typeOf<Double>(),
        typeOf<Boolean>(),
        typeOf<String>(),
        typeOf<String?>(),
    )
    return this in types
}