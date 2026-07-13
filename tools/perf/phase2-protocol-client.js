'use strict'

// A deliberately small, renderless protocol peer for isolated GitHub runtime
// measurements. It is not a visual-compatibility substitute for the vanilla
// client; it only keeps a real TCP play-state connection alive so Paper and
// Sparrow exercise their normal tracker and packet paths.

const fs = require('fs')
const path = require('path')

const protocolModule = process.env.PHASE2_MC_PROTOCOL_MODULE || 'minecraft-protocol'
// eslint-disable-next-line import/no-dynamic-require
const minecraftProtocol = require(protocolModule)

const host = process.env.PHASE2_SERVER_HOST || '127.0.0.1'
const port = parseInteger(process.env.PHASE2_SERVER_PORT, 25566)
const username = process.env.PHASE2_CLIENT_USERNAME || 'IVBench'
const version = process.env.PHASE2_CLIENT_VERSION || '26.1.2'
const readyFile = requiredPath('PHASE2_CLIENT_READY_FILE')
const stateFile = requiredPath('PHASE2_CLIENT_STATE_FILE')
const readyTimeoutMs = parseInteger(process.env.PHASE2_CLIENT_READY_TIMEOUT_MS, 120000)

let ending = false
let exitCode = 0
let failureMessage = null
let loginSeen = false
let positionSeen = false
let chunkSeen = false
let chunkBatchSeen = false
let playerLoadedSent = false
let readyWritten = false
let chunkLoadCount = 0
let chunkUnloadCount = 0
let currentPosition = { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 }

ensureParent(readyFile)
ensureParent(stateFile)
fs.rmSync(readyFile, { force: true })
fs.rmSync(stateFile, { force: true })

const client = minecraftProtocol.createClient({
  host,
  port,
  username,
  version,
  auth: 'offline',
  keepAlive: true,
  checkTimeoutInterval: 120000,
  hideErrors: false
})

client.on('state', state => {
  if (state !== 'configuration') return
  client.write('settings', {
    locale: 'en_us',
    viewDistance: 3,
    chatFlags: 0,
    chatColors: true,
    skinParts: 0x7f,
    mainHand: 1,
    enableTextFiltering: false,
    enableServerListing: false,
    particleStatus: 'minimal'
  })
})

const readyTimeout = setTimeout(() => {
  fail(`play-state readiness timed out after ${readyTimeoutMs} ms`)
}, readyTimeoutMs)

const tickEndInterval = setInterval(() => {
  if (client.state === 'play' && loginSeen && !ending) client.write('tick_end', {})
}, 50)

const movementInterval = setInterval(() => {
  if (client.state !== 'play' || !positionSeen || ending) return
  client.write('position_look', {
    ...currentPosition,
    flags: movementFlags(true)
  })
}, 1000)

client.on('login', () => {
  loginSeen = true
  writeState('login')
  markReadyIfComplete()
})

client.on('position', packet => {
  currentPosition = {
    x: relativeValue(currentPosition.x, packet.x, packet.flags, 'x'),
    y: relativeValue(currentPosition.y, packet.y, packet.flags, 'y'),
    z: relativeValue(currentPosition.z, packet.z, packet.flags, 'z'),
    yaw: relativeValue(currentPosition.yaw, packet.yaw, packet.flags, 'yaw'),
    pitch: relativeValue(currentPosition.pitch, packet.pitch, packet.flags, 'pitch')
  }
  positionSeen = true
  client.write('teleport_confirm', { teleportId: packet.teleportId })
  client.write('position_look', {
    ...currentPosition,
    flags: movementFlags(true)
  })
  sendPlayerLoadedIfReady()
  writeState('position')
  markReadyIfComplete()
})

client.on('map_chunk', () => {
  chunkLoadCount++
  chunkSeen = true
  sendPlayerLoadedIfReady()
  if (!readyWritten) writeState('chunk')
  markReadyIfComplete()
})

