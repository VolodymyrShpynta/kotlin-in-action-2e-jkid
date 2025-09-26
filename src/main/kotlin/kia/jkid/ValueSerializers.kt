package kia.jkid

import kia.jkid.StringSerializer.toJsonValue
import kia.jkid.deserialization.JKidException
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Returns a non-null [ValueSerializer] for the supplied primitive or String [KType].
 *
 * This is a convenience wrapper over [serializerForType] that asserts the type is
 * a primitive numeric, a Boolean, or a (nullable) String and that a serializer exists.
 *
 * Contract:
 * - Input: a [KType] that must satisfy [isPrimitiveOrString].
 * - Output: a matching serializer. Never returns null.
 * - Failure: throws [AssertionError] if the type is not supported (i.e. programming error).
 */
fun serializerForBasicType(type: KType): ValueSerializer<out Any?> {
    assert(type.isPrimitiveOrString()) { "Expected primitive type or String: $type" }
    return serializerForType(type)!!
}

/**
 * Attempts to locate a [ValueSerializer] for the given basic [KType].
 *
 * Supported types: numeric primitives (Byte, Short, Int, Long, Float, Double), Boolean, and
 * (nullable) String. Returns null for unsupported types so callers can decide whether to
 * fallback to composite / reflective logic.
 *
 * Note: Nullable primitives are not explicitly enumerated because primitive wrappers are
 * represented by the same serializers (null handling is typically performed at a higher layer).
 */
fun serializerForType(type: KType): ValueSerializer<out Any?>? =
    when (type) {
        typeOf<Byte>() -> ByteSerializer
        typeOf<Short>() -> ShortSerializer
        typeOf<Int>() -> IntSerializer
        typeOf<Long>() -> LongSerializer
        typeOf<Float>() -> FloatSerializer
        typeOf<Double>() -> DoubleSerializer
        typeOf<Boolean>() -> BooleanSerializer
        typeOf<String>(),
        typeOf<String?>() -> StringSerializer

        else -> null
    }

/**
 * Helper ensuring the receiver is a [Number]. Used by numeric serializers to give a uniform
 * error message when the JSON value kind doesn't match the expected numeric domain.
 * @throws JKidException if the value is not a number.
 */
private fun Any?.expectNumber(): Number {
    if (this !is Number) throw JKidException("Expected number, was: $this")
    return this
}

/**
 * Serializer for [Byte] values.
 * - fromJsonValue: expects a JSON number (integral range validation deferred to conversion).
 * - toJsonValue: returns the same numeric value (boxed Byte) for emission.
 */
object ByteSerializer : ValueSerializer<Byte> {
    /** Converts a numeric JSON value to [Byte], throwing if not numeric. */
    override fun fromJsonValue(jsonValue: Any?) = jsonValue.expectNumber().toByte()

    /** Returns the byte as-is for JSON emission. */
    override fun toJsonValue(value: Byte) = value
}

/**
 * Serializer for [Short] values.
 */
object ShortSerializer : ValueSerializer<Short> {
    /** Converts a numeric JSON value to [Short]. */
    override fun fromJsonValue(jsonValue: Any?) = jsonValue.expectNumber().toShort()

    /** Returns the short as-is for JSON emission. */
    override fun toJsonValue(value: Short) = value
}

/** Serializer for [Int] values. */
object IntSerializer : ValueSerializer<Int> {
    /** Converts a numeric JSON value to [Int]. */
    override fun fromJsonValue(jsonValue: Any?) = jsonValue.expectNumber().toInt()

    /** Returns the int as-is for JSON emission. */
    override fun toJsonValue(value: Int) = value
}

/** Serializer for [Long] values. */
object LongSerializer : ValueSerializer<Long> {
    /** Converts a numeric JSON value to [Long]. */
    override fun fromJsonValue(jsonValue: Any?) = jsonValue.expectNumber().toLong()

    /** Returns the long as-is for JSON emission. */
    override fun toJsonValue(value: Long) = value
}

