import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

// Preview-only safety guard. It forwards real reads, never fabricated responses.
const readPaths = new Set([
  '/sso-api/account/me',
  '/micro-api/api/office/office/member/my/office',
  '/micro-api/api/office/app/device/ac/myOffice/pageByAc',
  '/micro-api/api/office/device/page',
  '/micro-api/api/office/acAreaDevice/page',
  '/micro-api/api/office/lightingAreaDevice/page',
  '/micro-api/api/office/accessAreaDevice/page',
  '/micro-api/api/iot-center/device/page',
  '/micro-api/api/iot-center/home/yq/env/data',
  '/micro-api/api/iot-center/env/history/page',
  '/micro-api/api/energy/equipment/page',
  '/micro-api/api/energy/realTimeRead/page',
  '/micro-api/api/energy/electricData/page',
  '/micro-api/api/perception/device/page',
]);
const smartRead = /^\/micro-api\/api\/office\/app\/device\/(?:ac|lighting|access|monitor)\/(?:myAreaList|devicePage|myOffice\/pageByAc|myOffice\/pageByLighting|myOffice\/pageByAccess|myOffice\/pageByMonitor)$/;
const ledgerRead = /^\/micro-api\/api\/perception\/fire\/(?:gas|smoke|flooding)\/alarm\/page$/;
const deviceDetail = /^\/micro-api\/api\/iot-center\/device\/(?:[0-9]{1,20}|[a-f0-9]{32}|[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12})$/i;
const queryRoute = /^\/micro-api\/api\/iot-center\/interactive\/([^/]+)\/([^/]+)\/down$/;
const readActionTypes = new Set(['realTimeData', 'acDetail', 'view']);
const playbackActionId = 'bec190c9819348e08b203a95b7629ad8';

export function createPreviewProxy(upstreamUrl) {
  const origin = new URL(upstreamUrl).origin;
  const grants = new Map();
  const counts = { forwarded: 0, denied: 0, failed: 0 };
  const identity = headers => createHash('sha256')
    .update(`${headers.authorization ?? ''}\n${headers.tenantcode ?? ''}`).digest('hex');
  function observeCatalogue(value, owner, now) {
    if (!value || typeof value !== 'object') return;
    if (Array.isArray(value)) {
      for (const row of value) observeCatalogue(row, owner, now);
      return;
    }
    if (typeof value.id === 'string' && Array.isArray(value.actionList)) {
      for (const action of value.actionList) {
        if (readActionTypes.has(action.actionType) && typeof action.id === 'string') {
          grants.set(`${owner}:${value.id}:${action.id}`, now + 60_000);
        }
      }
    }
    for (const key of ['content', 'records', 'items']) observeCatalogue(value[key], owner, now);
  }
  function json(response, status, value) {
    response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    response.end(JSON.stringify(value));
  }
  return createServer(async (request, response) => {
    try {
      if (request.url === '/__preview/status' && request.method === 'GET') {
        return json(response, 200, { service: 'business-preview-readonly-upstream', ...counts });
      }
      if (!request.url?.startsWith('/') || request.url.startsWith('//')) {
        counts.denied++;
        return json(response, 403, { code: 'PREVIEW_UPSTREAM_WRITE_BLOCKED' });
      }
      const url = new URL(request.url, origin);
      const path = decodeURIComponent(url.pathname);
      let size = 0;
      const chunks = [];
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 65_536) return json(response, 413, { code: 'PREVIEW_REQUEST_TOO_LARGE' });
        chunks.push(chunk);
      }
      const body = Buffer.concat(chunks);
      const owner = identity(request.headers);
      const now = Date.now();
      for (const [key, expires] of grants) if (expires <= now) grants.delete(key);
      while (grants.size > 4096) grants.delete(grants.keys().next().value);
      const readGet = request.method === 'GET'
        && (readPaths.has(path) || smartRead.test(path) || ledgerRead.test(path) || deviceDetail.test(path));
      const query = queryRoute.exec(path);
      let readPost = false;
      if (request.method === 'POST' && query) {
        const args = body.length ? JSON.parse(body.toString()) : {};
        const keys = Object.keys(args);
        const emptyQuery = keys.length === 0 && (grants.get(`${owner}:${query[1]}:${query[2]}`) ?? 0) > now;
        const playback = query[2] === playbackActionId && keys.length === 2
          && typeof args.beginTime === 'string' && typeof args.endTime === 'string'
          && keys.every(key => key === 'beginTime' || key === 'endTime');
        const liveView = (grants.get(`${owner}:${query[1]}:${query[2]}`) ?? 0) > now
          && keys.every(key => key === 'type') && (args.type === undefined || args.type === 0);
        readPost = emptyQuery || playback || liveView;
      }
      if (request.method === 'POST' && path === '/micro-api/api/office/app/device/monitor/down') {
        const args = JSON.parse(body.toString());
        readPost = args !== null && !Array.isArray(args) && Object.keys(args).length === 1
          && typeof args.monitorAreaDeviceId === 'string'
          && args.monitorAreaDeviceId.length > 0 && args.monitorAreaDeviceId.length <= 256;
      }
      if (url.origin !== origin || !request.headers.authorization || !request.headers.tenantcode || (!readGet && !readPost)) {
        counts.denied++;
        return json(response, 403, { code: 'PREVIEW_UPSTREAM_WRITE_BLOCKED', message: '预览环境禁止未经核实的上游写入' });
      }
      const headers = { authorization: request.headers.authorization, tenantcode: request.headers.tenantcode };
      if (request.headers['content-type']) headers['content-type'] = request.headers['content-type'];
      const upstream = await fetch(url, {
        method: request.method, headers, body: request.method === 'GET' ? undefined : body,
        redirect: 'manual', signal: AbortSignal.timeout(30_000),
      });
      if (upstream.status >= 300 && upstream.status < 400) {
        counts.failed++;
        return json(response, 502, { code: 'PREVIEW_UPSTREAM_REDIRECT_BLOCKED' });
      }
      const payload = Buffer.from(await upstream.arrayBuffer());
      if (upstream.ok && path === '/micro-api/api/iot-center/device/page') {
        const envelope = JSON.parse(payload.toString());
        if (envelope.code === 0 || envelope.code === 200) observeCatalogue(envelope.content, owner, now);
      }
      counts.forwarded++;
      response.writeHead(upstream.status, {
        'content-type': upstream.headers.get('content-type') || 'application/json',
        'cache-control': 'no-store',
      });
      response.end(payload);
    } catch {
      counts.failed++;
      if (!response.headersSent) json(response, 502, { code: 'PREVIEW_UPSTREAM_UNAVAILABLE' });
      else response.end();
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const upstream = process.env.BUSINESS_UPSTREAM_ORIGIN;
  if (!upstream) throw new Error('BUSINESS_UPSTREAM_ORIGIN is required');
  const port = Number(process.env.BUSINESS_UPSTREAM_PORT || 3245);
  const server = createPreviewProxy(upstream);
  server.listen(port, '127.0.0.1', () => console.log(`Business preview readonly upstream ready on 127.0.0.1:${port}`));
}
