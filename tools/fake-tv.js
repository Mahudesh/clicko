// Fake Android TV for testing the app without a real TV.
// Speaks the Android TV Remote v2 protocol: pairing on 6467, remote on 6466.
//
//   node tools/fake-tv.js            (emulator reaches it at 10.0.2.2)
//
// It prints the pairing code here (a real TV shows it on screen) and logs
// every key, app launch and text the app sends.
const tls = require('tls');
const crypto = require('crypto');
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '.fake-tv');
fs.mkdirSync(dir, { recursive: true });
const keyFile = path.join(dir, 'key.pem'), certFile = path.join(dir, 'cert.pem');
if (!fs.existsSync(certFile)) {
  execSync(`openssl req -x509 -newkey rsa:2048 -nodes -keyout "${keyFile}" -out "${certFile}" -days 3650 -subj "/CN=FakeTV"`, { stdio: 'ignore' });
}
const key = fs.readFileSync(keyFile), cert = fs.readFileSync(certFile);
const serverJwk = new crypto.X509Certificate(cert).publicKey.export({ format: 'jwk' });
const b64 = (s) => Buffer.from(s, 'base64url');
const paired = new Set(); // client cert fingerprints we've paired with
const pairedFile = path.join(dir, 'paired.json');
if (fs.existsSync(pairedFile)) JSON.parse(fs.readFileSync(pairedFile)).forEach((f) => paired.add(f));
const NAME = process.env.TV_NAME || 'Fake Living Room TV';

// ---- tiny protobuf ----
function varint(n) { const b = []; while (n > 0x7f) { b.push((n & 0x7f) | 0x80); n >>>= 7; } b.push(n); return Buffer.from(b); }
const fInt = (f, v) => Buffer.concat([varint(f << 3), varint(v)]);
const fBytes = (f, buf) => Buffer.concat([varint((f << 3) | 2), varint(buf.length), buf]);
const fStr = (f, s) => fBytes(f, Buffer.from(s));
function parse(buf) {
  const out = {}; let i = 0;
  const rv = () => { let r = 0, s = 0, b; do { b = buf[i++]; r += (b & 0x7f) * 2 ** s; s += 7; } while (b & 0x80); return r; };
  while (i < buf.length) {
    const k = rv(), f = Math.floor(k / 8), w = k & 7;
    let v; if (w === 0) v = rv(); else if (w === 2) { const l = rv(); v = buf.subarray(i, i + l); i += l; } else break;
    (out[f] ||= []).push(v);
  }
  return out;
}
function framer(sock, onMsg) {
  let acc = Buffer.alloc(0);
  sock.on('data', (d) => {
    acc = Buffer.concat([acc, d]);
    for (;;) {
      let len = 0, s = 0, i = 0, b;
      do { if (i >= acc.length) return; b = acc[i++]; len |= (b & 0x7f) << s; s += 7; } while (b & 0x80);
      if (acc.length < i + len) return;
      const msg = acc.subarray(i, i + len); acc = acc.subarray(i + len); onMsg(parse(msg));
    }
  });
}
const send = (sock, payload) => sock.write(Buffer.concat([varint(payload.length), payload]));
const fp = (c) => crypto.createHash('sha256').update(c.raw).digest('hex');
const KEYS = { 3: 'HOME', 4: 'BACK', 19: 'UP', 20: 'DOWN', 21: 'LEFT', 22: 'RIGHT', 23: 'OK', 24: 'VOL+', 25: 'VOL-', 26: 'POWER', 66: 'ENTER', 67: 'DEL', 84: 'SEARCH', 85: 'PLAY/PAUSE', 89: 'REWIND', 90: 'FFWD', 164: 'MUTE', 176: 'SETTINGS' };
const opts = { key, cert, requestCert: true, rejectUnauthorized: false };

// Real TVs silently drop phones whose certificate isn't shaped like the Google TV app's
// (CN=atvremote, CA basic constraint, DNS SAN). Mimic that so tests catch it.
// Set LENIENT=1 to accept anything.
function acceptable(sock) {
  if (process.env.LENIENT) return true;
  const c = sock.getPeerCertificate(true);
  if (!c || !c.raw) { console.log('[tls] ❌ phone sent no certificate'); return false; }
  const x = new crypto.X509Certificate(c.raw);
  const problems = [];
  if (!x.ca) problems.push('not CA:TRUE');
  if (!/DNS:atvremote/.test(x.subjectAltName || '')) problems.push('no DNS:atvremote SAN');
  if (!/CN=atvremote/.test(x.subject)) problems.push('CN is not atvremote');
  if (new Date(x.validFrom) > new Date()) problems.push('not valid yet');
  if (problems.length) { console.log(`[tls] ❌ rejecting phone certificate: ${problems.join(', ')}`); return false; }
  return true;
}

