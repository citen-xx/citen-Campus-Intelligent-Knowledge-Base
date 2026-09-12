import fs from 'node:fs/promises'
import path from 'node:path'
import { performance } from 'node:perf_hooks'

const baseUrl = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
const workloadPath = process.env.PERFORMANCE_WORKLOAD || 'evaluation/data/performance-workload.json'
const outputPath = process.env.PERFORMANCE_RESULT || 'evaluation/results/performance-result.json'
const warmups = positiveInteger(process.env.WARMUP_RUNS || '1', 'WARMUP_RUNS')
const repeats = positiveInteger(process.env.MEASURED_RUNS || '5', 'MEASURED_RUNS')
const delayMs = nonNegativeInteger(process.env.REQUEST_DELAY_MS || '6500', 'REQUEST_DELAY_MS')
const workload = JSON.parse(await fs.readFile(workloadPath, 'utf8'))
if (!Array.isArray(workload.questions) || workload.questions.length === 0) {
  throw new Error('performance workload is empty; add a fixed real question set before measuring')
}

const cacheBefore = await readCacheMetrics()
for (let index = 0; index < warmups; index += 1) {
  for (const question of workload.questions) {
    await measure(question, `warmup-${index}-${crypto.randomUUID()}`)
    await delay(delayMs)
  }
}

const samples = []
for (let index = 0; index < repeats; index += 1) {
  for (const question of workload.questions) {
    samples.push(await measure(question, `measure-${index}-${crypto.randomUUID()}`))
    await delay(delayMs)
  }
}
const cacheAfter = await readCacheMetrics()
const firstToken = samples.map(item => item.firstTokenMs).filter(Number.isFinite)
const complete = samples.map(item => item.completeMs).filter(Number.isFinite)
const report = {
  generatedAt: new Date().toISOString(),
  workloadVersion: workload.workloadVersion,
  configuration: {
    baseUrl,
    warmups,
    repeats,
    delayMs,
    firstTokenDefinition: 'elapsed time to the first SSE answer event'
  },
  firstTokenMs: stats(firstToken),
  completeResponseMs: stats(complete),
  cacheMetrics: { before: cacheBefore, after: cacheAfter },
  samples
}
await fs.mkdir(path.dirname(outputPath), { recursive: true })
await fs.writeFile(outputPath, JSON.stringify(report, null, 2), 'utf8')

async function measure(question, conversationId) {
  const start = performance.now()
  let firstTokenMs = null
  let buffer = ''
  let answerChars = 0
  let sourcesCount = 0
  const response = await fetch(`${baseUrl}/api/ai/chat/public/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt: question, conversationId })
  })
  if (!response.ok) throw new Error(`benchmark request failed with HTTP ${response.status}`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''
    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      const event = JSON.parse(line.slice(5).trim())
      if (event.event === 'answer') {
        if (firstTokenMs === null) firstTokenMs = performance.now() - start
        if (typeof event.answer !== 'string') throw new Error('answer event is missing answer text')
        answerChars += event.answer.length
      }
      if (event.event === 'sources' && Array.isArray(event.sources)) sourcesCount = event.sources.length
    }
  }
  if (firstTokenMs === null) throw new Error('SSE stream completed without an answer event')
  return {
    question,
    firstTokenMs: round(firstTokenMs),
    completeMs: round(performance.now() - start),
    answerChars,
    sourcesCount
  }
}

async function readCacheMetrics() {
  const token = process.env.ADMIN_TOKEN
  if (!token) return null
  const response = await fetch(`${baseUrl}/system/ai/metrics/cache`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  if (!response.ok) throw new Error(`cache metrics failed with HTTP ${response.status}`)
  const payload = await response.json()
  return payload.data ?? payload
}

function stats(values) {
  if (values.length === 0) return { sampleCount: 0, average: null, p50: null, p95: null }
  const sorted = [...values].sort((a, b) => a - b)
  return {
    sampleCount: sorted.length,
    average: round(sorted.reduce((sum, value) => sum + value, 0) / sorted.length),
    p50: percentile(sorted, 0.50),
    p95: percentile(sorted, 0.95)
  }
}

function percentile(sorted, quantile) {
  return round(sorted[Math.ceil(sorted.length * quantile) - 1])
}

function round(value) {
  return value === null ? null : Math.round(value * 100) / 100
}

function positiveInteger(value, name) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`${name} must be a positive integer`)
  return parsed
}

function nonNegativeInteger(value, name) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed < 0) throw new Error(`${name} must be a non-negative integer`)
  return parsed
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}
