package kia.jkid.deserialization

import java.io.Reader

/**
 * Represents a lexical token produced by [Lexer] while scanning JSON input.
 *
 * The JSON grammar this lexer supports is a pragmatic subset tailored for the library:
 * - Structural tokens: comma, colon, braces, brackets
 * - Literal tokens: true, false, null
 * - Value tokens: string, number (long / double)
 *
 * Implementations:
 * - Singleton objects for punctuation and structural tokens
 * - [ValueToken] sub-interface for tokens that carry a parsed value
 */
interface Token {
    /** Comma separator token: ',' */
    object COMMA : Token
    /** Name / value separator token: ':' */
    object COLON : Token
    /** Object start token: '{' */
    object LBRACE : Token
    /** Object end token: '}' */
    object RBRACE : Token
    /** Array start token: '[' */
    object LBRACKET : Token
    /** Array end token: ']' */
    object RBRACKET : Token

    /**
     * Marker interface for tokens that encapsulate a scalar value (string, number, boolean, null).
     */
    interface ValueToken : Token {
        /** Parsed primitive value; may be null for [NullValue]. */
        val value: Any?
    }

    /** Token representing the JSON literal `null`. */
    object NullValue : ValueToken {
        override val value: Any?
            get() = null
    }

    /** Token representing a JSON boolean literal (`true` / `false`). */
    data class BoolValue(override val value: Boolean) : ValueToken
    /** Token representing a JSON string value (already unescaped). */
    data class StringValue(override val value: String) : ValueToken
    /** Token representing an integer number that fits in a Kotlin [Long]. */
    data class LongValue(override val value: Long) : ValueToken
    /** Token representing a floating point number, parsed as a Kotlin [Double]. */
    data class DoubleValue(override val value: Double) : ValueToken

    companion object {
        /** Pre-built singleton instance for the JSON literal `true`. */
        val TRUE = BoolValue(true)
        /** Pre-built singleton instance for the JSON literal `false`. */
        val FALSE = BoolValue(false)
    }
}

/**
 * Exception thrown when the input stream cannot be parsed as valid JSON according to the
 * expectations of the lexer (e.g., malformed escape, premature EOF, unexpected character).
 */
class MalformedJSONException(message: String): Exception(message)

/**
 * A small buffered character reader abstraction that exposes lookahead and controlled
 * consumption tailored for tokenizing JSON. It wraps an underlying [Reader] and offers:
 * - Single character lookahead via [peekNext]
 * - Character consumption via [readNext]
 * - Fixed-length read for escape sequences via [readNextChars]
 * - Simple expectation helper via [expectText]
 *
 * It maintains a minimal internal buffer for up to 4 chars used when reading Unicode escapes.
 */
internal class CharReader(val reader: Reader) {
    /** Temporary char buffer used for reading fixed-length sequences (e.g., unicode escapes). */
    private val tokenBuffer = CharArray(4)
    /** Cached next character used to provide lookahead semantics. */
    private var nextChar: Char? = null
    /** True when end-of-file has been reached. */
    var eof = false
        private set

    /**
     * Reads the next character from the underlying reader into [nextChar] unless EOF already reached.
     */
    private fun advance() {
        if (eof) return
        val c = reader.read()
        if (c == -1) {
            eof = true
        }
        else {
            nextChar = c.toChar()
        }
    }

    /**
     * Returns (without consuming) the next character or null if EOF. Performs an underlying read only
     * if necessary to populate [nextChar].
     */
    fun peekNext(): Char? {
        if (nextChar == null) {
            advance()
        }
        return if (eof) null else nextChar
    }

    /**
     * Consumes and returns the next character (or null if EOF). After consumption, the lookahead cache
     * is cleared so a subsequent [peekNext] will fetch a new character.
     */
    fun readNext() = peekNext().apply { nextChar = null }

    /**
     * Reads an exact number of characters directly into an internal buffer and returns them as a string.
     * Used primarily for Unicode escape sequences and fixed literal tails (e.g., "rue" of true).
     *
     * @throws MalformedJSONException if fewer characters than requested are available.
     */
    fun readNextChars(length: Int): String {
        assert(nextChar == null)
        assert(length <= tokenBuffer.size)
        if (reader.read(tokenBuffer, 0, length) != length) {
            throw MalformedJSONException("Premature end of data")
        }
        return String(tokenBuffer, 0, length)
    }

