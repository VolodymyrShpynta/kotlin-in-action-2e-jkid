package kia.jkid.deserialization

import kia.jkid.isPrimitiveOrString
import kia.jkid.serializerForBasicType
import java.io.Reader
import java.io.StringReader
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.starProjectedType

/**
 * Top-level entry points and internal machinery for turning JSON text into Kotlin objects.
 *
 * Stages:
 * 1. A root [Seed] (usually [ObjectSeed]) is created for the target class.
 * 2. A streaming [Parser] walks tokens and delegates to active [Seed]s.
 * 3. Seeds accumulate primitive values or nested child seeds (objects / lists / maps).
 * 4. After parsing, [Seed.spawn] materializes the object graph.
 *
 * Collections:
 *  - Lists: handled by [ValueListSeed] (primitive elements) and [ObjectListSeed] (object / composite elements).
 *  - Maps: handled by [ValueMapSeed] (primitive values) and [ObjectMapSeed] (object / composite values). Keys must be
 *          simple (String, Number, Boolean, Enum). Complex key objects are not supported (mirrors serializer rules).
 *
 * Design & limitations:
 *  - No detection of cyclic references (recursion will overflow the stack for cycles).
 *  - Only `List` and `Map` are recognized as collection types; `Set`, `Mutable*` variants, etc. are not.
 *  - Map keys are always sourced from JSON object field names (JSON syntax only allows string keys).
 *  - Structured (non-simple) keys are rejected early to avoid ambiguous conversions.
 */

/**
 * Deserialize the provided JSON string into an instance of type [T].
 *
 * @param T target Kotlin type (must be a non-nullable class type)
 * @param json JSON document as a string
 * @return a fully constructed instance of [T]
 * @throws JKidException if JSON structure does not match the target type requirements
 */
