'use strict'

// An offline semantic analyzer for phase2-protocol-client traces. Formal runs
// must provide an explicit half-open epoch window so these counts align with
// the corresponding pcap/MSPT evidence instead of including setup or teardown.

const fs = require('fs')
const path = require('path')

function main (argv) {
  const options = parseArguments(argv)
  if (options.help) {
    process.stdout.write(usage())
    return
  }
  if (options.selfTest) {
    runSelfTest()
    process.stdout.write('phase2 protocol trace analyzer self-test passed\n')
    return
  }

  const window = resolveWindow(options)
  const tracePath = path.resolve(requiredOption(options.trace, '--trace'))
  const trace = JSON.parse(fs.readFileSync(tracePath, 'utf8'))
  const result = analyzeTrace(trace, window.startEpochMs, window.endEpochMs, tracePath)
  const json = `${JSON.stringify(result, null, 2)}\n`

  if (options.output) writeOutput(path.resolve(options.output), json, options.overwrite)
  process.stdout.write(json)
}

function parseArguments (argv) {
  const options = { overwrite: false, selfTest: false, help: false }
  const valueOptions = new Map([
    ['--trace', 'trace'],
    ['--window-start-epoch-ms', 'windowStartEpochMs'],
    ['--window-end-epoch-ms', 'windowEndEpochMs'],
    ['--window-start-epoch-seconds', 'windowStartEpochSeconds'],
    ['--window-end-epoch-seconds', 'windowEndEpochSeconds'],
    ['--output', 'output']
  ])

  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index]
    if (argument === '--overwrite') {
      options.overwrite = true
    } else if (argument === '--self-test') {
      options.selfTest = true
    } else if (argument === '--help' || argument === '-h') {
      options.help = true
    } else if (valueOptions.has(argument)) {
      if (index + 1 >= argv.length) throw new Error(`${argument} requires a value`)
      options[valueOptions.get(argument)] = argv[++index]
    } else {
      throw new Error(`unknown argument: ${argument}`)
    }
  }
  return options
}

function resolveWindow (options) {
  const hasMilliseconds = options.windowStartEpochMs != null || options.windowEndEpochMs != null
  const hasSeconds = options.windowStartEpochSeconds != null || options.windowEndEpochSeconds != null
  if (hasMilliseconds && hasSeconds) {
    throw new Error('choose either epoch-millisecond or epoch-second window arguments, not both')
  }
  if (!hasMilliseconds && !hasSeconds) {
    throw new Error('an explicit half-open window is required')
  }

  const multiplier = hasSeconds ? 1000 : 1
  const startRaw = hasSeconds ? options.windowStartEpochSeconds : options.windowStartEpochMs
  const endRaw = hasSeconds ? options.windowEndEpochSeconds : options.windowEndEpochMs
  if (startRaw == null || endRaw == null) throw new Error('both window start and end are required')
  const startEpochMs = finiteNonNegativeNumber(startRaw, 'window start') * multiplier
  const endEpochMs = finiteNonNegativeNumber(endRaw, 'window end') * multiplier
  if (endEpochMs <= startEpochMs) throw new Error('window end must be greater than window start')
  return { startEpochMs, endEpochMs }
}

