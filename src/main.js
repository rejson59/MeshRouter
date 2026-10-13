import './styles/styles.css';
import './styles/native.css';
import { store } from './core/store.js';
import { Share } from '@capacitor/share';
import { Clipboard } from '@capacitor/clipboard';
import { formatSpeed, speedLabel, batteryColor, signalLevel, initials } from './core/format.js';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
let downloadSubmitting = false;

function boot() {
  document.documentElement.setAttribute('data-theme', store.theme);
  injectGaugeGradient();
  bindEvents();
  store.subscribe(render);
  store.loadDeviceInfo().catch((e) => toast(`Nie udało się odczytać urządzenia: ${message(e)}`, 'warn'));
  if (!store.isNative) registerSW();
  render();
}

function injectGaugeGradient() {
  const svg = $('#gaugeSvg');
  const ns = 'http://www.w3.org/2000/svg';
  const defs = document.createElementNS(ns, 'defs');
  defs.innerHTML = '<linearGradient id="gaugeGrad" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" stop-color="#4f8cff"/><stop offset="100%" stop-color="#7c5cff"/></linearGradient>';
  svg.insertBefore(defs, svg.firstChild);
}

function registerSW() {
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => {}));
  }
}

function bindEvents() {
  document.addEventListener('click', async (event) => {
    const modalBtn = event.target.closest('[data-modal]');
    if (modalBtn) return handleModal(modalBtn.dataset.modal);
    const btn = event.target.closest('[data-action]');
    if (!btn) return;
    try { await handleAction(btn.dataset.action, btn); }
    catch (e) { toast(message(e), 'warn'); }
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && ['meshNameInput', 'nodeNameInput'].includes(event.target.id)) {
      event.preventDefault();
      $('#startCreateBtn').click();
    }
  });
  document.addEventListener('change', async (event) => {
    if (!['relayConsent', 'relayLimitInput'].includes(event.target.id)) return;
    try {
      const limitMb = Number($('#relayLimitInput').value);
      if (!Number.isInteger(limitMb) || limitMb < 5 || limitMb > 100) {
        throw new Error('Wybierz limit od 5 do 100 MiB.');
      }
      const enabled = $('#relayConsent').checked;
      await store.setRelayEnabled(enabled, limitMb);
      toast(enabled ? 'Zgoda zapisana — w każdej chwili możesz ją cofnąć' : 'Przekazywanie danych wyłączone', 'good');
    } catch (e) {
      toast(message(e), 'warn');
      renderDownload(); // restore the actual native settings after a failed change
    }
  });
}

async function handleAction(action, element) {
  switch (action) {
    case 'toggle-theme': store.toggleTheme(); break;
    case 'create':
      $('#nodeNameInput').value = guessSelfName();
      $('#meshNameInput').value = `Mesh‑${Math.floor(Math.random() * 900 + 100)}`;
      store.go('create');
      break;
    case 'join':
      $('#joinNameInput').value = guessSelfName();
      store.go('join');
      if (!store.isNative) await runScan();
      break;
    case 'back': store.go('home'); break;
    case 'start-create': await startCreate(); break;
    case 'join-invite': await joinInvite(); break;
    case 'paste-invite': {
      const clip = await Clipboard.read();
      if (!clip.value?.includes('MR1:')) throw new Error('W schowku nie ma zaproszenia MR1:.');
      $('#inviteInput').value = clip.value;
      toast('Zaproszenie wklejone', 'good');
      break;
    }
    case 'select-network': {
      const net = store.foundNetworks.find((n) => n.id === element.dataset.id);
      if (net) await joinNetwork(net, $('#joinNameInput').value.trim() || guessSelfName());
      break;
    }
    case 'run-test': await runTest(); break;
    case 'test-relay': await testRelay(); break;
    case 'start-download': await startDownload(); break;
    case 'cancel-download': await store.cancelDownload(); break;
    case 'share-download': await store.shareDownload(); break;
    case 'leave': openModal(); break;
    case 'share-invite': await shareInvite(); break;
  }
}

function guessSelfName() {
  const model = store.deviceInfo?.model || '';
  return model && model !== 'Telefon demo' ? model.split(' ')[0] : 'Ty';
}

