package exercise

import kia.jkid.deserialization.JKidException
import kia.jkid.deserialization.deserialize
import kia.jkid.serialization.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

data class BookStore(val bookPrice: Map<String, Double>)

enum class Genre { FICTION, HISTORY }

data class Inventory(val byGenre: Map<Genre, Int>)

data class BookStoreNumberKey(val bookPrice: Map<Int, Double>)

data class PersonValue(val name: String, val age: Int)

data class PeopleById(val people: Map<Int, PersonValue>)

data class Flags(val flags: Map<Boolean, Int>)

data class Nested(val layers: Map<String, Map<String, Int>>)

data class Tags(val tags: Map<String, List<Int>>)

data class BadKeyMap(val data: Map<List<Int>, String>) // Unsupported key type

data class BadIntKey(val data: Map<Int, String>)

data class BoolMap(val data: Map<Boolean, String>)

class MapTest {
    private val bookStore = BookStore(mapOf("Catch-22" to 10.92, "The Lord of the Rings" to 11.49))
    private val json = """{"bookPrice": {"Catch-22": 10.92, "The Lord of the Rings": 11.49}}"""

    @Test
    fun testSerialization() {
        println(serialize(bookStore))
        assertEquals(json, serialize(bookStore))
    }

    @Test
    fun testSerializationNumberKey() {
        val store = BookStoreNumberKey(mapOf(1 to 10.0, 2 to 11.5))
        val expected = """{"bookPrice": {"1": 10.0, "2": 11.5}}"""
        assertEquals(expected, serialize(store))
    }

    @Test
    fun testSerializationEnumKey() {
        val inventory = Inventory(mapOf(Genre.FICTION to 5, Genre.HISTORY to 3))
        val expected = """{"byGenre": {"FICTION": 5, "HISTORY": 3}}"""
        assertEquals(expected, serialize(inventory))
    }

    @Test
    fun testSerializationObjectValues() {
        val people = PeopleById(mapOf(1 to PersonValue("Alice", 30), 2 to PersonValue("Bob", 25)))
        // Reflection returns properties in a deterministic order (often alphabetical), producing age before name.
        val expected = """{"people": {"1": {"age": 30, "name": "Alice"}, "2": {"age": 25, "name": "Bob"}}}"""
        assertEquals(expected, serialize(people))
    }

    @Test
    fun testDeserialization() {
        assertEquals(bookStore, deserialize(json))
    }

    @Test
    fun deserializeIntDoubleMap() {
        val json = """{"bookPrice": {"1": 10.0, "2": 11.5}}"""
        val expected = BookStoreNumberKey(mapOf(1 to 10.0, 2 to 11.5))
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun deserializeEnumIntMap() {
        val json = """{"byGenre": {"FICTION": 5, "HISTORY": 3}}"""
        val expected = Inventory(mapOf(Genre.FICTION to 5, Genre.HISTORY to 3))
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun deserializeIntObjectValueMap() {
        val json = """{"people": {"1": {"age": 30, "name": "Alice"}, "2": {"age": 25, "name": "Bob"}}}"""
        val expected = PeopleById(mapOf(1 to PersonValue("Alice", 30), 2 to PersonValue("Bob", 25)))
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun deserializeBooleanKeyMap() {
        val json = """{"flags": {"true": 1, "false": 2}}"""
        val expected = Flags(mapOf(true to 1, false to 2))
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun deserializeNestedMap() {
        val json = """{"layers": {"outer": {"inner1": 1, "inner2": 2}}}"""
        val expected = Nested(mapOf("outer" to mapOf("inner1" to 1, "inner2" to 2)))
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun deserializeMapWithListValues() {
        val json = """{"tags": {"a": [1, 2], "b": []}}"""
        val expected = Tags(mapOf("a" to listOf(1, 2), "b" to emptyList()))
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun deserializeEmptyMap() {
        val json = """{"bookPrice": {}}"""
        val expected = BookStore(emptyMap())
        assertEquals(expected, deserialize(json))
    }

    @Test
    fun failOnUnsupportedKeyType() {
        val json = """{"data": {"[1,2]": "x"}}""" // List<Int> key not supported
        assertFailsWith<JKidException> { deserialize<BadKeyMap>(json) }
    }

    @Test
    fun failOnInvalidIntKey() {
        val json = """{"data": {"01x": "oops"}}"""
        assertFailsWith<JKidException> { deserialize<BadIntKey>(json) }
    }

    @Test
    fun failOnInvalidBooleanKey() {
        val json = """{"data": {"True": "oops"}}""" // Should be lowercase true/false
        assertFailsWith<JKidException> { deserialize<BoolMap>(json) }
    }
}
