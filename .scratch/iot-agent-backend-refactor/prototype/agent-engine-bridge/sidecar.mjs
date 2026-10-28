import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { Agent } from '../../../../../iot-agent-runtime/node_modules/@earendil-works/pi-agent-core/dist/index.js'
import { Type } from '../../../../../iot-agent-runtime/node_modules/@earendil-works/pi-ai/dist/index.js'
import { fauxAssistantMessage, fauxProvider, fauxText, fauxToolCall } from '../../../../../iot-agent-runtime/node_modules/@earendil-works/pi-ai/dist/providers/faux.js'

const port = Number(process.env.PORT || 3211)
const activeRuns = new Map()
const runRecords = new Map()
const sessions = new Map()

function json(res, status, value) {
  res.statusCode = status
  res.setHeader('content-type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(value))
}

async function readJson(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
}

function eventContext(record) {
  return { runId: record.runId, conversationId: record.conversationId, turnId: record.turnId }
}

function writeEvent(record, res, event) {
  const payload = { ...eventContext(record), ...event }
  record.events.push(payload)
  if (record.connected && !res.writableEnded) {
    res.write(`${JSON.stringify(payload)}\n`)
    res.flushHeaders?.()
  }
}

function assistantText(messages) {
  const message = [...messages].reverse().find(item => item?.role === 'assistant')
  if (!message) return ''
  if (typeof message.content === 'string') return message.content
  return message.content.filter(block => block.type === 'text').map(block => block.text).join('')
}

function toolFor({ runId, toolBridgeUrl }) {
  return {
    name: 'echo_tool',
    label: 'Java bridge echo',
    description: 'Call the Java Tool Bridge and return its independent result.',
    parameters: Type.Object({
      text: Type.String({ minLength: 1 }),
      delayMs: Type.Optional(Type.Integer({ minimum: 0, maximum: 10000 })),
    }),
    async execute(_toolCallId, args, signal) {
      const response = await fetch(`${toolBridgeUrl}/tool`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ runId, toolName: 'echo_tool', arguments: args }),
        signal,
      })
      if (!response.ok) throw new Error(`Java Tool Bridge returned ${response.status}`)
      const result = await response.json()
      if (!result.ok) throw new Error(result.text || 'Java Tool Bridge rejected the call')
      return { content: [{ type: 'text', text: result.text }], details: result.details }
    },
  }
}

function createAgent(body, record) {
  const provider = fauxProvider(body.slow ? { tokensPerSecond: 1 } : {})
  provider.setResponses([
    fauxAssistantMessage(fauxToolCall('echo_tool', {
      text: String(body.content || 'bridge probe'),
      ...(body.slow ? { delayMs: 3000 } : {}),
    }), { stopReason: 'toolUse' }),
    fauxAssistantMessage(fauxText('Agent Engine 完成：Java Tool Bridge 已返回结果。')),
  ])
  return new Agent({
    initialState: {
      model: provider.getModel(),
      messages: structuredClone(sessions.get(record.conversationId) || []),
      tools: [toolFor({
        runId: record.runId,
        toolBridgeUrl: String(body.toolBridgeUrl || 'http://127.0.0.1:3200/prototype/bridge'),
      })],
      systemPrompt: '这是架构探针。先调用 echo_tool 获取 Java Tool Bridge 的事实，再用一句话完成答复。',
    },
    streamFn: provider.provider.streamSimple.bind(provider.provider),
  })
}