async function askPermissions() {
  const rows = $$('.perm-row');
  for (const row of rows) row.querySelector('.perm-state').textContent = 'oczekuje…';
  const res = await store.requestPermissions();
  for (const row of rows) {
    const key = row.dataset.perm;
    const state = row.querySelector('.perm-state');
    state.dataset.granted = res[key] ? 'true' : 'false';
    state.textContent = res[key] ? 'przyznane' : 'odmowa';
  }
  if (!res.location || !res.wifi || !res.nearby) {
    throw new Error('Bez uprawnień Wi‑Fi, lokalizacji i pobliskich urządzeń Android nie może utworzyć siatki.');
  }
}

async function startCreate() {
  const meshName = $('#meshNameInput').value.trim() || 'MeshRouter';
  const selfName = $('#nodeNameInput').value.trim() || guessSelfName();
  const btn = $('#startCreateBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = 'Uruchamiam siatkę…';
  try {
    await askPermissions();
    await store.createMesh(meshName, selfName);
    toast(store.isNative ? 'Sieć lokalna aktywna. Przekaż zaproszenie drugiemu telefonowi.' : 'Siatka DEMO uruchomiona', 'good');
    store.go('dashboard');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Uruchom siatkę';
  }
}

async function runScan() {
  const area = $('#scanArea');
  const list = $('#foundList');
  area.style.display = 'flex';
  list.innerHTML = '';
  $('#scanText').textContent = 'Skanowanie pokazowych siatek…';
  try {
    const nets = await store.scan();
    area.style.display = 'none';
    if (nets.length === 0) {
      list.innerHTML = '<div class="perm-note soft-inset"><small>Brak siatek w pobliżu. Spróbuj ponownie.</small></div>';
    } else renderFound(nets);
  } catch (e) {
    area.style.display = 'none';
    throw e;
  }
}

function renderFound(nets) {
  const list = $('#foundList');
  list.innerHTML = '';
  for (const n of nets) {
    const item = document.createElement('div');
    item.className = 'found-item soft-card';
    item.dataset.action = 'select-network';
    item.dataset.id = n.id;
    item.innerHTML = `
      <div class="found-item__icon"><svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 0 1 16 0M8 12a4 4 0 0 1 8 0M12 16.5h.01" /></svg></div>
      <div class="found-item__body"><div class="found-item__name">${escapeHtml(n.ssid)}</div>
        <div class="found-item__meta">Host: ${escapeHtml(n.host)} · ${n.members} urządzenia · ${n.rssi} dBm</div></div>
      <div class="found-item__go">›</div>`;
    list.appendChild(item);
  }
}

async function joinInvite() {
  const invite = $('#inviteInput').value.trim();
  if (!invite.includes('MR1:')) throw new Error('Wklej zaproszenie MR1: otrzymane od hosta.');
  const btn = $('#joinInviteBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = 'Łączenie… potwierdź okno Androida';
  try {
    const res = await store.requestPermissions();
    if (!res.location || !res.nearby || !res.wifi) throw new Error('Dołączenie wymaga uprawnień Wi‑Fi i lokalizacji.');
    await joinNetwork({ invite }, $('#joinNameInput').value.trim() || guessSelfName());
  } finally {
    btn.disabled = false;
    btn.textContent = 'Dołącz z zaproszenia';
  }
}

async function joinNetwork(net, name) {
  toast(store.isNative ? 'Android łączy się z lokalnym Wi‑Fi…' : 'Łączenie z siatką demo…');
  await store.joinMesh(net, name);
  toast(store.isNative ? 'Dołączono do prawdziwej sieci lokalnej' : 'Dołączono do siatki DEMO', 'good');
  store.go('dashboard');
}

async function runTest() {
  const btn = $('#runTestBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = 'Trwa test łącza…';
  try {
    const result = await store.runTest();
    const text = store.isNative
      ? `Pomiar przez dane komórkowe: ${speedLabel(result.download)} ↓, ${result.upload ? speedLabel(result.upload) : '—'} ↑, ${result.ping} ms. Test zużył ok. 1,3 MB.`
      : `Wygenerowano nowy pomiar pokazowy: ${speedLabel(result.download)}.`;
    showTestResult(text);
    toast('Pomiar zakończony', 'good');
  } finally {
    btn.disabled = false;
    btn.textContent = store.isNative ? 'Zmierz moje łącze (~1,3 MB danych)' : 'Odśwież wartości demo';
  }
}

async function testRelay() {
  const btn = $('#testRelayBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = 'Przesyłanie z drugiego telefonu…';
  try {
    const r = await store.testRelay();
    showTestResult(`${r.via} przesłał ${(r.bytes / 1024).toFixed(0)} KB przez swoje dane komórkowe i Wi‑Fi do hosta: ${r.mbps} Mbps. To test aplikacji, nie internet dla całego telefonu.`);
    toast('Przekazano prawdziwe dane między telefonami', 'good');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Sprawdź przekaźnik do hosta (~128 KB)';
  }
}

async function startDownload() {
  if (downloadSubmitting) return;
  const url = $('#downloadUrlInput').value.trim();
  const sha256 = $('#downloadHashInput').value.trim();
  if (!url.startsWith('https://')) throw new Error('Podaj publiczny adres HTTPS pliku.');
  if (sha256 && !/^[0-9a-fA-F]{64}$/.test(sha256)) throw new Error('SHA-256 musi zawierać 64 znaki szesnastkowe.');
  downloadSubmitting = true;
  $('#startDownloadBtn').disabled = true;
  try {
    await store.startDownload(url, sha256);
    toast('Pobieranie rozpoczęte — stan i weryfikacja pojawią się poniżej.', 'good');
  } finally {
    downloadSubmitting = false;
    renderDownload();
  }
}

function showTestResult(text) {
  const el = $('#testResult');
  el.textContent = text;
  el.hidden = false;
}

function openModal() { $('#modalBackdrop').hidden = false; }
async function handleModal(kind) {
  $('#modalBackdrop').hidden = true;
  if (kind !== 'confirm') return;
  try {
    await store.leave();
    store.go('home');
    toast('Opuszczono siatkę');
  } catch (e) { toast(message(e), 'warn'); }
}

async function shareInvite() {
  if (store.isNative && store.role !== 'host') throw new Error('Zaproszenie może udostępnić host.');
  const text = store.isNative
    ? `Dołącz do mojej siatki MeshRouter „${store.meshName}”. Zainstaluj MeshRouter na Androidzie, wybierz „Dołącz” i wklej zaproszenie:\n${store.invite}`
    : `Zobacz pokaz MeshRouter — ta strona to tylko DEMO: ${location.href}`;
  if (store.isNative) {
    // Android WebView does not reliably expose navigator.share/clipboard.
    // Use the native Share Sheet; a native clipboard fallback is guaranteed.
    try {
      const available = await Share.canShare();
      if (available.value) {
        await Share.share({ title: 'MeshRouter — zaproszenie', text, dialogTitle: 'Przekaż zaproszenie' });
        return;
      }
    } catch (e) {
      if (/cancel|anulowan/i.test(message(e))) return;
    }
    await Clipboard.write({ string: text });
    toast('Zaproszenie skopiowane do schowka', 'good');
  } else if (navigator.share) {
    try { await navigator.share({ title: 'MeshRouter', text }); }
    catch (e) { if (e.name !== 'AbortError') throw e; }
  } else if (navigator.clipboard) {
    await navigator.clipboard.writeText(text);
    toast('Link demo skopiowany do schowka', 'good');
  } else {
    throw new Error('Udostępnianie i schowek są niedostępne na tym urządzeniu.');
  }
}

function render() {
  $('#themeLabel').textContent = store.theme === 'dark' ? 'Ciemny' : 'Jasny';
  $('#dashThemeIcon').textContent = store.theme === 'dark' ? '☾' : '☀';
  $('#platformChip').textContent = store.isNative ? 'Android · sieć lokalna' : `Demo · ${prettyPlatform(store.platform)}`;
  $('#demoBadge').hidden = store.isNative;
  $('#homeNotice').hidden = !store.isNative;
  $('#demoJoin').hidden = store.isNative;
  $('#nativeJoin').hidden = !store.isNative;
  $('#joinInviteBtn').hidden = !store.isNative;
  $('#meshNameHint').textContent = store.isNative
    ? 'Android sam nada SSID i hasło Wi‑Fi. Nazwę MeshRouter pokażemy uczestnikom w aplikacji.'
    : 'Nazwa wyświetlana w pokazowej siatce.';
  $$('.view').forEach((view) => { view.hidden = view.dataset.view !== store.view; });
  if (store.view === 'home') renderHome();
  if (store.view === 'dashboard') renderDashboard();
}

function prettyPlatform(p) {
  return { 'android-web': 'Android (przeglądarka)', 'ios-web': 'iOS (przeglądarka)', web: 'przeglądarka' }[p] || p;
}

function renderHome() {
  const dot = $('.pulse-dot');
  if (store.status === 'connected') {
    $('#homeStatusText').textContent = `Połączono: ${store.meshName} · ${store.nodes.length} urządzeń`;
    dot.className = 'pulse-dot pulse-dot--live';
  } else {
    $('#homeStatusText').textContent = store.nativeError ? store.nativeError : 'Gotowy — brak aktywnej siatki';
    dot.className = 'pulse-dot pulse-dot--idle';
  }
}

function renderDashboard() {
  $('#dashMeshName').textContent = store.meshName || 'MeshRouter';
  $('#dashRole').textContent = store.isNative
    ? (store.role === 'host' ? 'host lokalnego Wi‑Fi' : 'członek siatki')
    : (store.role === 'host' ? 'host pokazowy' : 'członek demo');
  const mode = $('#modeStrip');
  mode.classList.toggle('mode-strip--native', store.isNative);
  mode.classList.toggle('mode-strip--error', store.isNative && !!store.nativeError);
  mode.textContent = store.nativeError && store.isNative
    ? `Problem z połączeniem: ${store.nativeError}`
    : store.isNative
      ? 'Prawdziwa sieć lokalna · internet całego telefonu nie jest współdzielony'
      : 'Tryb DEMO — urządzenia i prędkości są symulowane';
  $('#gaugeLabel').textContent = store.isNative ? 'suma pomiarów łączy*' : 'pokazowa prędkość';
  $('#statDownLabel').textContent = store.isNative ? 'Zmierzono ↓' : 'Pobieranie';
  $('#gatewayLabel').textContent = store.isNative ? 'Rekomendowany przekaźnik · złoty środek' : 'Pokazowy główny ruter';

  const combined = store.combined || 0;
  const { value, unit } = formatSpeed(combined);
  $('#gaugeValue').textContent = value;
  $('#gaugeUnit').textContent = unit;
  const circ = 2 * Math.PI * 84;
  $('#gaugeProgress').style.strokeDashoffset = String(circ * (1 - Math.min(combined / 1200, 1)));
  const stats = store.meshStats || { sumDown: 0, sumUp: 0 };
  $('#statDown').textContent = speedLabel(stats.sumDown);
  $('#statUp').textContent = stats.sumUp ? speedLabel(stats.sumUp) : '—';
  const measured = store.nodes.filter((n) => n.ping > 0);
  $('#statPing').textContent = measured.length
    ? `${Math.round(measured.reduce((sum, n) => sum + n.ping, 0) / measured.length)} ms` : '—';

  $('#gatewayName').textContent = store.gateway ? `${store.gateway.name} · ${store.gateway.model}` : 'Brak pomiaru łącza';
  $('#gatewayWhy').textContent = store.gatewayWhy || 'Zmierz łącze na każdym telefonie, aby wybrać najlepszy balans.';

  const details = $('#meshDetails');
  details.hidden = !store.isNative;
  if (store.isNative) {
    $('#detailSsid').textContent = store.meshSsid || '—';
    $('#detailPassword').textContent = store.meshPassword || '—';
    $('#detailIp').textContent = store.meshIp ? `http://${store.meshIp}:8787/` : '—';
    $('#passwordRow').hidden = store.role !== 'host';
    $('#shareInviteBtn').hidden = store.role !== 'host';
    $('#detailsInfo').textContent = store.role === 'host'
      ? 'Przekaż zaproszenie MR1: drugiemu telefonowi'
      : 'Połączono z Wi‑Fi hosta przez zaproszenie';
  }
  $('#runTestBtn').textContent = store.isNative ? 'Zmierz moje łącze (~1,3 MB danych)' : 'Odśwież wartości demo';
  $('#testHint').textContent = store.isNative
    ? 'Test korzysta z danych komórkowych. *Suma pomiarów nie oznacza połączenia łączy dla innych aplikacji.'
    : 'To wizualizacja. W APK pomiary pobierane są z prawdziwych telefonów.';
  $('#testRelayBtn').hidden = !store.isNative || store.role !== 'host' || !store.getRelayTarget();
  renderDownload();
  $('#footerShareBtn').hidden = store.isNative;
  renderDevices();
  $('#deviceCount').textContent = String(store.nodes.length);
}

function renderDownload() {
  $('#downloadDemo').hidden = store.isNative;
  $('#nativeDownloadControls').hidden = !store.isNative;
  if (!store.isNative) return;
  const consent = $('#relayConsent');
  consent.checked = store.relayEnabled;
  const limit = $('#relayLimitInput');
  if (document.activeElement !== limit) limit.value = String(store.relayLimitMb);
  $('#relayBudgetText').textContent = store.relayEnabled
    ? `Pozostało ${formatMiB(store.relayRemainingBytes)} z ${store.relayLimitMb} MiB na tę sesję. Wyłączenie zgody blokuje nowe fragmenty.`
    : `Przekazywanie wyłączone. Limit po włączeniu: ${store.relayLimitMb} MiB na sesję.`;

  const host = store.role === 'host';
  $('#hostDownloadControls').hidden = !host;
  if (!host) return;
  const ready = store.nodes.filter((n) => n.hasCellular && n.relayEnabled && n.relayRemainingBytes > 0);
  $('#downloadPeersText').textContent = ready.length
    ? `Telefony ze zgodą i modemem: ${ready.length} (${ready.map((n) => n.isSelf ? 'ten telefon' : n.name).join(', ')}). Aktywne dane komórkowe zostaną sprawdzone podczas pobierania.`
    : 'Brak zgadzających się telefonów z danymi komórkowymi i wolnym limitem. Uczestnicy włączają zgodę u siebie.';

  const job = store.download;
  const active = job && ['probing', 'downloading', 'saving'].includes(job.status);
  $('#startDownloadBtn').disabled = downloadSubmitting || active || ready.length === 0;
  $('#startDownloadBtn').textContent = active ? 'Pobieranie w toku…' : 'Pobierz przez telefony';
  $('#cancelDownloadBtn').hidden = !active;
  $('#shareDownloadBtn').hidden = job?.status !== 'complete';
  $('#downloadProgress').hidden = !job;
  if (!job) return;

  const stateLabel = {
    probing: 'Sprawdzanie serwera i dostępnych łączy…', downloading: 'Pobieranie fragmentów…',
    saving: 'Obliczanie SHA-256 i zapis pliku…',
    complete: job.expectedHashProvided ? 'Plik pobrany, SHA-256 zgodny' : 'Plik pobrany; SHA-256 obliczony',
    failed: 'Nie udało się pobrać pliku',
    cancelled: 'Pobieranie anulowane'
  }[job.status] || 'Sprawdzanie stanu…';
  $('#downloadStateText').textContent = stateLabel;
  const progress = job.total > 0 ? Math.min(100, Math.round(job.bytes / job.total * 100)) : 0;
  $('#downloadProgressBar').value = job.status === 'complete' ? 100 : progress;
  const sources = Array.isArray(job.contributors) && job.contributors.length
    ? ` · źródła: ${job.contributors.map((p) => `${p.name} ${formatMiB(p.bytes)}`).join(', ')}` : '';
  $('#downloadDetailsText').textContent = job.error
    ? job.error
    : job.status === 'complete'
      ? `${job.savedTo} · ${formatMiB(job.total)} · ${job.mbps} Mbps · SHA-256: ${job.sha256}${sources}`
      : `${formatMiB(job.bytes)} / ${job.total ? formatMiB(job.total) : '…'} · ${job.links || 0} łączy${sources}`;
}

function formatMiB(bytes) { return `${(Number(bytes || 0) / 1048576).toFixed(2)} MiB`; }

function renderDevices() {
  const list = $('#deviceList');
  const previous = new Map();
  $$('.device-card', list).forEach((card) => previous.set(card.dataset.id, card));
  const order = store.ranked.length ? store.ranked.map((r) => r.node) : store.nodes;
  const frag = document.createDocumentFragment();
  for (const node of order) {
    const card = previous.get(node.id) || buildDeviceCard(node);
    updateDeviceCard(card, node);
    frag.appendChild(card);
  }
  list.replaceChildren(frag);
}

function buildDeviceCard(n) {
  const li = document.createElement('li');
  li.className = 'device-card';
  li.dataset.id = n.id;
  li.innerHTML = `
    <div class="device-card__avatar">${escapeHtml(initials(n.name))}</div>
    <div class="device-card__body">
      <div class="device-card__top">
        <span class="device-card__name">${escapeHtml(n.name)}</span>
        <span class="device-card__you" data-tag="you" hidden>Ty</span>
        <span class="device-card__gw" data-tag="gw" hidden>★ lider</span>
        <span class="device-card__relay" data-tag="relay" hidden>↗ zgoda</span>
      </div>
      <div class="device-card__model">${escapeHtml(n.model)}</div>
      <div class="device-card__metrics">
        <span class="metric metric--operator"><svg viewBox="0 0 24 24"><path d="M4 20V10M9 20V4M14 20v-8M19 20V8" /></svg><span data-m="operator"></span></span>
        <span class="metric" title="Zasięg Wi‑Fi"><span class="signal" data-m="signal"></span><span data-m="signalval"></span></span>
        <span class="metric" title="Pomiar łącza"><svg viewBox="0 0 24 24"><path d="M13 2L3 14h7l-1 8 10-12h-7l1-8z" /></svg><span data-m="speed"></span></span>
        <span class="metric" title="Zliczone bajty pobierania/testów w MeshRouter; limit jest pokazany w ustawieniach zgody"><svg viewBox="0 0 24 24"><path d="M12 19V5M5 12l7-7 7 7" /></svg><span data-m="shared"></span></span>
      </div>
    </div>
    <div class="device-card__battery"><div class="battery"><div class="battery__fill"></div></div><span class="battery__pct"></span></div>`;
  return li;
}

function updateDeviceCard(card, n) {
  card.classList.toggle('device-card--gateway', !!n.isGateway);
  card.querySelector('[data-tag="you"]').hidden = !n.isSelf;
  card.querySelector('[data-tag="gw"]').hidden = !n.isGateway;
  card.querySelector('[data-tag="relay"]').hidden = !store.isNative || !n.relayEnabled || n.relayRemainingBytes <= 0;
  card.querySelector('[data-m="operator"]').textContent = n.operator || '—';
  const level = signalLevel(n.signal || 0);
  card.querySelector('[data-m="signal"]').innerHTML = [1, 2, 3, 4]
    .map((i) => `<span class="${i <= level ? 'on' : ''}"></span>`).join('');
  card.querySelector('[data-m="signalval"]').textContent = n.signal ? `${Math.round(n.signal)}%` : '—';
  card.querySelector('[data-m="speed"]').textContent = n.download > 0 ? speedLabel(n.download) : 'nie zmierzono';
  card.querySelector('[data-m="shared"]').textContent = n.dataShared > 0 ? `${n.dataShared.toFixed(2)} MB` : '—';
  card.querySelector('.battery__fill').style.width = `${Math.max(6, n.battery)}%`;
  card.querySelector('.battery__fill').style.background = batteryColor(n.battery, n.charging);
  card.querySelector('.battery__pct').textContent = `${Math.round(n.battery)}%${n.charging ? ' ⚡' : ''}`;
}

function toast(text, kind = '') {
  const node = document.createElement('div');
  node.className = `toast${kind ? ` toast--${kind}` : ''}`;
  node.textContent = text;
  $('#toastLayer').appendChild(node);
  setTimeout(() => { node.classList.add('out'); setTimeout(() => node.remove(), 300); }, 3200);
}

function message(e) { return e?.message || String(e) || 'Nieznany błąd'; }
function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

boot();