function analyzeTrace (trace, startEpochMs, endEpochMs, tracePath = null) {
  if (trace == null || typeof trace !== 'object' || Array.isArray(trace)) {
    throw new Error('trace root must be a JSON object')
  }
  if (!Array.isArray(trace.events)) throw new Error('trace.events must be an array')
  if (!Number.isFinite(startEpochMs) || !Number.isFinite(endEpochMs) ||
      startEpochMs < 0 || endEpochMs <= startEpochMs) {
    throw new Error('analysis requires a finite non-negative half-open window')
  }

  const normalization = normalizeEvents(trace.events)
  const allEvents = normalization.events.sort((left, right) =>
    left.epochMs - right.epochMs || left.sourceIndex - right.sourceIndex)
  const beforeWindow = allEvents.filter(event => event.epochMs < startEpochMs)
  const windowEvents = allEvents.filter(event => event.epochMs >= startEpochMs && event.epochMs < endEpochMs)
  const baseline = replayLifecycle(beforeWindow)
  const lifecycle = replayLifecycle(windowEvents, baseline.live, baseline.everSpawned)

  const packetCounts = new Map()
  const kindCounts = new Map()
  const parseStatusCounts = new Map()
  const spawnTypeCounts = new Map()
  const spawnPacketTypeCounts = new Map()
  const spawnIds = []
  const destroyIds = []
  let spawnEventsMissingIds = 0
  let destroyEventsMissingIds = 0

  for (const event of windowEvents) {
    increment(packetCounts, event.packetName)
    increment(kindCounts, event.kind)
    increment(parseStatusCounts, event.parseStatus)
    if (event.kind === 'spawn') {
      if (event.entityIds.length === 0) spawnEventsMissingIds++
      spawnIds.push(...event.entityIds)
      const type = entityTypeKey(event.entityType)
      increment(spawnTypeCounts, type, Math.max(1, event.entityIds.length))
      if (!spawnPacketTypeCounts.has(event.packetName)) spawnPacketTypeCounts.set(event.packetName, new Map())
      increment(spawnPacketTypeCounts.get(event.packetName), type, Math.max(1, event.entityIds.length))
    } else if (event.kind === 'destroy') {
      if (event.entityIds.length === 0) destroyEventsMissingIds++
      destroyIds.push(...event.entityIds)
    }
  }

  const traceStatus = trace.status && typeof trace.status === 'object' ? trace.status : {}
  const sourceParseErrorCount = nonNegativeInteger(
    traceStatus.parseErrorCount,
    Array.isArray(traceStatus.parseErrors) ? traceStatus.parseErrors.length : 0
  )
  const sourceDroppedEvents = nonNegativeInteger(traceStatus.droppedEvents, 0)
  const traceStartEpochMs = optionalFiniteNumber(trace.startedEpochMs)
  const traceEndEpochMs = optionalFiniteNumber(trace.endedEpochMs)
  const windowCovered = traceStartEpochMs != null && traceEndEpochMs != null &&
    traceStartEpochMs <= startEpochMs && traceEndEpochMs >= endEpochMs
  const evidenceParseStatusCounts = countParseStatuses([...beforeWindow, ...windowEvents])
  const partialWindowEventCount = nonOkParseStatusCount(parseStatusCounts)
  const partialEvidenceEventCount = nonOkParseStatusCount(evidenceParseStatusCounts)
  const criticalPartialWindowEventCount = nonOkLifecycleParseCount(windowEvents)
  const criticalPartialEvidenceEventCount = nonOkLifecycleParseCount([...beforeWindow, ...windowEvents])
  // Generic entity updates are retained as packet-rate evidence even when a
  // future protocol shape has no extractable entity id. Spawn/destroy identity
  // is the formal semantic claim, so only incomplete lifecycle records block it.
  const parseOk = sourceParseErrorCount === 0 && normalization.errors.length === 0 &&
    criticalPartialEvidenceEventCount === 0
  const dropOk = sourceDroppedEvents === 0 && normalization.droppedRecords === 0
  const traceComplete = traceStatus.complete === true
  const sourceExitCodeOk = traceStatus.requestedExitCode == null || Number(traceStatus.requestedExitCode) === 0
  const bundleBalanced = traceStatus.openBundleAtEnd !== true

  return {
    schemaVersion: 1,
    analyzer: 'analyze-phase2-protocol-trace',
    input: {
      tracePath,
      traceSchemaVersion: trace.schemaVersion == null ? null : trace.schemaVersion,
      producer: trace.producer == null ? null : String(trace.producer),
      clientVersion: trace.clientVersion == null ? null : String(trace.clientVersion),
      direction: trace.direction == null ? null : String(trace.direction)
    },
    window: {
      semantics: '[startEpochMs, endEpochMs)',
      startEpochMs,
      endEpochMs,
      durationMs: endEpochMs - startEpochMs,
      bucketAlignment: 'floor(epochMs / bucketMs), Unix epoch aligned'
    },
    traceCoverage: {
      startedEpochMs: traceStartEpochMs,
      endedEpochMs: traceEndEpochMs,
      windowCovered,
      sourceEventCount: trace.events.length,
      normalizedEventCount: allEvents.length,
      beforeWindowEventCount: beforeWindow.length,
      windowEventCount: windowEvents.length
    },
    counts: {
      byPacket: sortedMapObject(packetCounts),
      byKind: sortedMapObject(kindCounts),
      byParseStatus: sortedMapObject(parseStatusCounts),
      spawnByEntityType: sortedMapObject(spawnTypeCounts),
      spawnByPacketAndEntityType: sortedNestedMapObject(spawnPacketTypeCounts)
    },
    identity: {
      spawn: identitySummary(spawnIds, spawnEventsMissingIds),
      destroy: identitySummary(destroyIds, destroyEventsMissingIds),
      initialLiveIds: sortedIds([...baseline.live.keys()]),
      liveIds: sortedIds([...lifecycle.live.keys()]),
      liveIdCount: lifecycle.live.size,
      liveEntityTypes: sortedMapObject(new Map(
        [...lifecycle.live.entries()].map(([id, value]) => [id, value.entityType])
      )),
      duplicateLiveSpawnIds: sortedIds([...lifecycle.duplicateLiveSpawnIds]),
      duplicateLiveSpawnObservations: lifecycle.duplicateLiveSpawnObservations,
      reusedAfterDestroyIds: sortedIds([...lifecycle.reusedAfterDestroyIds]),
      destroyWithoutKnownLiveIds: sortedIds([...lifecycle.destroyWithoutKnownLiveIds]),
      destroyWithoutKnownLiveObservations: lifecycle.destroyWithoutKnownLiveObservations
    },
    spawnPeaks: {
      epochAligned50ms: spawnPeak(windowEvents, 50),
      epochAligned1s: spawnPeak(windowEvents, 1000)
    },
    bundle: bundleSummary(windowEvents),
    status: {
      traceComplete,
      sourceExitCodeOk,
      windowCovered,
      parse: {
        ok: parseOk,
        sourceParseErrorCount,
        sourceParseErrors: Array.isArray(traceStatus.parseErrors) ? traceStatus.parseErrors.slice(0, 32) : [],
        analyzerErrorCount: normalization.errors.length,
        analyzerErrors: normalization.errors.slice(0, 32),
        partialOrFallbackWindowEvents: partialWindowEventCount,
        partialOrFallbackEventsThroughWindowEnd: partialEvidenceEventCount,
        lifecycleCriticalPartialWindowEvents: criticalPartialWindowEventCount,
        lifecycleCriticalPartialEventsThroughWindowEnd: criticalPartialEvidenceEventCount
      },
      drop: {
        ok: dropOk,
        sourceDroppedEvents,
        analyzerDroppedRecords: normalization.droppedRecords
      },
      bundleBalanced,
      formalEvidenceReady: traceComplete && sourceExitCodeOk && windowCovered && parseOk && dropOk && bundleBalanced
    }
  }
}

