// throwaway NBT dumper (level.dat: 4-byte header + gzip) -> path list
const fs = require('fs'), zlib = require('zlib');

function parseValue(buf, o, type, pad, path, out) {
  switch (type) {
    case 1: { const v = buf.readInt8(o.p); o.p += 1; out.push(`${pad}${path}= ${v}`); return; }
    case 2: { const v = buf.readInt16BE(o.p); o.p += 2; out.push(`${pad}${path}= ${v}`); return; }
    case 3: { const v = buf.readInt32BE(o.p); o.p += 4; out.push(`${pad}${path}= ${v}`); return; }
    case 4: { const v = buf.readBigInt64BE(o.p).toString(); o.p += 8; out.push(`${pad}${path}= ${v}L`); return; }
    case 5: { const v = buf.readFloatBE(o.p); o.p += 4; out.push(`${pad}${path}= ${v}f`); return; }
    case 6: { const v = buf.readDoubleBE(o.p); o.p += 8; out.push(`${pad}${path}= ${v}d`); return; }
    case 7: { const n = buf.readInt32BE(o.p); o.p += 4; o.p += n; out.push(`${pad}${path}= byte[${n}]`); return; }
    case 8: {
      const n = buf.readUInt16BE(o.p); o.p += 2;
      const s = buf.toString('utf8', o.p, o.p + n); o.p += n;
      out.push(`${pad}${path}= ${JSON.stringify(n > 200 ? s.slice(0, 200) + '…' : s)}`);
      return;
    }
    case 9: {
      const et = buf[o.p++]; const n = buf.readInt32BE(o.p); o.p += 4;
      out.push(`${pad}${path}: LIST<${et}>[${n}]`);
      for (let i = 0; i < n; i++) parseValue(buf, o, et, pad + '  ', `[${i}]`, out);
      return;
    }
    case 10: {
      out.push(`${pad}${path}: COMPOUND {`);
      while (buf[o.p] !== 0) readTag(buf, o, pad + '  ', out);
      o.p++;
      out.push(`${pad}}`);
      return;
    }
    case 11: { const n = buf.readInt32BE(o.p); o.p += 4; o.p += n * 4; out.push(`${pad}${path}= int[${n}]`); return; }
    case 12: { const n = buf.readInt32BE(o.p); o.p += 4; o.p += n * 8; out.push(`${pad}${path}= long[${n}]`); return; }
    default: throw new Error('bad tag ' + type + ' at ' + (o.p - 1));
  }
}

function readTag(buf, o, pad, out) {
  const type = buf[o.p++];
  if (type === 0) return;
  const nlen = buf.readUInt16BE(o.p); o.p += 2;
  const name = buf.toString('utf8', o.p, o.p + nlen); o.p += nlen;
  parseValue(buf, o, type, pad, name, out);
}

const f = process.argv[2];
const raw = fs.readFileSync(f);
let data;
try { data = zlib.gunzipSync(raw.subarray(4)); }
catch (e) { try { data = zlib.gunzipSync(raw); } catch (e2) { data = raw; } }
const out = [];
readTag(data, { p: 0 }, '', out);
out.forEach(l => console.log(l));
