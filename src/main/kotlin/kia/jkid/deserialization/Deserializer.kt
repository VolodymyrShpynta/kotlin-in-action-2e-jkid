package kia.jkid.deserialization

import kia.jkid.isPrimitiveOrString
import kia.jkid.serializerForBasicType
import java.io.Reader
import java.io.StringReader
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType

/**
 * Top-level entry points and internal machinery for turning JSON text into Kotlin objects.
 *
 * The deserialization process is staged:
 * 1. A root [Seed] implementation (usually an [ObjectSeed]) is created for the target class.
 * 2. A streaming [Parser] walks through the JSON and calls back into the active [Seed] hierarchy.
 * 3. Each [Seed] collects primitive values or creates nested seeds for objects / arrays.
 * 4. After parsing finishes, [Seed.spawn] materializes the actual object graph (invoking constructors).
 *
 * Lists are treated specially: we distinguish between lists of primitive values ([ValueListSeed]) and
 * lists of objects ([ObjectListSeed]). Type information (possibly overridden via annotations) drives
 * which seed type is created.
 */

/**
 * Deserialize the provided JSON string into an instance of type [T].
 *
 * @param T target Kotlin type (must be a non-nullable class type)
 * @param json JSON document as a string
 * @return a fully constructed instance of [T]
 * @throws JKidException if JSON structure does not match the target type requirements
 */
inline fun <reified T: Any> deserialize(json: String): T {
    return deserialize(StringReader(json))
}

/**
 * Deserialize the JSON provided by the given [Reader] into an instance of [T].
 * A new reflection [ClassInfoCache] is created per invocation (cheap for small graphs, but could
 * be reused if performance is critical).
 *
 * @param T target Kotlin type (must be a non-nullable class type)
 * @param json JSON reader supplying the document
 */
inline fun <reified T: Any> deserialize(json: Reader): T {
    return deserialize(json, T::class)
}

/**
 * Deserialize the JSON from [json] into an instance of [targetClass].
 *
 * @param json JSON reader supplying the document
 * @param targetClass runtime class literal of the desired target type
 * @return constructed instance of [T]
 * @throws JKidException when structure / types are incompatible
 */
fun <T: Any> deserialize(json: Reader, targetClass: KClass<T>): T {
    val seed = ObjectSeed(targetClass, ClassInfoCache())
    Parser(json, seed).parse()
    return seed.spawn()
}

/**
 * A neutral JSON object builder used by the parser. Implementations accept primitive values
 * or request nested JSON objects / arrays to be built.
 */
interface JsonObject {
    /**
     * Sets a primitive (string / number / boolean / null) value for the given property.
     */
    fun setSimpleProperty(propertyName: String, value: Any?)

    /**
     * Creates (or returns) a nested JSON object seed for the given property name.
     */
    fun createObject(propertyName: String): JsonObject

    /**
     * Creates (or returns) a nested JSON array seed for the given property name.
     */
    fun createArray(propertyName: String): JsonObject
}

/**
 * A [JsonObject] that knows how to eventually produce a Kotlin value (object, list, primitive list)
 * once parsing has finished.
 */
interface Seed: JsonObject {
    /** Shared reflection cache for class metadata & annotations. */
    val classInfoCache: ClassInfoCache

    /**
     * Finalize and produce the runtime value represented by this seed (may return null for primitives).
     */
    fun spawn(): Any?

    /**
     * Create a nested composite (object or list) property seed.
     *
     * @param propertyName name in JSON / constructor
     * @param isList whether the requested structure is an array
     */
    fun createCompositeProperty(propertyName: String, isList: Boolean): JsonObject

    override fun createObject(propertyName: String) = createCompositeProperty(propertyName, false)

    override fun createArray(propertyName: String) = createCompositeProperty(propertyName, true)
}

/**
 * Determine if [type] is a (possibly generic) subtype of [List]. Internal helper.
 */
private fun isSubtypeOfList(type: KType): Boolean {
    val listType: KType = List::class.starProjectedType
    return type.isSubtypeOf(listType)
}

/**
 * Extract the single generic type parameter (e.g., T from List<T>). Assumes exactly one argument.
 */
private fun getParameterizedType(type: KType): KType {
    return type.arguments.single().type!!
}

