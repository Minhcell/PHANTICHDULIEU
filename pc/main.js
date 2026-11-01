// Phân tích Cuộc gọi / SMS - bản PC (Electron, chạy offline, có thể làm máy chủ mạng LAN)
const { app, BrowserWindow, ipcMain, dialog, shell, Menu } = require("electron");
const path = require("path");
const fs = require("fs");
const http = require("http");
const os = require("os");

const DATA_DIR_NAME = "PhanTich_DuLieu";
const KHO_FILE = "kho.json";          // tài khoản người dùng, mật khẩu admin
const LAN_FILE = "lan.json";
const CONFIG_NAME = "PhanTichCuocGoi.config.json";
const WEB_DIR = path.join(__dirname, "web");
const SUBS = ["TepDaPhanTich/mobi", "TepDaPhanTich/vina", "TepDaPhanTich/viettel", "KetQua"];

const exeDir = process.env.PORTABLE_EXECUTABLE_DIR || path.dirname(app.getPath("exe"));
let dataDir = null;
let kho = {};                // { pa_users:[...], pa_adm_pass:"..." }
let ver = Date.now();
let lan = { enabled: false, port: 6868, pass: "" };
let server = null, serverError = "";

function isDir(p) { try { return fs.statSync(p).isDirectory(); } catch { return false; } }
function readJson(p) { try { return JSON.parse(fs.readFileSync(p, "utf8")); } catch { return null; } }
function safeName(n) { return String(n || "").replace(/[\\/:*?"<>|]/g, "_").replace(/^\.+/, "").slice(0, 180) || "file"; }
function safeSub(s) { if (!SUBS.includes(s)) throw new Error("Thư mục không hợp lệ"); return s; }

function findDataDir() {
  const besideExe = path.join(exeDir, DATA_DIR_NAME);
  if (isDir(besideExe)) return besideExe;
  if (fs.existsSync(path.join(exeDir, KHO_FILE))) return exeDir;
  for (const cfgPath of [path.join(exeDir, CONFIG_NAME), path.join(app.getPath("userData"), CONFIG_NAME)]) {
    const cfg = readJson(cfgPath);
    if (cfg && cfg.dataDir && isDir(cfg.dataDir)) return cfg.dataDir;
  }
  return null;
}
function resolveChosen(folder) {
  if (path.basename(folder) === DATA_DIR_NAME || fs.existsSync(path.join(folder, KHO_FILE))) return folder;
  return path.join(folder, DATA_DIR_NAME);
}
function writeAtomic(file, data) {
  const tmp = file + ".tmp";
  fs.writeFileSync(tmp, data);
  if (fs.existsSync(file)) { try { fs.copyFileSync(file, file + ".bak"); } catch {} }
  fs.renameSync(tmp, file);
}

/* ---------- dữ liệu dùng chung ---------- */
function loadKho() {
  kho = (dataDir && (readJson(path.join(dataDir, KHO_FILE)) || readJson(path.join(dataDir, KHO_FILE + ".bak")))) || {};
  ver = Date.now();
}
function kvSet(key, value) {
  if (!["pa_users", "pa_adm_pass"].includes(key)) throw new Error("Khoá không hợp lệ");
  if (!dataDir) throw new Error("Chưa chọn thư mục dữ liệu");
  if (value === null || value === undefined) delete kho[key]; else kho[key] = value;
  writeAtomic(path.join(dataDir, KHO_FILE), JSON.stringify(kho, null, 1));
  ver = Math.max(Date.now(), ver + 1);
  return { ver };
}
function saveFile(sub, name, buf) {
  if (!dataDir) throw new Error("Chưa chọn thư mục dữ liệu");
  const dir = path.join(dataDir, safeSub(sub));
  fs.mkdirSync(dir, { recursive: true });
  const p = path.join(dir, safeName(name));
  fs.writeFileSync(p, buf);
  ver = Math.max(Date.now(), ver + 1);
  return p;
}
function listFiles(sub) {
  if (!dataDir) return [];
  const dir = path.join(dataDir, safeSub(sub));
  if (!isDir(dir)) return [];
  return fs.readdirSync(dir).filter(n => !n.startsWith(".")).map(n => {
    const st = fs.statSync(path.join(dir, n));
    return st.isFile() ? { name: n, size: st.size, time: st.mtimeMs } : null;
  }).filter(Boolean).sort((a, b) => b.time - a.time);
}
function filePath(sub, name) {
  if (!dataDir) throw new Error("Chưa chọn thư mục dữ liệu");
  const p = path.join(dataDir, safeSub(sub), safeName(name));
  if (!fs.existsSync(p)) throw new Error("Không tìm thấy file " + name);
  return p;
}

/* ---------- máy chủ mạng LAN ---------- */
function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) for (const a of list || []) if (a.family === "IPv4" && !a.internal) out.push(a.address);
  return out;
}
function lanStatus() { return { enabled: !!lan.enabled, port: lan.port, pass: lan.pass, running: !!server, addresses: lanAddresses(), error: serverError }; }
function loadLan() {
  const j = dataDir ? readJson(path.join(dataDir, LAN_FILE)) : null;
  lan = { enabled: !!(j && j.enabled), port: (j && +j.port) || 6868, pass: (j && j.pass) || "" };
}
function saveLan() { if (dataDir) fs.writeFileSync(path.join(dataDir, LAN_FILE), JSON.stringify(lan, null, 2)); }

const MIME = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css", ".json": "application/json", ".png": "image/png", ".svg": "image/svg+xml" };
function send(res, code, obj) { res.writeHead(code, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }); res.end(JSON.stringify(obj)); }
function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on("data", c => { size += c.length; if (size > limit) { reject(new Error("Dữ liệu gửi lên quá lớn")); req.destroy(); } else chunks.push(c); });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

