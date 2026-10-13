// ============================================================
// MeshRouter — "Golden middle" gateway election
// ------------------------------------------------------------
// The main router (gateway) is the phone that best balances a
// strong internet link with enough battery to sustain sharing.
// Every node is scored on the same scale; the highest score wins.
// The scoring is intentionally transparent so the UI can explain
// *why* a given phone was chosen.
// ============================================================

import { clamp } from './format.js';

// Tunable weights. speed and battery dominate (the "golden middle"),
// latency and mesh-signal act as tie-breakers / penalties.
export const WEIGHTS = {
  speed: 0.46,
  battery: 0.40,
  latency: 0.09,
  signal: 0.05
};

// A node below this battery floor won't be elected gateway unless it is
// charging — we don't want to drain someone's phone to 0 as the hub.
export const BATTERY_FLOOR = 20;

function normalizeSpeed(mbps, meshMax) {
  if (meshMax <= 0) return 0;
  return clamp(mbps / meshMax, 0, 1);
}

function batteryScore(node) {
  const b = clamp(node.battery, 0, 100) / 100;
  // Charging gives a meaningful boost — a plugged-in phone is an ideal hub.
  return node.charging ? clamp(b + 0.25, 0, 1) : b;
}

function latencyScore(ping, meshMaxPing) {
  // Lower ping => higher score.
  if (meshMaxPing <= 0) return 1;
  return clamp(1 - (ping - 5) / Math.max(meshMaxPing - 5, 1), 0, 1);
}

function signalScore(pct) {
  return clamp(pct / 100, 0, 1);
}

/**
 * Score a single node in the context of the current mesh.
 * Returns a 0..1 score plus the component breakdown (for the UI).
 */
export function scoreNode(node, meshStats) {
  const speed = normalizeSpeed(node.download, meshStats.maxDownload);
  const battery = batteryScore(node);
  const latency = latencyScore(node.ping, meshStats.maxPing);
  const signal = signalScore(node.signal);

  const raw =
    WEIGHTS.speed * speed +
    WEIGHTS.battery * battery +
    WEIGHTS.latency * latency +
    WEIGHTS.signal * signal;

  // Hard-ish penalty: a nearly-dead, unplugged phone shouldn't be the hub.
  let penalty = 0;
  if (!node.charging && node.battery < BATTERY_FLOOR) {
    penalty = (BATTERY_FLOOR - node.battery) / 100; // up to 0.20
  }

  return {
    total: clamp(raw - penalty, 0, 1),
    parts: { speed, battery, latency, signal, penalty }
  };
}

/** Aggregate stats needed to normalize across the mesh. */
export function computeMeshStats(nodes) {
  let maxDownload = 0;
  let maxPing = 0;
  let sumDown = 0;
  let sumUp = 0;
  for (const n of nodes) {
    if (n.download > maxDownload) maxDownload = n.download;
    if (n.ping > maxPing) maxPing = n.ping;
    sumDown += n.download;
    sumUp += n.upload;
  }
  return { maxDownload, maxPing, sumDown, sumUp, count: nodes.length };
}

/**
 * Elect the gateway and rank all nodes.
 * @returns {{gateway: object|null, ranked: Array<{node, score, parts}>}}
 */
export function electGateway(nodes) {
  if (!nodes || nodes.length === 0) return { gateway: null, ranked: [], stats: computeMeshStats([]) };

  const stats = computeMeshStats(nodes);
  const ranked = nodes
    .map((node) => {
      const { total, parts } = scoreNode(node, stats);
      // A real node with no measured uplink cannot be recommended as an
      // internet relay. Demo nodes have nonzero sample values.
      const eligible = node.download > 0 && node.hasCellular !== false;
      return { node, score: total, parts, eligible };
    })
    .sort((a, b) => Number(b.eligible) - Number(a.eligible) || b.score - a.score);

  return { gateway: ranked.find((r) => r.eligible)?.node ?? null, ranked, stats };
}

/**
 * Combined ("shared") speed the mesh can offer.
 * Model: the gateway carries the aggregate load, but parallel links add
 * capacity with diminishing returns (you can't perfectly bond channels).
 *   shared = gatewayDown + 0.35 * (sum of the other links' download)
 * This makes the "combined speed" visibly higher than any single phone,
 * which is the whole point the UI shows on the gauge.
 */
export function combinedSpeed(nodes, gateway) {
  if (!gateway) return 0;
  let others = 0;
  for (const n of nodes) {
    if (n.id !== gateway.id) others += n.download;
  }
  return gateway.download + 0.35 * others;
}

/** Human-readable explanation of why a node became the gateway. */
export function explainGateway(node, parts) {
  if (!node) return 'Brak węzłów.';
  const speedTxt = `${Math.round(node.download)} Mbps`;
  const battTxt = `${Math.round(node.battery)}%${node.charging ? ' (ładuje)' : ''}`;
  const reason =
    parts.battery >= parts.speed
      ? `najlepszy balans — ${battTxt} baterii przy ${speedTxt}`
      : `najszybsze łącze (${speedTxt}) przy ${battTxt} baterii`;
  return reason;
}
