/**
 * JSON serialization utilities for the jkid sample.
 *
 * This file contains the reflection based implementation that converts arbitrary Kotlin objects
 * into JSON strings. The implementation demonstrates:
 *  - Discovering properties via Kotlin reflection (`memberProperties`).
 *  - Honoring opt-out exclusion via [JsonExclude].
 *  - Supporting custom JSON property names via [JsonName].
 *  - Supporting pluggable per–property serializers via [CustomSerializer] / [ValueSerializer].
 *  - Simple handling of primitive types, strings, lists and nested objects.
 *
 * The goal is clarity rather than performance. The API that callers use is [serialize].
 */
package kia.jkid.serialization

import kia.jkid.CustomSerializer
import kia.jkid.DateSerializer
import kia.jkid.JsonExclude
import kia.jkid.JsonName
import kia.jkid.ValueSerializer
import kia.jkid.exercise.DateFormat
import kia.jkid.joinToStringBuilder
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Serializes the supplied [obj] to a JSON string.
 *
 * This is the only public entry point in this file. It delegates to the reflection based
 * [serializeObject] implementation which walks the object graph.
 *
 * Limitations:
 *  - Cyclic object graphs are not detected and will cause a stack overflow.
 *  - Only Lists are supported as collections (no Sets / Maps here).
 *  - Numbers are rendered using their default `toString()` representation.
 *
 * @param obj any non-null object to serialize (may contain nullable properties internally).
 * @return the JSON representation of [obj].
 */
fun serialize(obj: Any): String = buildString { serializeObject(obj) }

/**
 * Initial / simpler version shown in the book: serializes *all* properties without looking at
 * annotations. Kept for reference and comparison. Not used by [serialize].
 *
 * @receiver the [StringBuilder] accumulating JSON.
 * @param obj the object instance being serialized.
 */
private fun StringBuilder.serializeObjectWithoutAnnotation(obj: Any) {
    val kClass = obj::class as KClass<Any>
    val properties = kClass.memberProperties

    properties.joinToStringBuilder(this, prefix = "{", postfix = "}") { prop ->
        serializeString(prop.name)
        append(": ")
        serializePropertyValue(prop.get(obj))
    }
}

/**
 * Serializes an object honoring the supported annotations:
 *  - Skips properties annotated with [JsonExclude].
 *  - Uses an alternative name from [JsonName] when present.
 *  - Applies a custom value converter when [CustomSerializer] is present.
 *
 * @receiver the [StringBuilder] accumulating JSON.
 * @param obj the object instance being serialized.
 */
private fun StringBuilder.serializeObject(obj: Any) {
    (obj::class as KClass<Any>)
        .memberProperties
        .filter { it.findAnnotation<JsonExclude>() == null }
        .joinToStringBuilder(this, prefix = "{", postfix = "}") {
            serializeProperty(it, obj)
        }
}

/**
 * Serializes a single property: writes the (possibly overridden) JSON property name, a colon,
 * and the computed JSON value for the property.
 *
 * Resolution order for the value:
 *  1. If a custom serializer is declared via [CustomSerializer], its [ValueSerializer.toJsonValue]
 *     result is used.
 *  2. Otherwise the raw property value is used.
 * The resulting value is then serialized by [serializePropertyValue].
 *
 * @receiver the [StringBuilder] accumulating JSON.
 * @param prop the reflective property handle.
 * @param obj the instance from which to read [prop].
 */
private fun StringBuilder.serializeProperty(
    prop: KProperty1<Any, *>, obj: Any
) {
    val jsonNameAnn = prop.findAnnotation<JsonName>()
    val propName = jsonNameAnn?.name ?: prop.name
    serializeString(propName)
    append(": ")

    val value = prop.get(obj)
    val jsonValue = prop.getSerializer()?.toJsonValue(value) ?: value
    serializePropertyValue(jsonValue)
}

/**
 * Returns a [ValueSerializer] for the property if a custom serializer or date format annotation is present.
 *
 * Resolution order:
 * 1. If a [CustomSerializer] annotation is present, returns its serializer.
 * 2. If a [DateFormat] annotation is present, returns a [DateSerializer] for the format.
 * 3. Otherwise, returns null.
 *
 * @receiver The property to inspect for serializer annotations.
 * @return The [ValueSerializer] instance, or null if none found.
 */
fun KProperty<*>.getSerializer(): ValueSerializer<Any?>? =
    getCustomSerializer() ?: getDateSerializer()