async function handle(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Pass");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  if (req.method === "OPTIONS") { res.writeHead(204); return res.end(); }
  const url = new URL(req.url, "http://x");
  const p = decodeURIComponent(url.pathname);

  if (p.startsWith("/api/")) {
    if (p === "/api/info") return send(res, 200, { app: "PhanTichCuocGoi", needPass: !!lan.pass, name: os.hostname() });
    let pass = ""; try { pass = decodeURIComponent(req.headers["x-pass"] || ""); } catch {}
    if (lan.pass && pass !== lan.pass) return send(res, 401, { error: "Sai mật khẩu" });
    try {
      const q = k => url.searchParams.get(k) || "";
      if (req.method === "GET" && p === "/api/ver") return send(res, 200, { ver });
      if (req.method === "GET" && p === "/api/kv") return send(res, 200, { ver, data: kho });
      if (req.method === "POST" && p === "/api/kv") { const b = JSON.parse((await readBody(req, 5e6)).toString("utf8") || "{}"); return send(res, 200, kvSet(b.key, b.value)); }
      if (req.method === "GET" && p === "/api/files") return send(res, 200, { files: listFiles(q("sub")) });
      if (req.method === "GET" && p === "/api/file") {
        const fp = filePath(q("sub"), q("name"));
        res.writeHead(200, { "Content-Type": "application/octet-stream", "Cache-Control": "no-store" });
        return fs.createReadStream(fp).pipe(res);
      }
      if (req.method === "POST" && p === "/api/file") {
        const buf = await readBody(req, 500 * 1024 * 1024);
        return send(res, 200, { path: saveFile(q("sub"), q("name"), buf) });
      }
      return send(res, 404, { error: "Không có chức năng này" });
    } catch (e) { return send(res, 500, { error: e.message }); }
  }

  if (req.method !== "GET") { res.writeHead(405); return res.end(); }
  const file = p === "/" ? "index.html" : p.replace(/^\/+/, "");
  const full = path.normalize(path.join(WEB_DIR, file));
  if (!full.startsWith(WEB_DIR) || !fs.existsSync(full) || !fs.statSync(full).isFile()) { res.writeHead(404); return res.end("Not found"); }
  res.writeHead(200, { "Content-Type": MIME[path.extname(full).toLowerCase()] || "application/octet-stream" });
  fs.createReadStream(full).pipe(res);
}

