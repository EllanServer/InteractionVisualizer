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
// Optional. When present, clientbound entity lifecycle packets are retained in
// memory and written once, atomically, when the peer exits.
const protocolTraceFile = optionalPath('PHASE2_PROTOCOL_TRACE_FILE')
const protocolTraceMaxEvents = parsePositiveInteger(
  process.env.PHASE2_PROTOCOL_TRACE_MAX_EVENTS,
  100000
)
const protocolTracePacketAllowlist = parsePacketNameAllowlist(
  process.env.PHASE2_PROTOCOL_TRACE_PACKET_ALLOWLIST
)
const protocolTraceAggregatePacketAllowlist = parsePacketNameAllowlist(
  process.env.PHASE2_PROTOCOL_TRACE_AGGREGATE_PACKET_ALLOWLIST
)
assertDisjointPacketAllowlists(
  protocolTracePacketAllowlist,
  protocolTraceAggregatePacketAllowlist
)
const readyTimeoutMs = parseInteger(process.env.PHASE2_CLIENT_READY_TIMEOUT_MS, 120000)
const keepAliveTimeoutMs = parsePositiveInteger(
  process.env.PHASE2_CLIENT_KEEPALIVE_TIMEOUT_MS,
  120000
)

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
const protocolTraceStartedEpochMs = Date.now()
const protocolTraceEvents = []
const protocolTracePacketCountSeries = new Map()
const protocolTraceParseErrors = []
const protocolTraceWriteErrors = []
let protocolTraceParseErrorCount = 0
let protocolTraceDroppedEvents = 0
let protocolTraceFilteredEvents = 0
let protocolTraceObservedEvents = 0
let protocolTraceAggregatedEvents = 0
let protocolTraceWritten = false
let bundleDelimiterCount = 0
let bundleOpen = false
let bundleDrainTimeout = null
let stopFinalized = false

ensureParent(readyFile)
ensureParent(stateFile)
fs.rmSync(readyFile, { force: true })
fs.rmSync(stateFile, { force: true })
if (protocolTraceFile) {
  ensureParent(protocolTraceFile)
  fs.rmSync(protocolTraceFile, { force: true })
}

process.on('exit', code => {
  if (!flushProtocolTrace('process-exit', code)) process.exitCode = 1
})

const client = minecraftProtocol.createClient({
  host,
  port,
  username,
  version,
  auth: 'offline',
  keepAlive: true,
  checkTimeoutInterval: keepAliveTimeoutMs,
  hideErrors: false
})

