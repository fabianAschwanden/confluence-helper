// confluence-helper UI. All server/user data goes into the DOM via textContent (never innerHTML).

const $ = (sel, root = document) => root.querySelector(sel);

const SESSION_MSG = 'Sitzung ungültig – bitte über den Starter neu öffnen';
const MAX_UPLOAD = 100 * 1024 * 1024;
const TOKEN_KEY = 'cfh.token';
const PREFS_KEY = 'cfh.prefs';

// ---------- small helpers ----------

function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of kids.flat()) if (c != null && c !== false) el.append(c);
  return el;
}

function safeHref(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : null;
  } catch { return null; }
}

function hostOf(url) {
  try { return new URL(url).host; } catch { return url || ''; }
}

function formatSize(n) {
  if (!Number.isFinite(n)) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

function userName(u) {
  if (u == null) return '';
  if (typeof u === 'string') return u;
  return u.displayName || u.username || u.userKey || '';
}

const prefs = (() => {
  let data = {};
  try { data = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}') || {}; } catch { data = {}; }
  return {
    get: (k, d) => (k in data ? data[k] : d),
    set(k, v) {
      data[k] = v;
      try { localStorage.setItem(PREFS_KEY, JSON.stringify(data)); } catch { /* storage unavailable */ }
    },
  };
})();

function scrollIntoViewCalm(el) {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
}

// ---------- token & API ----------

let token = null;
let dead = false;
let offline = false;

function initToken() {
  const m = location.hash.match(/(?:^#|&)t=([A-Za-z0-9_-]+)/);
  if (m) {
    token = m[1];
    try { sessionStorage.setItem(TOKEN_KEY, token); } catch { /* keep in memory only */ }
    history.replaceState(null, '', location.pathname + location.search);
  } else {
    try { token = sessionStorage.getItem(TOKEN_KEY); } catch { token = null; }
  }
}

class ApiError extends Error {
  constructor(message, kind, status, problems = [], data = {}) {
    super(message);
    this.kind = kind;
    this.status = status;
    this.problems = problems;
    this.data = data; // full error body, e.g. reason, switchProfile
  }
}

async function api(method, path, body) {
  if (dead) throw new ApiError(SESSION_MSG, 'session', 401);
  const headers = { 'X-CFH-Token': token || '' };
  let payload;
  if (body instanceof Blob) {
    payload = body;
    headers['Content-Type'] = 'application/octet-stream';
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  let res;
  try {
    res = await fetch(path, { method, headers, body: payload, cache: 'no-store' });
  } catch {
    setOffline(true);
    throw new ApiError('Der lokale Server antwortet nicht. Läuft das Fenster des Starters noch?', 'offline', 0);
  }
  setOffline(false);
  const text = await res.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch { data = null; } }
  if (!res.ok) {
    const kind = data && typeof data.kind === 'string' ? data.kind : (res.status === 401 ? 'session' : 'other');
    if (kind === 'session' || (res.status === 401 && !['auth', 'config'].includes(kind))) {
      sessionInvalid();
      throw new ApiError(SESSION_MSG, 'session', res.status);
    }
    const msg = (data && data.error) || `Unerwartete Antwort des Servers (HTTP ${res.status}).`;
    throw new ApiError(msg, kind, res.status, Array.isArray(data?.problems) ? data.problems : [], data || {});
  }
  return data ?? {};
}

function block(title, text) {
  $('#blocker-title').textContent = title;
  $('#blocker-text').textContent = text;
  $('#blocker').hidden = false;
  $('#main').inert = true;
  for (const d of document.querySelectorAll('dialog[open]')) d.close();
}

function sessionInvalid() {
  if (dead) return;
  dead = true;
  stopHeartbeat();
  block(SESSION_MSG, 'Der Server wurde neu gestartet oder die Seite ohne Zugangsschlüssel geöffnet. Aus Sicherheitsgründen gilt jede Sitzung nur für einen Start.');
  renderConn();
}

// ---------- UI feedback ----------

function setBusy(btn, busy) {
  if (!btn) return;
  if (busy) {
    if (!btn.dataset.label) btn.dataset.label = btn.textContent;
    if (btn.dataset.busy) btn.textContent = btn.dataset.busy;
    // disabling the focused button drops focus to <body>; give it back afterwards
    if (document.activeElement === btn) btn.dataset.refocus = '1';
  } else if (btn.dataset.label) {
    btn.textContent = btn.dataset.label;
    delete btn.dataset.label;
  }
  btn.disabled = busy;
  btn.classList.toggle('busy', busy);
  btn.setAttribute('aria-busy', String(busy));
  if (!busy && btn.dataset.refocus) {
    delete btn.dataset.refocus;
    const a = document.activeElement;
    if (!a || a === document.body) btn.focus();
  }
}

async function withBusy(btn, fn) {
  setBusy(btn, true);
  try { return await fn(); } finally { setBusy(btn, false); }
}

function toast(text) {
  const el = h('div', { class: 'toast', text });
  $('#toasts').append(el);
  setTimeout(() => {
    el.classList.add('out');
    setTimeout(() => el.remove(), 350);
  }, 4000);
}

function problemList(problems, onJump) {
  const ul = h('ul', { class: 'problems' });
  for (const p of problems) {
    const err = String(p.severity).toUpperCase() !== 'WARNING';
    const loc = p.line ? `Zeile ${p.line}${p.column ? `, Spalte ${p.column}` : ''}` : '';
    const sev = h('span', { class: `sev ${err ? 'sev-error' : 'sev-warning'}`, text: err ? 'Fehler' : 'Hinweis' });
    const locEl = loc && onJump
      ? h('button', { type: 'button', class: 'btn-link loc', text: loc, title: 'Zur Zeile springen', onclick: () => onJump(p) })
      : (loc ? h('span', { class: 'loc', text: loc }) : null);
    ul.append(h('li', {}, sev, locEl, h('span', { text: p.message || '' })));
  }
  return ul;
}

function logBlock(log, label = 'Protokoll') {
  if (!Array.isArray(log) || !log.length) return null;
  return h('details', { class: 'log' }, h('summary', { text: label }), h('pre', { text: log.join('\n') }));
}

/** Renders an alert into a message container. type: error | ok | warn | info. */
function showMsg(box, type, text, { problems, onJump, extra } = {}) {
  box.replaceChildren();
  const el = h('div', { class: `alert alert-${type}`, role: type === 'error' ? 'alert' : 'status' }, h('p', { text }));
  if (problems && problems.length) el.append(problemList(problems, onJump));
  if (extra) el.append(...[].concat(extra).filter(Boolean));
  box.append(el);
  return el;
}

function showError(box, e, opts = {}) {
  if (e?.kind === 'session') return;
  const problems = e?.problems?.length ? e.problems : undefined;
  let text = e?.message || String(e);
  if (e?.kind === 'config') text += ' Richte die Verbindung unter «Verbindung» ein.';
  const extra = [].concat(opts.extra || [], otherServerActions(e));
  return showMsg(box, 'error', text, { ...opts, problems, extra });
}

/** Buttons for «page folder belongs to another server»: switch to the matching profile or open Verbindung. */
function otherServerActions(e) {
  if (e?.data?.reason !== 'otherServer') return [];
  const name = e.data.switchProfile;
  if (typeof name === 'string') {
    const label = name ? `Zu Profil «${name}» wechseln` : 'Zum Standardprofil wechseln';
    return [h('div', { class: 'actions' }, h('button', { type: 'button', class: 'btn small', text: label, onclick: (ev) => switchProfile(name, ev.currentTarget) }))];
  }
  return [h('div', { class: 'actions' }, h('button', { type: 'button', class: 'btn small', text: 'Verbindung öffnen', onclick: () => { for (const d of document.querySelectorAll('dialog[open]')) d.close(); mainTabs?.select('tab-conn', true); } }))];
}

async function switchProfile(name, btn) {
  try {
    status = await withBusy(btn, () => api('POST', '/api/profile', { profile: name || null }));
    connDirty = false;
    renderStatus();
    local.remote.clear();
    if (local.loaded) renderLocal();
    toast(name ? `Profil «${name}» ist aktiv` : 'Standardprofil ist aktiv');
    checkUser().catch(() => { /* shown in header */ });
  } catch (err) {
    if (err.kind !== 'session') toast(err.message);
  }
}

function clearMsg(box) { box.replaceChildren(); }

function renderDiff(pre, diff) {
  pre.replaceChildren();
  for (const line of String(diff || '').split('\n')) {
    let cls = 'd-ctx';
    if (line.startsWith('+++') || line.startsWith('---')) cls = 'd-file';
    else if (line.startsWith('@@')) cls = 'd-hunk';
    else if (line.startsWith('+')) cls = 'd-add';
    else if (line.startsWith('-')) cls = 'd-del';
    pre.append(h('span', { class: cls, text: line || ' ' }));
  }
}

function confirmDialog({ title, text, ok = 'OK', danger = false }) {
  const dlg = $('#dlg-confirm');
  $('#confirm-title').textContent = title;
  $('#confirm-text').textContent = text;
  const okBtn = $('#confirm-ok');
  okBtn.textContent = ok;
  okBtn.className = `btn ${danger ? 'danger' : 'primary'}`;
  dlg.returnValue = '';
  return new Promise((resolve) => {
    dlg.addEventListener('close', () => resolve(dlg.returnValue === 'ok'), { once: true });
    dlg.showModal();
    (danger ? $('#confirm-cancel') : okBtn).focus();
  });
}

async function openFolder(dir, btn) {
  try {
    await withBusy(btn, () => api('POST', '/api/open', { dir }));
    toast('Ordner geöffnet');
  } catch (e) {
    if (e.kind !== 'session') toast(e.message);
  }
}

// ---------- tabs ----------

function setupTabs(list, onSelect) {
  const tabs = [...list.querySelectorAll('[role="tab"]')];
  const select = (tab, focus = false) => {
    for (const t of tabs) {
      const on = t === tab;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
    }
    if (focus) tab.focus();
    // narrow screens: the tab bar scrolls sideways, keep the active tab in view
    if (list.scrollWidth > list.clientWidth) list.scrollLeft = Math.max(0, tab.offsetLeft - list.offsetLeft - 16);
    onSelect?.(tab.id);
  };
  list.addEventListener('click', (e) => {
    const t = e.target.closest('[role="tab"]');
    if (t && tabs.includes(t)) select(t);
  });
  list.addEventListener('keydown', (e) => {
    const i = tabs.indexOf(document.activeElement);
    if (i < 0) return;
    const next = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
    if (next === undefined) return;
    e.preventDefault();
    select(tabs[(next + tabs.length) % tabs.length], true);
  });
  return { select: (id, focus) => select(tabs.find((t) => t.id === id) || tabs[0], focus) };
}

// ---------- code editor (textarea + line numbers) ----------

function makeEditor(textarea) {
  const gutter = textarea.parentElement.querySelector('.gutter');
  let lines = 0;
  const sync = () => { gutter.scrollTop = textarea.scrollTop; };
  const update = () => {
    let n = 1;
    const v = textarea.value;
    for (let i = v.indexOf('\n'); i !== -1; i = v.indexOf('\n', i + 1)) n++;
    if (n !== lines) {
      lines = n;
      let s = '';
      for (let i = 1; i <= n; i++) s += `${i}\n`;
      gutter.textContent = `${s}\n\n`;
    }
    sync();
  };
  textarea.addEventListener('input', update);
  textarea.addEventListener('scroll', sync);
  return {
    get value() { return textarea.value; },
    set value(v) { textarea.value = v; textarea.scrollTop = 0; textarea.scrollLeft = 0; update(); },
    gotoLine(line) {
      const v = textarea.value;
      let start = 0;
      for (let l = 1; l < line; l++) {
        const i = v.indexOf('\n', start);
        if (i === -1) break;
        start = i + 1;
      }
      const nl = v.indexOf('\n', start);
      const end = nl === -1 ? v.length : nl;
      textarea.focus();
      textarea.setSelectionRange(start, end);
      const lh = parseFloat(getComputedStyle(textarea).lineHeight) || 20;
      textarea.scrollTop = Math.max(0, (line - 1) * lh - textarea.clientHeight / 3);
      textarea.scrollLeft = 0;
      sync();
    },
  };
}

// ---------- storage preview ----------

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function previewDoc(storage) {
  // The HTML parser drops CDATA and does not self-close unknown elements; normalise both.
  let s = storage.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_, t) => escapeHtml(t));
  s = s.replace(/<((?:ac|ri|at):[\w.-]+)((?:\s+[^\s=>/]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*\/>/g, '<$1$2></$1>');
  const css = new URL('app.css', location.href).href;
  return `<!doctype html><html lang="de"><head><meta charset="utf-8"><link rel="stylesheet" href="${css}"></head><body class="pv">${s}</body></html>`;
}

// ---------- header / status ----------

let status = null;
const conn = { state: 'unknown', user: '', error: '', kind: '' };

function setOffline(v) {
  if (offline === v) return;
  offline = v;
  renderConn();
}

function renderConn() {
  const box = $('#conn');
  let state = 'pending';
  let label = 'Verbinde …';
  let title = '';
  if (dead) { state = 'bad'; label = 'Sitzung ungültig'; }
  else if (offline) { state = 'bad'; label = 'Lokaler Server nicht erreichbar'; }
  else if (status && !status.configured) { state = 'warn'; label = 'Verbindung nicht eingerichtet'; }
  else if (status) {
    const host = hostOf(status.baseUrl);
    title = status.baseUrl + (status.profile ? ` (Profil ${status.profile})` : '');
    if (conn.state === 'ok') { state = 'ok'; label = conn.user ? `${host} · ${conn.user}` : host; }
    else if (conn.state === 'error') {
      state = 'bad';
      label = `${host} · ${conn.kind === 'auth' ? 'Anmeldung fehlgeschlagen' : 'nicht erreichbar'}`;
      title += ` – ${conn.error}`;
    }
    else if (conn.state === 'checking') { label = `${host} · wird geprüft …`; }
    else { state = 'warn'; label = host; }
  }
  box.dataset.state = state;
  box.title = title;
  $('#conn-text').textContent = label;
}

function renderStatus() {
  if (!status) return;
  const notice = $('#app-notice');
  if (status.workspaceWarning) {
    showMsg(notice, 'warn', status.workspaceWarning, { extra: h('div', { class: 'actions' }, h('button', { type: 'button', class: 'btn small', text: 'Arbeitsordner wählen …', onclick: () => $('#ws-btn').click() })) });
  } else {
    clearMsg(notice);
  }
  $('#ws-path').textContent = status.workspace || '–';
  $('#ws-btn').title = `Arbeitsordner ändern (${status.workspace || '–'})`;
  $('#conn-file').textContent = status.configFile || '–';
  fillConnForm();
  renderConn();
}

async function loadStatus() {
  status = await api('GET', '/api/status');
  renderStatus();
}

let checkSeq = 0;

async function checkUser() {
  const seq = ++checkSeq; // a slow answer for an earlier connection must not overwrite a newer one
  if (!status?.configured) { conn.state = 'unknown'; renderConn(); return null; }
  conn.state = 'checking';
  renderConn();
  try {
    const r = await api('POST', '/api/whoami', {});
    if (seq === checkSeq) {
      conn.state = 'ok';
      conn.user = userName(r.user);
    }
    return userName(r.user);
  } catch (e) {
    if (seq === checkSeq) {
      conn.state = 'error';
      conn.error = e.message;
      conn.kind = e.kind;
    }
    throw e;
  } finally {
    renderConn();
  }
}

// ---------- heartbeat ----------

let heartbeat = null;
function startHeartbeat() {
  heartbeat = setInterval(ping, 30000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) ping(); });
}
function stopHeartbeat() { clearInterval(heartbeat); heartbeat = null; }
async function ping() {
  if (dead || !heartbeat) return;
  try { await api('GET', '/api/ping'); } catch { /* reflected in header via setOffline / sessionInvalid */ }
}

// ---------- tab 1: new page ----------

const newPage = {
  upload: null,
  editor: null,
  previewStale: true,
  created: false,
  viewTabs: null,
};

function kindLabel(kind) {
  return { pdf: 'PDF', excel: 'Excel', csv: 'CSV' }[kind] || kind || 'Datei';
}

function baseName(name) {
  return String(name || '').replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ').trim();
}

async function handleFile(file) {
  const msg = $('#new-msg');
  clearMsg(msg);
  if (!file) return;
  if (!/\.(pdf|xlsx|xlsm|xls|csv|tsv)$/i.test(file.name)) {
    showMsg(msg, 'error', 'Dieser Dateityp wird nicht unterstützt. Möglich sind PDF, Excel (.xlsx, .xlsm, .xls) und CSV/TSV.');
    return;
  }
  if (file.size > MAX_UPLOAD) {
    showMsg(msg, 'error', `Die Datei ist zu gross (${formatSize(file.size)}). Erlaubt sind höchstens 100 MB.`);
    return;
  }
  const drop = $('#drop');
  const title = $('#drop-title');
  drop.classList.add('busy');
  drop.setAttribute('aria-busy', 'true');
  title.textContent = `«${file.name}» wird hochgeladen …`;
  try {
    const r = await api('POST', `/api/upload?name=${encodeURIComponent(file.name)}`, file);
    newPage.upload = r;
    showUpload(r);
  } catch (e) {
    showError(msg, e);
  } finally {
    drop.classList.remove('busy');
    drop.removeAttribute('aria-busy');
    title.textContent = newPage.upload ? 'Andere Datei wählen' : 'Datei hierher ziehen';
    $('#file-input').value = '';
  }
}

function showUpload(u) {
  $('#file-info').hidden = false;
  $('#file-kind').textContent = kindLabel(u.kind);
  $('#file-name').textContent = u.name || '';
  $('#file-size').textContent = formatSize(u.size);

  const isExcel = u.kind === 'excel';
  const tabular = isExcel || u.kind === 'csv';
  $('#opt-hidden-row').hidden = !isExcel;
  $('#opt-header-row').hidden = !tabular;
  $('#opt-hidden').checked = false;

  const sheetsBox = $('#sheets-box');
  const sheets = $('#sheets');
  sheets.replaceChildren();
  const list = isExcel && Array.isArray(u.sheets) ? u.sheets : [];
  sheetsBox.hidden = list.length === 0;
  for (const s of list) {
    const cb = h('input', { type: 'checkbox', value: s.name });
    cb.checked = !s.hidden;
    cb.disabled = !!s.hidden;
    if (s.hidden) cb.dataset.hiddenSheet = '1';
    sheets.append(h('label', { class: 'check' }, cb, h('span', {}, s.name, s.hidden ? h('span', { class: 'tag', text: 'ausgeblendet' }) : null)));
  }

  $('#convert-opts').hidden = false;
  $('#result-card').hidden = true;
  $('#create-card').hidden = true;
  clearMsg($('#create-msg'));
  clearMsg($('#new-problems'));
  newPage.created = false;
  $('#create-title').value = baseName(u.name);
  $('#create-attach').checked = false;
  $('#create-message-row').hidden = true;
  $('#convert-btn').focus();
}

async function convert() {
  const u = newPage.upload;
  const msg = $('#new-msg');
  clearMsg(msg);
  if (!u) return;
  const body = { uploadId: u.uploadId };
  if (u.kind === 'excel') {
    body.includeHidden = $('#opt-hidden').checked;
    if (u.sheets?.length) {
      const chosen = [...$('#sheets').querySelectorAll('input:checked')].map((c) => c.value);
      if (!chosen.length) {
        showMsg(msg, 'error', 'Bitte mindestens ein Blatt auswählen.');
        return;
      }
      body.sheets = chosen;
    }
  }
  if (u.kind === 'excel' || u.kind === 'csv') body.headerRow = $('#opt-header').checked;
  const t = $('#conv-title').value.trim();
  if (t) body.title = t;

  try {
    const r = await withBusy($('#convert-btn'), () => api('POST', '/api/convert', body));
    newPage.editor.value = r.storage || '';
    newPage.previewStale = true;
    newPage.created = false;
    $('#result-card').hidden = false;
    $('#create-card').hidden = false;
    clearMsg($('#create-msg'));
    newPage.viewTabs.select('view-preview');
    const probs = $('#new-problems');
    if (r.problems?.length) {
      showMsg(probs, 'warn', 'Die Umwandlung meldet Folgendes. Bitte im Quelltext prüfen:', { problems: r.problems, onJump: jumpNewSource });
    } else {
      clearMsg(probs);
    }
    const titleInput = $('#create-title');
    if (t && (!titleInput.value.trim() || titleInput.value === baseName(u.name))) titleInput.value = t;
    scrollIntoViewCalm($('#result-card'));
  } catch (e) {
    showError(msg, e);
  }
}

function refreshPreview() {
  if (!newPage.previewStale) return;
  // A fresh iframe per render: no session-history entries and no stale renderer state.
  const old = $('#preview');
  const frame = old.cloneNode(false);
  frame.srcdoc = previewDoc(newPage.editor.value);
  old.replaceWith(frame);
  newPage.previewStale = false;
}

function jumpNewSource(p) {
  newPage.viewTabs.select('view-source');
  newPage.editor.gotoLine(p.line || 1);
}

async function validateNew() {
  const box = $('#new-problems');
  try {
    const r = await withBusy($('#new-validate'), () => api('POST', '/api/validate', { storage: newPage.editor.value }));
    if (r.problems?.length) showMsg(box, hasErrors(r.problems) ? 'error' : 'warn', problemsHeadline(r.problems), { problems: r.problems, onJump: jumpNewSource });
    else showMsg(box, 'ok', 'Keine Probleme gefunden.');
  } catch (e) {
    showError(box, e, { onJump: jumpNewSource });
  }
}

function hasErrors(problems) {
  return problems.some((p) => String(p.severity).toUpperCase() !== 'WARNING');
}

function problemsHeadline(problems) {
  const errs = problems.filter((p) => String(p.severity).toUpperCase() !== 'WARNING').length;
  const warns = problems.length - errs;
  const parts = [];
  if (errs) parts.push(errs === 1 ? '1 Fehler' : `${errs} Fehler`);
  if (warns) parts.push(warns === 1 ? '1 Hinweis' : `${warns} Hinweise`);
  return `${parts.join(' und ')} gefunden.`;
}

function markInvalid(input, invalid) {
  if (invalid) input.setAttribute('aria-invalid', 'true');
  else input.removeAttribute('aria-invalid');
}

async function createPage(ev) {
  ev.preventDefault();
  const msg = $('#create-msg');
  clearMsg(msg);
  const space = $('#create-space');
  const title = $('#create-title');
  const spaceKey = space.value.trim();
  const t = title.value.trim();
  markInvalid(space, !spaceKey);
  markInvalid(title, !t);
  if (!spaceKey || !t) {
    showMsg(msg, 'error', 'Bitte Space-Key und Titel angeben.');
    (spaceKey ? title : space).focus();
    return;
  }
  const parent = $('#create-parent').value.trim();
  prefs.set('spaceKey', spaceKey);
  prefs.set('parent', parent);

  const body = { storage: newPage.editor.value, spaceKey, title: t };
  if (parent) body.parent = parent;
  if ($('#create-attach').checked && newPage.upload) {
    body.attachUploadId = newPage.upload.uploadId;
    const m = $('#create-message').value.trim();
    if (m) body.message = m;
  }
  try {
    const r = await withBusy($('#create-btn'), () => api('POST', '/api/create', body));
    newPage.created = true;
    local.stale = true;
    const href = safeHref(r.webUrl);
    const links = h('div', { class: 'actions' },
      href ? h('a', { class: 'btn primary', href, target: '_blank', rel: 'noopener noreferrer', text: 'In Confluence öffnen' }) : null,
      r.dir ? h('button', { type: 'button', class: 'btn', text: 'Ordner öffnen', onclick: (e) => openFolder(r.dir, e.currentTarget) }) : null,
    );
    const extra = [];
    if (r.attachmentError) extra.push(h('p', { class: 'small', text: r.attachmentError }));
    extra.push(links, logBlock(r.log));
    showMsg(msg, r.attachmentError ? 'warn' : 'ok', `Seite «${r.title || t}» angelegt (ID ${r.id}, Version ${r.version}). Die lokale Kopie liegt im Arbeitsordner unter ${r.dir}.`, {
      problems: r.problems, extra,
    });
  } catch (e) {
    showError(msg, e, { onJump: jumpNewSource });
  }
}

function downloadSource() {
  const blob = new Blob([newPage.editor.value], { type: 'application/xhtml+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: 'page.xhtml' });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

function initNewPage() {
  newPage.editor = makeEditor($('#new-source'));
  newPage.viewTabs = setupTabs($('#view-tabs'), (id) => { if (id === 'view-preview') refreshPreview(); });
  $('#new-source').addEventListener('input', () => { newPage.previewStale = true; });

  const input = $('#file-input');
  const drop = $('#drop');
  input.addEventListener('change', () => handleFile(input.files[0]));
  for (const t of ['dragenter', 'dragover']) {
    drop.addEventListener(t, (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'copy';
      drop.classList.add('over');
    });
  }
  drop.addEventListener('dragleave', (e) => { if (!drop.contains(e.relatedTarget)) drop.classList.remove('over'); });
  drop.addEventListener('drop', (e) => {
    e.preventDefault();
    drop.classList.remove('over');
    const files = e.dataTransfer.files;
    if (files.length > 1) toast('Es wird nur die erste Datei verwendet.');
    handleFile(files[0]);
  });
  // Dropping a file elsewhere must not navigate away from the app.
  for (const t of ['dragover', 'drop']) window.addEventListener(t, (e) => e.preventDefault());

  $('#opt-hidden').addEventListener('change', (e) => {
    for (const cb of $('#sheets').querySelectorAll('input[data-hidden-sheet]')) {
      cb.disabled = !e.target.checked;
      if (!e.target.checked) cb.checked = false;
    }
  });
  $('#convert-btn').addEventListener('click', convert);
  $('#new-validate').addEventListener('click', validateNew);
  $('#create-form').addEventListener('submit', createPage);
  $('#download-btn').addEventListener('click', downloadSource);
  $('#create-attach').addEventListener('change', (e) => { $('#create-message-row').hidden = !e.target.checked; });
  for (const id of ['create-space', 'create-title']) $(`#${id}`).addEventListener('input', (e) => markInvalid(e.target, false));

  $('#create-space').value = prefs.get('spaceKey', '');
  $('#create-parent').value = prefs.get('parent', '');
}

// ---------- tab 2: export ----------

async function doExport(ev) {
  ev.preventDefault();
  const msg = $('#export-msg');
  clearMsg(msg);
  const pageInput = $('#export-page');
  const page = pageInput.value.trim();
  markInvalid(pageInput, !page);
  if (!page) {
    showMsg(msg, 'error', 'Bitte eine Seiten-URL oder Seiten-ID angeben.');
    pageInput.focus();
    return;
  }
  const children = $('#export-children').checked;
  const body = {
    page,
    attachments: $('#export-att').checked,
    children,
    force: $('#export-force').checked,
  };
  const depth = parseInt($('#export-depth').value, 10);
  if (children && depth > 0) body.depth = depth;
  prefs.set('exportAtt', body.attachments);
  prefs.set('exportChildren', children);
  prefs.set('exportDepth', $('#export-depth').value);

  try {
    const r = await withBusy($('#export-btn'), () => api('POST', '/api/export', body));
    local.stale = true;
    $('#export-force').checked = false;
    const results = Array.isArray(r.results) ? r.results : [];
    const list = $('#export-list');
    list.replaceChildren();
    for (const p of results) {
      const att = Array.isArray(p.attachments) ? p.attachments.length : Number(p.attachments) || 0;
      const sub = [`Version ${p.version}`, `ID ${p.id}`];
      if (p.level > 0) sub.unshift(`Unterseite, Ebene ${p.level}`);
      if (att) sub.push(att === 1 ? '1 Anhang' : `${att} Anhänge`);
      list.append(h('li', {},
        h('div', { class: 'result-main' },
          h('strong', { text: p.title || p.id }),
          h('div', { class: 'result-sub' }, sub.join(' · '), ' · ', h('code', { text: p.dir || '' }))),
        h('div', { class: 'row-actions' },
          h('button', { type: 'button', class: 'btn small', text: 'Ordner öffnen', onclick: (e) => openFolder(p.dir, e.currentTarget) }),
          h('button', { type: 'button', class: 'btn small', text: 'Bearbeiten', onclick: () => openEditor({ dir: p.dir, title: p.title }) }))));
    }
    $('#export-log').replaceChildren(...[logBlock(r.log)].filter(Boolean));
    $('#export-result').hidden = false;
    showMsg(msg, 'ok', results.length === 1 ? 'Seite exportiert.' : `${results.length} Seiten exportiert.`);
  } catch (e) {
    const extra = e.data?.reason === 'localChanges' ? h('div', { class: 'actions' },
      h('button', { type: 'button', class: 'btn small', text: '«Lokale Änderungen überschreiben» aktivieren', onclick: () => { $('#export-force').checked = true; $('#export-btn').focus(); } }),
      e.data.dir ? h('button', { type: 'button', class: 'btn small', text: 'Ordner öffnen', onclick: (ev) => openFolder(e.data.dir, ev.currentTarget) }) : null) : null;
    showError(msg, e, { extra });
  }
}

function initExport() {
  const children = $('#export-children');
  const depthRow = $('#export-depth-row');
  const sync = () => { depthRow.hidden = !children.checked; };
  children.addEventListener('change', sync);
  $('#export-att').checked = !!prefs.get('exportAtt', false);
  children.checked = !!prefs.get('exportChildren', false);
  $('#export-depth').value = prefs.get('exportDepth', '');
  sync();
  $('#export-form').addEventListener('submit', doExport);
  $('#export-page').addEventListener('input', (e) => markInvalid(e.target, false));
}

// ---------- tab 3: local pages ----------

const local = {
  pages: [],
  remote: new Map(), // dir -> {loading} | {serverChanged, serverVersion, localVersion} | {error}
  stale: true,
  loaded: false,
};

async function loadLocal(btn) {
  const msg = $('#local-msg');
  clearMsg(msg);
  try {
    const r = await withBusy(btn, () => api('GET', '/api/local'));
    local.pages = Array.isArray(r.pages) ? r.pages : [];
    local.stale = false;
    local.loaded = true;
    const dirs = new Set(local.pages.map((p) => p.dir));
    for (const d of [...local.remote.keys()]) if (!dirs.has(d)) local.remote.delete(d);
    renderLocal();
  } catch (e) {
    showError(msg, e);
  }
}

function renderLocal() {
  const body = $('#local-body');
  body.replaceChildren();
  $('#local-empty').hidden = local.pages.length > 0;
  $('#local-table-wrap').hidden = local.pages.length === 0;
  for (const p of local.pages) body.append(localRow(p));
}

function statusBadges(p) {
  const out = [];
  if (p.error) return [h('span', { class: 'badge bad', text: 'Nicht lesbar', title: p.error })];
  out.push(p.localChanged
    ? h('span', { class: 'badge warn', text: 'Lokal geändert' })
    : h('span', { class: 'badge', text: 'Lokal unverändert' }));
  const r = local.remote.get(p.dir);
  if (r?.loading) out.push(h('span', { class: 'badge pending', text: 'Server wird geprüft' }));
  else if (r?.error) out.push(h('span', { class: 'badge', text: 'Serverstand unbekannt' }));
  else if (r) {
    out.push(r.serverChanged
      ? h('span', { class: 'badge bad', text: `Server neuer (Version ${r.serverVersion})` })
      : h('span', { class: 'badge ok', text: 'Server unverändert' }));
  }
  return out;
}

function localRow(p) {
  const href = safeHref(p.webUrl);
  const title = p.title || p.dir;
  const sub = [p.spaceKey, p.id ? `ID ${p.id}` : 'noch nicht angelegt'].filter(Boolean).join(' · ');
  const tr = h('tr', {},
    h('td', {},
      h('div', { class: 't-title' }, href ? h('a', { href, target: '_blank', rel: 'noopener noreferrer', text: title }) : title),
      h('div', { class: 't-sub', text: sub }),
      h('div', { class: 't-sub' }, h('code', { text: p.dir })),
      remoteError(p)),
    h('td', { class: 't-ver', text: p.version != null ? String(p.version) : '–' }),
    h('td', {}, h('div', { class: 'badges' }, statusBadges(p))),
    h('td', { class: 't-actions' },
      h('div', { class: 'row-actions' },
        h('button', { type: 'button', class: 'btn small', text: 'Unterschiede', 'aria-label': `Unterschiede für ${title}`, onclick: () => openDiff(p) }),
        h('button', { type: 'button', class: 'btn small', text: 'Bearbeiten', 'aria-label': `${title} bearbeiten`, onclick: () => openEditor(p) }),
        h('button', { type: 'button', class: 'btn small', text: 'Ordner öffnen', 'aria-label': `Ordner von ${title} öffnen`, onclick: (e) => openFolder(p.dir, e.currentTarget) }),
        h('button', { type: 'button', class: 'btn small primary', text: 'Zurückschreiben', 'aria-label': `${title} zurückschreiben`, onclick: () => openPush(p) }))));
  tr.dataset.dir = p.dir;
  return tr;
}

/** Why the server check failed, visible in the row (not only as a tooltip). */
function remoteError(p) {
  const r = local.remote.get(p.dir);
  if (p.error) return h('div', { class: 't-sub t-error', text: p.error });
  if (!r?.error) return null;
  return h('div', { class: 't-sub t-error' }, `Serverstand nicht geprüft: ${r.error}`, otherServerActions(r.err));
}

function rerenderRow(dir) {
  const p = local.pages.find((x) => x.dir === dir);
  const old = [...$('#local-body').children].find((tr) => tr.dataset.dir === dir);
  if (p && old) old.replaceWith(localRow(p));
}

async function checkRemote(p) {
  local.remote.set(p.dir, { loading: true });
  rerenderRow(p.dir);
  try {
    const r = await api('POST', '/api/remote', { dir: p.dir });
    local.remote.set(p.dir, r);
    if (typeof r.localChanged === 'boolean') p.localChanged = r.localChanged;
  } catch (e) {
    local.remote.set(p.dir, { error: e.message, err: e });
    if (e.kind === 'session') throw e;
  }
  rerenderRow(p.dir);
}

async function checkAllRemote() {
  const btn = $('#local-check');
  const msg = $('#local-msg');
  clearMsg(msg);
  if (!local.loaded) await loadLocal();
  const queue = local.pages.filter((p) => p.id);
  await withBusy(btn, async () => {
    const worker = async () => { while (queue.length && !dead) await checkRemote(queue.shift()); };
    await Promise.allSettled([worker(), worker(), worker()]);
  });
  const newer = local.pages.filter((p) => local.remote.get(p.dir)?.serverChanged).length;
  const failed = local.pages.filter((p) => local.remote.get(p.dir)?.error).length;
  if (dead) return;
  if (failed) showMsg(msg, 'warn', `Bei ${failed} ${failed === 1 ? 'Seite' : 'Seiten'} konnte der Serverstand nicht geprüft werden (Grund jeweils unter dem Seitennamen).`);
  else showMsg(msg, newer ? 'warn' : 'ok', newer
    ? `${newer} ${newer === 1 ? 'Seite wurde' : 'Seiten wurden'} auf dem Server seit dem Export geändert.`
    : 'Alle Seiten sind auf dem Server unverändert.');
}

// ---------- VS Code / IntelliJ with GitHub Copilot ----------

async function openIde(ide, btn) {
  const msg = $('#local-msg');
  clearMsg(msg);
  try {
    const r = await withBusy(btn, () => api('POST', '/api/open-ide', { ide }));
    if (status) status.copilotSetup = true;
    toast(`${r.ide} wird geöffnet`);
  } catch (e) {
    showError(msg, e);
  }
}

async function setupIde(btn, force = false) {
  const msg = $('#local-msg');
  clearMsg(msg);
  try {
    const r = await withBusy(btn, () => api('POST', '/api/workspace/init', { force }));
    if (status) status.copilotSetup = true;
    const extra = [logBlock(r.log, 'Dateien')];
    if (r.skipped) {
      extra.push(h('div', { class: 'actions' }, h('button', { type: 'button', class: 'btn small', text: 'Trotzdem ersetzen', title: 'Deine Fassungen bleiben als .bak daneben', onclick: (ev) => setupIde(ev.currentTarget, true) })));
    }
    showMsg(msg, r.skipped ? 'warn' : 'ok', r.skipped
      ? 'Eingerichtet, ausser bei Dateien, die du selbst geändert hast oder die schon vorher da waren. Sie bleiben unverändert.'
      : 'Arbeitsordner für VS Code und IntelliJ mit GitHub Copilot eingerichtet.', { extra });
  } catch (e) {
    showError(msg, e);
  }
}

function initLocal() {
  $('#local-refresh').addEventListener('click', (e) => loadLocal(e.currentTarget));
  $('#local-check').addEventListener('click', checkAllRemote);
  $('#ide-vscode').addEventListener('click', (e) => openIde('vscode', e.currentTarget));
  $('#ide-intellij').addEventListener('click', (e) => openIde('intellij', e.currentTarget));
  $('#ide-setup').addEventListener('click', (e) => setupIde(e.currentTarget));
}

// ---------- diff dialog ----------

async function openDiff(p) {
  const dlg = $('#dlg-diff');
  $('#diff-title').textContent = `Unterschiede – ${p.title || p.dir}`;
  $('#diff-meta').textContent = 'Wird geladen …';
  const view = $('#diff-view');
  view.replaceChildren();
  clearMsg($('#diff-msg'));
  if (!dlg.open) dlg.showModal();
  try {
    const r = await api('POST', '/api/diff', { dir: p.dir });
    const added = r.added ?? 0;
    const removed = r.removed ?? 0;
    $('#diff-meta').textContent = `Server Version ${r.serverVersion ?? '–'} · lokal Version ${r.localVersion ?? '–'} · ${added} hinzugefügt, ${removed} entfernt`;
    if (r.diff && r.diff.trim()) renderDiff(view, r.diff);
    else showMsg($('#diff-msg'), 'ok', 'Keine Unterschiede im Inhalt zwischen Server und lokaler Fassung.');
  } catch (e) {
    $('#diff-meta').textContent = '';
    showError($('#diff-msg'), e);
  }
}

// ---------- editor dialog ----------

const edit = { dir: null, saved: '', editor: null, hash: null, crlf: false };

function editDirty() { return edit.dir != null && edit.editor.value !== edit.saved; }

function updateDirty() { $('#edit-dirty').hidden = !editDirty(); }

async function openEditor(p, line) {
  const dlg = $('#dlg-edit');
  if (dlg.open && edit.dir !== p.dir && editDirty()) {
    const ok = await confirmDialog({ title: 'Änderungen verwerfen?', text: 'Im Editor gibt es ungespeicherte Änderungen. Trotzdem eine andere Seite öffnen?', ok: 'Verwerfen', danger: true });
    if (!ok) return;
  }
  const msg = $('#edit-msg');
  clearMsg(msg);
  $('#edit-title').textContent = p.title || p.dir;
  $('#edit-meta').textContent = `${p.dir}/page.xhtml`;
  edit.dir = null;
  edit.editor.value = '';
  updateDirty();
  for (const id of ['edit-validate', 'edit-save']) $(`#${id}`).disabled = true;
  if (!dlg.open) dlg.showModal();
  try {
    const r = await api('GET', `/api/page?dir=${encodeURIComponent(p.dir)}`);
    edit.dir = p.dir;
    edit.hash = r.hash || null;
    edit.crlf = /\r\n/.test(r.storage || '');
    // a textarea turns CRLF into LF: compare against what it holds, restore CRLF on save
    edit.editor.value = r.storage || '';
    edit.saved = edit.editor.value;
    if (r.meta?.title) $('#edit-title').textContent = r.meta.title;
    if (r.meta?.version != null) $('#edit-meta').textContent = `${p.dir}/page.xhtml · Version ${r.meta.version}`;
    for (const id of ['edit-validate', 'edit-save']) $(`#${id}`).disabled = false;
    updateDirty();
    if (line) edit.editor.gotoLine(line);
    else $('#edit-source').focus();
  } catch (e) {
    showError(msg, e);
  }
}

function jumpEdit(p) { edit.editor.gotoLine(p.line || 1); }

async function validateEdit() {
  const msg = $('#edit-msg');
  try {
    const r = await withBusy($('#edit-validate'), () => api('POST', '/api/validate', { storage: edit.editor.value }));
    if (r.problems?.length) showMsg(msg, hasErrors(r.problems) ? 'error' : 'warn', problemsHeadline(r.problems), { problems: r.problems, onJump: jumpEdit });
    else showMsg(msg, 'ok', 'Keine Probleme gefunden.');
  } catch (e) {
    showError(msg, e, { onJump: jumpEdit });
  }
}

async function saveEdit(force = false) {
  const msg = $('#edit-msg');
  const dir = edit.dir;
  const text = edit.editor.value;
  const storage = edit.crlf ? text.replace(/\n/g, '\r\n') : text;
  const body = { dir, storage, hash: edit.hash };
  if (force) body.force = true;
  try {
    const r = await withBusy($('#edit-save'), () => api('PUT', '/api/page', body));
    edit.saved = text;
    edit.hash = r.hash || null;
    updateDirty();
    const probs = r.problems || [];
    if (probs.length) {
      showMsg(msg, hasErrors(probs) ? 'error' : 'warn', `Gespeichert, aber ${problemsHeadline(probs).replace(/ gefunden\.$/, '')} – vor dem Zurückschreiben beheben.`, { problems: probs, onJump: jumpEdit });
    } else {
      showMsg(msg, 'ok', 'Gespeichert. Keine Probleme gefunden.');
    }
    const p = local.pages.find((x) => x.dir === dir);
    if (p) { p.localChanged = true; rerenderRow(dir); }
    local.stale = true;
  } catch (e) {
    if (e.data?.reason === 'changedOnDisk') {
      showMsg(msg, 'warn', `${e.message} Deine Änderungen im Editor sind noch nicht gespeichert.`, {
        extra: h('div', { class: 'actions' },
          h('button', { type: 'button', class: 'btn small', text: 'Neu laden (meine Änderungen verwerfen)', onclick: reloadEdit }),
          h('button', { type: 'button', class: 'btn small danger', text: 'Trotzdem überschreiben …', onclick: overwriteEdit })),
      });
      return;
    }
    showError(msg, e, { onJump: jumpEdit });
  }
}

async function reloadEdit() {
  const ok = await confirmDialog({ title: 'Neu laden?', text: 'Die Fassung auf der Festplatte wird geladen. Deine ungespeicherten Änderungen im Editor gehen verloren.', ok: 'Neu laden', danger: true });
  if (ok) openEditor({ dir: edit.dir, title: $('#edit-title').textContent });
}

async function overwriteEdit() {
  const ok = await confirmDialog({ title: 'Fremde Änderungen überschreiben?', text: 'page.xhtml wurde ausserhalb dieses Editors geändert. Mit «Überschreiben» gehen diese Änderungen verloren.', ok: 'Überschreiben', danger: true });
  if (ok) saveEdit(true);
}

async function closeEditor() {
  if (editDirty()) {
    const ok = await confirmDialog({ title: 'Änderungen verwerfen?', text: 'Die Änderungen im Editor sind noch nicht gespeichert.', ok: 'Verwerfen', danger: true });
    if (!ok) return;
  }
  edit.dir = null;
  $('#dlg-edit').close();
}

function initEditor() {
  edit.editor = makeEditor($('#edit-source'));
  $('#edit-source').addEventListener('input', updateDirty);
  $('#edit-validate').addEventListener('click', validateEdit);
  $('#edit-save').addEventListener('click', () => saveEdit());
  $('#edit-close').addEventListener('click', closeEditor);
  $('#edit-x').addEventListener('click', closeEditor);
  $('#dlg-edit').addEventListener('cancel', (e) => { e.preventDefault(); closeEditor(); });
  $('#dlg-edit').addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      if (!$('#edit-save').disabled) saveEdit();
    }
  });
}

// ---------- push dialog ----------

const push = { page: null };

function openPush(p) {
  push.page = p;
  $('#push-title').textContent = `Zurückschreiben – ${p.title || p.dir}`;
  $('#push-meta').textContent = p.dir;
  $('#push-message').value = '';
  $('#push-new-title').value = '';
  $('#push-new-title').placeholder = p.title || '';
  $('#push-dry').checked = false;
  $('#push-btn').textContent = 'Zurückschreiben';
  $('#push-att').checked = !!prefs.get('pushAtt', false);
  $('#push-conflict').hidden = true;
  clearMsg($('#push-msg'));
  $('#push-btn').disabled = false;
  $('#dlg-push').showModal();
  $('#push-message').focus();
}

async function doPush(force) {
  const p = push.page;
  const msg = $('#push-msg');
  clearMsg(msg);
  const body = {
    dir: p.dir,
    dryRun: $('#push-dry').checked,
    attachments: $('#push-att').checked,
  };
  const m = $('#push-message').value.trim();
  const t = $('#push-new-title').value.trim();
  if (m) body.message = m;
  if (t) body.title = t;
  if (force) body.force = true;
  prefs.set('pushAtt', body.attachments);

  const btn = force ? $('#push-force') : $('#push-btn');
  try {
    const r = await withBusy(btn, () => api('POST', '/api/push', body));
    $('#push-conflict').hidden = true;
    const href = safeHref(r.webUrl);
    const link = href ? h('div', { class: 'actions' }, h('a', { class: 'btn', href, target: '_blank', rel: 'noopener noreferrer', text: 'In Confluence öffnen' })) : null;
    if (r.status === 'dry-run') {
      const pre = h('pre', { class: 'diff', tabindex: '0', 'aria-label': 'Unified Diff' });
      if (r.diff && r.diff.trim()) renderDiff(pre, r.diff);
      showMsg(msg, 'info', r.diff && r.diff.trim()
        ? 'Probelauf – es wurde nichts geschrieben. Diese Änderungen würden hochgeladen:'
        : 'Probelauf – es wurde nichts geschrieben. Der Inhalt ist unverändert.', { extra: [pre.childNodes.length ? pre : null, logBlock(r.log)] });
      return;
    }
    if (r.status === 'unchanged') {
      showMsg(msg, 'info', 'Keine Änderungen – es wurde keine neue Version angelegt.', { extra: [link, logBlock(r.log)] });
    } else {
      showMsg(msg, 'ok', `Zurückgeschrieben – neue Version ${r.version}.`, { problems: r.problems, extra: [link, logBlock(r.log)] });
      toast(`«${p.title || p.dir}» ist jetzt Version ${r.version}`);
    }
    p.localChanged = false;
    if (r.version != null) p.version = r.version;
    if (t) p.title = t;
    local.remote.delete(p.dir);
    rerenderRow(p.dir);
    local.stale = true;
  } catch (e) {
    if (e.kind === 'conflict') {
      $('#push-conflict-detail').textContent = e.message;
      $('#push-conflict').hidden = false;
      $('#push-show-diff').focus();
      return;
    }
    const extra = e.data?.reason === 'spaceMismatch' ? h('div', { class: 'actions' },
      h('button', { type: 'button', class: 'btn small', text: 'Unterschiede anzeigen', onclick: () => openDiff(p) }),
      h('button', { type: 'button', class: 'btn small danger', text: 'Trotzdem überschreiben …', onclick: forcePush })) : null;
    showError(msg, e, {
      extra,
      onJump: (prob) => { $('#dlg-push').close(); openEditor(p, prob.line || 1); },
    });
  }
}

async function forcePush() {
  const ok = await confirmDialog({
    title: 'Fremde Änderungen überschreiben?',
    text: 'Die neuere Fassung auf dem Server wird durch deine lokale Fassung ersetzt. Die fremden Änderungen sind danach nur noch in der Seitenhistorie von Confluence zu finden.',
    ok: 'Überschreiben',
    danger: true,
  });
  if (ok) await doPush(true);
}

async function refreshFromServer() {
  const p = push.page;
  const ok = await confirmDialog({
    title: 'Server-Fassung holen?',
    text: 'page.xhtml wird durch die aktuelle Fassung vom Server ersetzt. Deine Fassung wird vorher als page.xhtml.bak im selben Ordner gesichert.',
    ok: 'Server-Fassung holen',
  });
  if (!ok) return;
  const msg = $('#push-msg');
  clearMsg(msg);
  try {
    const r = await withBusy($('#push-refresh'), () => api('POST', '/api/refresh', { dir: p.dir }));
    $('#push-conflict').hidden = true;
    p.version = r.version;
    p.localChanged = false;
    if (r.title) p.title = r.title;
    local.remote.delete(p.dir);
    rerenderRow(p.dir);
    local.stale = true;
    const text = r.backup
      ? `Server-Fassung (Version ${r.version}) geholt. Deine bisherige Fassung liegt als ${r.backup} im Ordner – übernimm deine Änderungen jetzt und schreibe danach zurück.`
      : `Server-Fassung (Version ${r.version}) geholt.`;
    showMsg(msg, 'ok', text, {
      extra: h('div', { class: 'actions' },
        h('button', { type: 'button', class: 'btn small', text: 'Bearbeiten', onclick: () => { $('#dlg-push').close(); openEditor(p); } }),
        h('button', { type: 'button', class: 'btn small', text: 'Ordner öffnen', onclick: (ev) => openFolder(p.dir, ev.currentTarget) })),
    });
  } catch (e) {
    showError(msg, e);
  }
}

function initPush() {
  $('#push-refresh').addEventListener('click', refreshFromServer);
  $('#push-form').addEventListener('submit', (e) => { e.preventDefault(); doPush(false); });
  $('#push-cancel').addEventListener('click', () => $('#dlg-push').close());
  $('#push-show-diff').addEventListener('click', () => openDiff(push.page));
  $('#push-force').addEventListener('click', forcePush);
  $('#push-dry').addEventListener('change', (e) => {
    $('#push-btn').textContent = e.target.checked ? 'Probelauf starten' : 'Zurückschreiben';
  });
}

// ---------- tab 4: connection ----------

let connDirty = false;
let connShown = null; // stored settings of the profile shown in the form: {profile, url, auth, username}

function authMode() { return $('#auth-basic').checked ? 'basic' : 'token'; }

function syncAuthRows() {
  const basic = authMode() === 'basic';
  $('#token-row').hidden = basic;
  $('#basic-row').hidden = !basic;
}

function storedConnection(profile) {
  const list = Array.isArray(status?.connections) ? status.connections : [];
  return list.find((c) => (c.profile || '') === (profile || '')) || null;
}

function sameServer(a, b) {
  try {
    const x = new URL(a);
    const y = new URL(b);
    return x.protocol === y.protocol && x.host.toLowerCase() === y.host.toLowerCase();
  } catch { return false; }
}

/** Hints for the secret fields: a stored token/password is only kept (and sent) for its own server. */
function connHints() {
  const c = connShown;
  const url = $('#conn-url').value.trim();
  const moved = c?.url && url && !sameServer(c.url, url);
  let tokenHint = 'Wird nur auf diesem Computer gespeichert und nie angezeigt.';
  let passHint = '';
  if (c?.auth === 'token') {
    tokenHint = moved
      ? `Neue Adresse: bitte den Token neu eingeben. Der gespeicherte wird nur an ${hostOf(c.url)} gesendet.`
      : 'Ein Token ist gespeichert. Leer lassen, um ihn unverändert zu behalten.';
  }
  if (c?.auth === 'basic') {
    passHint = moved
      ? `Neue Adresse: bitte das Passwort neu eingeben. Das gespeicherte wird nur an ${hostOf(c.url)} gesendet.`
      : 'Ein Passwort ist gespeichert. Leer lassen, um es zu behalten.';
  }
  $('#conn-token-hint').textContent = tokenHint;
  $('#conn-pass-hint').textContent = passHint;
}

/** Puts the stored settings of one profile into the form (never a secret). */
function showConnection(c, url) {
  connShown = c;
  $('#conn-url').value = url ?? c?.url ?? '';
  (c?.auth === 'basic' ? $('#auth-basic') : $('#auth-token')).checked = true;
  $('#conn-token').value = '';
  $('#conn-user').value = c?.username || '';
  $('#conn-pass').value = '';
  syncAuthRows();
  connHints();
}

function fillConnForm() {
  if (connDirty || !status) return;
  $('#conn-profile').value = status.profile || '';
  const list = $('#conn-profiles');
  list.replaceChildren(...(status.profiles || []).map((n) => h('option', { value: n })));
  const stored = storedConnection(status.profile) || { profile: status.profile, url: null, auth: null, username: null };
  showConnection({ ...stored, auth: status.auth ?? stored.auth, username: status.username ?? stored.username }, status.baseUrl || '');
  const notice = $('#conn-notice');
  clearMsg(notice);
  if (status.envOverride) {
    showMsg(notice, 'warn', 'Umgebungsvariablen (CONFLUENCE_URL, CONFLUENCE_TOKEN …) sind gesetzt und haben Vorrang vor der Konfigurationsdatei. Änderungen hier wirken erst, wenn sie entfernt sind oder ein Profil verwendet wird.');
  } else if (!status.configured && !status.baseUrl) {
    showMsg(notice, 'info', 'Noch keine Verbindung eingerichtet. Confluence-URL und Personal Access Token eingeben und speichern.');
  } else if (status.configError) {
    showMsg(notice, 'warn', status.configError);
  }
}

/** Another profile name in the form: show that profile's own settings, never carry over the URL silently. */
function profileChanged() {
  const name = $('#conn-profile').value.trim();
  if ((connShown?.profile || '') === name) return;
  const stored = storedConnection(name);
  if (stored) {
    showConnection(stored);
    clearMsg($('#conn-msg'));
  } else {
    // a new profile has no stored secret yet
    connShown = { profile: name, url: null, auth: null, username: null };
    connHints();
  }
}

/** The form values as request body, or null (with a message) if they are incomplete. */
function connBody() {
  const msg = $('#conn-msg');
  clearMsg(msg);
  const urlInput = $('#conn-url');
  const url = urlInput.value.trim();
  const ok = !!safeHref(url);
  markInvalid(urlInput, !ok);
  if (!ok) {
    showMsg(msg, 'error', 'Bitte eine gültige Adresse angeben, z.B. https://wiki.firma.ch');
    urlInput.focus();
    return null;
  }
  const body = { url };
  const profile = $('#conn-profile').value.trim();
  if (profile) body.profile = profile;
  if (authMode() === 'basic') {
    const userInput = $('#conn-user');
    const user = userInput.value.trim();
    markInvalid(userInput, !user);
    if (!user) {
      showMsg(msg, 'error', 'Bitte den Benutzernamen angeben.');
      userInput.focus();
      return null;
    }
    body.username = user;
    body.password = $('#conn-pass').value;
  } else {
    body.token = $('#conn-token').value.trim();
  }
  return body;
}

async function saveConn() {
  const body = connBody();
  if (!body) return false;
  try {
    status = await withBusy($('#conn-save'), () => api('POST', '/api/config', body));
    connDirty = false;
    renderStatus();
    return true;
  } catch (e) {
    showError($('#conn-msg'), e);
    return false;
  }
}

/** Tests the form values without saving them; unchanged form: tests the saved connection. */
async function testConn() {
  const msg = $('#conn-msg');
  if (connDirty) {
    const body = connBody();
    if (!body) return;
    try {
      const r = await withBusy($('#conn-test'), () => api('POST', '/api/whoami', body));
      const user = userName(r.user);
      showMsg(msg, 'ok', `${user ? `Verbindung funktioniert. Angemeldet als ${user}.` : 'Verbindung funktioniert.'} Noch nicht gespeichert – zum Übernehmen «Speichern» klicken.`);
    } catch (e) {
      showError(msg, e);
      if (e.kind !== 'session') msg.firstChild?.append(h('p', { class: 'small', text: 'Nichts gespeichert; die bisherige Verbindung bleibt unverändert.' }));
    }
    return;
  }
  clearMsg(msg);
  try {
    const user = await withBusy($('#conn-test'), checkUser);
    if (user !== null) showMsg(msg, 'ok', user ? `Verbindung funktioniert. Angemeldet als ${user}.` : 'Verbindung funktioniert.');
    else showMsg(msg, 'warn', 'Noch keine Verbindung eingerichtet.');
  } catch (e) {
    showError(msg, e);
  }
}

function initConn() {
  const form = $('#conn-form');
  form.addEventListener('input', () => { connDirty = true; });
  form.addEventListener('change', syncAuthRows);
  $('#conn-profile').addEventListener('input', profileChanged);
  $('#conn-profile').addEventListener('change', profileChanged);
  $('#conn-url').addEventListener('input', connHints);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (await saveConn()) {
      showMsg($('#conn-msg'), 'ok', 'Gespeichert.');
      try { await checkUser(); } catch { /* shown in header */ }
    }
  });
  $('#conn-test').addEventListener('click', testConn);
}

