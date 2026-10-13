// ============================================================
// Peer simulation (browser demo mode)
// ------------------------------------------------------------
// Generates realistic-looking peer phones and evolves their
// metrics over time so the dashboard feels alive. On a real
// device these values come from the native bridge instead.
// ============================================================

import { randomInt, pick, uid, clamp } from './format.js';

const NAMES = ['Kuba', 'Ola', 'Michał', 'Zosia', 'Bartek', 'Nadia', 'Igor', 'Lena', 'Kacper', 'Maja', 'Filip', 'Ada'];
const MODELS = [
  'Pixel 8 Pro', 'Pixel 7a', 'Galaxy S24', 'Galaxy S22', 'Galaxy A54',
  'iPhone 15', 'iPhone 13', 'iPhone SE', 'Redmi Note 12', 'Xiaomi 13',
  'OnePlus 12', 'Motorola Edge 40', 'Nothing Phone (2)', 'Realme GT 5'
];
const OPERATORS = ['Orange', 'Play', 'T‑Mobile', 'Plus', 'Virgin Mobile', 'nc+ Mobile', 'Orange 5G', 'Play 5G'];

export function makePeer() {
  const down = randomInt(25, 700) + Math.random();
  return {
    id: uid('peer'),
    name: pick(NAMES),
    model: pick(MODELS),
    operator: pick(OPERATORS),
    battery: randomInt(15, 100),
    charging: Math.random() > 0.7,
    download: round1(down),
    upload: round1(down * (0.12 + Math.random() * 0.3)),
    ping: randomInt(12, 95),
    signal: randomInt(38, 98),
    dataShared: 0,
    isSelf: false,
    isGateway: false
  };
}

/** Create the local ("you") node from real device info. */
export function makeSelfNode(info, name, speed) {
  return {
    id: 'self',
    name: name || 'Ty',
    model: info.model || 'Twój telefon',
    operator: info.operator || '—',
    battery: Math.round((info.batteryLevel ?? 0.7) * 100),
    charging: !!info.charging,
    download: round1(speed.download),
    upload: round1(speed.upload),
    ping: Math.round(speed.ping),
    signal: randomInt(60, 99),
    dataShared: 0,
    isSelf: true,
    isGateway: false
  };
}

/** Evolve a node's metrics a little each tick (drift). */
export function tickNode(node) {
  const drift = (base, amt, min, max) => clamp(base + (Math.random() - 0.5) * amt, min, max);
  node.download = round1(drift(node.download, node.download * 0.16 + 4, 5, 950));
  node.upload = round1(drift(node.upload, node.upload * 0.16 + 2, 2, 400));
  node.ping = Math.round(drift(node.ping, 8, 8, 180));
  node.signal = Math.round(drift(node.signal, 6, 12, 100));
  // Battery slowly drains unless charging.
  if (node.charging) {
    node.battery = Math.min(100, node.battery + (Math.random() > 0.5 ? 1 : 0));
    if (node.battery >= 100 && Math.random() > 0.9) node.charging = false;
  } else {
    if (Math.random() > 0.55) node.battery = Math.max(1, node.battery - 1);
    if (node.battery <= 8 && Math.random() > 0.85) node.charging = true;
  }
  // Data shared accumulates roughly with upload.
  node.dataShared += node.upload / 8; // arbitrary MB-ish units per tick
  return node;
}

function round1(n) {
  return Math.round(n * 10) / 10;
}