function normalizeEvents (events) {
  const normalized = []
  const errors = []
  let droppedRecords = 0

  for (let index = 0; index < events.length; index++) {
    const raw = events[index]
    if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) {
      errors.push({ sourceIndex: index, message: 'event must be an object' })
      droppedRecords++
      continue
    }
    const epochMs = firstFiniteNumber(raw.epochMs, raw.epochMilliseconds, raw.timestampEpochMs)
    if (epochMs == null || epochMs < 0) {
      errors.push({ sourceIndex: index, message: 'event epochMs must be finite and non-negative' })
      droppedRecords++
      continue
    }

    const packetName = typeof raw.packetName === 'string' && raw.packetName.length > 0
      ? raw.packetName
      : '<unknown>'
    if (packetName === '<unknown>') errors.push({ sourceIndex: index, message: 'packet name missing; retained as <unknown>' })
    const kind = normalizeKind(raw.kind, packetName)
    const idResult = normalizeEntityIds(raw.entityIds != null ? raw.entityIds : raw.entityId)
    for (const message of idResult.errors) errors.push({ sourceIndex: index, message })
    normalized.push({
      sourceIndex: index,
      epochMs,
      packetName,
      kind,
      entityIds: idResult.ids,
      entityType: raw.entityType != null ? raw.entityType : raw.type,
      bundleDelimiter: raw.bundleDelimiter,
      parseStatus: typeof raw.parseStatus === 'string' ? raw.parseStatus : 'unknown'
    })
  }
  return { events: normalized, errors, droppedRecords }
}

function countParseStatuses (events) {
  const counts = new Map()
  for (const event of events) increment(counts, event.parseStatus)
  return counts
}

function nonOkParseStatusCount (counts) {
  let total = 0
  for (const [status, count] of counts) {
    if (status !== 'ok') total += count
  }
  return total
}

function nonOkLifecycleParseCount (events) {
  let total = 0
  for (const event of events) {
    if ((event.kind === 'spawn' || event.kind === 'destroy') && event.parseStatus !== 'ok') total++
  }
  return total
}

