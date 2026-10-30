// End-to-end check of the web UI in headless Chromium against UiDevServer (fake Confluence).
// Not part of `mvn verify`. Usage (see README.md, «Web-UI testen»):
//   cd <dir with `npm i playwright`> && node /path/to/src/test/ui-e2e/ui-e2e.mjs
// Env: PORT (default 8771), SHOTS (screenshot dir, default $TMPDIR/cfh-ui-e2e), CHANNEL (e.g. "chrome"),
//      BUILD_DIR (Maven build dir, default <repo>/target).
import { spawn, execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(path.join(process.cwd(), '/'))('playwright');
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const port = process.env.PORT || '8771';
const buildDir = path.resolve(process.env.BUILD_DIR || path.join(repo, 'target'));
const shots = process.env.SHOTS || path.join(os.tmpdir(), 'cfh-ui-e2e');
fs.mkdirSync(shots, { recursive: true });

function assert(cond, msg) { if (!cond) throw new Error(`FAIL: ${msg}`); console.log(`ok  ${msg}`); }

// ---- minimal XLSX (stored zip, inline strings): sheets Personal, Budget, hidden Intern
function zip(files) {
  const parts = [], central = [];
  let off = 0;
  for (const [name, text] of Object.entries(files)) {
    const data = Buffer.from(text), n = Buffer.from(name), crc = zlib.crc32(data);
    const loc = Buffer.alloc(30);
    loc.writeUInt32LE(0x04034b50, 0); loc.writeUInt16LE(20, 4); loc.writeUInt32LE(crc, 14);
    loc.writeUInt32LE(data.length, 18); loc.writeUInt32LE(data.length, 22); loc.writeUInt16LE(n.length, 26);
    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 4); cen.writeUInt16LE(20, 6); cen.writeUInt32LE(crc, 16);
    cen.writeUInt32LE(data.length, 20); cen.writeUInt32LE(data.length, 24); cen.writeUInt16LE(n.length, 28);
    cen.writeUInt32LE(off, 42);
    parts.push(loc, n, data); central.push(cen, n);
    off += 30 + n.length + data.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(central.length / 2, 8); end.writeUInt16LE(central.length / 2, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...parts, cd, end]);
}
function sheet(rows) {
  const cell = (v, r, c) => `<c r="${String.fromCharCode(65 + c)}${r}" t="inlineStr"><is><t>${v}</t></is></c>`;
  return `<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${
    rows.map((row, i) => `<row r="${i + 1}">${row.map((v, c) => cell(v, i + 1, c)).join('')}</row>`).join('')}</sheetData></worksheet>`;
}
function xlsx() {
  const names = ['Personal', 'Budget', 'Intern'];
  const ns = 'http://schemas.openxmlformats.org/';
  return zip({
    '[Content_Types].xml': `<?xml version="1.0" encoding="UTF-8"?><Types xmlns="${ns}package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${
      names.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`,
    '_rels/.rels': `<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="${ns}package/2006/relationships"><Relationship Id="rId1" Type="${ns}officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
    'xl/workbook.xml': `<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="${ns}spreadsheetml/2006/main" xmlns:r="${ns}officeDocument/2006/relationships"><sheets>${
      names.map((s, i) => `<sheet name="${s}" sheetId="${i + 1}" r:id="rId${i + 1}"${s === 'Intern' ? ' state="hidden"' : ''}/>`).join('')}</sheets></workbook>`,
    'xl/_rels/workbook.xml.rels': `<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="${ns}package/2006/relationships">${
      names.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${ns}officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}</Relationships>`,
    'xl/worksheets/sheet1.xml': sheet([['Name', 'Abteilung'], ['Jürg Müller', 'Buchhaltung'], ['Béatrice Lüthi', 'Informatik']]),
    'xl/worksheets/sheet2.xml': sheet([['Posten', 'Betrag'], ['Miete', '24000'], ['Reisen', '3500']]),
    'xl/worksheets/sheet3.xml': sheet([['Geheim', 'Wert'], ['intern', '42']]),
  });
}

// ---- minimal one-page PDF with a heading line and a paragraph
function pdf() {
  const content = 'BT /F1 18 Tf 72 720 Td (Jahresbericht 2026) Tj ET BT /F1 11 Tf 72 690 Td (Das Team hat alle Ziele erreicht.) Tj ET';
  const objs = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  let body = '%PDF-1.4\n';
  const offs = objs.map((o, i) => { const off = body.length; body += `${i + 1} 0 obj\n${o}\nendobj\n`; return off; });
  const xref = body.length;
  body += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n${offs.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  return Buffer.from(`${body}trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`, 'latin1');
}

// ---- dev server
console.log('building test classes …');
const cpFile = path.join(os.tmpdir(), `cfh-ui-e2e-cp-${process.pid}.txt`);
execFileSync('mvn', ['-q', '-B', `-Dbuild.dir=${buildDir}`, 'test-compile', 'dependency:build-classpath', `-Dmdep.outputFile=${cpFile}`], { cwd: repo, stdio: 'inherit' });
const cp = [path.join(buildDir, 'test-classes'), path.join(buildDir, 'classes'), fs.readFileSync(cpFile, 'utf8').trim()].join(path.delimiter);
fs.rmSync(cpFile);
const srv = spawn('java', ['-cp', cp, 'ch.confluencehelper.ui.UiDevServer', port, '--no-open'], { cwd: repo });
let out = '';
const waiters = [];
srv.stdout.setEncoding('utf8').on('data', (d) => { out += d; for (const w of [...waiters]) w(); });
srv.stderr.pipe(process.stderr);
const exited = new Promise((r) => srv.on('exit', r));
function waitOut(re, ms = 20000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${re} in server output:\n${out}`)), ms);
    const check = () => { const m = out.match(re); if (m) { clearTimeout(t); waiters.splice(waiters.indexOf(check), 1); resolve(m); } };
    waiters.push(check); check();
  });
}
async function cmd(line, re) { srv.stdin.write(`${line}\n`); return waitOut(re); }