client.on('packet', (packet, metadata) => {
  captureProtocolPacket(packet, metadata)
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
  exitAfterTrace(ending ? exitCode : 1, 'client-end')
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
  // Paper sends the first chunks before client-loaded, but does not close the
  // first adaptive batch until that acknowledgement arrives. A renderless
  // peer therefore uses receipt of the first chunk as its level-ready proxy;
  // waiting for chunk_batch_finished here creates a protocol wait cycle.
  if (playerLoadedSent || client.state !== 'play' || !positionSeen || !chunkSeen) return
  client.write('player_loaded', {})
  playerLoadedSent = true
}

function stop () {
  if (ending) return
  ending = true
  cleanupTimers()
  if (protocolTraceFile && bundleOpen) {
    // A signal can land between a bundle's two delimiter packets. Give the
    // already-open clientbound bundle a bounded chance to close so trace
    // completeness is deterministic instead of timing-dependent.
    bundleDrainTimeout = setTimeout(finishStop, 1000)
    return
  }
  finishStop()
}

function finishStop () {
  if (stopFinalized) return
  stopFinalized = true
  if (bundleDrainTimeout != null) {
    clearTimeout(bundleDrainTimeout)
    bundleDrainTimeout = null
  }
  // Record a completed local shutdown before asking the saturated server to
  // acknowledge the socket close. The forced-exit fallback must not leave a
  // stale play-state snapshot after an otherwise valid measurement.
  writeState('stopped', { reason: 'client-stop-requested' })
  client.end('phase2 validation complete')
  setTimeout(() => exitAfterTrace(0, 'stop-timeout'), 2000).unref()
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
  setTimeout(() => exitAfterTrace(1, 'failure-timeout'), 100).unref()
}

function captureProtocolPacket (packet, metadata) {
  if (!protocolTraceFile) return

  try {
    const packetName = packetNameFromMetadata(metadata)
    const kind = classifyProtocolPacket(packetName)
    if (kind == null) return

    protocolTraceObservedEvents++
    const normalizedPacketName = packetName.toLowerCase()
    if (protocolTraceAggregatePacketAllowlist != null &&
        protocolTraceAggregatePacketAllowlist.has(normalizedPacketName)) {
      aggregateProtocolPacket(normalizedPacketName, Date.now())
      protocolTraceAggregatedEvents++
      return
    }
    if (protocolTracePacketAllowlist != null &&
        !protocolTracePacketAllowlist.has(normalizedPacketName)) {
      protocolTraceFilteredEvents++
      return
    }

    const entityIds = extractEntityIds(packet)
    const entityType = kind === 'spawn' ? extractEntityType(packetName, packet) : null
    const event = {
      epochMs: Date.now(),
      packetName,
      protocolState: metadata && metadata.state != null
        ? summarizePrimitive(metadata.state)
        : client.state,
      kind,
      entityIds,
      entityType,
      bundleDelimiter: null,
      parseStatus: lifecycleParseStatus(kind, entityIds, entityType),
      rawSummary: summarizePacket(packet)
    }

    if (kind === 'bundle-delimiter') {
      bundleDelimiterCount++
      event.bundleDelimiter = {
        ordinal: bundleDelimiterCount,
        boundary: bundleOpen ? 'end' : 'start'
      }
      bundleOpen = !bundleOpen
      if (ending && exitCode === 0 && !bundleOpen) finishStop()
    }

    if (protocolTraceEvents.length >= protocolTraceMaxEvents) {
      protocolTraceDroppedEvents++
      return
    }
    protocolTraceEvents.push(event)
  } catch (error) {
    protocolTraceParseErrorCount++
    if (protocolTraceParseErrors.length < 32) {
      protocolTraceParseErrors.push({
        epochMs: Date.now(),
        packetName: packetNameFromMetadata(metadata),
        message: String(error && (error.stack || error.message) || error)
      })
    }
  }
}

function aggregateProtocolPacket (packetName, epochMs) {
  let series = protocolTracePacketCountSeries.get(packetName)
  if (series == null) {
    series = new Map()
    protocolTracePacketCountSeries.set(packetName, series)
  }
  series.set(epochMs, (series.get(epochMs) || 0) + 1)
}

function packetNameFromMetadata (metadata) {
  return metadata && typeof metadata.name === 'string' && metadata.name.length > 0
    ? metadata.name
    : '<unknown>'
}

function classifyProtocolPacket (packetName) {
  const normalized = String(packetName).toLowerCase()
  if (normalized === 'bundle_delimiter' || normalized === 'bundle-delimiter') {
    return 'bundle-delimiter'
  }
  if (/^(?:spawn_(?:entity|entity_living|living_entity|mob|player|painting|weather|global_entity|entity_experience_orb|experience_orb)|named_entity_spawn|add_(?:entity|mob|player))$/.test(normalized)) {
    return 'spawn'
  }
  if (/^(?:entity_destroy|destroy_(?:entity|entities)|remove_(?:entity|entities))$/.test(normalized)) {
    return 'destroy'
  }
  if (/^(?:tile_entity_data|block_entity_data|open_sign_entity)$/.test(normalized)) return null
  if (/(?:^|_)(?:entity|entities)(?:_|$)/.test(normalized) ||
      /^(?:set_passengers|attach_entity|collect|rel_entity_move|entity_move_look)$/.test(normalized)) {
    return 'entity'
  }
  return null
}

function lifecycleParseStatus (kind, entityIds, entityType) {
  if (kind === 'bundle-delimiter') return 'ok'
  if (kind === 'spawn') {
    if (entityIds.length === 0 || entityType == null) return 'partial'
    return 'ok'
  }
  if (kind === 'destroy') return entityIds.length === 0 ? 'partial' : 'ok'
  return entityIds.length === 0 ? 'raw-fallback' : 'ok'
}

function extractEntityIds (packet) {
  if (packet == null || typeof packet !== 'object') return []
  const values = []
  const singularNames = new Set([
    'id',
    'entityid',
    'vehicleid',
    'passengerid',
    'collectedentityid',
    'collectorentityid',
    'targetentityid',
    'sourceentityid',
    'playerid'
  ])
  const pluralNames = new Set([
    'ids',
    'entityids',
    'entities',
    'passengers'
  ])

  for (const [key, value] of Object.entries(packet)) {
    const normalizedKey = key.replace(/[^a-z0-9]/gi, '').toLowerCase()
    if (singularNames.has(normalizedKey) || normalizedKey.endsWith('entityid')) {
      appendEntityId(values, value)
    } else if (pluralNames.has(normalizedKey)) {
      if (Array.isArray(value) || (ArrayBuffer.isView(value) && !Buffer.isBuffer(value))) {
        for (const nested of value) appendEntityId(values, nested)
      } else {
        appendEntityId(values, value)
      }
    }
  }

  return [...new Map(values.map(value => [String(value), value])).values()]
}

function appendEntityId (target, value) {
  if (typeof value === 'number' && Number.isSafeInteger(value)) {
    target.push(value)
    return
  }
  if (typeof value === 'bigint') {
    const numeric = Number(value)
    target.push(Number.isSafeInteger(numeric) ? numeric : value.toString())
    return
  }
  if (typeof value === 'string' && /^-?\d+$/.test(value)) {
    const numeric = Number(value)
    target.push(Number.isSafeInteger(numeric) ? numeric : value)
  }
}

function extractEntityType (packetName, packet) {
  if (packet && typeof packet === 'object') {
    const typeFieldNames = new Set(['entitytype', 'type', 'mobtype', 'objecttype', 'entitytypeid', 'typeid'])
    for (const [key, value] of Object.entries(packet)) {
      const normalizedKey = key.replace(/[^a-z0-9]/gi, '').toLowerCase()
      if (typeFieldNames.has(normalizedKey) && value != null) return summarizeValue(value, 0)
    }
  }

  const normalized = String(packetName).toLowerCase()
  if (normalized.includes('player') || normalized === 'named_entity_spawn') return 'player'
  if (normalized.includes('experience_orb')) return 'experience_orb'
  if (normalized.includes('painting')) return 'painting'
  if (normalized.includes('weather')) return 'weather'
  return null
}

function summarizePacket (packet) {
  if (packet == null || typeof packet !== 'object') return summarizeValue(packet, 0)
  const result = {}
  const entries = Object.entries(packet)
  for (const [key, value] of entries.slice(0, 32)) result[key] = summarizeValue(value, 0)
  if (entries.length > 32) result['<truncatedKeys>'] = entries.length - 32
  return result
}

function summarizeValue (value, depth) {
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value
  if (typeof value === 'bigint') return value.toString()
  if (typeof value === 'string') return value.length <= 256 ? value : `${value.slice(0, 256)}<truncated>`
  if (Buffer.isBuffer(value)) {
    return { kind: 'buffer', length: value.length, hexPrefix: value.subarray(0, 16).toString('hex') }
  }
  if (Array.isArray(value)) {
    return {
      kind: 'array',
      length: value.length,
      sample: value.slice(0, 8).map(nested => summarizeValue(nested, depth + 1))
    }
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value)
    if (depth >= 2) return { kind: 'object', keys: keys.slice(0, 16), truncatedKeys: Math.max(0, keys.length - 16) }
    const preview = {}
    for (const key of keys.slice(0, 16)) preview[key] = summarizeValue(value[key], depth + 1)
    if (keys.length > 16) preview['<truncatedKeys>'] = keys.length - 16
    return preview
  }
  return String(value)
}

