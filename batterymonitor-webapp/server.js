'use strict';

const http = require('http');
const PORT = process.env.PORT || 3000;
const HUBITAT_URL = 'http://192.168.10.250/apps/api/80/summary?access_token=190245b7-d66d-49dd-9385-d2f70f92b699';

// ─── Proxy ───────────────────────────────────────────────────────────────────

function fetchSummary() {
  return new Promise((resolve, reject) => {
    http.get(HUBITAT_URL, (res) => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        try { resolve(JSON.parse(raw)); }
        catch (e) { reject(new Error('Invalid JSON from Hubitat')); }
      });
    }).on('error', reject);
  });
}

// ─── HTML ─────────────────────────────────────────────────────────────────────

const HTML = `<!DOCTYPE html>
<html lang="ro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Battery Monitor</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #f4f6f9;
    color: #222;
    padding: 12px;
  }

  h1 { font-size: 1.3rem; font-weight: 700; margin-bottom: 2px; }
  .meta { font-size: .75rem; color: #888; margin-bottom: 16px; }

  .hub-section { margin-bottom: 24px; }
  .hub-title {
    font-size: .85rem; font-weight: 700; text-transform: uppercase;
    letter-spacing: .06em; color: #555; margin-bottom: 6px;
    padding-bottom: 4px; border-bottom: 2px solid #dde2ea;
  }

  /* Table wrapper enables horizontal scroll on small screens */
  .tbl-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; }

  table { width: 100%; border-collapse: collapse; background: #fff;
          border-radius: 8px; overflow: hidden;
          box-shadow: 0 1px 4px rgba(0,0,0,.08); }
  thead tr { background: #f0f3f8; }
  th { font-size: .72rem; font-weight: 600; text-align: left;
       padding: 8px 10px; color: #555; white-space: nowrap; }
  td { font-size: .82rem; padding: 7px 10px; border-top: 1px solid #eef0f4;
       white-space: nowrap; }
  td.col-device { white-space: normal; word-break: break-word; min-width: 120px; }
  tr:hover td { background: #fafbfd; }

  /* Responsive column visibility */
  .col-drain, .col-lastbat, .col-type { display: none; }
  @media (min-width: 600px) {
    .col-health, .col-days { display: table-cell; }
  }
  @media (min-width: 900px) {
    .col-drain, .col-lastbat, .col-activity, .col-type { display: table-cell; }
  }

  /* Battery colours */
  .bat-red    { color: #e53935; font-weight: 600; }
  .bat-orange { color: #fb8c00; font-weight: 600; }
  .bat-green  { color: #43a047; font-weight: 600; }

  /* Colored dot indicator */
  .dot {
    display: inline-block; width: 10px; height: 10px;
    border-radius: 50%; vertical-align: middle; margin-right: 4px;
  }
  .dot.bat-red    { background: #e53935; }
  .dot.bat-orange { background: #fb8c00; }
  .dot.bat-green  { background: #43a047; }

  /* Action badges */
  .badge {
    display: inline-block; font-size: .68rem; font-weight: 700;
    padding: 2px 7px; border-radius: 10px; white-space: nowrap;
  }
  .badge-replace   { background: #fdecea; color: #c62828; }
  .badge-reconnect { background: #fff3e0; color: #e65100; }

  /* Batteries needed */
  .batteries-section { margin-bottom: 24px; }
  .batteries-section h2 { font-size: .95rem; font-weight: 700; margin-bottom: 8px; }
  .bat-cards { display: flex; flex-wrap: wrap; gap: 10px; }
  .bat-card {
    background: #fff; border-radius: 8px; padding: 10px 14px;
    box-shadow: 0 1px 4px rgba(0,0,0,.08); min-width: 110px;
  }
  .bat-card .bat-type { font-size: 1rem; font-weight: 700; color: #333; }
  .bat-card .bat-count { font-size: .8rem; color: #555; margin-top: 2px; }
  .bat-card .bat-break { font-size: .72rem; color: #888; margin-top: 3px; }

  /* Legend */
  .legend {
    font-size: .73rem; color: #777; line-height: 1.7;
    background: #fff; border-radius: 8px; padding: 10px 14px;
    box-shadow: 0 1px 4px rgba(0,0,0,.08);
  }
  .legend b { color: #444; }

  .error { color: #c62828; padding: 16px; background: #fdecea;
           border-radius: 8px; font-size: .85rem; }

  #spinner { text-align: center; padding: 40px; color: #888; font-size: .9rem; }
</style>
</head>
<body>

<h1>🔋 Battery Monitor</h1>
<div class="meta" id="meta">Se încarcă…</div>
<button onclick="load()" style="margin-bottom:16px;padding:6px 14px;font-size:.8rem;cursor:pointer;border:1px solid #ccc;border-radius:6px;background:#fff">Reîmprospătare</button>

<div id="content"><div id="spinner">Se încarcă datele…</div></div>

<script>
function batteryClass(pct) {
  if (pct < 30) return 'bat-red';
  if (pct <= 70) return 'bat-orange';
  return 'bat-green';
}

function batteryDot(pct) {
  const cls = batteryClass(pct);
  return \`<span class="dot \${cls}"></span>\`;
}

function actionBadge(action) {
  if (action === 'replace')   return '<span class="badge badge-replace">Înlocuiește</span>';
  if (action === 'reconnect') return '<span class="badge badge-reconnect">Reconectează</span>';
  return '';
}

function fmtTs(ms) {
  if (!ms) return '—';
  const diff = Math.floor((Date.now() - ms) / 1000);
  if (diff < 60)   return diff + 's ago';
  if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
  if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
  return Math.floor(diff / 86400) + 'd ago';
}

function renderTable(devices) {
  const rows = devices.map(d => {
    const cls = batteryClass(d.battery);
    return \`<tr>
      <td>\${actionBadge(d.action)}</td>
      <td class="col-device">\${d.device}</td>
      <td class="\${cls}">\${batteryDot(d.battery)} \${d.battery}%</td>
      <td class="col-health">\${d.health || '—'}</td>
      <td class="col-drain">\${d.drain != null ? d.drain.toFixed(2) : '—'}</td>
      <td class="col-days">\${d.estDays != null ? d.estDays + 'z' : '—'}</td>
      <td class="col-activity">\${fmtTs(d.lastActivity)}</td>
      <td class="col-lastbat">\${fmtTs(d.lastBattery)}</td>
      <td class="col-type">\${d.batteryType || '—'}</td>
    </tr>\`;
  }).join('');

  return \`<div class="tbl-wrap"><table>
    <thead><tr>
      <th>Acțiune</th>
      <th>Dispozitiv</th>
      <th>Baterie</th>
      <th class="col-health">Stare</th>
      <th class="col-drain">Consum</th>
      <th class="col-days">Zile Est.</th>
      <th class="col-activity">Ult. Activitate</th>
      <th class="col-lastbat">Ult. Baterie</th>
      <th class="col-type">Tip Bat.</th>
    </tr></thead>
    <tbody>\${rows}</tbody>
  </table></div>\`;
}

function renderBatteries(needed) {
  const entries = Object.entries(needed || {});
  if (!entries.length) return '';
  const cards = entries.map(([type, data]) => {
    const breakdown = Object.entries(data.breakdown || {})
      .map(([s, c]) => \`\${s}: \${c}\`).join(', ');
    return \`<div class="bat-card">
      <div class="bat-type">\${type}</div>
      <div class="bat-count">\${data.total} buc.</div>
      <div class="bat-break">\${breakdown}</div>
    </div>\`;
  }).join('');
  return \`<div class="batteries-section">
    <h2>Baterii Necesare</h2>
    <div class="bat-cards">\${cards}</div>
  </div>\`;
}

function render(data) {
  const hubs = data.hubs || {};
  let html = '';

  // Filter: only devices with action (same as default filter in app)
  const showAll = false; // change to true to see all devices

  for (const [hub, devices] of Object.entries(hubs)) {
    const visible = showAll ? devices : devices.filter(d => d.action);
    if (!visible.length) continue;
    html += \`<div class="hub-section">
      <div class="hub-title">Hub: \${hub}</div>
      \${renderTable(visible)}
    </div>\`;
  }

  html += renderBatteries(data.batteriesNeeded);

  html += \`<div class="legend">
    <b>Sufixe:</b> CS = Senzor de contact | LS = Senzor Scurgere | MS = Senzor Mișcare |
    TH = Senzor Temperatură | TRV = Ventil Termostat | SW = Buton<br>
    <b>Baterii:</b> CR2016 = CS | CR2450 = LS | CR2032 = MS, TH, SW | AA = TRV
  </div>\`;

  document.getElementById('content').innerHTML = html || '<p style="color:#888;padding:20px">Niciun dispozitiv nu necesită acțiune.</p>';
  document.getElementById('meta').textContent =
    'Actualizat: ' + new Date(data.generated).toLocaleString('ro-RO') +
    '';
}

async function load() {
  try {
    const res = await fetch('/api/summary');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    render(await res.json());
  } catch (e) {
    document.getElementById('content').innerHTML =
      '<div class="error">Eroare la încărcarea datelor: ' + e.message + '</div>';
    document.getElementById('meta').textContent = 'Eroare la încărcare';
  }
}

load();
</script>
</body>
</html>`;

// ─── Server ───────────────────────────────────────────────────────────────────

http.createServer(async (req, res) => {
  if (req.url === '/api/summary') {
    try {
      const data = await fetchSummary();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(data));
    } catch (e) {
      res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message }));
    }
  } else {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(HTML);
  }
}).listen(PORT, '0.0.0.0', () => {
  console.log(`Battery Monitor running on http://0.0.0.0:${PORT}`);
});