/**
 * Factory helper for creating an appropriate [Seed] instance for a given parameter type.
 *
 * Handles:
 * - Lists of primitives -> [ValueListSeed]
 * - Lists of objects -> [ObjectListSeed]
 * - Plain objects -> [ObjectSeed]
 *
 * @param paramType the (possibly annotated / substituted) Kotlin type for the property
 * @param isList whether the parser context expects a JSON array at this point
 * @throws JKidException when array vs object expectations mismatch or unsupported shapes are found
 */
fun Seed.createSeedForType(paramType: KType, isList: Boolean): Seed {
    val paramClass = paramType.classifier as KClass<out Any>
    if (isSubtypeOfList(paramType)) {
        println("It's a list!")
        if (!isList) throw JKidException("An array expected, not a composite object")

        val elementType = getParameterizedType(paramType)
        if (elementType.isPrimitiveOrString()) {
            return ValueListSeed(elementType, classInfoCache)
        }
        return ObjectListSeed(elementType, classInfoCache)
    }
    if (isList) throw JKidException("Object of the type $paramType expected, not an array")
    return ObjectSeed(paramClass, classInfoCache)
}

/**
 * Seed that accumulates constructor arguments for a Kotlin class and eventually instantiates it.
 *
 * @param T target class type
 * @property classInfoCache shared cache for looking up [ClassInfo]
 */
class ObjectSeed<out T: Any>(
        targetClass: KClass<T>,
        override val classInfoCache: ClassInfoCache
) : Seed {

    private val classInfo: ClassInfo<T> = classInfoCache[targetClass]

    /** Primitive / simple argument values keyed by constructor parameter. */
    private val valueArguments = mutableMapOf<KParameter, Any?>()
    /** Nested object/list arguments captured as child seeds. */
    private val seedArguments = mutableMapOf<KParameter, Seed>()

    /** Merge primitive and spawned composite arguments into a single map. */
    private val arguments: Map<KParameter, Any?>
        get() = valueArguments + seedArguments.mapValues { it.value.spawn() }

    /** Store a primitive (or already converted) value for a constructor parameter. */
    override fun setSimpleProperty(propertyName: String, value: Any?) {
        val param = classInfo.getConstructorParameter(propertyName)
        valueArguments[param] = classInfo.deserializeConstructorArgument(param, value)
    }

    /** Create a child seed (object or list), possibly honoring a @Deserialize annotation override. */
    override fun createCompositeProperty(propertyName: String, isList: Boolean): Seed {
        val param = classInfo.getConstructorParameter(propertyName)
        val deserializeAs = classInfo.getDeserializeClass(propertyName)?.starProjectedType
        val seed = createSeedForType(
            deserializeAs ?: param.type, isList
        )
        return seed.apply { seedArguments[param] = this }
    }

    /** Instantiate the target class using the collected constructor arguments. */
    override fun spawn(): T = classInfo.createInstance(arguments)
}

/**
 * Seed for a list whose elements are JSON objects (each realized through its own [Seed]).
 *
 * @property elementType Kotlin type of each element in the list
 */
class ObjectListSeed(
    val elementType: KType,
        override val classInfoCache: ClassInfoCache
) : Seed {
    private val elements = mutableListOf<Seed>()

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        throw JKidException("Found primitive value in collection of object types")
    }

    override fun createCompositeProperty(propertyName: String, isList: Boolean) =
            createSeedForType(elementType, isList).apply { elements.add(this) }

    /** Produce the final list by spawning all collected element seeds. */
    override fun spawn(): List<*> = elements.map { it.spawn() }
}

/**
 * Seed for a list of primitive values (numbers, strings, booleans, nulls) converted via a serializer.
 */
class ValueListSeed(
    elementType: KType,
        override val classInfoCache: ClassInfoCache
) : Seed {
    private val elements = mutableListOf<Any?>()
    private val serializerForType = serializerForBasicType(elementType)

    /** Add a primitive element (converted through a registered serializer). */
    override fun setSimpleProperty(propertyName: String, value: Any?) {
        elements.add(serializerForType.fromJsonValue(value))
    }

    override fun createCompositeProperty(propertyName: String, isList: Boolean): Seed {
        throw JKidException("Found object value in collection of primitive types")
    }

    /** Return the accumulated primitive list. */
    override fun spawn() = elements
}