package uk.co.tomos.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefixed byte framing shared by the Android host and Bare worker.
 * Each frame is a 4-byte big-endian body length followed by the exact body.
 */
internal object PearIpcFrameCodec {
    const val MAX_FRAME_BYTES = 64 * 1024

    fun encode(body: ByteArray): ByteArray {
        require(body.isNotEmpty() && body.size <= MAX_FRAME_BYTES) { "Invalid Pear IPC frame body size." }
        return ByteBuffer.allocate(HEADER_BYTES + body.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(body.size)
            .put(body)
            .array()
    }

    private const val HEADER_BYTES = 4
}

/** Incremental decoder that safely handles fragmented and coalesced IPC reads. */
internal class PearIpcFrameDecoder {
    private var pending = ByteArray(0)

    fun push(chunk: ByteArray): List<ByteArray> {
        if (chunk.isNotEmpty()) pending += chunk
        val frames = mutableListOf<ByteArray>()
        while (pending.size >= HEADER_BYTES) {
            val length = ByteBuffer.wrap(pending, 0, HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            if (length <= 0 || length > PearIpcFrameCodec.MAX_FRAME_BYTES) {
                pending = ByteArray(0)
                throw IllegalArgumentException("Invalid Pear IPC frame length.")
            }
            if (pending.size < HEADER_BYTES + length) break
            frames += pending.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)
            pending = pending.copyOfRange(HEADER_BYTES + length, pending.size)
        }
        return frames
    }

    private companion object {
        const val HEADER_BYTES = 4
    }
}
