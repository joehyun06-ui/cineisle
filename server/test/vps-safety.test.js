const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { setTimeout: delay } = require("node:timers/promises");

const serverPath = path.resolve(__dirname, "../server.js");
const token = crypto.randomBytes(32).toString("hex");
const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=";

test("VPS authentication, MCP and persistence", { timeout: 25000 }, async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "cineisle-vps-test-"));
  const dataFile = path.join(dir, "rooms.json");
  const processes = [];
  t.after(async () => {
    for (const p of processes) {
      if (!p.finished) p.child.kill("SIGKILL");
    }
    await Promise.all(processes.map(p => p.exited));
    fs.rmSync(dir, { recursive: true, force: true });
  });

  function launch(overrides = {}) {
    const env = {
      ...process.env,
      NODE_ENV: "test",
      PORT: "0",
      CINEISLE_TOKEN: token,
      CINEISLE_DATA_FILE: dataFile,
      LINJIAN_CINEMA_TOKEN: "",
      LINJIAN_CINEMA_DATA_FILE: "",
      CINEISLE_PUBLIC_URL: "",
      PUBLIC_BASE_URL: "",
      RENDER_EXTERNAL_URL: "",
      ...overrides
    };
    delete env.CINEISLE_HOST; // Test the actual loopback default.
    const child = spawn(process.execPath, [serverPath], {
      cwd: dir, env, stdio: ["ignore", "pipe", "pipe"]
    });
    const p = { child, output: "", finished: false, exited: null };
    child.stdout.on("data", b => { p.output += b; });
    child.stderr.on("data", b => { p.output += b; });
    p.exited = new Promise(resolve => {
      child.once("error", e => {
        p.output += e.message;
        p.finished = true;
        resolve({ code: null, signal: null, error: e.message });
      });
      child.once("exit", (code, signal) => {
        p.finished = true;
        resolve({ code, signal });
      });
    });
    processes.push(p);
    return p;
  }

  async function exitResult(p) {
    for (let i = 0; i < 300; i++) {
      if (p.finished) return p.exited;
      await delay(20);
    }
    throw new Error("Server did not exit: " + p.output);
  }

  async function start() {
    const p = launch();
    for (let i = 0; i < 300; i++) {
      const match = p.output.match(/CineIsle server: (http:\/\/127\.0\.0\.1:\d+)/);
      if (match) return { ...p, process: p, base: match[1] };
      if (p.finished) throw new Error("Server exited before ready: " + p.output);
      await delay(20);
    }
    throw new Error("Server did not bind to loopback: " + p.output);
  }

  await t.test("missing or short tokens fail closed before binding", async () => {
    for (const value of ["", "short-token"]) {
      const p = launch({ CINEISLE_TOKEN: value });
      assert.equal((await exitResult(p)).code, 1);
      assert.match(p.output, /at least 32 characters/);
      assert.doesNotMatch(p.output, /CineIsle server: http/);
    }
    assert.equal(fs.existsSync(dataFile), false);
  });

  let running = await start();
  async function request(method, route, body, authToken = token) {
    const headers = {};
    if (authToken) headers.Authorization = "Bearer " + authToken;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    return fetch(running.base + route, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(4000)
    });
  }
  async function json(method, route, body, authToken = token) {
    const response = await request(method, route, body, authToken);
    return { status: response.status, body: await response.json() };
  }
  async function rpc(name, args = {}, authToken = token) {
    return json("POST", "/mcp", {
      jsonrpc: "2.0", id: 1, method: "tools/call",
      params: { name, arguments: args }
    }, authToken);
  }

  await t.test("loopback health and static assets work from another working directory", async () => {
    assert.match(running.base, /^http:\/\/127\.0\.0\.1:\d+$/);
    const health = await json("GET", "/api/health", undefined, null);
    assert.equal(health.status, 200);
    assert.equal(health.body.ok, true);
    assert.equal(health.body.tokenRequired, true);
    assert.equal((await request("GET", "/", undefined, null)).status, 200);
    assert.equal((await request("GET", "/app.js", undefined, null)).status, 200);
  });

  let room;
  await t.test("creation and room reads require a valid token", async () => {
    assert.equal((await request("POST", "/api/rooms", { title: "Test film" }, null)).status, 403);
    assert.equal((await request("POST", "/api/rooms", {}, "incorrect-token")).status, 403);
    const created = await json("POST", "/api/rooms", { title: "Test film", assistantName: "Test assistant" });
    assert.equal(created.status, 200);
    room = created.body.room.id;
    assert.equal((await request("GET", "/api/rooms/" + room, undefined, null)).status, 403);
    assert.equal((await request("GET", "/api/rooms/" + room, undefined, "incorrect-token")).status, 403);
    const valid = await json("GET", "/api/rooms/" + room);
    assert.equal(valid.body.room.title, "Test film");
    const legacy = await fetch(running.base + "/api/rooms/" + room, {
      headers: { "X-CineIsle-Token": token }, signal: AbortSignal.timeout(4000)
    });
    assert.equal(legacy.status, 200);
  });

  await t.test("MCP discovery, authenticated playback and unauthorized denial", async () => {
    const init = await json("POST", "/mcp", { jsonrpc: "2.0", id: 2, method: "initialize", params: {} }, null);
    assert.equal(init.status, 200);
    assert.ok(init.body.result.capabilities.tools);
    const list = await json("POST", "/mcp", { jsonrpc: "2.0", id: 3, method: "tools/list" }, null);
    assert.ok(list.body.result.tools.some(tool => tool.name === "play_movie"));
    const denied = await rpc("play_movie", { room }, null);
    assert.equal(denied.body.error.code, -32001);
    const played = await rpc("play_movie", { room, currentTime: 42 });
    assert.ok(played.body.result.content.length);
    const state = await json("GET", "/api/rooms/" + room);
    assert.equal(state.body.room.paused, false);
    assert.equal(state.body.room.currentTime, 42);
    assert.equal(state.body.room.context.playbackCommand.action, "play");
    const paused = await rpc("pause_movie", { room });
    assert.ok(paused.body.result);
    assert.equal((await json("GET", "/api/rooms/" + room)).body.room.paused, true);
  });

  await t.test("notes and subtitle context remain functional", async () => {
    assert.equal((await request("POST", "/api/rooms/" + room + "/note", { text: "Test note", time: 17 })).status, 200);
    assert.equal((await request("POST", "/api/rooms/" + room + "/context", {
      currentSubtitle: "Test subtitle", recentSubtitles: ["Earlier subtitle"], currentTime: 42
    })).status, 200);
    const context = await rpc("get_viewing_context", { room });
    const result = JSON.parse(context.body.result.content[0].text);
    assert.equal(result.context.currentSubtitle, "Test subtitle");
    assert.ok(result.room.notes.some(note => note.text === "Test note"));
  });

  await t.test("unsigned screenshots are blocked but MCP signed links still work", async () => {
    const result = await json("POST", "/api/rooms/" + room + "/screenshot", {
      dataUrl: "data:image/png;base64," + png, mime: "image/png", width: 1, height: 1
    });
    assert.equal(result.status, 200);
    const signed = new URL(result.body.frame.image_url);
    assert.equal(signed.origin, running.base);
    assert.ok(signed.searchParams.get("sig"));
    const image = await fetch(signed, { signal: AbortSignal.timeout(4000) });
    assert.equal(image.status, 200);
    assert.equal(image.headers.get("content-type"), "image/png");
    assert.deepEqual(Buffer.from(await image.arrayBuffer()), Buffer.from(png, "base64"));
    signed.searchParams.delete("sig");
    assert.equal((await fetch(signed)).status, 403);
    signed.searchParams.set("sig", "incorrect-signature");
    assert.equal((await fetch(signed)).status, 403);
    const mcp = await rpc("get_viewing_context", { room, includeScreenshot: true });
    assert.ok(mcp.body.result.content.some(part => part.type === "image"));
  });

  await t.test("continuous updates do not indefinitely postpone saving", async () => {
    await delay(300);
    assert.ok(fs.existsSync(dataFile));
    await request("POST", "/api/rooms/" + room + "/note", { text: "Continuous-save marker", time: 19 });
    for (let i = 0; i < 14; i++) {
      await request("POST", "/api/rooms/" + room + "/playback", { currentTime: 100 + i });
      await delay(25);
    }
    const saved = JSON.parse(fs.readFileSync(dataFile, "utf8"));
    assert.ok(saved.rooms.find(r => r.id === room).notes.some(n => n.text === "Continuous-save marker"));
    assert.equal(fs.statSync(dataFile).mode & 0o777, 0o600);
    assert.equal(fs.existsSync(dataFile + ".tmp"), false);
  });

  await t.test("SIGTERM flushes pending changes and restart restores rooms", async () => {
    await request("POST", "/api/rooms/" + room + "/note", { text: "Shutdown-save marker", time: 23 });
    running.child.kill("SIGTERM");
    assert.equal((await exitResult(running.process)).code, 0);
    const saved = JSON.parse(fs.readFileSync(dataFile, "utf8"));
    assert.ok(saved.rooms.find(r => r.id === room).notes.some(n => n.text === "Shutdown-save marker"));
    running = await start();
    const restored = await json("GET", "/api/rooms/" + room);
    assert.equal(restored.status, 200);
    assert.ok(restored.body.room.notes.some(n => n.text === "Shutdown-save marker"));
    assert.equal(restored.body.room.context.currentSubtitle, "Test subtitle");
    running.child.kill("SIGTERM");
    assert.equal((await exitResult(running.process)).code, 0);
  });

  await t.test("corrupt or invalid existing data is not silently replaced", async () => {
    for (const content of ["{broken-json", '{"notRooms":[]}']) {
      const broken = path.join(dir, "broken.json");
      fs.writeFileSync(broken, content);
      const p = launch({ CINEISLE_DATA_FILE: broken });
      assert.equal((await exitResult(p)).code, 1);
      assert.match(p.output, /existing data was not changed/);
      assert.equal(fs.readFileSync(broken, "utf8"), content);
      assert.doesNotMatch(p.output, /CineIsle server: http/);
    }
  });

  await t.test("Android creation and polling include authentication", () => {
    const android = fs.readFileSync(path.resolve(__dirname, "../../android/app/src/main/java/com/cineisle/app/MainActivity.java"), "utf8");
    assert.match(android, /postJson\("\/api\/rooms", body, true\)/);
    const getJson = android.slice(android.indexOf("private JSONObject getJson("), android.indexOf("private JSONObject postJson("));
    assert.match(getJson, /setRequestProperty\("Authorization", "Bearer " \+ token\)/);
  });
});
