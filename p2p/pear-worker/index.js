'use strict'

// Shared Bare worker. Native hosts supply a dedicated Pear/Hyperswarm X25519
// keypair and per-contact conversation keys derived from signed Pear identities.
// Both devices derive the same pair of device-writer Hypercores; each device
// writes only its own feed, and only end-to-end ciphertext enters Hyperbee.

const Corestore = require('corestore')
const Hyperbee = require('hyperbee')
const Hyperswarm = require('hyperswarm')
const goodbye = require('graceful-goodbye')
const path = require('bare-path')
const storage = require('bare-storage')
const { isBareKit } = require('which-runtime')
const { FrameDecoder, frame } = require('./protocol')

const argv = (index) => Bare.argv[index + (isBareKit ? 0 : 2)]
const storageDir = argv(0) || storage.persistent()
const decoder = new FrameDecoder()
let commandQueue = Promise.resolve()
const conversations = new Map()
const openingConversations = new Map()
const trustedPeers = new Map()
const activeConnections = new Map()
let swarm = null
let localPublicKey = null
let closed = false

function emit (type, payload = {}) {
  if (!closed) Bare.IPC.write(frame(type, payload))
}

function decodeKey (value, label) {
  if (typeof value !== 'string' || !/^[a-f0-9]{64}$/i.test(value)) {
    throw new Error(label + ' must be a 32-byte hexadecimal key')
  }
  return Buffer.from(value, 'hex')
}

async function ensureConversation (transportKey) {
  const existing = conversations.get(transportKey)
  if (existing) return existing
  const opening = openingConversations.get(transportKey)
  if (opening) return opening

  const conversationKey = trustedPeers.get(transportKey)
  if (!conversationKey) throw new Error('Conversation key is not available for this trusted contact')

  const pending = (async () => {
    const directory = path.join(storageDir, 'tomchat', 'conversations', transportKey)
    const store = new Corestore(directory, { primaryKey: conversationKey })
    try {
      await store.ready()

      // Both devices derive the same two feed keys from their shared
      // conversation key. Each device writes only to its own feed.
      const localBee = new Hyperbee(store.get({ name: 'tomchat-messages-' + localPublicKey.toString('hex') }), {
        keyEncoding: 'utf-8',
        valueEncoding: 'json'
      })
      const remoteBee = new Hyperbee(store.get({ name: 'tomchat-messages-' + transportKey }), {
        keyEncoding: 'utf-8',
        valueEncoding: 'json'
      })
      await Promise.all([localBee.ready(), remoteBee.ready()])
      const conversation = { store, localBee, remoteBee }
      conversations.set(transportKey, conversation)
      return conversation
    } catch (error) {
      await store.close()
      throw error
    }
  })()

  openingConversations.set(transportKey, pending)
  try {
    return await pending
  } finally {
    openingConversations.delete(transportKey)
  }
}

async function boot () {
  emit('backend:ready', {
    protocolVersion: 1,
    storage: 'per-contact-corestore-hyperbee',
    identityConfigurationRequired: true
  })
}