/**
 * Returns a [DateSerializer] for the property if a [DateFormat] annotation is present.
 *
 * @receiver The property to inspect for a [DateFormat] annotation.
 * @return The [DateSerializer] instance, or null if not annotated.
 */
private fun KProperty<*>.getDateSerializer(): ValueSerializer<Any?>? =
    findAnnotation<DateFormat>()?.let {
        @Suppress("UNCHECKED_CAST")
        DateSerializer(it.format) as ValueSerializer<Any?>
    }

/**
 * Returns a custom [ValueSerializer] for the property if a [CustomSerializer] annotation is present.
 *
 * Instantiates the serializer class via its object instance or no-arg constructor.
 *
 * @receiver The property to inspect for a [CustomSerializer] annotation.
 * @return The [ValueSerializer] instance, or null if not annotated.
 */
private fun KProperty<*>.getCustomSerializer(): ValueSerializer<Any?>? {
    val customSerializerAnn = findAnnotation<CustomSerializer>() ?: return null
    val serializerClass = customSerializerAnn.serializerClass

    val valueSerializer = serializerClass.objectInstance
        ?: serializerClass.createInstance()
    @Suppress("UNCHECKED_CAST")
    return valueSerializer as ValueSerializer<Any?>
}

/**
 * Serializes an arbitrary value based on its runtime type.
 *
 * Supported kinds:
 *  - null (written as the literal `null`)
 *  - String (escaped via [serializeString])
 *  - Number / Boolean (verbatim `toString()`)
 *  - List (delegated to [serializeList])
 *  - Any other object (recursively serialized via [serializeObject])
 *
 * @receiver the accumulating JSON [StringBuilder].
 * @param value the value to serialize (may be null).
 */
private fun StringBuilder.serializePropertyValue(value: Any?) {
    when (value) {
        null -> append("null")
        is String -> serializeString(value)
        is Number, is Boolean -> append(value.toString())
        is List<*> -> serializeList(value)
        is Map<*, *> -> serializeMap(value)
        else -> serializeObject(value)
    }
}

/**
 * Serializes a list of values (possibly heterogeneous) into a JSON array.
 *
 * @receiver the accumulating JSON [StringBuilder].
 * @param data the list to serialize; elements may be null or nested objects.
 */
private fun StringBuilder.serializeList(data: List<Any?>) {
    data.joinToStringBuilder(this, prefix = "[", postfix = "]") {
        serializePropertyValue(it)
    }
}

/**
 * Serializes a Map as a JSON object, but only if all keys are "simple":
 * String, Number, Boolean or Enum. Enum keys use their name.
 *
 * If any key is null or not one of the supported types, an [IllegalArgumentException] is thrown.
 * This avoids ambiguous or lossy encodings for complex key objects.
 */
private fun StringBuilder.serializeMap(data: Map<*, Any?>) {
    data.entries.joinToStringBuilder(this, prefix = "{", postfix = "}") { (k, v) ->
        val keyStr = when (k) {
            is String -> k
            is Number, is Boolean -> k.toString()
            is Enum<*> -> k.name
            else -> {
                val typeDesc = k?.let { "of type ${it::class.qualifiedName}" } ?: "null"
                throw IllegalArgumentException(
                    "Cannot serialize map: non-simple key $typeDesc. Supported key types: String, Number, Boolean, Enum."
                )
            }
        }
        serializeString(keyStr)
        append(": ")
        serializePropertyValue(v)
    }
}

/**
 * Serializes a raw String value, adding surrounding quotes and escaping special characters.
 * Delegates per-character escaping logic to [Char.escape].
 *
 * @receiver the accumulating JSON [StringBuilder].
 * @param s the raw (unescaped) string value.
 */
private fun StringBuilder.serializeString(s: String) {
    append('\"')
    s.forEach { append(it.escape()) }
    append('\"')
}

/**
 * Returns the escaped representation for a single character suitable for inclusion inside a JSON
 * string literal. Characters that do not need escaping are returned unchanged. The return type is
 * `Any` simply because the original implementation appends either a `String` escape sequence or
 * the original `Char` directly; both are acceptable to [StringBuilder.append].
 *
 * Escaped characters: backslash, double quote, backspace, form-feed, newline, carriage return, tab.
 *
 * @receiver the character to escape.
 * @return either the original character or the corresponding escape sequence as a String.
 */
private fun Char.escape(): Any =
    when (this) {
        '\\' -> "\\\\"
        '\"' -> "\\\""
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> this
    }
