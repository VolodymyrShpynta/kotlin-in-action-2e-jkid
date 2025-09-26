package kia.jkid.deserialization

import kia.jkid.DeserializeInterface
import kia.jkid.JsonName
import kia.jkid.ValueSerializer
import kia.jkid.serialization.getSerializer
import kia.jkid.serializerForType
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

/**
 * Central cache storing reflective metadata ([ClassInfo]) for Kotlin classes involved in
 * deserialization. This avoids repeating relatively expensive reflective lookups
 * (primary constructor, parameters, annotations, serializers) for every object parsed.
 *
 * The cache key is the Kotlin [KClass]; values are created lazily on first access.
 */
class ClassInfoCache {
    private val cacheData = mutableMapOf<KClass<*>, ClassInfo<*>>()

    /**
     * Returns the [ClassInfo] for the given class, creating and caching it if necessary.
     *
     * @param cls the Kotlin class whose metadata should be retrieved
     * @return cached (or newly created) [ClassInfo] instance
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(cls: KClass<T>): ClassInfo<T> =
            cacheData.getOrPut(cls) { ClassInfo(cls) } as ClassInfo<T>
}

/**
 * Immutable snapshot of all reflection / annotation data needed to instantiate a class
 * from a JSON object:
 *
 * 1. Maps JSON names (possibly overridden with [JsonName]) to constructor [KParameter]s.
 * 2. Associates parameters with a custom [ValueSerializer] if one is declared via property annotations.
 * 3. Records a concrete target implementation class for interface properties marked with [DeserializeInterface].
 * 4. Validates argument presence and nullability before instantiation.
 *
 * Instances are meant to be reused; all preprocessing work is done eagerly in [init].
 *
 * @param T concrete class type represented by this metadata
 * @property className fully qualified name (used only for error reporting)
 */
class ClassInfo<T : Any>(cls: KClass<T>) {
    private val className = cls.qualifiedName
    private val constructor = cls.primaryConstructor
            ?: throw JKidException("Class ${cls.qualifiedName} doesn't have a primary constructor")

    /** Map: effective JSON property name -> primary constructor parameter. */
    private val jsonNameToParamMap = hashMapOf<String, KParameter>()
    /** Map: constructor parameter -> serializer to use (if any). */
    private val paramToSerializerMap = hashMapOf<KParameter, ValueSerializer<out Any?>>()
    /** Map: effective JSON property name -> concrete class for interface/abstract property deserialization. */
    private val jsonNameToDeserializeClassMap = hashMapOf<String, KClass<out Any>? >()

    init {
        constructor.parameters.forEach { cacheDataForParameter(cls, it) }
    }

    /**
     * Gathers and stores metadata for a single primary constructor parameter:
     * - Finds matching property by name.
     * - Resolves effective JSON name (respects [JsonName]).
     * - Resolves target class for interface properties (via [DeserializeInterface]).
     * - Determines a custom serializer if one is declared, falling back to a generic serializer for the parameter type.
     */
    private fun cacheDataForParameter(cls: KClass<*>, param: KParameter) {
        val paramName = param.name
                ?: throw JKidException("Class $className has constructor parameter without name")

        val property = cls.declaredMemberProperties.find { it.name == paramName } ?: return
        val name = property.findAnnotation<JsonName>()?.name ?: paramName
        jsonNameToParamMap[name] = param

        val deserializeClass = property.findAnnotation<DeserializeInterface>()?.targetClass
        jsonNameToDeserializeClassMap[name] = deserializeClass

        val valueSerializer = property.getSerializer()
            ?: serializerForType(param.type)
                ?: return
        paramToSerializerMap[param] = valueSerializer
    }

    /**
     * Resolves a primary constructor parameter by (effective) JSON property name.
     * @throws JKidException if no parameter with that JSON name exists.
     */
    fun getConstructorParameter(propertyName: String): KParameter = jsonNameToParamMap[propertyName]
            ?: throw JKidException("Constructor parameter $propertyName is not found for class $className")

    /** Returns a concrete implementation class specified for an interface/abstract property (may be null). */
    fun getDeserializeClass(propertyName: String) = jsonNameToDeserializeClassMap[propertyName]

    /**
     * Converts a raw JSON value into a constructor argument value for the given parameter.
     * If a custom serializer is registered it is used; otherwise basic type / nullability
     * validation is performed.
     */
    fun deserializeConstructorArgument(param: KParameter, value: Any?): Any? {
        val serializer = paramToSerializerMap[param]
        if (serializer != null) return serializer.fromJsonValue(value)

        validateArgumentType(param, value)
        return value
    }

    /**
     * Ensures the provided value is acceptable for the given parameter w.r.t. nullability
     * and (rough) type matching. Throws [JKidException] on mismatch.
     */
    private fun validateArgumentType(param: KParameter, value: Any?) {
        println("Validating $param with value $value")
        if (value == null && !param.type.isMarkedNullable) {
            throw JKidException("Received null value for non-null parameter ${param.name}")
        }
        if (value != null && value.javaClass != param.type.javaType) {
            throw JKidException("Type mismatch for parameter ${param.name}: " +
                    "expected ${param.type.javaType}, found ${value.javaClass}")
        }
    }

    /**
     * Creates a new instance of [T] by invoking the primary constructor with the provided arguments.
     * @throws JKidException if a required (non-null, non-optional) parameter is missing.
     */
    fun createInstance(arguments: Map<KParameter, Any?>): T {
        ensureAllParametersPresent(arguments)
        return constructor.callBy(arguments)
    }

    /**
     * Verifies that all required constructor parameters are present (or nullable/optional).
     * @throws JKidException if a required parameter is missing.
     */
    private fun ensureAllParametersPresent(arguments: Map<KParameter, Any?>) {
        for (param in constructor.parameters) {
            if (arguments[param] == null && !param.isOptional && !param.type.isMarkedNullable) {
                throw JKidException("Missing value for parameter ${param.name}")
            }
        }
    }
}

/** General-purpose exception type used for errors encountered during serialization/deserialization. */
class JKidException(message: String) : Exception(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
