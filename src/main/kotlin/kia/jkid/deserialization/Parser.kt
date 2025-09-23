package kia.jkid.deserialization

import java.io.Reader

/**
 * Streaming, single-use JSON parser that builds an in-memory representation
 * rooted at the supplied [rootObject]. The parser performs a single forward
 * pass over the character [Reader] using a [Lexer] to obtain tokens and
 * constructs nested objects / arrays by delegating to the provided
 * [JsonObject] API (e.g. `createObject`, `createArray`, `setSimpleProperty`).
 *
 * Design notes:
 * - This parser is intentionally minimal and assumes well‑formed JSON with
 *   standard structural tokens `{`, `}`, `[`, `]`, `:` and `,`.
 * - Errors in structure (missing commas, unexpected tokens, premature end)
 *   are reported via [MalformedJSONException] or [IllegalArgumentException].
 * - The supplied [rootObject] is mutated; no new root is created.
 *
 * Usage:
 * ```kotlin
 * val root = SomeJsonObjectImpl()
 * Parser(reader, root).parse()
 * // root now populated
 * ```
 *
 * Thread-safety: not thread-safe. Create a new instance per parse.
 *
 * @param reader Source of JSON text. It is not closed by this parser.
 * @param rootObject The mutable root container to populate.
 */
class Parser(reader: Reader, val rootObject: JsonObject) {
    private val lexer = Lexer(reader)

    /**
     * Parses the entire JSON document starting at the root object.
     *
     * Expected top-level structure is a single JSON object (must begin with `{`).
     * After the root object is processed, any remaining tokens trigger an error.
     *
     * @throws IllegalArgumentException If the first token is not `{`, if there are
     *   trailing tokens after a complete object, or if the input ends prematurely.
     * @throws MalformedJSONException If a structural problem is detected inside
     *   nested parsing (delegated from helper methods).
     */
    fun parse() {
        expect(Token.LBRACE)
        parseObjectBody(rootObject)
        if (lexer.nextToken() != null) {
            throw IllegalArgumentException("Too many tokens")
        }
    }

    /**
     * Parses the body of an object assuming the opening `{` has already been consumed
     * and stops upon encountering the matching `}`.
     *
     * Object members are processed as a comma-separated list of:
     * "stringKey" : value
     *
     * @param jsonObject The target object to receive parsed properties.
     * @throws MalformedJSONException If a key string, colon, or value token is malformed
     *   or missing, or if commas / closing brace are not in expected positions.
     */
    private fun parseObjectBody(jsonObject: JsonObject) {
        parseCommaSeparated(Token.RBRACE) { token ->
            if (token !is Token.StringValue) {
                throw MalformedJSONException("Unexpected token $token")
            }

            val propName = token.value
            expect(Token.COLON)
            parsePropertyValue(jsonObject, propName, nextToken())
        }
    }

    /**
     * Parses the elements of an array property. The opening `[` must have been
     * consumed by the caller; parsing stops at the corresponding closing `]`.
     * Each element becomes either a simple value appended to the array or a
     * nested object/array created via the [JsonObject] API.
     *
     * @param currentObject The parent object containing the array property.
     * @param propName The property name of the array being populated.
     * @throws MalformedJSONException If array element separation (commas) or
     *   structure is invalid.
     */
    private fun parseArrayBody(currentObject: JsonObject, propName: String) {
        parseCommaSeparated(Token.RBRACKET) { token ->
            parsePropertyValue(currentObject, propName, token)
        }
    }

    /**
     * Utility for parsing a comma-separated sequence of homogeneous structural
     * elements until a terminating [stopToken] (either `}` or `]`).
     * The first element is read immediately (no leading comma required). After
     * each element, a comma is expected unless the next token is [stopToken].
     *
     * @param stopToken Token that ends the sequence (e.g. [Token.RBRACE], [Token.RBRACKET]).
     * @param body Invoked for each element's first token (string key for objects
     *   or initial value token for arrays).
     * @throws MalformedJSONException If commas are missing or unexpected tokens appear.
     * @throws IllegalArgumentException If the input ends before [stopToken].
     */
    private fun parseCommaSeparated(stopToken: Token, body: (Token) -> Unit) {
        var expectComma = false
        while (true) {
            var token = nextToken()
            if (token == stopToken) return
            if (expectComma) {
                if (token != Token.COMMA) throw MalformedJSONException("Expected comma")
                token = nextToken()
            }

            body(token)

            expectComma = true
        }
    }

    /**
     * Dispatches handling of a property (for objects) or element (for arrays)
     * based on the encountered [token]. For scalar values it stores them
     * directly; for `{` or `[` it creates and recursively populates child
     * containers via [JsonObject].
     *
     * @param currentObject The object currently being built (owner of the property / array).
     * @param propName The property name (for arrays this is the array's property name for all elements).
     * @param token The first token of the value.
     * @throws MalformedJSONException If the token is not a valid start of a JSON value.
     */
    private fun parsePropertyValue(currentObject: JsonObject, propName: String, token: Token) {
        when (token) {
            is Token.ValueToken ->
                currentObject.setSimpleProperty(propName, token.value)

            Token.LBRACE -> {
                val childObj = currentObject.createObject(propName)
                parseObjectBody(childObj)
            }

            Token.LBRACKET -> {
                val childObj = currentObject.createArray(propName)
                parseArrayBody(childObj, propName)
            }

            else ->
                throw MalformedJSONException("Unexpected token $token")
        }
    }

    /**
     * Consumes the next token and verifies it matches [token]; otherwise throws.
     *
     * @throws IllegalArgumentException If the next token differs or input ends early.
     */
    private fun expect(token: Token) {
        if (lexer.nextToken() != token) {
            throw IllegalArgumentException("$token expected")
        }
    }

    /**
     * Retrieves the next token from the lexer or throws if no further tokens exist.
     * This ensures calling code need not handle null (end-of-stream) directly.
     *
     * @return The next [Token].
     * @throws IllegalArgumentException If end of data is reached unexpectedly.
     */
    private fun nextToken(): Token = lexer.nextToken() ?: throw IllegalArgumentException("Premature end of data")
}
