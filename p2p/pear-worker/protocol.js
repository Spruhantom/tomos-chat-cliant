'use strict'

const MAX_FRAME_BYTES = 64 * 1024
const PROTOCOL_VERSION = 1
const HEADER_BYTES = 4

function parseMessage (buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0 || buffer.length > MAX_FRAME_BYTES) {
    throw new Error('Invalid TomChat worker frame size')
  }
  let message
  try { message = JSON.parse(buffer.toString('utf8')) } catch {
    throw new Error('Invalid TomChat worker JSON frame')
  }
  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    throw new Error('TomChat worker frame must be an object')
  }
  if (message.version !== PROTOCOL_VERSION || typeof message.type !== 'string' || message.type.trim().length === 0) {
    throw new Error('Unsupported TomChat worker protocol version or message type')
  }
  return message
}

function frame (type, payload = {}) {
  if (typeof type !== 'string' || type.trim().length === 0) throw new Error('Worker message type is required')
  const body = Buffer.from(JSON.stringify({ ...payload, version: PROTOCOL_VERSION, type }))
  if (body.length === 0 || body.length > MAX_FRAME_BYTES) throw new Error('TomChat worker frame is too large')
  const header = Buffer.alloc(HEADER_BYTES)
  header.writeUInt32BE(body.length, 0)
  return Buffer.concat([header, body])
}

function parseFrame (buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < HEADER_BYTES) throw new Error('Truncated TomChat worker frame header')
  const length = buffer.readUInt32BE(0)
  if (length === 0 || length > MAX_FRAME_BYTES || buffer.length !== HEADER_BYTES + length) {
    throw new Error('Invalid TomChat worker frame length')
  }
  return parseMessage(buffer.subarray(HEADER_BYTES))
}

class FrameDecoder {
  constructor () {
    this.pending = Buffer.alloc(0)
  }

  push (chunk) {
    if (!Buffer.isBuffer(chunk)) throw new Error('TomChat IPC chunks must be buffers')
    if (chunk.length) this.pending = this.pending.length
      ? Buffer.concat([this.pending, chunk])
      : Buffer.from(chunk)

    const messages = []
    while (this.pending.length >= HEADER_BYTES) {
      const length = this.pending.readUInt32BE(0)
      if (length === 0 || length > MAX_FRAME_BYTES) {
        this.pending = Buffer.alloc(0)
        throw new Error('Invalid TomChat worker frame length')
      }
      if (this.pending.length < HEADER_BYTES + length) break
      const body = this.pending.subarray(HEADER_BYTES, HEADER_BYTES + length)
      this.pending = this.pending.subarray(HEADER_BYTES + length)
      try {
        messages.push(parseMessage(body))
      } catch (error) {
        // Do not retain trailing coalesced frames after a malformed message;
        // the stream is no longer safe to continue parsing.
        this.pending = Buffer.alloc(0)
        throw error
      }
    }
    return messages
  }
}

module.exports = { FrameDecoder, HEADER_BYTES, MAX_FRAME_BYTES, PROTOCOL_VERSION, frame, parseFrame, parseMessage }
