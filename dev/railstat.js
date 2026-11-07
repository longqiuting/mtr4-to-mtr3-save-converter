// Classify reference rail_connections: who carries path_mode / path_* keys.
const fs = require('fs'), path = require('path');

function readNode(buf, o) {
  const b = buf[o.p];
  if (b <= 0x7f) { o.p++; return { t: 'int', v: b }; }
  if (b >= 0xe0) { o.p++; return { t: 'int', v: b - 256 }; }
  if ((b & 0xf0) === 0x80) { const n = b & 0x0f; o.p++; return map(buf, o, n); }
  if ((b & 0xf0) === 0x90) { const n = b & 0x0f; o.p++; return arr(buf, o, n); }
  if ((b & 0xe0) === 0xa0) { const n = b & 0x1f; o.p++; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
  switch (b) {
    case 0xc0: o.p++; return { t: 'nil' };
    case 0xc2: case 0xc3: o.p++; return { t: 'bool' };
    case 0xc4: { o.p++; const n = buf[o.p++]; o.p += n; return { t: 'bin' }; }
    case 0xc5: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2 + n; return { t: 'bin' }; }
    case 0xc6: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4 + n; return { t: 'bin' }; }
    case 0xca: o.p += 5; return { t: 'f32' };
    case 0xcb: o.p += 9; return { t: 'f64' };
    case 0xcc: { o.p++; const v = buf.readUInt8(o.p); o.p++; return { t: 'int', v }; }
    case 0xcd: { o.p++; const v = buf.readUInt16BE(o.p); o.p += 2; return { t: 'int', v }; }
    case 0xce: { o.p++; const v = buf.readUInt32BE(o.p); o.p += 4; return { t: 'int', v }; }
    case 0xcf: { o.p++; const v = buf.readBigUInt64BE(o.p); o.p += 8; return { t: 'int', v }; }
    case 0xd0: { o.p++; const v = buf.readInt8(o.p); o.p++; return { t: 'int', v }; }
    case 0xd1: { o.p++; const v = buf.readInt16BE(o.p); o.p += 2; return { t: 'int', v }; }
    case 0xd2: { o.p++; const v = buf.readInt32BE(o.p); o.p += 4; return { t: 'int', v }; }
    case 0xd3: { o.p++; const v = buf.readBigInt64BE(o.p); o.p += 8; return { t: 'int', v }; }
    case 0xd9: { o.p++; const n = buf[o.p++]; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
    case 0xda: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
    case 0xdb: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
    case 0xdc: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2; return arr(buf, o, n); }
    case 0xdd: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4; return arr(buf, o, n); }
    case 0xde: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2; return map(buf, o, n); }
    case 0xdf: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4; return map(buf, o, n); }
    default: throw new Error('op 0x' + b.toString(16));
  }
}
function map(buf, o, n) { const f = {}; for (let i = 0; i < n; i++) { const k = readNode(buf, o); f[k.v] = readNode(buf, o); } return { t: 'map', f }; }
function arr(buf, o, n) { const a = []; for (let i = 0; i < n; i++) a.push(readNode(buf, o)); return { t: 'array', a }; }

function walk(d, a = []) { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p, a); else a.push(p); } return a; }

const stats = {};
for (const save of process.argv.slice(2)) {
  const files = walk(path.join(save, 'mtr')).filter(f => f.replace(/\\/g, '/').includes('/rails/'));
  for (const f of files) {
    let doc;
    try { doc = readNode(fs.readFileSync(f), { p: 0 }); } catch (e) { continue; }
    const conns = doc.f && doc.f.rail_connections;
    if (!conns || !conns.a) continue;
    for (const c of conns.a) {
      const has = k => (k in c.f);
      const mk = has('model_key') ? (c.f.model_key.v || '""(empty)') : '(absent)';
      const key = has('path_mode') ? `path_mode + model=${mk}` : `no path_mode + model=${mk}`;
      stats[key] = (stats[key] || 0) + 1;
      if (has('path_mode')) {
        const bk = 'path_keys=' + ['pos_start', 'facing_start', 'pos_end', 'facing_end', 'bezier_curve'].filter(has).length + '/5';
        stats['  ' + bk] = (stats['  ' + bk] || 0) + 1;
      }
    }
  }
}
Object.entries(stats).sort((a, b) => b[1] - a[1]).forEach(([k, v]) => console.log(String(v).padStart(7), k));