function configureIdentity (message) {
  if (swarm) throw new Error('TomChat Pear identity is already configured')
  const publicKey = decodeKey(message.publicKey, 'Local public key')
  const secretKey = decodeKey(message.secretKey, 'Local secret key')
  if (!Array.isArray(message.trustedPeers) || message.trustedPeers.length > 10000) {
    throw new Error('Trusted peer list is invalid')
  }

  localPublicKey = publicKey
  for (const peer of message.trustedPeers) {
    if (!peer || typeof peer !== 'object') throw new Error('Trusted peer entry is invalid')
    const remoteKey = decodeKey(peer.transportKey, 'Trusted peer key')
    const conversationKey = decodeKey(peer.conversationKey, 'Conversation key')
    trustedPeers.set(remoteKey.toString('hex'), conversationKey)
  }

  // The local secret key and pairwise conversation keys are supplied from
  // platform-secure storage and held only in this worker's process memory.
  swarm = new Hyperswarm({ keyPair: { publicKey, secretKey } })
  swarm.on('connection', async (connection, info) => {
    const transportKey = Buffer.from(info.publicKey || []).toString('hex')
    if (!trustedPeers.has(transportKey)) {
      if (info.ban) info.ban(true)
      connection.destroy(new Error('Peer key is not bound to a trusted TomChat contact'))
      emit('peer:rejected', { transportKey })
      return
    }

    connection.on('close', () => {
      if (activeConnections.get(transportKey) === connection) activeConnections.delete(transportKey)
      emit('peer:closed', { transportKey })
    })

    try {
      const conversation = await ensureConversation(transportKey)
      if (!trustedPeers.has(transportKey)) {
        connection.destroy(new Error('TomChat contact trust was revoked during connection setup'))
        return
      }
      activeConnections.set(transportKey, connection)
      connection.on('error', (error) => emit('peer:error', {
        transportKey,
        message: error.message
      }))
      conversation.store.replicate(connection)
      emit('peer:authorized', { transportKey })
    } catch (error) {
      connection.destroy(error)
      emit('peer:error', { transportKey, message: error.message })
    }
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
      const transportKey = decodeKey(message.transportKey, 'Trusted peer key')
      const conversationKey = decodeKey(message.conversationKey, 'Conversation key')
      trustedPeers.set(transportKey.toString('hex'), conversationKey)
      emit('peer:trust-added', { transportKey: transportKey.toString('hex') })
      return
    }

    case 'peer:untrust': {
      const transportKey = decodeKey(message.transportKey, 'Peer key').toString('hex')
      trustedPeers.delete(transportKey)
      const connection = activeConnections.get(transportKey)
      if (connection) connection.destroy()
      activeConnections.delete(transportKey)
      emit('peer:trust-removed', { transportKey })
      return
    }

    case 'message:append': {
      const transportKey = decodeKey(message.transportKey, 'Peer key').toString('hex')
      if (!swarm || !trustedPeers.has(transportKey)) {
        throw new Error('Cannot append a message for an untrusted peer')
      }
      if (typeof message.id !== 'string' || !/^[0-9a-f-]{36}$/i.test(message.id) ||
          typeof message.ciphertext !== 'string' || !message.ciphertext ||
          Buffer.byteLength(message.ciphertext, 'utf8') > 24 * 1024 ||
          !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(message.ciphertext) ||
          typeof message.senderFingerprint !== 'string' ||
          !/^(?:[A-F0-9]{4} ){15}[A-F0-9]{4}$/.test(message.senderFingerprint) ||
          typeof message.createdAt !== 'number' || !Number.isSafeInteger(message.createdAt) || message.createdAt < 0) {
        throw new Error('Invalid encrypted message record')
      }
      const conversation = await ensureConversation(transportKey)
      const record = {
        id: message.id,
        ciphertext: message.ciphertext,
        senderFingerprint: message.senderFingerprint,
        createdAt: message.createdAt
      }
      await conversation.localBee.put(record.id, record)
      emit('message:stored', { transportKey, id: record.id })
      return
    }

    case 'message:list': {
      const transportKey = decodeKey(message.transportKey, 'Peer key').toString('hex')
      if (!trustedPeers.has(transportKey)) throw new Error('Cannot read an untrusted conversation')
      if (typeof message.requestId !== 'string' || !/^[0-9a-f-]{36}$/i.test(message.requestId)) {
        throw new Error('Invalid message history request ID')
      }
      const conversation = await ensureConversation(transportKey)
      const byId = new Map()
      for (const [bee, writerTransportKey] of [
        [conversation.localBee, localPublicKey.toString('hex')],
        [conversation.remoteBee, transportKey]
      ]) {
        for await (const entry of bee.createReadStream({ reverse: true, limit: 500 })) {
          if (!byId.has(entry.key)) byId.set(entry.key, { ...entry.value, writerTransportKey })
        }
      }
      const result = [...byId.values()].sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id)).slice(-500)
      emit('message:list-result', { transportKey, requestId: message.requestId, messages: result })
      return
    }

    case 'backend:close':
      await shutdown()
      return

    default:
      throw new Error('Unknown TomChat worker command')
  }
}

Bare.IPC.on('data', (chunk) => {
  let messages
  try {
    messages = decoder.push(Buffer.from(chunk))
  } catch (error) {
    emit('backend:error', { message: error.message })
    return
  }
  commandQueue = commandQueue.then(async () => {
    for (const message of messages) await handle(message)
  }).catch((error) => emit('backend:error', { message: error.message }))
})

Bare.IPC.on('end', () => {
  shutdown().catch(() => {})
})

async function shutdown () {
  if (closed) return
  closed = true
  if (swarm) await swarm.destroy()
  await Promise.all([...conversations.values()].map(({ store }) => store.close()))
  conversations.clear()
  openingConversations.clear()
  Bare.IPC.end()
}

goodbye(shutdown)
boot().catch((error) => emit('backend:fatal', { message: error.message }))
