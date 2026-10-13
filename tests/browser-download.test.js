import test from 'node:test';
import assert from 'node:assert/strict';

// A browser may display the clearly labelled UI, but has no mesh transfer.
globalThis.window = globalThis;
const bridge = await import('../src/native/bridge.js');

test('browser demo cannot consent, transfer, cancel, or share a fake download', async () => {
  assert.equal(bridge.isNative(), false);
  await assert.rejects(bridge.setRelayEnabled(true, 25), /tylko w aplikacji Android/);
  await assert.rejects(bridge.startDownload('https://example.org/file.zip', ''), /tylko w aplikacji Android/);
  await assert.rejects(bridge.cancelDownload(), /tylko w aplikacji Android/);
  await assert.rejects(bridge.shareDownload(), /aplikacji Android/);
});
