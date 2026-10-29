const baseUrl = required('IOT_GATEWAY_BASE_URL');
const authorization = required('IOT_TEST_AUTHORIZATION');
const tenantCode = required('IOT_TEST_TENANTCODE');

const headers = { Authorization: authorization, Tenantcode: tenantCode };
const reads = [];

async function read(path) {
  const response = await fetch(new URL(path, baseUrl), {
    headers,
    signal: AbortSignal.timeout(30_000),
  });
  const body = await response.json().catch(() => null);
  return { response, body };
}

function records(content) {
  if (Array.isArray(content)) return content;
  if (content && Array.isArray(content.content)) return content.content;
  return [];
}

function required(name) {
  const value = process.env[name];
  if (!value || !value.trim()) throw new Error(`missing ${name}`);
  return value;
}

const account = await read('/sso-api/account/me');
reads.push({
  path: '/sso-api/account/me',
  status: account.response.status,
  code: account.body?.code ?? null,
  principalIdPresent: typeof account.body?.content?.id === 'string' && account.body.content.id.trim().length > 0,
});

const offices = await read('/micro-api/api/office/office/member/my/office');
const officeRows = records(offices.body?.content);
reads.push({
  path: '/micro-api/api/office/office/member/my/office',
  status: offices.response.status,
  code: offices.body?.code ?? null,
  recordCount: officeRows.length,
});

const routes = [
  ['/micro-api/api/office/device/page', { type: 'LIGHTING' }],
  ['/micro-api/api/office/device/page', { type: 'SECURITY_PROTECTION', subClass: 'ACCESS' }],
  ['/micro-api/api/office/device/page', { type: 'SECURITY_PROTECTION', subClass: 'VIDEO' }],
  ['/micro-api/api/office/app/device/ac/myOffice/pageByAc', { type: 'AirCold' }],
];

for (const [path, params] of routes) {
  let successfulPages = 0;
  let recordCount = 0;
  for (const office of officeRows) {
    const query = new URLSearchParams({
      ...params,
      elementId: String(office.elementId),
      page: '0',
      size: '100',
    });
    const result = await read(`${path}?${query}`);
    if (result.response.status === 200 && (result.body?.code === 0 || result.body?.code === 200)) successfulPages += 1;
    recordCount += records(result.body?.content).length;
  }
  reads.push({ path, officeCount: officeRows.length, successfulPages, recordCount });
}

console.log(JSON.stringify({ baseHost: new URL(baseUrl).host, reads }));
