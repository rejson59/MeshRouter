import test from 'node:test';
import assert from 'node:assert/strict';
import { electGateway, scoreNode, computeMeshStats, combinedSpeed, BATTERY_FLOOR } from '../src/core/election.js';
import { formatSpeed, signalLevel } from '../src/core/format.js';

function phone(id, download, battery, extra = {}) {
  return {
    id, name: id, download, upload: download / 4, battery,
    charging: false, ping: 30, signal: 75, hasCellular: true,
    ...extra
  };
}

test('no nodes, or nodes without a measured cellular link => no recommended relay', () => {
  assert.equal(electGateway([]).gateway, null);
  const res = electGateway([phone('a', 0, 90), phone('b', 0, 100)]);
  assert.equal(res.gateway, null);
  assert.equal(res.ranked.length, 2); // still show unmeasured devices in UI
  assert.equal(res.stats.sumDown, 0);
});

test('battery/speed balance can beat a very fast phone with a near-empty battery', () => {
  const fastAndEmpty = phone('fast', 400, 7);
  const balanced = phone('balanced', 240, 95);
  assert.equal(electGateway([fastAndEmpty, balanced]).gateway.id, 'balanced');
  assert.equal(BATTERY_FLOOR, 20);
});

test('charging makes a phone a more sustainable gateway', () => {
  const battery40 = phone('plugged', 120, 40, { charging: true });
  const battery50 = phone('unplugged', 120, 50);
  assert.equal(electGateway([battery40, battery50]).gateway.id, 'plugged');
});

test('exclude non-cellular nodes even when their reported speed is high', () => {
  const wifiOnly = phone('wifi', 500, 100, { hasCellular: false });
  const mobile = phone('mobile', 20, 60);
  assert.equal(electGateway([wifiOnly, mobile]).gateway.id, 'mobile');
  assert.equal(electGateway([wifiOnly]).gateway, null);
});

test('scores stay in 0..1 and gateway follows total score', () => {
  const nodes = [phone('a', 250, 30), phone('b', 190, 90), phone('c', 450, 10)];
  const stats = computeMeshStats(nodes);
  const { gateway, ranked } = electGateway(nodes);
  assert.ok(gateway);
  for (const n of nodes) {
    const s = scoreNode(n, stats);
    assert.ok(s.total >= 0 && s.total <= 1);
  }
  assert.ok(ranked[0].score >= ranked[1].score);
  assert.equal(gateway.id, ranked[0].node.id);
});

test('demo-only illustrative speed model is not a real link bond', () => {
  const a = phone('a', 100, 80);
  const b = phone('b', 100, 80);
  assert.equal(combinedSpeed([a, b], a), 135);
  assert.equal(combinedSpeed([], null), 0);
});

test('speed and signal formatters handle bounds and Gbps', () => {
  assert.deepEqual(formatSpeed(1234), { value: '1.23', unit: 'Gbps' });
  assert.deepEqual(formatSpeed(0), { value: '0', unit: 'Mbps' });
  assert.equal(signalLevel(0), 0);
  assert.equal(signalLevel(-65), 3);
  assert.equal(signalLevel(100), 4);
});
