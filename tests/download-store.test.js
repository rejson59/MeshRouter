import test from 'node:test';
import assert from 'node:assert/strict';

// Isolated process: give Capacitor a native Android bridge and a minimal
// storage stub, then verify the real plugin's JSON is mapped without mocks.
globalThis.window = globalThis;
globalThis.localStorage = { getItem: () => null, setItem: () => {} };
globalThis.androidBridge = {};
globalThis.Capacitor = {
  PluginHeaders: [{ name: 'MeshBridge', methods: [{ name: 'getMeshState', rtype: 'promise' }] }],
  nativePromise: () => Promise.reject(new Error('No device in JVM tests'))
};
const { store } = await import('../src/core/store.js');

test('maps native opt-in quota, completed job, and consenting participants', () => {
  assert.equal(store.isNative, true);
  store._applyNative({
    connected: true, role: 'host', meshName: 'Test', selfId: 'self',
    relayEnabled: true, relayBudgetBytes: 25 * 1048576, relaySpentBytes: 1048576,
    downloadJob: {
      status: 'complete', total: 1024, bytes: 1024, sha256: 'a'.repeat(64),
      expectedHashProvided: true, contributors: [{ name: 'Peer', bytes: 512 }]
    },
    nodes: [
      { id: 'self', name: 'Host', battery: 80, hasCellular: true, relayEnabled: true, relayRemainingBytes: 24 * 1048576 },
      { id: 'peer', name: 'Peer', battery: 70, hasCellular: true, relayEnabled: true, relayRemainingBytes: 4 * 1048576 }
    ]
  });
  assert.equal(store.status, 'connected');
  assert.equal(store.relayLimitMb, 25);
  assert.equal(store.relayRemainingBytes, 24 * 1048576);
  assert.equal(store.download.status, 'complete');
  assert.equal(store.nodes.filter((node) => node.relayEnabled).length, 2);
  assert.equal(store.nodes.find((node) => node.id === 'peer').relayRemainingBytes, 4 * 1048576);
});

test('mesh disconnection revokes UI consent and clears active job', () => {
  store._applyNative({ connected: false, error: 'Wi-Fi lost' });
  assert.equal(store.status, 'idle');
  assert.equal(store.relayEnabled, false);
  assert.equal(store.download, null);
  assert.equal(store.nodes.length, 0);
});
