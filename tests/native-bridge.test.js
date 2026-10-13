import test from 'node:test';
import assert from 'node:assert/strict';

// Runs in its own test process. Pretend Capacitor has initialized an Android
// WebView before our bridge module is imported. An absent/failed native
// method MUST NOT silently switch to browser demo data.
globalThis.window = globalThis;
globalThis.androidBridge = {};
globalThis.Capacitor = {
  PluginHeaders: [{
    name: 'MeshBridge',
    methods: ['getDeviceInfo', 'getBattery', 'requestPermissions', 'speedTest', 'getMeshState',
      'setRelayEnabled', 'startCooperativeDownload', 'cancelCooperativeDownload', 'shareDownloadedFile']
      .map((name) => ({ name, rtype: 'promise' }))
  }],
  nativePromise: (_plugin, method) => {
    if (method === 'speedTest') return Promise.reject(new Error('Brak aktywnej sieci komórkowej'));
    if (method === 'getDeviceInfo') return Promise.resolve({ model: 'Real Android', batteryLevel: 0.5 });
    if (method === 'requestPermissions') return Promise.resolve({ location: false, wifi: true, nearby: false });
    if (method === 'getMeshState') return Promise.resolve({ connected: false, nodes: [] });
    if (method === 'setRelayEnabled') return Promise.resolve({ connected: true, relayEnabled: true, relayBudgetBytes: 5242880 });
    if (method === 'startCooperativeDownload') return Promise.reject(new Error('Brak zgody uczestników'));
    if (method === 'cancelCooperativeDownload') return Promise.resolve({ downloadJob: { status: 'cancelled' } });
    if (method === 'shareDownloadedFile') return Promise.reject(new Error('Nie ma pliku'));
    return Promise.reject(new Error('Nieobsługiwana metoda'));
  }
};

const bridge = await import('../src/native/bridge.js');

test('Android path is selected, not the browser simulator', () => {
  assert.equal(bridge.isNative(), true);
  assert.equal(bridge.platformName(), 'android');
});

test('native failures and denied permissions propagate without fake success', async () => {
  await assert.rejects(bridge.runSpeedTest(), /Brak aktywnej sieci komórkowej/);
  const perms = await bridge.requestPermissions();
  assert.equal(perms.location, false);
  assert.equal(perms.nearby, false);
  assert.deepEqual(await bridge.getMeshState(), { connected: false, nodes: [] });
  assert.equal((await bridge.getDeviceInfo()).model, 'Real Android');
});

test('cooperative download bridge never falls back to a simulated result', async () => {
  assert.equal((await bridge.setRelayEnabled(true, 5)).relayBudgetBytes, 5242880);
  await assert.rejects(bridge.startDownload('https://example.org/file.zip', ''), /Brak zgody uczestników/);
  assert.equal((await bridge.cancelDownload()).downloadJob.status, 'cancelled');
  await assert.rejects(bridge.shareDownload(), /Nie ma pliku/);
});
