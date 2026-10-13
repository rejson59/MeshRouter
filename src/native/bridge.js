// A strict boundary: the browser runs an explicitly labelled visual DEMO;
// Android runs the real MeshBridge plugin and NEVER falls back to fake data.
import { Capacitor, registerPlugin } from '@capacitor/core';
import { randomInt, pick, uid } from '../core/format.js';

const android = Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
const MeshBridge = registerPlugin('MeshBridge');

export function isNative() { return android; }

export function platformName() {
  if (android) return 'android';
  const ua = navigator.userAgent || '';
  if (/android/i.test(ua)) return 'android-web';
  if (/iphone|ipad|ipod/i.test(ua)) return 'ios-web';
  return 'web';
}

export async function getDeviceInfo() {
  if (android) return MeshBridge.getDeviceInfo();
  let battery = { level: 0.78, charging: false };
  try {
    if (navigator.getBattery) {
      const b = await navigator.getBattery();
      battery = { level: b.level, charging: b.charging };
    }
  } catch (_) { /* unsupported web API */ }
  return {
    model: 'Telefon demo', manufacturer: 'Demo', operator: 'Operator demo',
    batteryLevel: battery.level, charging: battery.charging, signal: 80,
    hasCellular: true
  };
}

export async function getBattery() {
  if (android) return MeshBridge.getBattery();
  try {
    if (navigator.getBattery) {
      const b = await navigator.getBattery();
      return { level: b.level, charging: b.charging };
    }
  } catch (_) { /* unsupported web API */ }
  return { level: 0.78, charging: false };
}

export async function runSpeedTest() {
  if (android) return MeshBridge.speedTest();
  // Demo values are deliberately separate from native metrics.
  await sleep(800);
  const download = randomInt(40, 500);
  return { download, upload: Math.round(download * 0.24), ping: randomInt(15, 65), source: 'demo' };
}

export async function requestPermissions() {
  if (android) return MeshBridge.requestPermissions();
  await sleep(350);
  return { location: true, wifi: true, nearby: true };
}

export async function startHotspot(config) {
  if (android) return MeshBridge.startHotspot(config);
  await sleep(700);
  return {
    connected: true, role: 'host', meshName: config.ssid,
    ssid: `DEMO-${config.ssid}`, password: 'demo12345', ip: '192.168.43.1',
    invite: 'MR1:DEMO', internetSharing: false
  };
}

export async function stopHotspot() {
  if (android) return MeshBridge.stopHotspot();
  await sleep(150);
  return { ok: true };
}

export async function leaveMesh() {
  if (android) return MeshBridge.leaveMesh();
  await sleep(150);
  return { ok: true };
}

export async function getMeshState() {
  if (android) return MeshBridge.getMeshState();
  throw new Error('getMeshState jest dostępne wyłącznie w aplikacji Android.');
}

export async function testRelay(nodeId) {
  if (android) return MeshBridge.testRelay({ nodeId });
  throw new Error('Test przekaźnika wymaga dwóch prawdziwych telefonów Android.');
}

export async function scanNetworks() {
  if (android) throw new Error('Na Androidzie wklej zaproszenie od hosta. Nazwę i hasło sieci nadaje system.');
  await sleep(1200);
  const names = ['Ekipa‑Katowice', 'Rodzina‑Mesh', 'Biuro‑Siatka', 'Team‑Nord'];
  const hosts = ['Pixel 8', 'Galaxy S23', 'iPhone 14', 'Redmi Note 12'];
  return Array.from({ length: randomInt(1, 3) }, () => ({
    id: uid('net'), ssid: `${pick(names)} ${randomInt(1, 99)}`,
    host: pick(hosts), members: randomInt(1, 5), rssi: -randomInt(45, 85)
  }));
}

export async function joinNetwork(network, name) {
  if (android) {
    if (!network?.invite) throw new Error('Wklej zaproszenie MR1: od hosta.');
    return MeshBridge.joinMeshNetwork({ invite: network.invite, name });
  }
  await sleep(950);
  return { ok: true, demo: true, networkId: network.id };
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
