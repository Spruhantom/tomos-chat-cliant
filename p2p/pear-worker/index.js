'use strict'

// Bare worker shared by the Android and Linux native hosts. The host remains
// responsible for binding a discovered transport key to TomChat's signed
// contact identity before it sends peer:authorize.

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
const swarm = new Hyperswarm()
const pendingPeers = new Map()
const authorizedPeers = new Set()
let messages
let closed = false

function emit (type, payload = {}) {
  if (!closed) pipe.write(frame(type, payload))
}

function validTopic (value) {
  return typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value)
}

async function boot () {
  await store.ready()
  messages = new Hyperbee(store.get({ name: 'tomchat-local-messages' }), {
    keyEncoding: 'utf-8',
    valueEncoding: 'json'
  })
  await messages.ready()

  swarm.on('connection', (connection, info) => {
    const id = Buffer.from(info.publicKey || []).toString('hex')
    if (!id || id.length !== 64) {
      connection.destroy(new Error('Peer did not provide a valid transport public key'))
      return
    }

    // An encrypted Hyperswarm transport is not TomChat identity verification.
    // Quarantine the stream until the native host verifies the signed contact
    // card and Noise identity binding already used by TomChat.
    pendingPeers.set(id, connection)
    emit('peer:offer', { transportKey: id })
  })

  swarm.on('error', (error) => emit('backend:error', { message: error.message }))
  emit('backend:ready', { protocolVersion: 1, storage: 'corestore-hyperbee' })
}

async function handle (message) {
  switch (message.type) {
    case 'swarm:join': {
      if (!validTopic(message.topic)) throw new Error('Topic must be a 32-byte hex value')
      const discovery = swarm.join(Buffer.from(message.topic, 'hex'), {
        client: true,
        server: true
      })
      await discovery.flushed()
      emit('swarm:joined', { topic: message.topic })
      return
    }

    case 'peer:authorize': {
      if (typeof message.transportKey !== 'string' || !/^[a-f0-9]{64}$/i.test(message.transportKey)) {
        throw new Error('Invalid transport key')
      }
      const connection = pendingPeers.get(message.transportKey)
      if (!connection) throw new Error('Peer offer is no longer pending')
      pendingPeers.delete(message.transportKey)
      authorizedPeers.add(message.transportKey)
      connection.on('error', (error) => emit('peer:error', {
        transportKey: message.transportKey,
        message: error.message
      }))
      store.replicate(connection)
      emit('peer:authorized', { transportKey: message.transportKey })
      return
    }

    case 'peer:reject': {
      const connection = pendingPeers.get(message.transportKey)
      if (connection) connection.destroy()
      pendingPeers.delete(message.transportKey)
      emit('peer:rejected', { transportKey: message.transportKey })
      return
    }

    case 'message:append': {
      if (!authorizedPeers.has(message.transportKey)) {
        throw new Error('Cannot append a network message before peer identity authorization')
      }
      if (typeof message.id !== 'string' || message.id.length > 160 ||
          typeof message.body !== 'string' || !message.body.trim() ||
          Buffer.byteLength(message.body, 'utf8') > 16 * 1024) {
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
  for (const connection of pendingPeers.values()) connection.destroy()
  pendingPeers.clear()
  await swarm.destroy()
  await store.close()
  pipe.end()
}

goodbye(shutdown)
boot().catch((error) => emit('backend:fatal', { message: error.message }))