function normalizeKind (kind, packetName) {
  if (kind === 'spawn' || kind === 'destroy' || kind === 'entity' || kind === 'bundle-delimiter') return kind
  const normalized = packetName.toLowerCase()
  if (normalized === 'bundle_delimiter' || normalized === 'bundle-delimiter') return 'bundle-delimiter'
  if (normalized.startsWith('spawn_') || normalized === 'named_entity_spawn' || normalized.startsWith('add_entity')) return 'spawn'
  if (/^(?:entity_destroy|destroy_(?:entity|entities)|remove_(?:entity|entities))$/.test(normalized)) return 'destroy'
  return 'entity'
}

function normalizeEntityIds (raw) {
  const values = raw == null ? [] : Array.isArray(raw) ? raw : [raw]
  const ids = []
  const errors = []
  const seen = new Set()
  for (const value of values) {
    const id = canonicalEntityId(value)
    if (id == null) {
      errors.push(`ignored invalid entity id summary: ${safeStringify(value)}`)
    } else if (!seen.has(id)) {
      seen.add(id)
      ids.push(id)
    }
  }
  return { ids, errors }
}

function replayLifecycle (events, initialLive = new Map(), initialEverSpawned = new Set()) {
  const live = new Map([...initialLive.entries()].map(([id, value]) => [id, { ...value }]))
  const everSpawned = new Set(initialEverSpawned)
  const duplicateLiveSpawnIds = new Set()
  const reusedAfterDestroyIds = new Set()
  const destroyWithoutKnownLiveIds = new Set()
  let duplicateLiveSpawnObservations = 0
  let destroyWithoutKnownLiveObservations = 0

  for (const event of events) {
    if (event.kind === 'spawn') {
      for (const id of event.entityIds) {
        if (live.has(id)) {
          duplicateLiveSpawnIds.add(id)
          duplicateLiveSpawnObservations++
        } else if (everSpawned.has(id)) {
          reusedAfterDestroyIds.add(id)
        }
        live.set(id, { entityType: entityTypeKey(event.entityType), spawnedEpochMs: event.epochMs })
        everSpawned.add(id)
      }
    } else if (event.kind === 'destroy') {
      for (const id of event.entityIds) {
        if (!live.delete(id)) {
          destroyWithoutKnownLiveIds.add(id)
          destroyWithoutKnownLiveObservations++
        }
      }
    }
  }

  return {
    live,
    everSpawned,
    duplicateLiveSpawnIds,
    duplicateLiveSpawnObservations,
    reusedAfterDestroyIds,
    destroyWithoutKnownLiveIds,
    destroyWithoutKnownLiveObservations
  }
}

function identitySummary (ids, missingIdEvents) {
  const frequencies = new Map()
  for (const id of ids) increment(frequencies, id)
  const duplicateIds = [...frequencies.entries()].filter(([, count]) => count > 1).map(([id]) => id)
  const duplicateObservations = [...frequencies.values()].reduce((sum, count) => sum + Math.max(0, count - 1), 0)
  return {
    observations: ids.length,
    uniqueIdCount: frequencies.size,
    allObservationsUnique: duplicateObservations === 0,
    duplicateObservations,
    duplicateIds: sortedIds(duplicateIds),
    missingIdEvents
  }
}

function spawnPeak (events, bucketMs) {
  const buckets = new Map()
  for (const event of events) {
    if (event.kind !== 'spawn') continue
    const bucketIndex = Math.floor(event.epochMs / bucketMs)
    const bucket = buckets.get(bucketIndex) || { eventCount: 0, entityIdCount: 0 }
    bucket.eventCount++
    bucket.entityIdCount += event.entityIds.length
    buckets.set(bucketIndex, bucket)
  }

  let bestIndex = null
  let best = { eventCount: 0, entityIdCount: 0 }
  for (const [index, bucket] of buckets) {
    if (bucket.entityIdCount > best.entityIdCount ||
        (bucket.entityIdCount === best.entityIdCount && bucket.eventCount > best.eventCount) ||
        (bucket.entityIdCount === best.entityIdCount && bucket.eventCount === best.eventCount &&
          (bestIndex == null || index < bestIndex))) {
      bestIndex = index
      best = bucket
    }
  }
  return {
    bucketMs,
    alignment: 'floor(epochMs / bucketMs), Unix epoch aligned',
    bucketStartEpochMs: bestIndex == null ? null : bestIndex * bucketMs,
    bucketEndEpochMs: bestIndex == null ? null : (bestIndex + 1) * bucketMs,
    spawnEventCount: best.eventCount,
    spawnedEntityIdCount: best.entityIdCount
  }
}

