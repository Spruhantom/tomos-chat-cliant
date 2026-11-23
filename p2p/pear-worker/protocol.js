'use strict'

const MAX_FRAME_BYTES = 64 * 1024
const PROTOCOL_VERSION = 1

function parseMessage (buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0 || buffer.length > MAX_FRAME_BYTES) {
    throw new Error('Invalid TomChat worker frame size')
  }

  let message
  try {
    message = JSON.parse(buffer.toString('utf8'))
  } catch {
    throw new Error('Invalid TomChat worker JSON frame')
  }

  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    throw new Error('TomChat worker frame must be an object')
  }
  if (message.version !== PROTOCOL_VERSION || typeof message.type !== 'string') {
    throw new Error('Unsupported TomChat worker protocol version or message type')
  }

  return message
}

function frame (type, payload = {}) {
  if (typeof type !== 'string' || !type) throw new Error('Worker message type is required')
  const encoded = Buffer.from(JSON.stringify({ version: PROTOCOL_VERSION, type, ...payload }))
  if (encoded.length > MAX_FRAME_BYTES) throw new Error('TomChat worker frame is too large')
  return encoded
}

module.exports = { MAX_FRAME_BYTES, PROTOCOL_VERSION, frame, parseMessage }
