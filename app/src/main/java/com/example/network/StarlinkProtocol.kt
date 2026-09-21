package com.example.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal read-only wire subset. Sources and compatibility limits: docs/STARLINK-TEST.md. */
internal object StarlinkProtocol {
    const val MAX_BYTES = 1024 * 1024
    enum class Query(val field: Int) { STATUS(1004), CLIENTS(3002) }
    data class Client(val name: String, val ip: String, val mac: String, val active: Boolean?)
    data class Reply(val kind: String, val clients: List<Client>?, val hardware: String, val software: String)
    class RpcFailure(val code: Int, val layer: String) : Exception("$layer=$code")

    fun request(query: Query): ByteArray = field(query.field, byteArrayOf())
    fun frame(payload: ByteArray): ByteArray = ByteBuffer.allocate(payload.size + 5)
        .put(0).putInt(payload.size).put(payload).array()

    fun unwrap(body: ByteArray, headerStatus: String?, trailerStatus: String?, web: Boolean): ByteArray {
        require(body.size <= MAX_BYTES) { "response_too_large" }
        val frames = ByteBuffer.wrap(body)
        var message: ByteArray? = null
        var webStatus: String? = null
        var ended = false
        while (frames.hasRemaining()) {
            require(!ended && frames.remaining() >= 5) { "invalid_frame" }
            val flag = frames.get().toInt() and 255
            val size = frames.int
            require(size >= 0 && size <= frames.remaining()) { "truncated_frame" }
            val data = ByteArray(size).also { frames.get(it) }
            when (flag) {
                0 -> { require(message == null) { "multiple_messages" }; message = data }
                128 -> {
                    require(web) { "unexpected_web_trailer" }
                    val statuses = data.toString(Charsets.US_ASCII).split("\r\n")
                        .filter { it.substringBefore(':').equals("grpc-status", true) }
                    require(statuses.size == 1) { "invalid_status" }
                    webStatus = statuses.single().substringAfter(':').trim()
                    ended = true
                }
                else -> error("unsupported_compression")
            }
        }
        val statuses = listOfNotNull(headerStatus, trailerStatus, webStatus)
        require(statuses.isNotEmpty()) { "missing_grpc_status" }
        statuses.forEach {
            val code = it.toIntOrNull() ?: error("invalid_status")
            require(code in 0..16) { "invalid_status" }
            if (code != 0) throw RpcFailure(code, "gRPC")
        }
        return message ?: error("missing_message")
    }

    fun decode(payload: ByteArray): Reply {
        val response = fields(payload)
        response.singleBytes(2)?.let { status ->
            val code = fields(status).singleNumber(1) ?: 0
            if (code != 0L) throw RpcFailure(code.toInt(), "Starlink")
        }
        val variants = response.filter { it.number in setOf(2004, 3002, 3004) }
        require(variants.size == 1) { "unsupported_response" }
        val variant = variants.single()
        val inner = fields(variant.bytes ?: error("invalid_response"))
        val clientField = when (variant.number) {
            3002 -> 1
            3004 -> if (inner.any { it.number == 3000 }) 3000 else 2
            else -> null
        }
        // An empty get_status on new firmware does NOT establish an empty client list.
        val clients = if (variant.number == 3002 || (clientField != null && inner.any { it.number == clientField })) {
            val entries = inner.filter { it.number == clientField }
            require(entries.size <= 512) { "too_many_clients" }
            entries.map { entry ->
                val client = fields(entry.bytes ?: error("invalid_client"))
                Client(client.string(31).ifBlank { client.string(1) }.ifBlank { "جهاز بدون اسم" },
                    client.string(3), client.string(2), client.singleNumber(58)?.let { it != 0L })
            }
        } else null
        val infoField = if (variant.number == 2004) 1 else 3
        val info = if (variant.number == 3002) emptyList() else inner.singleBytes(infoField)?.let(::fields).orEmpty()
        return Reply(when (variant.number) { 2004 -> "DISH"; 3004 -> "ROUTER"; else -> "CLIENTS" },
            clients, info.string(2), info.string(3))
    }

    private data class WireField(val number: Int, val bytes: ByteArray? = null, val numeric: Long? = null)
    private fun List<WireField>.singleBytes(number: Int): ByteArray? {
        val matches = filter { it.number == number }
        require(matches.size <= 1) { "duplicate_field" }
        return matches.singleOrNull()?.let { it.bytes ?: error("wrong_wire_type") }
    }
    private fun List<WireField>.singleNumber(number: Int): Long? {
        val matches = filter { it.number == number }
        require(matches.size <= 1) { "duplicate_field" }
        return matches.singleOrNull()?.let { it.numeric ?: error("wrong_wire_type") }
    }
    private fun List<WireField>.string(number: Int): String = singleBytes(number)?.let {
        require(it.size <= 2048) { "string_too_long" }
        it.toString(Charsets.UTF_8).filter { ch -> !ch.isISOControl() && ch !in '\u202a'..'\u202e' && ch !in '\u2066'..'\u2069' }.take(160)
    }.orEmpty()
    private fun fields(data: ByteArray): List<WireField> {
        require(data.size <= MAX_BYTES) { "response_too_large" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableListOf<WireField>()
        while (buffer.hasRemaining()) {
            require(result.size < 8192) { "too_many_fields" }
            val tag = varint(buffer)
            require(tag > 0 && tag <= 0xffffffffL && tag ushr 3 > 0) { "invalid_tag" }
            val number = (tag ushr 3).toInt()
            result += when ((tag and 7).toInt()) {
                0 -> WireField(number, numeric = varint(buffer))
                1 -> { require(buffer.remaining() >= 8); buffer.long; WireField(number) }
                2 -> {
                    val length = varint(buffer)
                    require(length >= 0 && length <= buffer.remaining().toLong()) { "invalid_length" }
                    WireField(number, bytes = ByteArray(length.toInt()).also { buffer.get(it) })
                }
                5 -> { require(buffer.remaining() >= 4); buffer.int; WireField(number) }
                else -> error("unsupported_wire_type")
            }
        }
        return result
    }
    private fun varint(buffer: ByteBuffer): Long {
        var value = 0L
        repeat(10) { index ->
            require(buffer.hasRemaining()) { "truncated_varint" }
            val b = buffer.get().toInt() and 255
            require(index != 9 || b <= 1) { "overflow_varint" }
            value = value or ((b and 127).toLong() shl (index * 7))
            if (b < 128) return value
        }
        error("overflow_varint")
    }
    internal fun field(number: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeVarint(v: Int) {
            var value = v
            while (value >= 128) { out.write((value and 127) or 128); value = value ushr 7 }
            out.write(value)
        }
        writeVarint((number shl 3) or 2); writeVarint(payload.size); out.write(payload)
        return out.toByteArray()
    }
}
