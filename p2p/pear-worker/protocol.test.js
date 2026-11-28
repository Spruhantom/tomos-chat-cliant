'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { FrameDecoder, frame, parseFrame, parseMessage, MAX_FRAME_BYTES } = require('./protocol')

test('round-trips a length-prefixed versioned worker message', () => {
  const decoded = parseFrame(frame('backend:ready', { platform: 'android' }))
  assert.equal(decoded.version, 1)
  assert.equal(decoded.type, 'backend:ready')
  assert.equal(decoded.platform, 'android')
})

test('rejects unsupported protocol versions', () => {
  assert.throws(() => parseMessage(Buffer.from('{"version":2,"type":"hello"}')))
})

test('rejects malformed JSON and oversized frames', () => {
  assert.throws(() => parseMessage(Buffer.from('{')))
  assert.throws(() => frame('large', { payload: 'x'.repeat(MAX_FRAME_BYTES) }))
})

test('rejects truncated and inconsistent frame headers', () => {
  assert.throws(() => parseFrame(Buffer.from([0, 0, 0])))
  assert.throws(() => parseFrame(Buffer.from([0, 0, 0, 10, 123])))
})

test('does not permit empty message types', () => {
  assert.throws(() => frame(''))
})

test('rejects whitespace-only message types on encode and decode', () => {
  assert.throws(() => frame('   '))
  assert.throws(() => parseMessage(Buffer.from('{"version":1,"type":"   "}')))
})

test('payload cannot override the protocol envelope', () => {
  const decoded = parseFrame(frame('backend:ready', { version: 99, type: 'spoofed' }))
  assert.equal(decoded.version, 1)
  assert.equal(decoded.type, 'backend:ready')
})

test('decoder clears buffered trailing frames after malformed JSON', () => {
  const decoder = new FrameDecoder()
  const invalidBody = Buffer.from('not-json')
  const invalid = Buffer.alloc(4 + invalidBody.length)
  invalid.writeUInt32BE(invalidBody.length, 0)
  invalidBody.copy(invalid, 4)
  const trailing = frame('backend:ready')

  assert.throws(() => decoder.push(Buffer.concat([invalid, trailing])))
  assert.deepEqual(decoder.push(trailing), [{ version: 1, type: 'backend:ready' }])
})

test('decoder buffers partial frames and emits coalesced frames in order', () => {
  const decoder = new FrameDecoder()
  const first = frame('peer:authorized', { id: 'a' })
  const second = frame('peer:closed', { id: 'b' })
  assert.deepEqual(decoder.push(first.subarray(0, 2)), [])
  assert.deepEqual(decoder.push(Buffer.concat([first.subarray(2), second])), [
    { version: 1, type: 'peer:authorized', id: 'a' },
    { version: 1, type: 'peer:closed', id: 'b' }
  ])
})
