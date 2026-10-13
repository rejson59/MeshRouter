// Formatting helpers shared across the app.

export function formatSpeed(mbps) {
  const v = Number(mbps) || 0;
  if (v >= 1000) return { value: (v / 1000).toFixed(2).replace(/\.00$/, ''), unit: 'Gbps' };
  if (v >= 1) return { value: Math.round(v).toString(), unit: 'Mbps' };
  return { value: Math.round(v * 1000).toString(), unit: 'Kbps' };
}

export function speedLabel(mbps) {
  const { value, unit } = formatSpeed(mbps);
  return `${value} ${unit}`;
}

export function clamp(n, min, max) {
  return Math.min(max, Math.max(min, n));
}

export function batteryColor(pct, charging) {
  if (charging) return 'var(--good)';
  if (pct <= 15) return 'var(--danger)';
  if (pct <= 35) return 'var(--warn)';
  return 'var(--good)';
}

export function signalLevel(rssiOrPct) {
  // Accepts either an RSSI (dBm, e.g. -70) or a 0..100 percentage.
  // 0 is the native bridge's 'not available' value, not a valid RSSI.
  if (!Number.isFinite(rssiOrPct) || rssiOrPct === 0) return 0;
  let pct;
  if (rssiOrPct < 0) {
    // RSSI: -100 dBm => 0%, -50 dBm => 100%
    pct = clamp(((rssiOrPct + 100) / 50) * 100, 0, 100);
  } else {
    pct = clamp(rssiOrPct, 0, 100);
  }
  if (pct >= 75) return 4;
  if (pct >= 50) return 3;
  if (pct >= 25) return 2;
  if (pct > 0) return 1;
  return 0;
}

export function initials(name) {
  return (name || '?')
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
    .toUpperCase();
}

export function uid(prefix = 'n') {
  return `${prefix}_${Math.random().toString(36).slice(2, 9)}`;
}

export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
