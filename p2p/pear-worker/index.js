'use strict'

// Shared Bare worker. The native host supplies the local X25519 keypair and
// the set of already-verified TomChat contact keys at startup. Hyperswarm's
// Noise key is therefore the same key pinned in signed TomChat contact cards.

const Corestore = require('corestore')
const Hyperbee = require('hyperbee')
const Hyperswarm = require('hyperswarm')
const FramedStream = require('framed-stream')
const goodbye = require('graceful-goodbye')
const path = require('bare-path')
const storage = require('bare-storage')
const { isBareKit } = require('which-runtime')
const { frame, parseMessage } = require('./protocol')

const argv = (index) => Bare.argv[index + (isBareKit ? 0 : 2)]
const storageDir = argv(0) || storage.persistent()
const pipe = new FramedStream(Bare.IPC)
const store = new Corestore(path.join(storageDir, 'tomchat', 'pear-corestore'))
const trustedPeers = new Set()
let swarm = null
let messages = null
let closed = false

function emit (type, payload = {}) {
  if (!closed) pipe.write(frame(type, payload))
}

function decodeKey (value, label) {
  if (typeof value !== 'string' || !/^[a-f0-9]{64}$/i.test(value)) {
    throw new Error(label + ' must be a 32-byte hexadecimal key')
  }
  return Buffer.from(value, 'hex')
}

async function boot () {
  await store.ready()
  messages = new Hyperbee(store.get({ name: 'tomchat-local-messages' }), {
    keyEncoding: 'utf-8',
    valueEncoding: 'json'
  })
  await messages.ready()
  emit('backend:ready', {
    protocolVersion: 1,
    storage: 'corestore-hyperbee',
    identityConfigurationRequired: true
  })
}

function configureIdentity (message) {
  if (swarm) throw new Error('TomChat Pear identity is already configured')
  const publicKey = decodeKey(message.publicKey, 'Local public key')
  const secretKey = decodeKey(message.secretKey, 'Local secret key')
  if (!Array.isArray(message.trustedPeers) || message.trustedPeers.length > 10000) {
    throw new Error('Trusted peer key list is invalid')
  }

  for (const key of message.trustedPeers) {
    decodeKey(key, 'Trusted peer key')
    trustedPeers.add(key.toLowerCase())
  }

  // The local secret key is passed from platform-secure storage for this
  // process only; it is never written to Corestore or emitted over IPC.
  swarm = new Hyperswarm({ keyPair: { publicKey, secretKey } })
  swarm.on('connection', (connection, info) => {
    const transportKey = Buffer.from(info.publicKey || []).toString('hex')
    if (!trustedPeers.has(transportKey)) {
      connection.destroy(new Error('Peer key is not bound to a trusted TomChat contact'))
      emit('peer:rejected', { transportKey })
      return
    }

    connection.on('error', (error) => emit('peer:error', {
      transportKey,
      message: error.message
    }))
    connection.on('close', () => emit('peer:closed', { transportKey }))
    store.replicate(connection)
    emit('peer:authorized', { transportKey })
  })
  swarm.on('error', (error) => emit('backend:error', { message: error.message }))
  emit('identity:configured', { trustedPeerCount: trustedPeers.size })
}

async function handle (message) {
  switch (message.type) {
    case 'identity:configure':
      configureIdentity(message)
      return

    case 'swarm:join': {
      if (!swarm) throw new Error('Configure the TomChat identity before joining a swarm')
      const topic = decodeKey(message.topic, 'Swarm topic')
      const discovery = swarm.join(topic, { client: true, server: true })
      await discovery.flushed()
      emit('swarm:joined', { topic: message.topic })
      return
    }

    case 'peer:trust': {
      decodeKey(message.transportKey, 'Trusted peer key')
      trustedPeers.add(message.transportKey.toLowerCase())
      emit('peer:trust-added', { transportKey: message.transportKey.toLowerCase() })
      return
    }

    case 'peer:untrust': {
      decodeKey(message.transportKey, 'Peer key')
      trustedPeers.delete(message.transportKey.toLowerCase())
      const peer = swarm && swarm.peers.get(message.transportKey.toLowerCase())
      if (peer) peer.ban(true)
      emit('peer:trust-removed', { transportKey: message.transportKey.toLowerCase() })
      return
    }

    case 'message:append': {
      if (!swarm || !trustedPeers.has(String(message.transportKey).toLowerCase())) {
        throw new Error('Cannot append a network message for an untrusted peer')
      }
      if (typeof message.id !== 'string' || message.id.length > 160 ||
          typeof message.body !== 'string' || !message.body.trim() ||
          Buffer.byteLength(message.body, 'utf8') > 16 * 1024 ||
          typeof message.senderFingerprint !== 'string' ||
          typeof message.createdAt !== 'number' || !Number.isFinite(message.createdAt)) {
        throw new Error('Invalid message record')
      }
      const record = {
        id: message.id,
        body: message.body,
        senderFingerprint: message.senderFingerprint,
        createdAt: message.createdAt
      }
      await messages.put(record.id, record)
      emit('message:stored', { id: record.id })
      return
    }

    case 'message:list': {
      const result = []
      for await (const entry of messages.createReadStream()) {
        result.push(entry.value)
        if (result.length >= 500) break
      }
      emit('message:list-result', { messages: result })
      return
    }

    case 'backend:close':
      await shutdown()
      return

    default:
      throw new Error('Unknown TomChat worker command')
  }
}

pipe.on('data', async (data) => {
  try {
    await handle(parseMessage(data))
  } catch (error) {
    emit('backend:error', { message: error.message })
  }
})

async function shutdown () {
  if (closed) return
  closed = true
  if (swarm) await swarm.destroy()
  await store.close()
  pipe.end()
}

goodbye(shutdown)
boot().catch((error) => emit('backend:fatal', { message: error.message }))
