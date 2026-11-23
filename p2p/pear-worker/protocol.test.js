'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { frame, parseMessage, MAX_FRAME_BYTES } = require('./protocol')

test('round-trips a versioned worker message', () => {
  const decoded = parseMessage(frame('backend:ready', { platform: 'linux' }))
  assert.equal(decoded.version, 1)
  assert.equal(decoded.type, 'backend:ready')
  assert.equal(decoded.platform, 'linux')
})

test('rejects unsupported protocol versions', () => {
  assert.throws(() => parseMessage(Buffer.from('{"version":2,"type":"hello"}')))
})

test('rejects malformed JSON and oversized frames', () => {
  assert.throws(() => parseMessage(Buffer.from('{')))
  assert.throws(() => parseMessage(Buffer.alloc(MAX_FRAME_BYTES + 1)))
})

test('does not permit empty message types', () => {
  assert.throws(() => frame(''))
})

test('payload cannot override the protocol envelope', () => {
  const decoded = parseMessage(frame('backend:ready', { version: 99, type: 'spoofed' }))
  assert.equal(decoded.version, 1)
  assert.equal(decoded.type, 'backend:ready')
})
