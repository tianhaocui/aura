#!/usr/bin/env node

const http = require('http');
const readline = require('readline');

const url = "__API_URL_PLACEHOLDER__";

const baseUrl = url.endsWith('/') ? url.slice(0, -1) : url;
let routes = null;

function httpRequest(method, path, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(path, baseUrl);
    const opts = { method, hostname: u.hostname, port: u.port, path: u.pathname + u.search };
    const req = http.request(opts, res => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve(data));
    });
    req.on('error', reject);
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
  return { protocolVersion: '2024-11-05', capabilities: { tools: {} },
    serverInfo: { name: 'aura-mcp', version: '0.1.0' } };
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
    if (p.source === 'path') path = path.replace(`{${p.name}}`, val);
    else if (p.source === 'query') query.push(`${p.name}=${encodeURIComponent(val)}`);
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

  const rl = readline.createInterface({ input: process.stdin });
  for await (const line of rl) {
    if (!line.trim()) continue;
    const msg = JSON.parse(line);
    const response = await handleMessage(msg);
    if (response) {
      process.stdout.write(JSON.stringify(response) + '\n');
    }
  }
}

main().catch(e => { process.stderr.write(e.message + '\n'); process.exit(1); });
