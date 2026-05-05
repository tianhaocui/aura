#!/usr/bin/env node
const { spawn } = require('child_process');
const path = require('path');

const jar = path.join(__dirname, 'bridge.jar');
const url = process.env.API_URL || process.argv[2];

if (!url) {
  process.stderr.write('Usage: aura-mcp <url>\n');
  process.stderr.write('  or set API_URL environment variable\n');
  process.exit(1);
}

const child = spawn('java', ['-jar', jar, url], {
  stdio: ['pipe', 'pipe', 'inherit']
});

process.stdin.pipe(child.stdin);
child.stdout.pipe(process.stdout);

child.on('exit', (code) => process.exit(code || 0));
process.on('SIGTERM', () => child.kill());
process.on('SIGINT', () => child.kill());
