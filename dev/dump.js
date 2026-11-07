// throwaway msgpack dumper for analysis
const fs = require('fs');

function dec(buf, o) {
  const b = buf[o.p++];
  const r = {};
  function readStr(n) { const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return s; }
  function readBin(n) { const s = buf.subarray(o.p, o.p + n).toString('hex'); o.p += n; return 'bin:' + s; }
  function readMap(n) { const m = {}; for (let i = 0; i < n; i++) { const k = dec(buf, o); const v = dec(buf, o); m[String(k)] = v; } return m; }
  function readArr(n) { const a = []; for (let i = 0; i < n; i++) a.push(dec(buf, o)); return a; }
  if (b <= 0x7f) return b;
  if (b >= 0xe0) return b - 256;
  if ((b & 0xf0) === 0x80) return readMap(b & 0x0f);
  if ((b & 0xf0) === 0x90) return readArr(b & 0x0f);
  if ((b & 0xe0) === 0xa0) return readStr(b & 0x1f);
  switch (b) {
    case 0xc0: return null;
    case 0xc2: return false;
    case 0xc3: return true;
    case 0xc4: return readBin(buf[o.p++]);
    case 0xc5: { const n = buf.readUInt16BE(o.p); o.p += 2; return readBin(n); }
    case 0xc6: { const n = buf.readUInt32BE(o.p); o.p += 4; return readBin(n); }
    case 0xca: { const v = buf.readFloatBE(o.p); o.p += 4; return v; }
    case 0xcb: { const v = buf.readDoubleBE(o.p); o.p += 8; return v; }
    case 0xcc: return buf[o.p++];
    case 0xcd: { const v = buf.readUInt16BE(o.p); o.p += 2; return v; }
    case 0xce: { const v = buf.readUInt32BE(o.p); o.p += 4; return v; }
    case 0xcf: { const v = buf.readBigUInt64BE(o.p); o.p += 8; return 'u64:' + v.toString(); }
    case 0xd0: { const v = buf.readInt8(o.p); o.p += 1; return v; }
    case 0xd1: { const v = buf.readInt16BE(o.p); o.p += 2; return v; }
    case 0xd2: { const v = buf.readInt32BE(o.p); o.p += 4; return v; }
    case 0xd3: { const v = buf.readBigInt64BE(o.p); o.p += 8; return 'i64:' + v.toString(); }
    case 0xd9: return readStr(buf[o.p++]);
    case 0xda: { const n = buf.readUInt16BE(o.p); o.p += 2; return readStr(n); }
    case 0xdb: { const n = buf.readUInt32BE(o.p); o.p += 4; return readStr(n); }
    case 0xdc: { const n = buf.readUInt16BE(o.p); o.p += 2; return readArr(n); }
    case 0xdd: { const n = buf.readUInt32BE(o.p); o.p += 4; return readArr(n); }
    case 0xde: { const n = buf.readUInt16BE(o.p); o.p += 2; return readMap(n); }
    case 0xdf: { const n = buf.readUInt32BE(o.p); o.p += 4; return readMap(n); }
    default: throw new Error('opcode 0x' + b.toString(16) + ' at ' + (o.p - 1));
  }
}

function allDocs(buf) {
  const docs = []; const o = { p: 0 };
  while (o.p < buf.length) {
    try { const start = o.p; const v = dec(buf, o); docs.push({ start, end: o.p, v }); }
    catch (e) { docs.push({ error: e.message, at: o.p, rest: buf.length - o.p }); break; }
  }
  return docs;
}

const f = process.argv[2];
const buf = fs.readFileSync(f);
const docs = allDocs(buf);
console.log('FILE', f, 'bytes=' + buf.length, 'docs=' + docs.length);
docs.forEach((d, i) => {
  if (d.error) { console.log('-- doc', i, 'ERROR', d.error, 'at', d.at, 'remaining', d.rest); return; }
  console.log('-- doc', i, '[' + d.start + ',' + d.end + ')');
  console.log(JSON.stringify(d.v, (k, v) => typeof v === 'bigint' ? v.toString() + 'n' : v, 1));
});