function summarizePrimitive (value) {
  const summarized = summarizeValue(value, 0)
  return summarized != null && typeof summarized === 'object' ? safeJson(summarized) : summarized
}

function flushProtocolTrace (reason, requestedExitCode) {
  if (!protocolTraceFile || protocolTraceWritten) return true

  const trace = {
    schemaVersion: 1,
    producer: 'phase2-protocol-client',
    direction: 'clientbound',
    clientVersion: version,
    keepAliveTimeoutMs,
    capturePacketAllowlist: protocolTracePacketAllowlist == null
      ? null
      : [...protocolTracePacketAllowlist].sort(),
    aggregatePacketAllowlist: protocolTraceAggregatePacketAllowlist == null
      ? null
      : [...protocolTraceAggregatePacketAllowlist].sort(),
    packetCountSeriesResolutionMs: 1,
    packetCountSeriesTimestampSource: 'Date.now',
    packetCountSeriesTimestampSemantics: 'point-observation',
    server: { host, port },
    startedEpochMs: protocolTraceStartedEpochMs,
    endedEpochMs: Date.now(),
    status: {
      complete: true,
      finalizationReason: reason,
      requestedExitCode,
      observedEvents: protocolTraceObservedEvents,
      filteredEvents: protocolTraceFilteredEvents,
      capturedEvents: protocolTraceEvents.length,
      aggregatedEvents: protocolTraceAggregatedEvents,
      maxBufferedEvents: protocolTraceMaxEvents,
      memoryBuffered: true,
      droppedEvents: protocolTraceDroppedEvents,
      parseErrorCount: protocolTraceParseErrorCount,
      parseErrors: protocolTraceParseErrors,
      writeErrorsBeforeSuccess: protocolTraceWriteErrors,
      bundleDelimiterCount,
      openBundleAtEnd: bundleOpen
    },
    events: protocolTraceEvents,
    packetCountSeries: Object.fromEntries(
      [...protocolTracePacketCountSeries.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([packetName, series]) => [
          packetName,
          [...series.entries()]
            .sort(([left], [right]) => left - right)
            .map(([epochMs, count]) => ({ epochMs, count }))
        ])
    )
  }
  const temporaryPath = `${protocolTraceFile}.tmp-${process.pid}-${Date.now()}`

  try {
    fs.writeFileSync(temporaryPath, `${JSON.stringify(trace)}\n`, 'utf8')
    fs.renameSync(temporaryPath, protocolTraceFile)
    protocolTraceWritten = true
    return true
  } catch (error) {
    protocolTraceWriteErrors.push(String(error && (error.stack || error.message) || error))
    try {
      fs.rmSync(temporaryPath, { force: true })
    } catch (_) {
      // The original trace write failure is the useful diagnostic.
    }
    console.error(`protocol trace write failed: ${protocolTraceWriteErrors[protocolTraceWriteErrors.length - 1]}`)
    return false
  }
}