// PROTOCOL=1 mimics an older TV (Remote Service < 5, e.g. Xiaomi Mi TV on Android 9):
// JSON pairing with 4-byte lengths and 4-char codes, binary remote frames, TLS 1.2 only.
const V1 = process.env.PROTOCOL === '1';
if (V1) { opts.maxVersion = 'TLSv1.2'; startV1(); } else startV2();

function startV1() {
  const hashFor = (client, nonce) => crypto.createHash('sha256').update(Buffer.from(client.modulus, 'hex')).update(Buffer.from([1, 0, 1]))
    .update(b64(serverJwk.n)).update(b64(serverJwk.e)).update(nonce).digest();
  tls.createServer(opts, (sock) => {
    if (!acceptable(sock)) return sock.destroy();
    const client = sock.getPeerCertificate(true);
    console.log(`\n[pair v1] ${sock.remoteAddress} connected`);
    let acc = Buffer.alloc(0), nonce = null;
    const reply = (type, payload = {}, status = 200) => {
      const body = Buffer.from(JSON.stringify({ protocol_version: 1, status, type, payload }));
      const len = Buffer.alloc(4); len.writeUInt32BE(body.length); sock.write(Buffer.concat([len, body]));
    };
    sock.on('data', (d) => {
      acc = Buffer.concat([acc, d]);
      while (acc.length >= 4) {
        const len = acc.readUInt32BE(0);
        if (len > 4096) { console.log('[pair v1] ❌ not JSON framing (protobuf?), hanging up like a real old TV'); return sock.destroy(); }
        if (acc.length < 4 + len) return;
        let msg; try { msg = JSON.parse(acc.subarray(4, 4 + len).toString()); } catch { console.log('[pair v1] ❌ bad JSON'); return sock.destroy(); }
        acc = acc.subarray(4 + len);
        if (msg.type === 10) reply(11);
        else if (msg.type === 20) reply(20, { input_encodings: [{ type: 3, symbol_length: 4 }], output_encodings: [{ type: 3, symbol_length: 4 }], preferred_role: 2 });
        else if (msg.type === 30) {
          nonce = crypto.randomBytes(1);
          const code = (hashFor(client, nonce).subarray(0, 1).toString('hex') + nonce.toString('hex')).toUpperCase();
          console.log(`\n   ┌──────────────────────┐\n   │  TV CODE:  ${code}      │\n   └──────────────────────┘\n`);
          reply(31);
        } else if (msg.type === 40) {
          const secret = Buffer.from(msg.payload.secret, 'base64');
          if (Buffer.compare(secret, hashFor(client, nonce)) === 0) {
            paired.add(fp(client)); fs.writeFileSync(pairedFile, JSON.stringify([...paired]));
            console.log('[pair v1] ✅ paired'); reply(41, { secret: msg.payload.secret });
          } else { console.log('[pair v1] ❌ wrong secret'); reply(40, {}, 403); sock.end(); }
        }
      }
    });
    sock.on('error', () => {});
  }).listen(6467, () => console.log(`${NAME} (v1/legacy) pairing on :6467`));

  tls.createServer(opts, (sock) => {
    if (!acceptable(sock)) return sock.destroy();
    const client = sock.getPeerCertificate(true);
    if (!paired.has(fp(client))) { console.log('[remote v1] unknown phone, hanging up'); return sock.destroy(); }
    console.log('[remote v1] phone connected');
    const frame = (type, payload = Buffer.alloc(0)) => { const h = Buffer.from([1, type, 0, 0]); h.writeUInt16BE(payload.length, 2); sock.write(Buffer.concat([h, payload])); };
    frame(0x14);
    const ping = setInterval(() => frame(0x14), 5000);
    let acc = Buffer.alloc(0);
    sock.on('data', (d) => {
      acc = Buffer.concat([acc, d]);
      while (acc.length >= 4) {
        const len = acc.readUInt16BE(2);
        if (acc.length < 4 + len) return;
        const type = acc[1], p = acc.subarray(4, 4 + len); acc = acc.subarray(4 + len);
        if (type === 0) { frame(7, Buffer.from('000000010000000000', 'hex')); frame(0x1b, Buffer.from([0])); frame(0x26, Buffer.alloc(16)); }
        else if (type === 2) { const action = p.readUInt32BE(8), code = p.readUInt32BE(12); if (action === 0) console.log(`[remote v1] key ${KEYS[code] || code}`); }
        else if (type === 16) console.log(`[remote v1] open ${p.toString()}`);
        else if (type === 0x15) { /* pong */ }
      }
    });
    sock.on('close', () => { clearInterval(ping); console.log('[remote v1] phone left'); });
    sock.on('error', () => {});
  }).listen(6466, () => console.log(`${NAME} (v1/legacy) remote on :6466`));
}

