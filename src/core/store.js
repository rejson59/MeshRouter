// One observable state store for both modes. Only the browser demo creates
// simulated peers; native Android reads every peer from the local LAN server.
import * as bridge from '../native/bridge.js';
import { electGateway, combinedSpeed, computeMeshStats, explainGateway } from './election.js';
import { makePeer, makeSelfNode, tickNode } from './simulation.js';
import { randomInt } from './format.js';

const DEMO_TICK_MS = 2000;
const NATIVE_POLL_MS = 2500;

export const store = {
  theme: localStorage.getItem('mr_theme') || 'dark',
  view: 'home',
  platform: bridge.platformName(),
  isNative: bridge.isNative(),
  deviceInfo: null,
  status: 'idle', // idle | creating | joining | scanning | connected
  role: null,
  meshName: '', meshPassword: '', meshSsid: '', meshIp: '', invite: '',
  nodes: [], gateway: null, ranked: [], meshStats: null, combined: 0,
  selfId: 'self', foundNetworks: [],
  permissions: { location: false, wifi: false, nearby: false },
  nativeError: '', relayResult: null,
  relayEnabled: false, relayLimitMb: 25, relayRemainingBytes: 0, download: null,
  _subs: new Set(), _timer: null, _polling: false, _pendingDemoTimers: [],

  subscribe(fn) { this._subs.add(fn); return () => this._subs.delete(fn); },
  emit() {
    for (const fn of this._subs) {
      try { fn(this); } catch (e) { console.error('[store] subscriber error', e); }
    }
  },
  set(patch) { Object.assign(this, patch); this.emit(); },

  setTheme(theme) {
    this.theme = theme;
    localStorage.setItem('mr_theme', theme);
    document.documentElement.setAttribute('data-theme', theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#0f1115' : '#e9edf5');
    this.emit();
  },
  toggleTheme() { this.setTheme(this.theme === 'dark' ? 'light' : 'dark'); },

  async loadDeviceInfo() {
    this.deviceInfo = await bridge.getDeviceInfo();
    this.emit();
    if (this.isNative) await this.syncNative();
    return this.deviceInfo;
  },

  async requestPermissions() {
    const res = await bridge.requestPermissions();
    this.permissions = { ...this.permissions, ...res };
    this.emit();
    return this.permissions;
  },

  async createMesh(meshName, selfName) {
    this.set({ status: 'creating', nativeError: '' });
    try {
      if (this.isNative) {
        const raw = await bridge.startHotspot({ ssid: meshName, name: selfName });
        this._applyNative(raw);
        this._startNativePolling();
      } else {
        const info = this.deviceInfo || (await this.loadDeviceInfo());
        const speed = await bridge.runSpeedTest();
        const self = makeSelfNode(info, selfName, speed);
        const hs = await bridge.startHotspot({ ssid: meshName });
        this.set({
          nodes: [self], role: 'host', meshName, meshSsid: hs.ssid,
          meshPassword: hs.password, meshIp: hs.ip, invite: hs.invite,
          selfId: self.id, status: 'connected'
        });
        this._recompute();
        this._scheduleDemoPeers(randomInt(2, 4));
        this._startDemoTick();
      }
      this.emit();
      return { ok: true, password: this.meshPassword };
    } catch (e) {
      this.set({ status: 'idle' });
      throw e;
    }
  },

  async scan() {
    this.set({ status: 'scanning', foundNetworks: [] });
    try {
      const nets = await bridge.scanNetworks();
      this.set({ foundNetworks: nets, status: 'idle' });
      return nets;
    } catch (e) {
      this.set({ status: 'idle' });
      throw e;
    }
  },

  async joinMesh(network, selfName) {
    this.set({ status: 'joining', nativeError: '' });
    try {
      if (this.isNative) {
        const raw = await bridge.joinNetwork(network, selfName);
        this._applyNative(raw);
        this._startNativePolling();
      } else {
        await bridge.joinNetwork(network, selfName);
        const info = this.deviceInfo || (await this.loadDeviceInfo());
        const speed = await bridge.runSpeedTest();
        const self = makeSelfNode(info, selfName, speed);
        const host = makePeer();
        host.name = network.host ? network.host.split(' ')[0] : host.name;
        const nodes = [self, host];
        for (let i = 1; i < (network.members || 2); i++) nodes.push(makePeer());
        this.set({ nodes, role: 'member', meshName: network.ssid, status: 'connected', selfId: self.id });
        this._recompute();
        this._scheduleDemoPeers(randomInt(0, 2));
        this._startDemoTick();
      }
      this.emit();
      return { ok: true };
    } catch (e) {
      this.set({ status: 'idle' });
      throw e;
    }
  },

  _applyNative(raw) {
    if (!raw?.connected) {
      if (this.status === 'connected') {
        this.nativeError = raw?.error || 'Połączenie z siatką zostało przerwane.';
        this._stopTimers();
        this.status = 'idle';
        this.view = 'home';
        this.nodes = [];
        this.gateway = null;
        this.combined = 0;
        this.download = null;
        this.relayEnabled = false;
        this.relayRemainingBytes = 0;
        this.emit();
      }
      return;
    }
    this.status = 'connected';
    this.role = raw.role;
    this.meshName = raw.meshName;
    this.meshSsid = raw.ssid || '';
    this.meshPassword = raw.password || '';
    this.meshIp = raw.ip || '';
    this.invite = raw.invite || '';
    this.selfId = raw.selfId;
    this.nativeError = raw.error || '';
    this.relayEnabled = !!raw.relayEnabled;
    this.relayLimitMb = raw.relayBudgetBytes ? Math.round(raw.relayBudgetBytes / 1048576) : 25;
    this.relayRemainingBytes = raw.relayEnabled
      ? Math.max(0, (raw.relayBudgetBytes || 0) - (raw.relaySpentBytes || 0)) : 0;
    this.download = raw.downloadJob || null;
    this.nodes = Array.isArray(raw.nodes) ? raw.nodes.map((n) => ({
      id: n.id, name: n.name || 'Telefon', model: n.model || 'Android',
      operator: n.operator || '—', battery: n.battery || 0,
      charging: !!n.charging, signal: n.signal || 0,
      download: n.download || 0, upload: n.upload || 0, ping: n.ping || 0,
      dataShared: n.dataShared || 0, measuredAt: n.measuredAt || 0,
      hasCellular: !!n.hasCellular, relayEnabled: !!n.relayEnabled,
      relayRemainingBytes: n.relayRemainingBytes || 0,
      isSelf: n.id === raw.selfId, isGateway: false
    })) : [];
    this._recompute();
    this.emit();
  },

  async syncNative() {
    if (!this.isNative || this._polling) return;
    this._polling = true;
    try { this._applyNative(await bridge.getMeshState()); }
    catch (e) { this.nativeError = e.message || 'Błąd mostka Androida'; this.emit(); }
    finally { this._polling = false; }
  },

  _startNativePolling() {
    this._stopTimers();
    this._timer = setInterval(() => this.syncNative(), NATIVE_POLL_MS);
  },

  async runTest() {
    const result = await bridge.runSpeedTest();
    if (this.isNative) await this.syncNative();
    else {
      const self = this.nodes.find((n) => n.id === this.selfId);
      if (self) Object.assign(self, result);
      this._recompute();
      this.emit();
    }
    return result;
  },

  getRelayTarget() {
    // Even when the host wins the election, a measured remote peer can
    // demonstrate the real application-level cellular → Wi-Fi data path.
    return this.ranked.find((r) => r.eligible && r.node.id !== this.selfId)?.node || null;
  },

  async testRelay() {
    if (!this.isNative || this.role !== 'host') throw new Error('Test przekazania wymaga hosta.');
    const peer = this.getRelayTarget();
    if (!peer) throw new Error('Najpierw zmierz łącze komórkowe na drugim telefonie.');
    const result = await bridge.testRelay(peer.id);
    this.relayResult = result;
    this.emit();
    return result;
  },

  async setRelayEnabled(enabled, limitMb) {
    if (!this.isNative || this.status !== 'connected') throw new Error('Najpierw dołącz do siatki na Androidzie.');
    const snapshot = await bridge.setRelayEnabled(enabled, limitMb);
    this._applyNative(snapshot);
    return snapshot;
  },

  async startDownload(url, sha256 = '') {
    if (!this.isNative || this.role !== 'host' || this.status !== 'connected') {
      throw new Error('Pobieranie pliku może uruchomić tylko host w aplikacji Android.');
    }
    const result = await bridge.startDownload(url, sha256);
    this.download = result.job;
    this.emit();
    return result.job;
  },

  async cancelDownload() {
    if (!this.isNative || this.role !== 'host') throw new Error('Anulowanie wymaga hosta.');
    const snapshot = await bridge.cancelDownload();
    this._applyNative(snapshot);
    return snapshot;
  },

  async shareDownload() {
    if (!this.isNative || this.role !== 'host' || this.download?.status !== 'complete') {
      throw new Error('Nie ma ukończonego pliku do udostępnienia.');
    }
    return bridge.shareDownload();
  },

  _scheduleDemoPeers(count) {
    for (let i = 0; i < count; i++) {
      const id = setTimeout(() => {
        if (this.status !== 'connected' || this.isNative) return;
        this.nodes.push(makePeer());
        this._recompute();
        this.emit();
      }, 2500 + i * randomInt(1800, 4200));
      this._pendingDemoTimers.push(id);
    }
  },

  _startDemoTick() {
    this._stopTimers(false);
    this._timer = setInterval(() => {
      if (this.status !== 'connected') return;
      for (const n of this.nodes) tickNode(n);
      this._recompute();
      this.emit();
    }, DEMO_TICK_MS);
  },

  _stopTimers(clearPeers = true) {
    if (this._timer) clearInterval(this._timer);
    this._timer = null;
    if (clearPeers) {
      for (const t of this._pendingDemoTimers) clearTimeout(t);
      this._pendingDemoTimers = [];
    }
  },

  _recompute() {
    const { gateway, ranked, stats } = electGateway(this.nodes);
    for (const n of this.nodes) n.isGateway = gateway ? n.id === gateway.id : false;
    this.gateway = gateway;
    this.ranked = ranked;
    this.meshStats = stats || computeMeshStats(this.nodes);
    // Native: sum of independent opt-in *measurements*, NOT a bonded
    // connection or the actual throughput of the Wi-Fi hotspot.
    // Demo: illustrative model, labelled as such in the UI.
    this.combined = this.isNative ? this.meshStats.sumDown : combinedSpeed(this.nodes, gateway);
    const gRank = ranked.find((r) => gateway && r.node.id === gateway.id);
    this.gatewayWhy = gRank ? explainGateway(gateway, gRank.parts) : '';
  },

  async leave() {
    this._stopTimers();
    if (this.isNative) await bridge.leaveMesh();
    else if (this.role === 'host') await bridge.stopHotspot();
    this.set({
      status: 'idle', role: null, nodes: [], gateway: null, ranked: [],
      meshName: '', meshSsid: '', meshPassword: '', meshIp: '', invite: '',
      combined: 0, foundNetworks: [], meshStats: null, nativeError: '', relayResult: null,
      relayEnabled: false, relayRemainingBytes: 0, download: null
    });
  },

  go(view) { this.view = view; this.emit(); }
};
