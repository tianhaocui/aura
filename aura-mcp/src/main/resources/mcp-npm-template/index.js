#!/usr/bin/env node
const { spawn } = require('child_process');
const path = require('path');

const jar = path.join(__dirname, 'bridge.jar');
const url = process.env.API_URL || '${API_URL}';

const child = spawn('java', ['-jar', jar, url], {
  stdio: ['pipe', 'pipe', 'inherit']
});

process.stdin.pipe(child.stdin);
child.stdout.pipe(process.stdout);

child.on('exit', (code) => process.exit(code || 0));
process.on('SIGTERM', () => child.kill());
process.on('SIGINT', () => child.kill());