function startV2() {
// ---- pairing (6467) ----
tls.createServer(opts, (sock) => {
  if (!acceptable(sock)) return sock.destroy();
  const client = sock.getPeerCertificate(true);
  console.log(`\n[pair] ${sock.remoteAddress} connected`);
  let code = null;
  const reply = (body, status = 200) => send(sock, Buffer.concat([fInt(1, 2), fInt(2, status), body]));
  framer(sock, (m) => {
    if (m[10]) { reply(fBytes(11, fStr(1, NAME))); }
    else if (m[20]) { reply(fBytes(20, Buffer.concat([fBytes(1, Buffer.concat([fInt(1, 3), fInt(2, 6)])), fInt(3, 1)]))); }
    else if (m[30]) {
      const nonce = crypto.randomBytes(2);
      const h = crypto.createHash('sha256').update(Buffer.from(client.modulus, 'hex')).update(Buffer.from([1, 0, 1]))
        .update(b64(serverJwk.n)).update(b64(serverJwk.e)).update(nonce).digest();
      code = (h.subarray(0, 1).toString('hex') + nonce.toString('hex')).toUpperCase();
      console.log(`\n   ┌──────────────────────┐\n   │  TV CODE:  ${code}    │\n   └──────────────────────┘\n`);
      reply(fBytes(31, Buffer.alloc(0)));
    } else if (m[40]) {
      const secret = parse(m[40][0])[1][0];
      const expected = crypto.createHash('sha256').update(Buffer.from(client.modulus, 'hex')).update(Buffer.from([1, 0, 1]))
        .update(b64(serverJwk.n)).update(b64(serverJwk.e)).update(Buffer.from(code.slice(2), 'hex')).digest();
      if (Buffer.compare(secret, expected) === 0) {
        paired.add(fp(client)); fs.writeFileSync(pairedFile, JSON.stringify([...paired]));
        console.log('[pair] ✅ paired'); reply(fBytes(41, fBytes(1, secret)));
      } else { console.log('[pair] ❌ wrong secret'); reply(Buffer.alloc(0), 402); sock.end(); }
    }
  });
  sock.on('error', () => {});
}).listen(6467, () => console.log(`${NAME} pairing on :6467`));

// ---- remote (6466) ----
tls.createServer(opts, (sock) => {
  if (!acceptable(sock)) return sock.destroy();
  const client = sock.getPeerCertificate(true);
  if (!client.raw || !paired.has(fp(client))) { console.log('[remote] unknown phone, hanging up'); return sock.destroy(); }
  console.log('[remote] phone connected');
  let vol = 12, muted = false;
  const volMsg = () => fBytes(50, Buffer.concat([fInt(6, 100), fInt(7, vol), fInt(8, muted ? 1 : 0)]));
  send(sock, fBytes(1, Buffer.concat([fInt(1, 622), fBytes(2, fStr(1, 'FakeTV'))])));
  const ping = setInterval(() => send(sock, fBytes(8, fInt(1, 1))), 5000);
  framer(sock, (m) => {
    if (m[1]) { send(sock, fBytes(2, fInt(1, 622))); send(sock, fBytes(40, fInt(1, 1))); send(sock, volMsg()); }
    if (m[10]) {
      const k = parse(m[10][0]); const code = k[1]?.[0];
      console.log(`[remote] key ${KEYS[code] || code}`);
      if (code === 24) vol = Math.min(100, vol + 1); if (code === 25) vol = Math.max(0, vol - 1); if (code === 164) muted = !muted;
      if ([24, 25, 164].includes(code)) send(sock, volMsg());
    }
    if (m[90]) console.log(`[remote] open app ${parse(m[90][0])[1][0].toString()}`);
    if (m[21]) { const e = parse(parse(m[21][0])[3][0]); console.log(`[remote] text "${parse(e[2][0])[3]?.[0]?.toString() ?? ''}"`); }
  });
  sock.on('close', () => { clearInterval(ping); console.log('[remote] phone left'); });
  sock.on('error', () => {});
}).listen(6466, () => console.log(`${NAME} remote on :6466`));
}
