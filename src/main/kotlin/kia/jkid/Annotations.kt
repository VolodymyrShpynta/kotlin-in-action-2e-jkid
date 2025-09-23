package kia.jkid

import kotlin.reflect.KClass

/**
 * Annotation to mark a property that should be excluded from JSON serialization
 * and deserialization. When present, the property will be ignored completely.
 */
@Target(AnnotationTarget.PROPERTY)
annotation class JsonExclude

/**
 * Annotation to override the JSON field name for a property. Use this when the
 * Kotlin property name differs from the desired serialized/deserialized key.
 *
 * @property name The JSON key to use instead of the property name.
 */
@Target(AnnotationTarget.PROPERTY)
annotation class JsonName(val name: String)

/**
 * Strategy interface for plugging in custom (de)serialization of a value type.
 * A serializer translates between a Kotlin value of type [T] and a JSON-ready
 * representation (a primitive, String, List, Map, or null).
 *
 * Implementations must be symmetric: a value produced by [toJsonValue] must be
 * correctly consumed by [fromJsonValue].
 *
 * @param T The Kotlin value type supported by this serializer.
 */
interface ValueSerializer<T> {
    /**
     * Converts the Kotlin [value] into a JSON-compatible representation.
     * Return types should be limited to primitives (String, Number, Boolean),
     * null, Lists, or Maps whose contents are themselves JSON-compatible.
     */
    fun toJsonValue(value: T): Any?

    /**
     * Reconstructs a Kotlin value of type [T] from its JSON-compatible
     * representation [jsonValue]. Implementations should perform validation
     * and throw an exception if the structure is invalid.
     */
    fun fromJsonValue(jsonValue: Any?): T
}

/**
 * Annotation to instruct the deserializer which concrete class to instantiate
 * for an interface-typed property. Useful when a property is declared with an
 * interface or abstract type that cannot be directly instantiated.
 *
 * Example: `@DeserializeInterface(CompanyImpl::class) val company: Company`
 *
 * @property targetClass The concrete implementation class to use.
 */
@Target(AnnotationTarget.PROPERTY)
annotation class DeserializeInterface(val targetClass: KClass<out Any>)

/**
 * Annotation to specify a custom [ValueSerializer] for a property. This allows
 * fine-grained control over how a specific property is serialized and
 * deserialized without altering global behavior.
 *
 * Example: `@CustomSerializer(DateSerializer::class) val created: Date`
 *
 * @property serializerClass The KClass of the serializer implementation.
 */
@Target(AnnotationTarget.PROPERTY)
annotation class CustomSerializer(val serializerClass: KClass<out ValueSerializer<*>>)