function stopServer() { return new Promise(r => { if (!server) return r(); const s = server; server = null; s.close(() => r()); setTimeout(r, 1000); }); }
function startServer() {
  return new Promise(resolve => {
    serverError = "";
    const s = http.createServer((req, res) => { handle(req, res).catch(e => { try { send(res, 500, { error: e.message }); } catch {} }); });
    s.on("error", e => { serverError = e.code === "EADDRINUSE" ? `Cổng ${lan.port} đang bị chương trình khác dùng, hãy chọn cổng khác.` : "Không bật được chia sẻ: " + e.message; server = null; resolve(); });
    s.listen(lan.port, "0.0.0.0", () => { server = s; resolve(); });
  });
}
async function applyLan() { await stopServer(); if (lan.enabled && dataDir) await startServer(); return lanStatus(); }

/* ---------- cầu nối với giao diện ---------- */
ipcMain.handle("store:status", () => ({ platform: "pc", configured: !!dataDir, folder: dataDir, suggest: path.join(exeDir, DATA_DIR_NAME) }));
ipcMain.handle("store:choose", async (e) => {
  const r = await dialog.showOpenDialog(BrowserWindow.fromWebContents(e.sender), { title: "Chọn nơi lưu dữ liệu", defaultPath: exeDir, properties: ["openDirectory", "createDirectory"] });
  if (r.canceled || !r.filePaths[0]) return null;
  const folder = resolveChosen(r.filePaths[0]);
  return { folder, hasData: fs.existsSync(path.join(folder, KHO_FILE)) };
});
ipcMain.handle("store:confirm", async (e, folder) => {
  try {
    const dir = resolveChosen(folder || path.join(exeDir, DATA_DIR_NAME));
    fs.mkdirSync(dir, { recursive: true });
    for (const sub of SUBS) fs.mkdirSync(path.join(dir, sub), { recursive: true });
    if (!fs.existsSync(path.join(dir, KHO_FILE))) writeAtomic(path.join(dir, KHO_FILE), "{}");
    const cfg = JSON.stringify({ dataDir: dir }, null, 2);
    try { fs.writeFileSync(path.join(exeDir, CONFIG_NAME), cfg); } catch {}
    try { fs.mkdirSync(app.getPath("userData"), { recursive: true }); fs.writeFileSync(path.join(app.getPath("userData"), CONFIG_NAME), cfg); } catch {}
    dataDir = dir; loadKho(); loadLan(); await applyLan();
    return { folder: dir };
  } catch (err) { return { error: err.message }; }
});
ipcMain.handle("store:kvGet", () => ({ ver, data: kho }));
ipcMain.handle("store:kvSet", (e, key, value) => kvSet(key, value));
ipcMain.handle("store:version", () => ver);
ipcMain.handle("store:saveFile", (e, sub, name, data) => saveFile(sub, name, Buffer.from(data)));
ipcMain.handle("store:listFiles", (e, sub) => listFiles(sub));
ipcMain.handle("store:readFile", (e, sub, name) => fs.readFileSync(filePath(sub, name)));
ipcMain.handle("store:reveal", (e, p) => { if (p) shell.showItemInFolder(p); return true; });
ipcMain.handle("store:lanStatus", () => lanStatus());
ipcMain.handle("store:lanSet", async (e, cfg) => {
  const port = parseInt(cfg && cfg.port, 10);
  lan = { enabled: !!(cfg && cfg.enabled), port: port >= 1024 && port <= 65535 ? port : 6868, pass: String((cfg && cfg.pass) || "").trim() };
  try { saveLan(); } catch {}
  return applyLan();
});

function createWindow() {
  const win = new BrowserWindow({
    width: 1360, height: 860, minWidth: 380, minHeight: 500, title: "Phân tích Cuộc gọi / SMS",
    backgroundColor: "#f3f5f8", autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, spellcheck: false }
  });
  win.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  win.webContents.on("will-navigate", (ev, url) => { if (!url.startsWith("file:")) ev.preventDefault(); });
  win.loadFile(path.join(WEB_DIR, "index.html"));
}

app.whenReady().then(async () => {
  Menu.setApplicationMenu(null);
  dataDir = findDataDir();
  if (dataDir) { loadKho(); loadLan(); await applyLan(); }
  createWindow();
});
app.on("window-all-closed", () => { stopServer(); app.quit(); });
