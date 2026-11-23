'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { frame, parseFrame, parseMessage, MAX_FRAME_BYTES } = require('./protocol')

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

test('payload cannot override the protocol envelope', () => {
  const decoded = parseFrame(frame('backend:ready', { version: 99, type: 'spoofed' }))
  assert.equal(decoded.version, 1)
  assert.equal(decoded.type, 'backend:ready')
})
