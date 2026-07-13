package com.cyrusublerman.distaut.recipes

sealed interface JsonValue {
    data class Object(val values: LinkedHashMap<String, JsonValue>) : JsonValue {
        constructor(vararg entries: Pair<String, JsonValue>) : this(linkedMapOf(*entries))
        operator fun get(key: String): JsonValue? = values[key]
    }

    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String) : JsonValue {
        fun asDouble(): Double = raw.toDouble()
        fun asLong(): Long = raw.toLong()
        fun isIntegral(): Boolean = raw.none { it == '.' || it == 'e' || it == 'E' }
    }
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

object JsonCodec {
    fun parse(text: String): JsonValue = Parser(text).parse()

    fun stringify(value: JsonValue, pretty: Boolean = false): String = buildString {
        Writer(this, pretty).write(value, 0)
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            require(index == source.length) { "Unexpected trailing JSON at character $index" }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected JSON token '${source[index]}' at character $index")
            }
        }

        private fun parseObject(): JsonValue.Object {
            expect('{')
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.Object(values)
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "Expected object key at character $index" }
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                values[key] = value
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return JsonValue.Object(values)
        }

        private fun parseArray(): JsonValue.Array {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.Array(values)
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return JsonValue.Array(values)
        }

        private fun parseString(): String {
            expect('"')
            val output = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when (character) {
                    '"' -> return output.toString()
                    '\\' -> {
                        require(index < source.length) { "Incomplete JSON escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> output.append(escaped)
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000c')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete unicode escape" }
                                val code = source.substring(index, index + 4).toInt(16)
                                output.append(code.toChar())
                                index += 4
                            }
                            else -> error("Unsupported JSON escape \\$escaped at character ${index - 1}")
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { "Control character in JSON string" }
                        output.append(character)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = index
            consume('-')
            if (consume('0')) {
                // Leading zero is complete unless followed by a fraction or exponent.
            } else {
                require(peek() in '1'..'9') { "Invalid number at character $index" }
                while (peek() in '0'..'9') index++
            }
            if (consume('.')) {
                require(peek() in '0'..'9') { "Expected decimal digits at character $index" }
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                require(peek() in '0'..'9') { "Expected exponent digits at character $index" }
                while (peek() in '0'..'9') index++
            }
            val raw = source.substring(start, index)
            require(raw.toDouble().isFinite()) { "JSON number is outside the supported finite range" }
            return JsonValue.NumberValue(raw)
        }

        private fun <T : JsonValue> parseLiteral(token: String, value: T): T {
            require(source.regionMatches(index, token, 0, token.length)) {
                "Expected '$token' at character $index"
            }
            index += token.length
            return value
        }

        private fun expect(character: Char) {
            skipWhitespace()
            require(index < source.length && source[index] == character) {
                "Expected '$character' at character $index"
            }
            index++
        }

        private fun consume(character: Char): Boolean {
            if (index < source.length && source[index] == character) {
                index++
                return true
            }
            return false
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private class Writer(
        private val output: StringBuilder,
        private val pretty: Boolean,
    ) {
        fun write(value: JsonValue, depth: Int) {
            when (value) {
                is JsonValue.Object -> writeObject(value, depth)
                is JsonValue.Array -> writeArray(value, depth)
                is JsonValue.StringValue -> writeString(value.value)
                is JsonValue.NumberValue -> output.append(value.raw)
                is JsonValue.BooleanValue -> output.append(value.value)
                JsonValue.NullValue -> output.append("null")
            }
        }

        private fun writeObject(value: JsonValue.Object, depth: Int) {
            output.append('{')
            if (value.values.isNotEmpty()) {
                var first = true
                for ((key, child) in value.values) {
                    if (!first) output.append(',')
                    newline(depth + 1)
                    writeString(key)
                    output.append(if (pretty) ": " else ":")
                    write(child, depth + 1)
                    first = false
                }
                newline(depth)
            }
            output.append('}')
        }

        private fun writeArray(value: JsonValue.Array, depth: Int) {
            output.append('[')
            if (value.values.isNotEmpty()) {
                value.values.forEachIndexed { index, child ->
                    if (index > 0) output.append(',')
                    newline(depth + 1)
                    write(child, depth + 1)
                }
                newline(depth)
            }
            output.append(']')
        }

        private fun writeString(value: String) {
            output.append('"')
            for (character in value) {
                when (character) {
                    '"' -> output.append("\\\"")
                    '\\' -> output.append("\\\\")
                    '\b' -> output.append("\\b")
                    '\u000c' -> output.append("\\f")
                    '\n' -> output.append("\\n")
                    '\r' -> output.append("\\r")
                    '\t' -> output.append("\\t")
                    else -> if (character.code < 0x20) {
                        output.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        output.append(character)
                    }
                }
            }
            output.append('"')
        }

        private fun newline(depth: Int) {
            if (pretty) output.append('\n').append("  ".repeat(depth))
        }
    }
}

fun JsonValue?.asObjectOrNull(): JsonValue.Object? = this as? JsonValue.Object
fun JsonValue?.asArrayOrNull(): JsonValue.Array? = this as? JsonValue.Array
fun JsonValue?.asStringOrNull(): String? = (this as? JsonValue.StringValue)?.value
fun JsonValue?.asBooleanOrNull(): Boolean? = (this as? JsonValue.BooleanValue)?.value
fun JsonValue?.asNumberOrNull(): JsonValue.NumberValue? = this as? JsonValue.NumberValue
