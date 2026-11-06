#!/usr/bin/env node
'use strict';

import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = path.join(repoRoot, 'aura-mcp-npm');
const entrypoint = path.join(packageRoot, 'index.js');
const metadata = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));

function verifyPackageShape() {
  execFileSync(process.execPath, ['--check', entrypoint], {stdio: 'inherit'});
  const raw = execFileSync('npm', ['pack', '--dry-run', '--json'], {
    cwd: packageRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'inherit']
  });
  const reports = JSON.parse(raw);
  assert.equal(reports.length, 1, 'npm pack must produce one package report');
  const files = reports[0].files.map(file => file.path);
  assert(files.includes('index.js'), 'npm package must contain index.js');
  assert(files.includes('package.json'), 'npm package must contain package.json');
  assert.equal(reports[0].name, metadata.name);
  assert.equal(reports[0].version, metadata.version);
}

function startFixture() {
  const server = http.createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json');
    if (request.url === '/__schema__') {
      response.end(JSON.stringify({routes: [
        {method: 'GET', path: '/orders', description: 'List orders', params: []}
      ]}));
      return;
    }
    if (request.url === '/orders' && request.method === 'GET') {
      response.end(JSON.stringify({orders: []}));
      return;
    }
    response.statusCode = 404;
    response.end(JSON.stringify({error: 'not found'}));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      resolve({server, url: `http://127.0.0.1:${address.port}`});
    });
  });
}

function readProtocol(child, expectedResponses) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const responses = [];
    let settled = false;
    const succeed = value => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(value);
    };
    const fail = error => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(error);
    };
    const timeout = setTimeout(() => {
      child.kill('SIGKILL');
      fail(new Error(`MCP stdio smoke timed out; stderr=${child.stderrText || ''}`));
    }, 5000);
    child.stdout.on('data', chunk => {
      buffer += chunk.toString('utf8');
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, '');
        buffer = buffer.slice(newline + 1);
        if (!line.trim()) continue;
        try {
          responses.push(JSON.parse(line));
        } catch (error) {
          fail(new Error(`MCP emitted invalid JSON: ${line}`));
          return;
        }
        if (responses.length === expectedResponses) {
          succeed(responses);
          return;
        }
      }
    });
    child.once('error', fail);
    child.stdin.once('error', fail);
    child.once('exit', (code, signal) => {
      fail(new Error(
          `MCP bridge exited before completing protocol smoke (code=${code}, signal=${signal}); ` +
          `stderr=${child.stderrText || ''}`));
    });
  });
}

async function verifyStdio() {
  const fixture = await startFixture();
  const child = spawn(process.execPath, [entrypoint], {
    cwd: packageRoot,
    env: {...process.env, API_URL: fixture.url},
    stdio: ['pipe', 'pipe', 'pipe']
  });
  child.stderrText = '';
  child.stderr.on('data', chunk => { child.stderrText += chunk.toString('utf8'); });
  try {
    const responsesPromise = readProtocol(child, 3);
    child.stdin.write(JSON.stringify({jsonrpc: '2.0', id: 1, method: 'initialize'}) + '\n');
    child.stdin.write(JSON.stringify({jsonrpc: '2.0', id: 2, method: 'tools/list'}) + '\n');
    child.stdin.write(JSON.stringify({jsonrpc: '2.0', id: 3, method: 'tools/call', params: {
      name: 'get_orders', arguments: {}
    }}) + '\n');
    child.stdin.end();
    const responses = await responsesPromise;
    assert.equal(responses[0].result.serverInfo.version, metadata.version);
    assert.equal(responses[1].result.tools[0].name, 'get_orders');
    assert.match(responses[2].result.content[0].text, /orders/);
  } finally {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = new Promise(resolve => child.once('exit', resolve));
      child.kill('SIGTERM');
      await exited;
    }
    await new Promise(resolve => fixture.server.close(resolve));
  }
}

async function main() {
  verifyPackageShape();
  await verifyStdio();
  process.stdout.write(`Aura MCP npm package passed (${metadata.name}@${metadata.version})\n`);
}

main().catch(error => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