async function executeRun(body, record, res) {
  const agent = createAgent(body, record)
  record.agent = agent
  activeRuns.set(record.runId, record)
  let aborted = false
  let timedOut = false
  const timeout = record.timeoutMs > 0 ? setTimeout(() => {
    timedOut = true
    agent.abort()
  }, record.timeoutMs) : undefined

  agent.subscribe((event, signal) => {
    writeEvent(record, res, {
      type: `pi.${event.type}`,
      ...(event.type === 'message_update' ? { assistantMessageEvent: event.assistantMessageEvent } : {}),
      ...(event.type === 'tool_execution_start' ? { toolName: event.toolName, args: event.args } : {}),
      ...(event.type === 'tool_execution_end' ? { toolName: event.toolName, isError: event.isError, result: event.result } : {}),
    })
    if (event.type === 'agent_end') {
      aborted = signal.aborted
      const text = assistantText(event.messages)
      for (const chunk of text.match(/.{1,12}/gu) || []) writeEvent(record, res, { type: 'message.delta', phase: 'draft', content: chunk })
      if (text) writeEvent(record, res, { type: 'message.completed', content: text })
    }
  })

  try {
    if (record.cancelRequested) agent.abort()
    await agent.prompt(String(body.content || 'bridge probe'))
    sessions.set(record.conversationId, structuredClone(agent.state.messages))
    if (timedOut) writeEvent(record, res, { type: 'run.failed', message: '运行超时' })
    else if (aborted || record.cancelRequested) writeEvent(record, res, { type: 'run.aborted', outcome: 'stopped' })
    else writeEvent(record, res, { type: 'run.completed', outcome: 'message' })
  } catch (error) {
    writeEvent(record, res, { type: 'run.failed', message: error instanceof Error ? error.message : String(error) })
  } finally {
    clearTimeout(timeout)
    record.done = true
    activeRuns.delete(record.runId)
    if (!res.writableEnded) res.end()
  }
}

async function run(req, res) {
  const body = await readJson(req)
  const runId = String(body.runId || randomUUID())
  if (runRecords.has(runId)) return json(res, 409, { code: 'RUN_ID_CONFLICT', runId })
  const record = {
    runId,
    conversationId: String(body.conversationId || 'probe-conversation'),
    turnId: String(body.turnId || randomUUID()),
    timeoutMs: Number(body.timeoutMs || 0),
    cancelRequested: false,
    connected: true,
    done: false,
    events: [],
    agent: undefined,
  }
  runRecords.set(runId, record)
  res.statusCode = 200
  res.setHeader('content-type', 'application/x-ndjson; charset=utf-8')
  res.setHeader('cache-control', 'no-store')
  res.on('close', () => { record.connected = false })
  writeEvent(record, res, { type: 'run.started' })
  void executeRun(body, record, res)
}

async function replay(res, runId) {
  const record = runRecords.get(runId)
  if (!record) return json(res, 404, { code: 'RUN_NOT_FOUND', runId })
  res.statusCode = 200
  res.setHeader('content-type', 'application/x-ndjson; charset=utf-8')
  res.end(record.events.map(event => JSON.stringify(event)).join('\n') + (record.events.length ? '\n' : ''))
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/healthz') return json(res, 200, { service: 'pi-agent-engine-probe', status: 'ok' })
    if (req.method === 'POST' && req.url === '/run') return await run(req, res)
    const replayMatch = req.method === 'GET' && /^\/runs\/([^/]+)\/events$/.exec(req.url || '')
    if (replayMatch) return await replay(res, replayMatch[1])
    const cancelMatch = req.method === 'POST' && /^\/cancel\/([^/]+)$/.exec(req.url || '')
    if (cancelMatch) {
      const runId = cancelMatch[1]
      const record = runRecords.get(runId)
      if (!record || record.done) return json(res, 200, { runId, aborted: false })
      record.cancelRequested = true
      record.agent?.abort()
      return json(res, 200, { runId, aborted: true })
    }
    return json(res, 404, { code: 'NOT_FOUND' })
  } catch (error) {
    return json(res, 500, { code: 'PROBE_FAILED', message: error instanceof Error ? error.message : String(error) })
  }
})

server.listen(port, '127.0.0.1', () => {
  console.log(JSON.stringify({ service: 'pi-agent-engine-probe', port }))
})