function exitAfterTrace (code, reason) {
  process.exit(flushProtocolTrace(reason, code) ? code : 1)
}

function cleanupTimers () {
  clearTimeout(readyTimeout)
  clearInterval(tickEndInterval)
  clearInterval(movementInterval)
  if (stopFinalized && bundleDrainTimeout != null) {
    clearTimeout(bundleDrainTimeout)
    bundleDrainTimeout = null
  }
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
    keepAliveTimeoutMs,
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

function optionalPath (name) {
  const value = process.env[name]
  return value ? path.resolve(value) : null
}

function ensureParent (file) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
}

function parseInteger (value, fallback) {
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) ? parsed : fallback
}

function parsePositiveInteger (value, fallback) {
  const parsed = parseInteger(value, fallback)
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new Error(`expected a positive integer but received: ${value}`)
  }
  return parsed
}

function parsePacketNameAllowlist (value) {
  if (value == null || String(value).trim() === '') return null
  const names = String(value)
    .split(',')
    .map(name => name.trim().toLowerCase())
    .filter(name => name.length > 0)
  if (names.length === 0) throw new Error('protocol trace packet allowlist is empty')
  for (const name of names) {
    if (!/^[a-z0-9_-]+$/.test(name)) {
      throw new Error(`invalid protocol trace packet name: ${name}`)
    }
  }
  return new Set(names)
}

function assertDisjointPacketAllowlists (capture, aggregate) {
  if (capture == null || aggregate == null) return
  const overlap = [...capture].filter(name => aggregate.has(name)).sort()
  if (overlap.length > 0) {
    throw new Error(`protocol trace capture and aggregate allowlists overlap: ${overlap.join(',')}`)
  }
}

function safeJson (value) {
  try {
    return JSON.stringify(value, (_, nested) => typeof nested === 'bigint' ? nested.toString() : nested)
  } catch (_) {
    return String(value)
  }
}