function bundleSummary (events) {
  let delimiterEvents = 0
  let starts = 0
  let ends = 0
  let unknownBoundaries = 0
  for (const event of events) {
    if (event.kind !== 'bundle-delimiter') continue
    delimiterEvents++
    const boundary = event.bundleDelimiter && event.bundleDelimiter.boundary
    if (boundary === 'start') starts++
    else if (boundary === 'end') ends++
    else unknownBoundaries++
  }
  return { delimiterEvents, starts, ends, unknownBoundaries }
}

function entityTypeKey (value) {
  if (value == null) return '<unknown>'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value)
  return `json:${stableStringify(value)}`
}

function canonicalEntityId (value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) ? String(value) : null
  if (typeof value === 'string' && /^-?\d+$/.test(value)) {
    try {
      return BigInt(value).toString()
    } catch (_) {
      return null
    }
  }
  return null
}

function sortedIds (ids) {
  return [...ids].sort(compareEntityIds).map(id => {
    const numeric = Number(id)
    return Number.isSafeInteger(numeric) && String(numeric) === id ? numeric : id
  })
}

function compareEntityIds (left, right) {
  try {
    const leftBigInt = BigInt(left)
    const rightBigInt = BigInt(right)
    return leftBigInt < rightBigInt ? -1 : leftBigInt > rightBigInt ? 1 : 0
  } catch (_) {
    return String(left).localeCompare(String(right))
  }
}

function increment (map, key, amount = 1) {
  map.set(String(key), (map.get(String(key)) || 0) + amount)
}

function sortedMapObject (map) {
  return Object.fromEntries([...map.entries()].sort(([left], [right]) => String(left).localeCompare(String(right))))
}

function sortedNestedMapObject (map) {
  return Object.fromEntries([...map.entries()]
    .sort(([left], [right]) => String(left).localeCompare(String(right)))
    .map(([key, nested]) => [key, sortedMapObject(nested)]))
}