    /**
     * Ensures that the upcoming characters exactly match [text] and that the following character (if present)
     * is either one of the allowed [followedBy] delimiters or EOF. This helps disambiguate literals such as
     * distinguishing `true` from `trueX` which would be invalid.
     */
    fun expectText(text: String, followedBy: Set<Char>) {
        if (readNextChars(text.length) != text) {
            throw MalformedJSONException("Expected text $text")
        }
        val next = peekNext()
        if (next != null && next !in followedBy)
            throw MalformedJSONException("Expected text in $followedBy")
    }
}

/**
 * A simple streaming JSON lexer that converts character input into a sequence of [Token] instances.
 *
 * Responsibilities:
 * - Skips insignificant whitespace
 * - Recognizes structural punctuation tokens
 * - Parses string literals with escape/unicode handling
 * - Parses numbers as [Long] or [Double] depending on presence of a decimal point
 * - Produces singleton tokens for booleans and null
 *
 * Usage pattern:
 * ```kotlin
 * val lexer = Lexer(reader)
 * while (true) {
 *   val token = lexer.nextToken() ?: break
 *   // process token
 * }
 * ```
 *
 * The lexer is intentionally minimal and does not validate higher-level JSON structure; it only tokenizes.
 */
class Lexer(reader: Reader) {
    /** Underlying character reader providing lookahead functionality. */
    private val charReader = CharReader(reader)

    companion object {
        /** Characters that legally terminate a value (used to detect number literal boundaries). */
        private val valueEndChars = setOf(',', '}', ']', ' ', '\t', '\r', '\n')
    }

    /**
     * Dispatch map from the first character of a token to a handler that returns the appropriate [Token].
     * Number handling is added for all digit characters dynamically in an initializer block.
     */
    private val tokenMap = hashMapOf<Char, (Char) -> Token> (
        ',' to { Token.COMMA },
        '{' to { Token.LBRACE },
        '}' to { Token.RBRACE },
        '[' to { Token.LBRACKET },
        ']' to { Token.RBRACKET },
        ':' to { Token.COLON },
        't' to { charReader.expectText("rue", valueEndChars); Token.TRUE },
        'f' to { charReader.expectText("alse", valueEndChars); Token.FALSE },
        'n' to { charReader.expectText("ull", valueEndChars); Token.NullValue },
        '"' to { readStringToken() },
        '-' to { c -> readNumberToken(c) }
    ).apply {
        for (i in '0'..'9') {
            this[i] = { c -> readNumberToken(c) }
        }
    }

    /**
     * Returns the next [Token] in the stream, or null if the end of input has been reached.
     * Skips leading whitespace before attempting token recognition.
     *
     * @throws MalformedJSONException for any unexpected or malformed sequence.
     */
    fun nextToken(): Token? {
        var c: Char?
        do {
            c = charReader.readNext()
        } while (c != null && c.isWhitespace())
        if (c == null) return null

        return tokenMap[c]?.invoke(c)
                ?: throw MalformedJSONException("Unexpected token $c")
    }

    /**
     * Parses a JSON string literal, handling escape sequences (including Unicode \uXXXX) and returns a [Token.StringValue].
     * The opening quote has already been consumed when this method is invoked.
     */
    private fun readStringToken(): Token {
        val result = StringBuilder()
        while (true) {
            val c = charReader.readNext() ?: throw MalformedJSONException("Unterminated string")
            if (c == '"') break
            if (c == '\\') {
                val escaped = charReader.readNext() ?: throw MalformedJSONException("Unterminated escape sequence")
                when(escaped) {
                    '\\', '/', '\"' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        val hexChars = charReader.readNextChars(4)
                        result.append(Integer.parseInt(hexChars, 16).toChar())
                    }
                    else -> throw MalformedJSONException("Unsupported escape sequence \\$escaped")
                }
            }
            else {
                result.append(c)
            }
        }
        return Token.StringValue(result.toString())
    }

    /**
     * Parses a number literal starting with [firstChar]. Continues consuming digits (and possibly a '.')
     * until a terminating character is encountered. Returns a [Token.LongValue] if the literal contains
     * no decimal point; otherwise returns a [Token.DoubleValue].
     */
    private fun readNumberToken(firstChar: Char): Token {
        val buffer = StringBuilder(firstChar.toString())
        while (true) {
            val c = charReader.peekNext()
            if (c == null || c in valueEndChars) break
            buffer.append(charReader.readNext()!!)
        }
        val value = buffer.toString()
        return if (value.contains(".")) Token.DoubleValue(value.toDouble()) else Token.LongValue(value.toLong())
    }
}