// ---------- workspace ----------

function initWorkspace() {
  const dlg = $('#dlg-ws');
  $('#ws-btn').addEventListener('click', () => {
    $('#ws-input').value = status?.workspace || '';
    clearMsg($('#ws-msg'));
    dlg.showModal();
    $('#ws-input').select();
  });
  $('#ws-cancel').addEventListener('click', () => dlg.close());
  $('#ws-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const path = $('#ws-input').value.trim();
    if (!path) { showMsg($('#ws-msg'), 'error', 'Bitte einen Ordner angeben.'); return; }
    try {
      status = await withBusy($('#ws-save'), () => api('POST', '/api/workspace', { path }));
      renderStatus();
      dlg.close();
      toast('Arbeitsordner geändert');
      local.remote.clear();
      local.stale = true;
      if (!$('#panel-local').hidden) loadLocal();
    } catch (err) {
      showError($('#ws-msg'), err);
    }
  });
}

// ---------- shutdown ----------

async function shutdown() {
  const ok = await confirmDialog({
    title: 'Confluence-Helper beenden?',
    text: 'Der lokale Server wird gestoppt. Ungespeicherte Änderungen im Editor gehen verloren.',
    ok: 'Beenden',
  });
  if (!ok) return;
  stopHeartbeat();
  try { await api('POST', '/api/shutdown'); } catch { /* server may already be gone */ }
  dead = true;
  try { sessionStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
  block('Beendet', 'Der Confluence-Helper wurde beendet. Du kannst dieses Fenster schliessen. Zum erneuten Starten den Starter verwenden.');
  $('#conn').dataset.state = 'bad';
  $('#conn-text').textContent = 'Beendet';
}

// ---------- boot ----------

let mainTabs = null;

function hasUnsavedWork() {
  return editDirty() || (!newPage.created && !$('#result-card').hidden && newPage.editor.value.trim() !== '');
}

async function boot() {
  initToken();
  initNewPage();
  initExport();
  initLocal();
  initEditor();
  initPush();
  initConn();
  initWorkspace();
  $('#quit-btn').addEventListener('click', shutdown);
  window.addEventListener('beforeunload', (e) => {
    if (!dead && hasUnsavedWork()) { e.preventDefault(); e.returnValue = ''; }
  });

  mainTabs = setupTabs($('#main-tabs'), (id) => {
    prefs.set('tab', id);
    if (id === 'tab-local' && local.stale) loadLocal($('#local-refresh'));
  });
  $('#conn').addEventListener('click', () => mainTabs.select('tab-conn', true));

  if (!token) {
    sessionInvalid();
    return;
  }

  try {
    await loadStatus();
  } catch (e) {
    if (e.kind !== 'session') showError($('#new-msg'), e);
    return;
  }
  mainTabs.select(status.configured ? prefs.get('tab', 'tab-new') : 'tab-conn');
  startHeartbeat();
  if (status.configured) checkUser().catch(() => { /* shown in header */ });
}

boot();
