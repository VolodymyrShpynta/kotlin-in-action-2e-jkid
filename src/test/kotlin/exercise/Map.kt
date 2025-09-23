package exercise

import kia.jkid.deserialization.deserialize
import kia.jkid.serialization.serialize
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

data class BookStore(val bookPrice: Map<String, Double>)

enum class Genre { FICTION, HISTORY }

data class Inventory(val byGenre: Map<Genre, Int>)

data class BookStoreNumberKey(val bookPrice: Map<Int, Double>)

data class PersonValue(val name: String, val age: Int)

data class PeopleById(val people: Map<Int, PersonValue>)

class MapTest {
    private val bookStore = BookStore(mapOf("Catch-22" to 10.92, "The Lord of the Rings" to 11.49))
    private val json = """{"bookPrice": {"Catch-22": 10.92, "The Lord of the Rings": 11.49}}"""

    @Test fun testSerialization() {
        println(serialize(bookStore))
        assertEquals(json, serialize(bookStore))
    }

    @Test fun testSerializationNumberKey() {
        val store = BookStoreNumberKey(mapOf(1 to 10.0, 2 to 11.5))
        val expected = """{"bookPrice": {"1": 10.0, "2": 11.5}}"""
        assertEquals(expected, serialize(store))
    }

    @Test fun testSerializationEnumKey() {
        val inventory = Inventory(mapOf(Genre.FICTION to 5, Genre.HISTORY to 3))
        val expected = """{"byGenre": {"FICTION": 5, "HISTORY": 3}}"""
        assertEquals(expected, serialize(inventory))
    }

    @Test fun testSerializationObjectValues() {
        val people = PeopleById(mapOf(1 to PersonValue("Alice", 30), 2 to PersonValue("Bob", 25)))
        // Reflection returns properties in a deterministic order (often alphabetical), producing age before name.
        val expected = """{"people": {"1": {"age": 30, "name": "Alice"}, "2": {"age": 25, "name": "Bob"}}}"""
        assertEquals(expected, serialize(people))
    }

    @Test fun testDeserialization() {
        assertEquals(bookStore, deserialize(json))
    }
}
