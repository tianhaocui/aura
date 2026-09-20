#!/usr/bin/env node

const http = require('http');
const https = require('https');
const packageMetadata = require('./package.json');

const url = process.env.API_URL || process.argv[2];
if (!url) {
  process.stderr.write('Usage: aura-mcp <url> or set API_URL\n');
  process.exit(1);
}

const baseUrl = url.endsWith('/') ? url.slice(0, -1) : url;
let parsedBaseUrl;
try {
  parsedBaseUrl = new URL(baseUrl);
} catch (_) {
  process.stderr.write('Invalid API_URL\n');
  process.exit(1);
}
if (!['http:', 'https:'].includes(parsedBaseUrl.protocol) || parsedBaseUrl.username || parsedBaseUrl.password) {
  process.stderr.write('Invalid API_URL: use HTTP(S) without userinfo\n');
  process.exit(1);
}
const MAX_HTTP_BYTES = 1024 * 1024;
const MAX_LINE_BYTES = 1024 * 1024;
const schemaToken = process.env.API_SCHEMA_TOKEN || process.env.AURA_SCHEMA_TOKEN || null;
let routes = null;

function httpRequest(method, path, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(path, baseUrl);
    if (u.origin !== parsedBaseUrl.origin) return reject(new Error('Cross-origin request target rejected'));
    const transport = u.protocol === 'https:' ? https : http;
    const opts = { method, hostname: u.hostname, port: u.port, path: u.pathname + u.search };
    if (schemaToken && path.endsWith('/__schema__')) opts.headers = { 'X-Aura-Schema-Token': schemaToken };
    const req = transport.request(opts, res => {
      let data = '';
      let size = 0;
      res.on('data', chunk => {
        size += chunk.length;
        if (size > MAX_HTTP_BYTES) {
          res.destroy(new Error('HTTP response exceeds maximum size'));
          return;
        }
        data += chunk;
      });
      res.on('end', () => resolve(data));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.setTimeout(10000, () => req.destroy(new Error('HTTP request timed out')));
    if (body) {
      req.setHeader('Content-Type', 'application/json');
      req.write(JSON.stringify(body));
    }
    req.end();
  });
}

async function loadSchema() {
  const data = await httpRequest('GET', baseUrl + '/__schema__');
  const schema = JSON.parse(data);
  routes = schema.routes || [];
}

function buildToolName(method, path) {
  let name = path.replace(/^\//, '').replace(/\{[a-zA-Z_]+\}/g, 'by_id').replace(/\/+/g, '_');
  if (!name) name = 'root';
  return method.toLowerCase() + '_' + name;
}

function buildInputSchema(params) {
  const properties = {};
  const required = [];
  (params || []).forEach(p => {
    const type = { int: 'integer', long: 'integer', Integer: 'integer', Long: 'integer',
      double: 'number', float: 'number', boolean: 'boolean', Boolean: 'boolean' }[p.type] || 'string';
    properties[p.name] = { type };
    if (p.description) properties[p.name].description = p.description;
    required.push(p.name);
  });
  return { type: 'object', properties, ...(required.length ? { required } : {}) };
}

function handleInitialize() {
  return { protocolVersion: '2025-03-26', capabilities: { tools: {}, resources: {}, prompts: {} },
    serverInfo: { name: 'aura-mcp', version: packageMetadata.version } };
}

function handleToolsList() {
  const tools = routes.map(r => ({
    name: buildToolName(r.method, r.path),
    ...(r.description ? { description: r.description } : {}),
    inputSchema: buildInputSchema(r.params)
  }));
  return { tools };
}

async function handleToolsCall(params) {
  const toolName = params.name;
  const args = params.arguments || {};

  const route = routes.find(r => buildToolName(r.method, r.path) === toolName);
  if (!route) return { isError: true, content: [{ type: 'text', text: 'Tool not found: ' + toolName }] };

  let path = route.path;
  let query = [];

  (route.params || []).forEach(p => {
    const val = args[p.name];
    if (val === undefined) return;
    if (p.source === 'path') path = path.replace(`{${p.name}}`, encodeURIComponent(String(val)));
    else if (p.source === 'query') query.push(`${encodeURIComponent(p.name)}=${encodeURIComponent(String(val))}`);
  });

  const fullPath = baseUrl + path + (query.length ? '?' + query.join('&') : '');
  const body = (route.params || []).find(p => p.source === 'body') ? args : null;

  try {
    const result = await httpRequest(route.method, fullPath, body);
    return { content: [{ type: 'text', text: result }] };
  } catch (e) {
    return { isError: true, content: [{ type: 'text', text: 'Error: ' + e.message }] };
  }
}

async function handleMessage(msg) {
  const { method, id, params } = msg;
  let result;

  switch (method) {
    case 'initialize': result = handleInitialize(); break;
    case 'notifications/initialized': return null;
    case 'tools/list': result = handleToolsList(); break;
    case 'tools/call': result = await handleToolsCall(params); break;
    default: return null;
  }

  if (id == null) return null;
  return { jsonrpc: '2.0', id, result };
}

async function main() {
  await loadSchema();

  let buffered = Buffer.alloc(0);
  for await (const chunk of process.stdin) {
    buffered = Buffer.concat([buffered, chunk]);
    let newline;
    while ((newline = buffered.indexOf(0x0a)) !== -1) {
      if (newline > MAX_LINE_BYTES) throw new Error('MCP request exceeds maximum size');
      const line = buffered.subarray(0, newline).toString('utf8').replace(/\r$/, '');
      buffered = buffered.subarray(newline + 1);
      if (!line.trim()) continue;
      const msg = JSON.parse(line);
      const response = await handleMessage(msg);
      if (response) process.stdout.write(JSON.stringify(response) + '\n');
    }
    if (buffered.length > MAX_LINE_BYTES) throw new Error('MCP request exceeds maximum size');
  }
  if (buffered.length) {
    const line = buffered.toString('utf8').replace(/\r$/, '');
    if (line.trim()) {
      const msg = JSON.parse(line);
      const response = await handleMessage(msg);
      if (response) process.stdout.write(JSON.stringify(response) + '\n');
    }
  }
}

main().catch(e => { process.stderr.write(e.message + '\n'); process.exit(1); });
