import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { Agent } from '../../../../../iot-agent-runtime/node_modules/@earendil-works/pi-agent-core/dist/index.js'
import { Type } from '../../../../../iot-agent-runtime/node_modules/@earendil-works/pi-ai/dist/index.js'
import { fauxAssistantMessage, fauxProvider, fauxText, fauxToolCall } from '../../../../../iot-agent-runtime/node_modules/@earendil-works/pi-ai/dist/providers/faux.js'

const port = Number(process.env.PORT || 3211)
const runs = new Map()

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

function writeEvent(res, event) {
  res.write(`${JSON.stringify(event)}\n`)
}

function toolFor({ runId, toolBridgeUrl }) {
  return {
    name: 'echo_tool',
    label: 'Java bridge echo',
    description: 'Call the Java Tool Bridge and return its independent result.',
    parameters: Type.Object({ text: Type.String({ minLength: 1 }), delayMs: Type.Optional(Type.Integer({ minimum: 0, maximum: 10000 })) }),
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
      return {
        content: [{ type: 'text', text: result.text }],
        details: result.details,
      }
    },
  }
}

function assistantText(messages) {
  const message = [...messages].reverse().find(item => item?.role === 'assistant')
  if (!message) return ''
  if (typeof message.content === 'string') return message.content
  return message.content.filter(block => block.type === 'text').map(block => block.text).join('')
}

async function run(req, res) {
  const body = await readJson(req)
  const runId = String(body.runId || randomUUID())
  const provider = fauxProvider(body.slow ? { tokensPerSecond: 1 } : {})
  provider.setResponses([
    fauxAssistantMessage(fauxToolCall('echo_tool', { text: String(body.content || 'bridge probe'), ...(body.slow ? { delayMs: 3000 } : {}) }), { stopReason: 'toolUse' }),
    fauxAssistantMessage(fauxText('Agent Engine 完成：Java Tool Bridge 已返回结果。')),
  ])
  const agent = new Agent({
    initialState: {
      model: provider.getModel(),
      messages: [],
      tools: [toolFor({ runId, toolBridgeUrl: String(body.toolBridgeUrl || 'http://127.0.0.1:3200/prototype/bridge') })],
      systemPrompt: '这是架构探针。先调用 echo_tool 获取 Java Tool Bridge 的事实，再用一句话完成答复。',
    },
    streamFn: provider.provider.streamSimple.bind(provider.provider),
  })
  runs.set(runId, agent)
  res.statusCode = 200
  res.setHeader('content-type', 'application/x-ndjson; charset=utf-8')
  res.setHeader('cache-control', 'no-store')
  writeEvent(res, { type: 'run.started', runId })
  let aborted = false
  agent.subscribe((event, signal) => {
    writeEvent(res, {
      type: `pi.${event.type}`,
      runId,
      ...(event.type === 'message_update' ? { assistantMessageEvent: event.assistantMessageEvent } : {}),
      ...(event.type === 'tool_execution_start' ? { toolName: event.toolName, args: event.args } : {}),
      ...(event.type === 'tool_execution_end' ? { toolName: event.toolName, isError: event.isError, result: event.result } : {}),
    })
    if (event.type === 'agent_end') {
      aborted = signal.aborted
      const text = assistantText(event.messages)
      for (const chunk of text.match(/.{1,12}/gu) || []) writeEvent(res, { type: 'message.delta', runId, phase: 'draft', content: chunk })
      if (text) writeEvent(res, { type: 'message.completed', runId, content: text })
    }
    res.flushHeaders?.()
  })
  try {
    await agent.prompt(String(body.content || 'bridge probe'))
    writeEvent(res, { type: aborted ? 'run.aborted' : 'run.completed', runId, outcome: aborted ? 'stopped' : 'message' })
  } catch (error) {
    writeEvent(res, { type: 'run.failed', runId, message: error instanceof Error ? error.message : String(error) })
  } finally {
    runs.delete(runId)
    res.end()
  }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/healthz') return json(res, 200, { service: 'pi-agent-engine-probe', status: 'ok' })
    if (req.method === 'POST' && req.url === '/run') return await run(req, res)
    const cancel = req.method === 'POST' && /^\/cancel\/[^/]+$/.exec(req.url || '')
    if (cancel) {
      const runId = cancel[0].slice('/cancel/'.length)
      const agent = runs.get(runId)
      agent?.abort()
      return json(res, 200, { runId, aborted: Boolean(agent) })
    }
    return json(res, 404, { code: 'NOT_FOUND' })
  } catch (error) {
    return json(res, 500, { code: 'PROBE_FAILED', message: error instanceof Error ? error.message : String(error) })
  }
})

server.listen(port, '127.0.0.1', () => {
  console.log(JSON.stringify({ service: 'pi-agent-engine-probe', port }))
})