/** Serializer for [Float] values. */
object FloatSerializer : ValueSerializer<Float> {
    /** Converts a numeric JSON value to [Float]. */
    override fun fromJsonValue(jsonValue: Any?) = jsonValue.expectNumber().toFloat()

    /** Returns the float as-is for JSON emission. */
    override fun toJsonValue(value: Float) = value
}

/** Serializer for [Double] values. */
object DoubleSerializer : ValueSerializer<Double> {
    /** Converts a numeric JSON value to [Double]. */
    override fun fromJsonValue(jsonValue: Any?) = jsonValue.expectNumber().toDouble()

    /** Returns the double as-is for JSON emission. */
    override fun toJsonValue(value: Double) = value
}

/**
 * Serializer for [Boolean] values.
 * - fromJsonValue: enforces that the JSON value is a Boolean.
 * - toJsonValue: returns the same Boolean.
 */
object BooleanSerializer : ValueSerializer<Boolean> {
    /** Validates the JSON value is a Boolean and returns it. */
    override fun fromJsonValue(jsonValue: Any?): Boolean {
        if (jsonValue !is Boolean) throw JKidException("Expected boolean, was: $jsonValue")
        return jsonValue
    }

    /** Returns the boolean unchanged. */
    override fun toJsonValue(value: Boolean) = value
}

/**
 * Serializer for Kotlin [String] values, including nullable variants.
 *
 * JSON representation rules:
 * - Accepts a JSON string value (mapped to a Kotlin [String]).
 * - Accepts JSON null (mapped to Kotlin null).
 * - Any other JSON value type results in a [JKidException].
 *
 * This serializer is idempotent on the way out: the Kotlin value is already in the
 * correct shape for the JSON layer, so [toJsonValue] returns it unchanged.
 */
object StringSerializer : ValueSerializer<String?> {
    /**
     * Converts a parsed JSON value into a Kotlin [String] or null.
     * @param jsonValue The decoded JSON value (string or null expected).
     * @throws JKidException if the value is neither a [String] nor null.
     */
    override fun fromJsonValue(jsonValue: Any?): String? {
        if (jsonValue !is String?) throw JKidException("Expected string, was: $jsonValue")
        return jsonValue
    }

    /**
     * Returns the given value unchanged. Strings (and null) map 1:1 to JSON primitives.
     */
    override fun toJsonValue(value: String?) = value
}

/**
 * Serializer for [Date] values using a custom date format.
 *
 * @constructor Creates a [DateSerializer] with the specified [dateFormat].
 * @property formatter Internal [SimpleDateFormat] used for parsing and formatting dates.
 *
 * The [dateFormat] string must be compatible with [SimpleDateFormat].
 *
 * Example usage:
 *   val serializer = DateSerializer("yyyy-MM-dd")
 *   val date = serializer.fromJsonValue("2025-09-26")
 *   val json = serializer.toJsonValue(date)
 */
class DateSerializer(dateFormat: String) : ValueSerializer<Date> {
    /**
     * Internal [SimpleDateFormat] instance initialized with [dateFormat].
     * Used for parsing and formatting [Date] objects.
     */
    private val formatter = SimpleDateFormat(dateFormat)

    /**
     * Converts a JSON value to a [Date] object.
     *
     * @param jsonValue The JSON value to parse. Must be a [String] in the expected date format.
     * @return The parsed [Date] object.
     * @throws JKidException If [jsonValue] is not a [String] or cannot be parsed as a date.
     */
    override fun fromJsonValue(jsonValue: Any?): Date {
        require(jsonValue is String) { "Expected string for date, was: $jsonValue" }
        return runCatching { formatter.parse(jsonValue) }
            .getOrNull()
            ?: throw JKidException("Failed to parse date: $jsonValue")
    }

    /**
     * Converts a [Date] object to its JSON string representation.
     *
     * @param value The [Date] to format.
     * @return The formatted date string.
     */
    override fun toJsonValue(value: Date): Any {
        return formatter.format(value)
    }
}