inline fun <reified T : Any> deserialize(json: String): T {
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
inline fun <reified T : Any> deserialize(json: Reader): T {
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
fun <T : Any> deserialize(json: Reader, targetClass: KClass<T>): T {
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
interface Seed : JsonObject {
    /** Shared reflection cache for class metadata & annotations. */
    val classInfoCache: ClassInfoCache

    /**
     * Finalizes and produces the runtime value represented by this seed.
     * For composite structures this recursively spawns child seeds; for primitive collections
     * it returns the accumulated values directly.
     */
    fun spawn(): Any?

    /**
     * Create a nested composite (object or list) property seed.
     *
     * @param propertyName name in JSON / constructor
     * @param isArray whether the requested structure is an array
     */
    fun createPropertySeed(propertyName: String, isArray: Boolean): JsonObject

    override fun createObject(propertyName: String) = createPropertySeed(propertyName, false)

    override fun createArray(propertyName: String) = createPropertySeed(propertyName, true)
}

/**
 * Determine if [type] is a (possibly generic) subtype of [List]. Internal helper.
 */
private fun isSubtypeOfList(type: KType): Boolean = type.isSubtypeOf(List::class.starProjectedType)

/**
 * Determine if [type] is a (possibly generic) subtype of [Map]. Internal helper.
 */
private fun isSubtypeOfMap(type: KType): Boolean = type.isSubtypeOf(Map::class.starProjectedType)

/**
 * Extract the single generic type parameter (e.g., T from List<T>). Assumes exactly one argument.
 */
private fun getSingleTypeArg(type: KType): KType = type.arguments.single().type!!

/**
 * Get the key type of map (e.g., T from Map<T, V>).
 */
private fun getMapKeyType(type: KType): KType =
    type.arguments[0].type!!
        .also {
            if (!isSimpleMapKeyType(it)) {
                throw JKidException("Unsupported map key type ${it.classifier}. Only String/Number/Boolean/Enum keys are supported.")
            }
        }

/**
 * Get the value type of map (e.g., V from Map<T, V>).
 */
private fun getMapValueType(type: KType): KType = type.arguments[1].type!!

/** Determine if [type] is an enum type. */
private fun isEnumType(type: KType): Boolean = (type.classifier as? KClass<*>)?.isSubclassOf(Enum::class) == true

/** Determine if [type] is a simple map key type (String, Number, Boolean, Enum). */
private fun isSimpleMapKeyType(type: KType): Boolean = type.isPrimitiveOrString() || isEnumType(type)

/**
 * Factory creating an appropriate [Seed] for the supplied (possibly annotated) [paramType].
 *
 * @param paramType Kotlin reflection type of the target property / constructor parameter (post-@Deserialize override).
 * @param isList Indicates the JSON shape at this position: true if the parser just saw a '[' and
 *               expects an array, false if it saw '{' or is resolving an object property.
 *
 * Behavior:
 *  - If [paramType] is a subtype of `List<*>` then an array JSON shape is required. The element type's
 *    classification (primitive vs object) chooses [ValueListSeed] vs [ObjectListSeed].
 *  - If [paramType] is a subtype of `Map<*, *>` a JSON *object* shape is required (not a JSON array).
 *    The value type classification picks [ValueMapSeed] vs [ObjectMapSeed]. Keys are validated to be simple.
 *  - Otherwise a plain [ObjectSeed] is produced (still represented by a JSON object in the input).
 *
 * Validation:
 *  - Shape mismatches (expecting array vs object) raise a [JKidException] with a clear message.
 *  - Unsupported map key types raise immediately; we fail fast before descending into values.
 *
 * Note: The boolean is named `isList` for historical reasons – it really means "JSON array expected here".
 */
fun Seed.createSeedForType(paramType: KType, isList: Boolean): Seed {
    val paramClass = paramType.classifier as KClass<out Any>
    if (isSubtypeOfList(paramType)) {
        if (!isList) throw JKidException("An array expected, not a composite object")
        val elementType = getSingleTypeArg(paramType)
        return if (elementType.isPrimitiveOrString()) ValueListSeed(elementType, classInfoCache)
        else ObjectListSeed(elementType, classInfoCache)
    }
    if (isSubtypeOfMap(paramType)) {
        if (isList) throw JKidException("Object of the type $paramType expected, not an array")
        val keyType = getMapKeyType(paramType)
        val valueType = getMapValueType(paramType)
        return if (valueType.isPrimitiveOrString()) ValueMapSeed(keyType, valueType, classInfoCache)
        else ObjectMapSeed(keyType, valueType, classInfoCache)
    }
    if (isList) throw JKidException("Object of the type $paramType expected, not an array")
    return ObjectSeed(paramClass, classInfoCache)
}

/**
 * Collects constructor arguments for a Kotlin class instance. Primitive values are stored directly;
 * nested objects / arrays / maps are delegated to child [Seed] instances tracked in [seedArguments].
 * When [spawn] is invoked, all child seeds are spawned and merged with primitive arguments, then the
 * reflective constructor is called.
 * @param T target class type
 * @property classInfoCache shared cache for looking up [ClassInfo]
 */
class ObjectSeed<out T : Any>(
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
    override fun createPropertySeed(propertyName: String, isArray: Boolean): Seed {
        val param = classInfo.getConstructorParameter(propertyName)
        val deserializeAs = classInfo.getDeserializeClass(propertyName)?.starProjectedType
        return createSeedForType(deserializeAs ?: param.type, isArray)
            .also { seedArguments[param] = it }
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
    private val elementType: KType,
    override val classInfoCache: ClassInfoCache
) : Seed {
    private val elements = mutableListOf<Seed>()

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        throw JKidException("Found primitive value in collection of object types")
    }

    override fun createPropertySeed(propertyName: String, isArray: Boolean) =
        createSeedForType(elementType, isArray)
            .also { elements.add(it) }

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

    override fun createPropertySeed(propertyName: String, isArray: Boolean): Seed {
        throw JKidException("Found object value in collection of primitive types")
    }

    /** Return the accumulated primitive list. */
    override fun spawn(): List<Any?> = elements
}

/** Map with primitive/simple values. */
class ValueMapSeed(
    private val keyType: KType,
    private val valueType: KType,
    override val classInfoCache: ClassInfoCache
) : Seed {
    private val map = linkedMapOf<Any, Any?>()
    private val valueSerializer = serializerForBasicType(valueType)

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        val key = convertKey(propertyName)
        map[key] = valueSerializer.fromJsonValue(value)
    }

    override fun createPropertySeed(propertyName: String, isArray: Boolean): Seed {
        throw JKidException("Found composite value in map of primitive values")
    }

    /** Return the accumulated map. */
    override fun spawn(): Map<Any, Any?> = map

    private fun convertKey(raw: String): Any = convertSimpleKey(raw, keyType)
}

/** Map with object / composite values. */
class ObjectMapSeed(
    private val keyType: KType,
    private val valueType: KType,
    override val classInfoCache: ClassInfoCache
) : Seed {
    private val map = linkedMapOf<Any, Seed>()

    override fun setSimpleProperty(propertyName: String, value: Any?) {
        throw JKidException("Found primitive value in map of object values")
    }

    override fun createPropertySeed(propertyName: String, isArray: Boolean): Seed {
        val key = convertSimpleKey(propertyName, keyType)
        return createSeedForType(valueType, isArray)
            .also { map[key] = it }
    }

    /** Return the accumulated map. */
    override fun spawn(): Map<Any, Any?> = map.mapValues { it.value.spawn() }
}

/**
 * Attempts the given [convert] function and throws a [JKidException] with a consistent message if it fails.
 * Used to keep numeric / boolean key parsing branches concise.
 */
private inline fun <T> String.parseOrFail(typeName: String, convert: (String) -> T?): T =
    convert(this) ?: throw JKidException("Invalid $typeName map key '$this'")

/**
 * Converts a raw JSON object field name into a strongly-typed map key. Supported key target types:
 *  - String (identity)
 *  - Integral / floating numeric primitives (via `toXxxOrNull` with validation)
 *  - Boolean (strict: only "true" or "false")
 *  - Enum (exact constant name match)
 *
 * Error modes:
 *  - Malformed lexeme -> descriptive [JKidException] (e.g. "Invalid Int map key '01x'").
 *  - Enum mismatch -> lists enum type in message.
 *  - Unsupported target type -> early failure (should not happen if pre-validated in [getMapKeyType]).
 *
 * Rationale for strict boolean parsing: avoids silently accepting ambiguous keys like "True" / "1".
 */
private fun convertSimpleKey(raw: String, keyType: KType): Any {
    return when (val kClass = keyType.classifier as KClass<*>) {
        String::class -> raw
        Int::class -> raw.parseOrFail("Int") { it.toIntOrNull() }
        Long::class -> raw.parseOrFail("Long") { it.toLongOrNull() }
        Short::class -> raw.parseOrFail("Short") { it.toShortOrNull() }
        Byte::class -> raw.parseOrFail("Byte") { it.toByteOrNull() }
        Float::class -> raw.parseOrFail("Float") { it.toFloatOrNull() }
        Double::class -> raw.parseOrFail("Double") { it.toDoubleOrNull() }
        Boolean::class -> raw.parseOrFail("Boolean") { it.toBooleanStrictOrNull() }
        else -> if (kClass.isSubclassOf(Enum::class)) {
            enumValueOfOrNull(kClass, raw)
                ?: throw JKidException("Invalid enum key '$raw' for ${kClass.simpleName}")
        } else {
            throw JKidException("Unsupported map key type ${kClass.qualifiedName}")
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun enumValueOfOrNull(enumKClass: KClass<*>, name: String): Any? =
    (enumKClass.java.enumConstants as Array<Enum<*>>?)
        ?.firstOrNull { it.name == name }