const uiUrl = (await waitOut(/UI_URL=(\S+)/))[1];
const workspace = (await waitOut(/Arbeitsordner: (.+)/))[1].trim();
const homeId = (await waitOut(/Seite (\d+)\)/))[1];
const fakeToken = (await waitOut(/FAKE_TOKEN=(\S+)/))[1];

const browser = await chromium.launch(process.env.CHANNEL ? { channel: process.env.CHANNEL } : {});
const problems = [];
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  const page = await ctx.newPage();
  // 4xx/502 answers are part of the tested flows; Chrome logs each as «Failed to load resource»
  page.on('console', (m) => {
    if ((m.type() === 'error' || m.type() === 'warning') && !m.text().startsWith('Failed to load resource')) problems.push(`console ${m.type()}: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  page.on('response', (r) => { if (r.status() === 500) problems.push(`HTTP 500 ${r.url()}`); });
  // fullPage captures leave sandboxed iframes blank in headless Chromium: grow the viewport instead
  const shot = async (name) => {
    const vp = page.viewportSize();
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width: vp.width, height: Math.max(vp.height, Math.min(height, 6000)) });
    await page.waitForTimeout(100);
    await page.screenshot({ path: path.join(shots, `${name}.png`) });
    await page.setViewportSize(vp);
  };
  const tab = async (id) => { await page.click(`#${id}`); await page.waitForSelector(`#${id}[aria-selected="true"]`); };
  const text = (sel) => page.locator(sel).innerText();

  // --- start: token handling and status
  await page.goto(uiUrl);
  await page.waitForSelector('#conn[data-state="ok"]');
  assert(!page.url().includes('#t='), 'token removed from address bar');
  assert((await text('#conn-text')).includes('Anna Muster'), 'header shows connection and user');
  assert(await page.getByRole('button', { name: /^Verbindung: .*Anna Muster/ }).count() === 1, 'header button is named by its status');
  assert(await page.evaluate(() => Object.keys(sessionStorage).length === 1), 'token kept in sessionStorage');
  await page.reload();
  await page.waitForSelector('#conn[data-state="ok"]');
  assert(true, 'reload keeps the session');

  // --- Verbindung: «testen» checks the form values without saving; stored secrets stay with their server
  await tab('tab-conn');
  const goodUrl = await page.inputValue('#conn-url');
  assert(goodUrl.includes('/confluence'), 'Verbindung shows saved URL');
  assert((await text('#conn-file')).endsWith('config.properties'), 'Verbindung shows config file');
  const cfgPath = path.join(path.dirname(workspace), 'config.properties');
  const cfg0 = fs.readFileSync(cfgPath, 'utf8');
  const msgError = async () => { await page.waitForSelector('#conn-msg .alert-error'); return text('#conn-msg'); };
  await page.fill('#conn-url', 'http://127.0.0.1:9/confluence');
  assert((await text('#conn-token-hint')).includes('neu eingeben'), 'changed URL: hint asks for the token again');
  await page.click('#conn-test');
  assert((await msgError()).includes('neu eingeben'), 'stored token is not sent to another address');
  await page.fill('#conn-token', 'irgendwas');
  await page.click('#conn-test');
  assert((await msgError()).includes('erreichbar'), 'unreachable URL reported');
  await page.fill('#conn-url', goodUrl);
  await page.fill('#conn-token', 'falsch');
  await page.click('#conn-test');
  assert((await msgError()).includes('Anmeldung fehlgeschlagen'), 'wrong token reported');
  assert(fs.readFileSync(cfgPath, 'utf8') === cfg0, 'testing saved nothing (wrong token not stored)');
  assert(await page.locator('#conn[data-state="ok"]').count() === 1, 'header keeps the saved, working connection');
  await shot('conn-test-unsaved');
  await page.fill('#conn-token', '');
  await page.click('#conn-test');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.includes('Noch nicht gespeichert'));
  await page.click('#conn-save');
  await page.waitForSelector('#conn-msg .alert-ok');
  await page.click('#conn-test');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.includes('Angemeldet als Anna Muster'));
  assert(fs.readFileSync(cfgPath, 'utf8').includes(`token=${fakeToken}`), 'save + test with stored token works');
  await page.check('#auth-basic');
  await page.fill('#conn-user', 'anna.muster');
  await page.fill('#conn-pass', 'falsch');
  await page.click('#conn-test');
  assert((await msgError()).includes('Anmeldung fehlgeschlagen'), 'wrong password reported');
  await page.fill('#conn-pass', 'geheim');
  await page.click('#conn-test');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.includes('Angemeldet als'));
  assert(fs.readFileSync(cfgPath, 'utf8').includes(`token=${fakeToken}`), 'successful test of basic auth saved nothing');
  await page.click('#conn-save');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.startsWith('Gespeichert'));
  const cfg = fs.readFileSync(cfgPath, 'utf8');
  assert(/^username=anna.muster$/m.test(cfg) && !/^token=/m.test(cfg), 'basic auth saved, token removed from file');
  await page.check('#auth-token');
  await page.fill('#conn-token', fakeToken);
  await page.click('#conn-save');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.startsWith('Gespeichert'));
  assert(!/^(username|password)=/m.test(fs.readFileSync(cfgPath, 'utf8')), 'back to token, basic credentials removed');

  // profiles: switching the profile field shows that profile's own URL, nothing crosses over
  await page.fill('#conn-profile', 'zweit');
  await page.fill('#conn-url', 'https://zweit.example.ch');
  await page.fill('#conn-token', 'abc');
  await page.click('#conn-save');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.startsWith('Gespeichert'));
  await page.fill('#conn-profile', '');
  assert(await page.inputValue('#conn-url') === goodUrl, 'back to the default profile: its own URL is loaded');
  assert((await text('#conn-token-hint')).includes('Ein Token ist gespeichert'), 'default profile shows its stored token');
  await page.fill('#conn-profile', 'zweit');
  assert(await page.inputValue('#conn-url') === 'https://zweit.example.ch', 'picking zweit loads its URL');
  await page.fill('#conn-profile', '');
  await page.click('#conn-save');
  await page.waitForFunction(() => document.querySelector('#conn-msg')?.innerText.startsWith('Gespeichert'));
  const cfg2 = fs.readFileSync(cfgPath, 'utf8');
  assert(cfg2.includes(`\nurl=${goodUrl}\n`) || cfg2.startsWith(`url=${goodUrl}\n`), 'default profile keeps its URL');
  assert(cfg2.includes('zweit.url=https://zweit.example.ch') && cfg2.includes(`token=${fakeToken}`), 'zweit unchanged, default token kept');
  await page.waitForSelector('#conn[data-state="ok"]');

  // --- Neue Seite: CSV
  await tab('tab-new');
  await page.setInputFiles('#file-input', { name: 'Telefonliste.csv', mimeType: 'text/csv',
    buffer: Buffer.from('Name;Telefon;Ort\nJürg Müller;044 111 22 33;Zürich\nÖmer Özdemir;031 444 55 66;Bern\n') });
  await page.waitForSelector('#convert-opts:not([hidden])');
  assert((await text('#file-kind')) === 'CSV', 'CSV upload recognised');
  assert(await page.isHidden('#sheets-box'), 'no sheet choice for CSV');
  await page.click('#convert-btn');
  await page.waitForSelector('#result-card:not([hidden])');
  const frame = page.frameLocator('#preview');
  await frame.locator('table').waitFor();
  assert((await frame.locator('body').innerText()).includes('Ömer Özdemir'), 'CSV preview renders the table');
  await page.click('#view-source');
  const src = await page.inputValue('#new-source');
  await page.fill('#new-source', src.replace('</table>', '<p>kaputt</table>'));
  await page.click('#new-validate');
  await page.waitForSelector('#new-problems .alert-error .loc');
  await page.click('#new-problems .loc');
  assert(await page.evaluate(() => document.activeElement.id === 'new-source'), 'Prüfen reports error, click jumps to source');
  await page.fill('#new-source', src.replace('</table>', '</table><p>Stand: Oktober</p>'));
  await page.click('#new-validate');
  await page.waitForSelector('#new-problems .alert-ok');
  await page.click('#view-preview');
  await page.frameLocator('#preview').getByText('Stand: Oktober').waitFor();
  assert(true, 'edited source shows in preview');
  await shot('new-csv-1280-light');
  await page.fill('#create-space', 'DEMO');
  await page.fill('#create-title', 'Telefonliste');
  await page.fill('#create-parent', homeId);
  await page.click('#create-btn');
  await page.waitForSelector('#create-msg .alert-ok a.btn');
  const created = await text('#create-msg');
  assert(/angelegt \(ID \d+, Version 1\)/.test(created), 'CSV page created, link shown');
  const createdDir = created.match(/unter (\S+?)\.?\n/)?.[1] || created.match(/unter (\S+)\./)[1];
  assert(fs.existsSync(path.join(workspace, createdDir, 'page.xhtml')), `local folder ${createdDir} exists`);

  // --- Neue Seite: XLSX with sheet choice and original as attachment
  await page.setInputFiles('#file-input', { name: 'Budget_2026.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: xlsx() });
  await page.waitForSelector('#sheets-box:not([hidden])');
  assert(await page.locator('#sheets input').count() === 3, 'three sheets listed');
  assert(await page.locator('#sheets input[value="Intern"]').isDisabled(), 'hidden sheet disabled');
  await page.uncheck('#sheets input[value="Personal"]');
  await page.click('#convert-btn');
  await page.waitForFunction(() => !document.querySelector('#convert-btn').disabled);
  await page.frameLocator('#preview').locator('table').first().waitFor();
  const xbody = await page.frameLocator('#preview').locator('body').innerText();
  assert(xbody.includes('Miete') && !xbody.includes('Jürg') && !xbody.includes('Geheim'), 'only chosen sheet converted');
  assert((await page.inputValue('#create-title')) === 'Budget 2026', 'title proposed from file name');
  await page.check('#opt-hidden');
  assert(await page.locator('#sheets input[value="Intern"]').isEnabled(), '«Ausgeblendete einbeziehen» enables hidden sheet');
  await page.uncheck('#opt-hidden');
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('#download-btn')]);
  assert(fs.readFileSync(await download.path(), 'utf8').includes('Miete'), '«Als Datei speichern» downloads the XHTML');
  await page.check('#create-attach');
  await page.fill('#create-message', 'Original-Excel');
  await page.click('#create-btn');
  await page.waitForSelector('#create-msg .alert-ok');
  assert((await text('#create-msg')).includes('Budget 2026'), 'XLSX page created with attachment');
  await shot('new-xlsx-1280-light');

  // --- Neue Seite: TSV is accepted like CSV
  await page.setInputFiles('#file-input', { name: 'Daten.tsv', mimeType: 'text/tab-separated-values', buffer: Buffer.from('A\tB\n1\t2\n') });
  await page.waitForFunction(() => document.querySelector('#file-name').textContent === 'Daten.tsv');
  assert((await text('#file-kind')) === 'CSV' && !(await page.locator('#new-msg .alert-error').count()), 'TSV upload accepted');

  // --- Neue Seite: PDF (convert + preview only)
  await page.setInputFiles('#file-input', { name: 'Jahresbericht.pdf', mimeType: 'application/pdf', buffer: pdf() });
  await page.waitForFunction(() => document.querySelector('#file-kind').textContent === 'PDF');
  assert(await page.isHidden('#opt-header-row'), 'PDF upload hides table options');
  await page.click('#convert-btn');
  await page.frameLocator('#preview').getByText('alle Ziele erreicht').waitFor();
  assert(true, 'PDF converted and previewed');

  // --- Exportieren: unknown page, then page with attachments and children
  await tab('tab-export');
  await page.fill('#export-page', '999999');
  await page.focus('#export-btn');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#export-msg .alert-error');
  assert(await page.evaluate(() => document.activeElement?.id === 'export-btn'), 'keyboard focus stays on the button after the request');
  await page.fill('#export-page', 'https://anderes.wiki.ch/pages/viewpage.action?pageId=' + homeId);
  await page.click('#export-btn');
  await page.waitForFunction(() => document.querySelector('#export-msg')?.innerText.includes('anderes.wiki.ch'));
  assert(true, 'URL of another Confluence is refused');
  assert(true, `unknown page reported: ${(await text('#export-msg')).slice(0, 80)}`);
  await page.fill('#export-page', homeId);
  await page.check('#export-att');
  await page.check('#export-children');
  await page.click('#export-btn');
  await page.waitForSelector('#export-msg .alert-ok');
  const exported = await page.locator('#export-list li').count();
  assert(exported === 4, `export lists page + three children (${exported})`);
  const exportDirs = await page.locator('#export-list code').allInnerTexts();
  for (const d of exportDirs) assert(fs.existsSync(path.join(workspace, d, 'page.xhtml')), `export folder ${d} exists`);
  assert(fs.existsSync(path.join(workspace, exportDirs[0], 'attachments', 'organigramm.png')), 'attachment downloaded');
  await page.locator('#export-list button', { hasText: 'Ordner öffnen' }).first().click();
  await waitOut(/OPEN .+/);
  assert(true, '«Ordner öffnen» reaches the launcher');
  await shot('export-1280-light');
  const homeDir = exportDirs[0];

  // --- Lokale Seiten: list, edit, save, diff, push
  await tab('tab-local');
  await page.waitForSelector('#local-body tr');
  assert(await page.locator('#local-body tr').count() === 6, 'six local pages listed (2 created + 4 exported)');
  await page.click('#ide-setup');
  await page.waitForSelector('#local-msg .alert-warn, #local-msg .alert-ok');
  assert(fs.existsSync(path.join(workspace, '.github', 'copilot-instructions.md')), '«Für Copilot einrichten» writes the instructions');
  await page.click('#ide-vscode');
  await waitOut(/IDE VSCODE .+/);
  assert(true, '«In VS Code öffnen» reaches the launcher');
  await page.click('#local-refresh');
  await page.waitForSelector('#local-body tr');
  assert(await page.locator('#local-body tr').count() === 6, 'setup folders are not listed as pages');
  await page.click('#local-check');
  await page.waitForSelector('#local-msg .alert-ok');
  const row = page.locator(`#local-body tr[data-dir="${homeDir}"]`);
  await row.getByRole('button', { name: /bearbeiten$/ }).click();
  await page.waitForFunction(() => document.querySelector('#edit-source').value.includes('Teamhandbuch'));
  const orig = await page.inputValue('#edit-source');
  const homeFile = path.join(workspace, homeDir, 'page.xhtml');
  fs.appendFileSync(homeFile, '<p>Von Claude Desktop ergänzt</p>\n');
  await page.fill('#edit-source', orig.replace('Willkommen im Handbuch', 'Herzlich willkommen im Handbuch'));
  assert(await page.isVisible('#edit-dirty'), 'editor marks unsaved changes');
  await page.click('#edit-save');
  await page.waitForSelector('#edit-msg .alert-warn');
  assert((await text('#edit-msg')).includes('ausserhalb') && fs.readFileSync(homeFile, 'utf8').includes('Claude Desktop'), 'external change detected, not overwritten');
  await shot('local-editor-external-change');
  await page.getByRole('button', { name: /^Neu laden/ }).click();
  await page.click('#confirm-ok');
  await page.waitForFunction(() => document.querySelector('#edit-source').value.includes('Claude Desktop'));
  await page.fill('#edit-source', (await page.inputValue('#edit-source')).replace('Willkommen im Handbuch', 'Herzlich willkommen im Handbuch'));
  await page.click('#edit-save');
  await page.waitForSelector('#edit-msg .alert-ok');
  assert(fs.readFileSync(homeFile, 'utf8').includes('Claude Desktop'), 'reload kept the external paragraph');
  assert(fs.readFileSync(path.join(workspace, homeDir, 'page.xhtml'), 'utf8').includes('Herzlich willkommen'), 'saved to page.xhtml');
  await shot('local-editor-1280-light');
  await page.click('#edit-close');
  assert((await row.innerText()).includes('Lokal geändert'), 'row shows local change');

  // export over unpushed local edits: UI wording, no CLI options
  await tab('tab-export');
  await page.fill('#export-page', homeId);
  await page.click('#export-btn');
  await page.waitForSelector('#export-msg .alert-error');
  const lc = await text('#export-msg');
  assert(lc.includes('Lokale Änderungen überschreiben') && !lc.includes('cfh') && !lc.includes('--') && !lc.includes(workspace), `local edits: UI wording (${lc.slice(0, 60)})`);
  await shot('export-local-changes');
  await page.getByRole('button', { name: /aktivieren$/ }).click();
  assert(await page.isChecked('#export-force'), 'tip button ticks the checkbox');
  await page.uncheck('#export-force');
  await tab('tab-local');
  await page.waitForSelector(`#local-body tr[data-dir="${homeDir}"]`);

  // CRLF page: not dirty after opening, line endings kept on save
  const crlfDir = exportDirs[1];
  const crlfFile = path.join(workspace, crlfDir, 'page.xhtml');
  fs.writeFileSync(crlfFile, fs.readFileSync(crlfFile, 'utf8').replace(/\r?\n/g, '\r\n'));
  const crlfRow = page.locator(`#local-body tr[data-dir="${crlfDir}"]`);
  await crlfRow.getByRole('button', { name: /bearbeiten$/ }).click();
  await page.waitForFunction(() => document.querySelector('#edit-source').value.length > 0 && !document.querySelector('#edit-save').disabled);
  assert(await page.isHidden('#edit-dirty'), 'CRLF file is not dirty after opening');
  await page.fill('#edit-source', `${await page.inputValue('#edit-source')}<p>CRLF</p>\n`);
  await page.keyboard.press('Control+s');
  await page.waitForSelector('#edit-msg .alert-ok');
  const crlfText = fs.readFileSync(crlfFile, 'utf8');
  assert(crlfText.includes('<p>CRLF</p>\r\n') && !/[^\r]\n/.test(crlfText), 'save keeps CRLF line endings');
  await page.click('#edit-close');
  assert(await page.isHidden('#dlg-edit'), 'saved editor closes without asking');

  // server check fails (page folder of another address): reason visible in the row
  const otherDir = exportDirs[2];
  const otherMeta = path.join(workspace, otherDir, 'page.json');
  const metaText = fs.readFileSync(otherMeta, 'utf8');
  fs.writeFileSync(otherMeta, metaText.replaceAll('127.0.0.1', 'localhost'));
  await page.click('#local-check');
  await page.waitForSelector('#local-msg .alert-warn');
  const otherRow = page.locator(`#local-body tr[data-dir="${otherDir}"]`);
  assert((await otherRow.locator('.t-error').innerText()).includes('localhost'), 'failed server check shows its reason in the row');
  assert(await otherRow.getByRole('button', { name: 'Verbindung öffnen' }).count() === 1, 'row offers the Verbindung tab');
  await shot('local-other-server');
  fs.writeFileSync(otherMeta, metaText);
  await row.getByRole('button', { name: /^Unterschiede/ }).click();
  await page.waitForSelector('#diff-view .d-add');
  assert((await text('#diff-view')).includes('Herzlich'), 'diff shows local change');
  await shot('local-diff-1280-light');
  await page.click('#dlg-diff .dlg-foot button');
  await row.getByRole('button', { name: /zurückschreiben$/ }).click();
  await page.check('#push-dry');
  await page.click('#push-btn');
  await page.waitForSelector('#push-msg .alert-info');
  assert((await cmd(`version ${homeId}`, new RegExp(`VERSION ${homeId} (\\d+)`)))[1] === '1', 'dry run writes nothing');
  await page.uncheck('#push-dry');
  await page.fill('#push-message', 'Begrüssung angepasst');
  await page.click('#push-btn');
  await page.waitForSelector('#push-msg .alert-ok');
  assert((await text('#push-msg')).includes('neue Version 2'), 'push creates version 2');
  out = out.replace(/VERSION \d+ \d+/g, '');
  assert((await cmd(`version ${homeId}`, new RegExp(`VERSION ${homeId} (\\d+)`)))[1] === '2', 'fake server has version 2');
  await page.click('#push-cancel');

  // --- conflict: someone else edits, local push must stop
  await cmd(`edit ${homeId}`, /EDITED \d+ 3/);
  await page.click('#local-check');
  await page.waitForSelector('#local-msg .alert-warn');
  assert((await row.innerText()).includes('Server neuer'), 'remote change detected');
  await row.getByRole('button', { name: /bearbeiten$/ }).click();
  await page.waitForFunction(() => document.querySelector('#edit-source').value.includes('Herzlich'));
  await page.fill('#edit-source', (await page.inputValue('#edit-source')).replace('Ferienantrag', 'Ferienantrag und Kompensation'));
  await page.keyboard.press('Control+s');
  await page.waitForSelector('#edit-msg .alert-ok');
  await page.click('#edit-close');
  await row.getByRole('button', { name: /zurückschreiben$/ }).click();
  await page.click('#push-btn');
  await page.waitForSelector('#push-conflict:not([hidden])');
  assert(true, 'conflict UI shown');
  await shot('local-conflict-1280-light');
  await page.click('#push-show-diff');
  await page.waitForSelector('#diff-view .d-del');
  assert((await text('#diff-view')).includes('jemand anderem'), 'conflict diff shows remote change');
  await page.click('#dlg-diff .dlg-foot button');
  await page.click('#push-refresh');
  await page.click('#confirm-ok');
  await page.waitForSelector('#push-msg .alert-ok');
  assert((await text('#push-msg')).includes('page.xhtml.bak'), 'server version fetched, own version kept as .bak');
  assert(fs.readFileSync(`${homeFile}.bak`, 'utf8').includes('Kompensation') && fs.readFileSync(homeFile, 'utf8').includes('jemand anderem'), '.bak holds the local edit, page.xhtml the server version');
  await shot('local-refresh');
  await page.locator('#push-msg').getByRole('button', { name: 'Bearbeiten' }).click();
  await page.waitForFunction(() => document.querySelector('#edit-source').value.includes('jemand anderem'));
  await page.fill('#edit-source', (await page.inputValue('#edit-source')).replace('Ferienantrag', 'Ferienantrag und Kompensation'));
  await page.click('#edit-save');
  await page.waitForSelector('#edit-msg .alert-ok');
  await page.click('#edit-close');
  await row.getByRole('button', { name: /zurückschreiben$/ }).click();
  await page.click('#push-btn');
  await page.waitForSelector('#push-msg .alert-ok');
  assert((await text('#push-msg')).includes('neue Version 4'), 'after merging, a normal push creates version 4');
  await page.click('#push-cancel');

  // conflict again, this time overwrite
  await cmd(`edit ${homeId}`, /EDITED \d+ 5/);
  await row.getByRole('button', { name: /bearbeiten$/ }).click();
  await page.waitForFunction(() => document.querySelector('#edit-source').value.includes('Kompensation'));
  await page.fill('#edit-source', (await page.inputValue('#edit-source')).replace('Kompensation', 'Kompensation (neu)'));
  await page.click('#edit-save');
  await page.waitForSelector('#edit-msg .alert-ok');
  await page.click('#edit-close');
  await row.getByRole('button', { name: /zurückschreiben$/ }).click();
  await page.click('#push-btn');
  await page.waitForSelector('#push-conflict:not([hidden])');
  await page.click('#push-force');
  await page.click('#confirm-ok');
  await page.waitForSelector('#push-msg .alert-ok');
  assert((await text('#push-msg')).includes('neue Version 6'), 'forced push creates version 6');
  await page.click('#push-cancel');

  assert(await page.locator('#conn[data-state="ok"]').count() === 1, 'header still shows the working connection (no stale check result)');

  // --- screenshots: every tab, light/dark, 1280/390
  for (const scheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const w of [1280, 390]) {
      await page.setViewportSize({ width: w, height: 900 });
      for (const t of ['tab-new', 'tab-export', 'tab-local', 'tab-conn']) {
        await tab(t);
        await page.waitForTimeout(150);
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        if (overflow > 0) problems.push(`horizontal overflow ${overflow}px on ${t} at ${w}px`);
        if (await page.evaluate(() => { const l = document.querySelector('#main-tabs'); return l.scrollWidth > l.clientWidth; })) problems.push(`tab bar scrolls at ${w}px`);
        await shot(`${t.slice(4)}-${w}-${scheme}`);
      }
    }
  }
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 900 });

  // --- Arbeitsordner wechseln and back
  const emptyWs = path.join(path.dirname(workspace), 'Leer');
  const switchWs = async (dir) => {
    await page.click('#ws-btn');
    await page.fill('#ws-input', dir);
    await page.click('#ws-save');
    await page.waitForSelector('#dlg-ws', { state: 'hidden' });
  };
  await tab('tab-local');
  await switchWs(emptyWs);
  await page.waitForSelector('#local-empty:not([hidden])');
  assert(fs.existsSync(emptyWs) && (await text('#ws-path')).includes('Leer'), 'workspace switched, list empty');
  await switchWs(workspace);
  await page.waitForSelector('#local-body tr');
  assert(await page.locator('#local-body tr').count() === 6, 'workspace switched back');

  // --- foreign tab without token
  const other = await ctx.newPage();
  await other.goto(uiUrl.replace(/#.*/, ''));
  await other.waitForSelector('#blocker:not([hidden])');
  assert((await other.innerText('#blocker-title')).includes('Sitzung ungültig'), 'page without token is blocked');
  await other.close();

  // --- Beenden
  await page.click('#quit-btn');
  await page.click('#confirm-ok');
  await page.waitForSelector('#blocker:not([hidden])');
  assert((await text('#blocker-title')) === 'Beendet', '«Beendet» screen shown');
  await shot('quit-1280-light');
  const code = await Promise.race([exited, new Promise((r) => setTimeout(() => r('timeout'), 10000))]);
  assert(code === 0, 'server process stopped');
} finally {
  await browser.close();
  srv.kill();
}
if (problems.length) { console.log(`\n${problems.length} problem(s):\n${problems.join('\n')}`); process.exit(1); }
console.log(`\nall flows passed; screenshots in ${shots}`);