function stableStringify (value) {
  if (value == null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`
}

function firstFiniteNumber (...values) {
  for (const value of values) {
    const parsed = optionalFiniteNumber(value)
    if (parsed != null) return parsed
  }
  return null
}

function optionalFiniteNumber (value) {
  if (value == null || value === '') return null
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function finiteNonNegativeNumber (value, name) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric < 0) throw new Error(`${name} must be finite and non-negative`)
  return numeric
}

function nonNegativeInteger (value, fallback) {
  const numeric = Number(value)
  return Number.isSafeInteger(numeric) && numeric >= 0 ? numeric : fallback
}

function requiredOption (value, name) {
  if (value == null || value === '') throw new Error(`${name} is required`)
  return value
}

function writeOutput (outputPath, json, overwrite) {
  if (fs.existsSync(outputPath) && !overwrite) {
    throw new Error(`output already exists: ${outputPath}; pass --overwrite only after verifying the target`)
  }
  const parent = path.dirname(outputPath)
  if (!fs.existsSync(parent)) throw new Error(`output directory does not exist: ${parent}`)
  fs.writeFileSync(outputPath, json, 'utf8')
}

function safeStringify (value) {
  try {
    return JSON.stringify(value)
  } catch (_) {
    return String(value)
  }
}

function usage () {
  return [
    'Usage:',
    '  node tools/perf/analyze-phase2-protocol-trace.js --trace TRACE.json',
    '    --window-start-epoch-ms START --window-end-epoch-ms END [--output RESULT.json]',
    '  node tools/perf/analyze-phase2-protocol-trace.js --trace TRACE.json',
    '    --window-start-epoch-seconds START --window-end-epoch-seconds END',
    '  node tools/perf/analyze-phase2-protocol-trace.js --self-test',
    '',
    'The selected interval is half-open: [start, end).',
    ''
  ].join('\n')
}

function runSelfTest () {
  const trace = {
    schemaVersion: 1,
    producer: 'self-test',
    clientVersion: '26.2',
    direction: 'clientbound',
    startedEpochMs: 900,
    endedEpochMs: 2200,
    status: {
      complete: true,
      requestedExitCode: 0,
      droppedEvents: 0,
      parseErrorCount: 0,
      parseErrors: [],
      openBundleAtEnd: false
    },
    events: [
      event(950, 'spawn_entity', 'spawn', [99], 10),
      event(999, 'spawn_entity', 'spawn', [100], 10),
      event(1000, 'spawn_entity', 'spawn', [1], 11),
      event(1020, 'bundle_delimiter', 'bundle-delimiter', [], null, { ordinal: 1, boundary: 'start' }),
      event(1049, 'spawn_entity', 'spawn', [2], 11),
      event(1050, 'spawn_entity', 'spawn', [3], 12),
      event(1070, 'entity_destroy', 'destroy', [1], null),
      event(1080, 'spawn_entity', 'spawn', [1], 11),
      event(1090, 'spawn_entity', 'spawn', [2], 11),
      event(1100, 'remove_entities', 'destroy', [2, 99], null),
      event(1120, 'bundle_delimiter', 'bundle-delimiter', [], null, { ordinal: 2, boundary: 'end' }),
      event(1500, 'destroy_entities', 'destroy', [404], null),
      event(1600, 'entity_future_shape', 'entity', [], null, null, 'raw-fallback'),
      event(1999, 'spawn_entity', 'spawn', [4], { registry: 'minecraft:item_display' }),
      event(2000, 'spawn_entity', 'spawn', [5], 13)
    ]
  }

  const result = analyzeTrace(trace, 1000, 2000, '<self-test>')
  assertEqual(result.traceCoverage.windowEventCount, 12, 'half-open window event count')
  assertEqual(result.counts.byPacket.spawn_entity, 6, 'spawn packet count')
  assertEqual(result.counts.spawnByEntityType['11'], 4, 'numeric entity type count')
  assertEqual(result.identity.spawn.observations, 6, 'spawn id observations')
  assertEqual(result.identity.spawn.uniqueIdCount, 4, 'spawn unique ids')
  assertDeepEqual(result.identity.spawn.duplicateIds, [1, 2], 'spawn duplicate ids')
  assertDeepEqual(result.identity.initialLiveIds, [99, 100], 'initial live ids')
  assertDeepEqual(result.identity.liveIds, [1, 3, 4, 100], 'final live ids')
  assertDeepEqual(result.identity.duplicateLiveSpawnIds, [2], 'duplicate live spawn ids')
  assertDeepEqual(result.identity.reusedAfterDestroyIds, [1], 'reused ids')
  assertDeepEqual(result.identity.destroyWithoutKnownLiveIds, [404], 'destroy without live ids')
  assertEqual(result.spawnPeaks.epochAligned50ms.spawnEventCount, 3, '50ms spawn peak')
  assertEqual(result.spawnPeaks.epochAligned1s.spawnEventCount, 6, '1s spawn peak')
  assertEqual(result.bundle.starts, 1, 'bundle starts')
  assertEqual(result.bundle.ends, 1, 'bundle ends')
  assertEqual(result.status.parse.partialOrFallbackWindowEvents, 1, 'generic fallback count')
  assertEqual(result.status.parse.lifecycleCriticalPartialWindowEvents, 0, 'critical fallback count')
  assertEqual(result.status.formalEvidenceReady, true, 'formal evidence readiness')

  const malformed = JSON.parse(JSON.stringify(trace))
  malformed.events.push({ epochMs: 'not-a-time', packetName: 'spawn_entity' })
  const malformedResult = analyzeTrace(malformed, 1000, 2000)
  assertEqual(malformedResult.status.drop.analyzerDroppedRecords, 1, 'malformed record drop count')
  assertEqual(malformedResult.status.parse.ok, false, 'malformed parse status')
  assertEqual(malformedResult.status.formalEvidenceReady, false, 'malformed evidence readiness')

  const secondsWindow = resolveWindow({ windowStartEpochSeconds: '1', windowEndEpochSeconds: '2' })
  assertDeepEqual(secondsWindow, { startEpochMs: 1000, endEpochMs: 2000 }, 'second window conversion')
}

function event (epochMs, packetName, kind, entityIds, entityType, bundleDelimiter = null, parseStatus = 'ok') {
  return { epochMs, packetName, kind, entityIds, entityType, bundleDelimiter, parseStatus, rawSummary: {} }
}

function assertEqual (actual, expected, name) {
  if (actual !== expected) throw new Error(`${name}: expected ${expected}, received ${actual}`)
}

function assertDeepEqual (actual, expected, name) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${name}: expected ${JSON.stringify(expected)}, received ${JSON.stringify(actual)}`)
  }
}

if (require.main === module) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    console.error(error && (error.stack || error.message) || error)
    process.exitCode = 1
  }
}

module.exports = { analyzeTrace, parseArguments, resolveWindow }
