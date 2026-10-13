import './site.css';

// Website-only interactions. The APK still boots from the separate app entry.
const root = document.documentElement;
const toggle = document.getElementById('themeToggle');
const icon = toggle?.querySelector('.theme-toggle__icon');
const meta = document.querySelector('meta[name="theme-color"]');
let saved = 'dark';
try { saved = localStorage.getItem('mr_site_theme') || 'dark'; } catch (_) { /* storage may be disabled */ }

function setTheme(theme) {
  const light = theme === 'light';
  root.dataset.siteTheme = light ? 'light' : 'dark';
  if (toggle) {
    toggle.setAttribute('aria-pressed', String(light));
    toggle.setAttribute('aria-label', light ? 'Włącz ciemny motyw' : 'Włącz jasny motyw');
  }
  if (icon) icon.textContent = light ? '☾' : '☼';
  if (meta) meta.setAttribute('content', light ? '#eef1f8' : '#101219');
  try { localStorage.setItem('mr_site_theme', light ? 'light' : 'dark'); } catch (_) { /* optional */ }
}

setTheme(saved);
toggle?.addEventListener('click', () => setTheme(root.dataset.siteTheme === 'dark' ? 'light' : 'dark'));
const year = document.getElementById('year');
if (year) year.textContent = String(new Date().getFullYear());