client.on('unload_chunk', () => {
  chunkUnloadCount++
  writeState('chunk-unload')
})

client.on('chunk_batch_finished', () => {
  // Match the vanilla calculator's conservative initial target instead of
  // asking Paper to burst the whole view distance into one renderless tick.
  client.write('chunk_batch_received', { chunksPerTick: 3.5 })
  chunkBatchSeen = true
  sendPlayerLoadedIfReady()
  markReadyIfComplete()
})

client.on('respawn', () => {
  positionSeen = false
  chunkSeen = false
  chunkBatchSeen = false
  playerLoadedSent = false
  writeState('respawn')
})

client.on('ping', packet => {
  client.write('pong', { id: packet.id })
})

client.on('kick_disconnect', packet => {
  fail(`server disconnected the protocol client: ${safeJson(packet)}`)
})

client.on('error', error => {
  fail(`protocol client error: ${error.stack || error.message || error}`)
})

client.on('end', reason => {
  cleanupTimers()
  writeState(exitCode !== 0 ? 'failed' : ending ? 'stopped' : 'ended', {
    reason: safeJson(reason),
    ...(failureMessage == null ? {} : { message: failureMessage })
  })
  process.exit(ending ? exitCode : 1)
})

process.on('SIGINT', stop)
process.on('SIGTERM', stop)

function markReadyIfComplete () {
  if (readyWritten || !loginSeen || !positionSeen || !chunkSeen || !playerLoadedSent) return
  readyWritten = true
  clearTimeout(readyTimeout)
  const state = stateSnapshot('ready')
  fs.writeFileSync(readyFile, `${JSON.stringify(state)}\n`, 'utf8')
  fs.writeFileSync(stateFile, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
}

function sendPlayerLoadedIfReady () {
  if (playerLoadedSent || client.state !== 'play' || !positionSeen || !chunkSeen || !chunkBatchSeen) return
  client.write('player_loaded', {})
  playerLoadedSent = true
}

function stop () {
  if (ending) return
  ending = true
  cleanupTimers()
  client.end('phase2 validation complete')
  setTimeout(() => process.exit(0), 2000).unref()
}

function fail (message) {
  if (ending) return
  ending = true
  exitCode = 1
  failureMessage = message
  cleanupTimers()
  const state = stateSnapshot('failed', { message })
  fs.writeFileSync(stateFile, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
  console.error(message)
  try {
    client.end('phase2 validation failure')
  } catch (_) {
    // The original failure is the useful evidence.
  }
  setTimeout(() => process.exit(1), 100).unref()
}

function cleanupTimers () {
  clearTimeout(readyTimeout)
  clearInterval(tickEndInterval)
  clearInterval(movementInterval)
}

function writeState (phase, extra = {}) {
  fs.writeFileSync(stateFile, `${JSON.stringify(stateSnapshot(phase, extra), null, 2)}\n`, 'utf8')
}

function stateSnapshot (phase, extra = {}) {
  return {
    phase,
    timestamp: new Date().toISOString(),
    host,
    port,
    username,
    version,
    loginSeen,
    positionSeen,
    chunkSeen,
    chunkBatchSeen,
    chunkLoadCount,
    chunkUnloadCount,
    playerLoadedSent,
    position: currentPosition,
    ...extra
  }
}

function relativeValue (previous, supplied, flags, name) {
  return hasFlag(flags, name) ? previous + supplied : supplied
}

function hasFlag (flags, name) {
  if (Array.isArray(flags)) return flags.includes(name)
  return Boolean(flags && flags[name])
}

function movementFlags (onGround) {
  return { onGround, hasHorizontalCollision: false }
}

function requiredPath (name) {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is required`)
  return path.resolve(value)
}

function ensureParent (file) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
}

function parseInteger (value, fallback) {
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) ? parsed : fallback
}

function safeJson (value) {
  try {
    return JSON.stringify(value, (_, nested) => typeof nested === 'bigint' ? nested.toString() : nested)
  } catch (_) {
    return String(value)
  }
}
