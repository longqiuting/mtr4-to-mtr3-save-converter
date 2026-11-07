// Wire-level schema comparison against MTR3's own saves.
// Compares, per kind: key names AND the msgpack type opcode of every value
// (including nested rail_connections entries).
//
// usage: node schema.js <refSave> [<refSave2> ...] -- <ourSave>
const fs = require('fs'), path = require('path');

function readNode(buf, o) {
  const b = buf[o.p];
  if (b <= 0x7f) { o.p++; return { t: 'int+' }; }
  if (b >= 0xe0) { o.p++; return { t: 'int8' }; }
  if ((b & 0xf0) === 0x80) { const n = b & 0x0f; o.p++; return readMapNode(buf, o, n); }
  if ((b & 0xf0) === 0x90) { const n = b & 0x0f; o.p++; return readArrNode(buf, o, n); }
  if ((b & 0xe0) === 0xa0) { const n = b & 0x1f; o.p++; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
  switch (b) {
    case 0xc0: o.p++; return { t: 'nil' };
    case 0xc2: o.p++; return { t: 'bool', v: false };
    case 0xc3: o.p++; return { t: 'bool', v: true };
    case 0xc4: { o.p++; const n = buf[o.p++]; o.p += n; return { t: 'bin' }; }
    case 0xc5: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2 + n; return { t: 'bin' }; }
    case 0xc6: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4 + n; return { t: 'bin' }; }
    case 0xca: { o.p++; const v = buf.readFloatBE(o.p); o.p += 4; return { t: 'f32', v }; }
    case 0xcb: { o.p++; const v = buf.readDoubleBE(o.p); o.p += 8; return { t: 'f64', v }; }
    case 0xcc: { o.p++; const v = buf.readUInt8(o.p); o.p += 1; return { t: 'uint8', v }; }
    case 0xcd: { o.p++; const v = buf.readUInt16BE(o.p); o.p += 2; return { t: 'uint16', v }; }
    case 0xce: { o.p++; const v = buf.readUInt32BE(o.p); o.p += 4; return { t: 'uint32', v }; }
    case 0xcf: { o.p++; const v = buf.readBigUInt64BE(o.p); o.p += 8; return { t: 'uint64', v }; }
    case 0xd0: { o.p++; const v = buf.readInt8(o.p); o.p += 1; return { t: 'int8', v }; }
    case 0xd1: { o.p++; const v = buf.readInt16BE(o.p); o.p += 2; return { t: 'int16', v }; }
    case 0xd2: { o.p++; const v = buf.readInt32BE(o.p); o.p += 4; return { t: 'int32', v }; }
    case 0xd3: { o.p++; const v = buf.readBigInt64BE(o.p); o.p += 8; return { t: 'int64', v }; }
    case 0xd9: { o.p++; const n = buf[o.p++]; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
    case 0xda: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
    case 0xdb: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4; const s = buf.toString('utf8', o.p, o.p + n); o.p += n; return { t: 'str', v: s }; }
    case 0xdc: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2; return readArrNode(buf, o, n); }
    case 0xdd: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4; return readArrNode(buf, o, n); }
    case 0xde: { o.p++; const n = buf.readUInt16BE(o.p); o.p += 2; return readMapNode(buf, o, n); }
    case 0xdf: { o.p++; const n = buf.readUInt32BE(o.p); o.p += 4; return readMapNode(buf, o, n); }
    default: throw new Error('opcode 0x' + b.toString(16) + ' at ' + (o.p - 1));
  }
}
function readMapNode(buf, o, n) {
  const fields = {};
  for (let i = 0; i < n; i++) {
    const k = readNode(buf, o);
    if (k.t !== 'str') throw new Error('non-string key');
    fields[k.v] = readNode(buf, o);
  }
  return { t: 'map', fields };
}
function readArrNode(buf, o, n) {
  const items = [];
  for (let i = 0; i < n; i++) items.push(readNode(buf, o));
  return { t: 'array', items };
}

function top(buf) {
  const o = { p: 0 };
  const n = readNode(buf, o);
  if (n.t !== 'map') throw new Error('top-level is not a map');
  return n;
}

function walkFiles(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walkFiles(p, out); else out.push(p);
  }
  return out;
}

// Category of a msgpack value: widths are an encoder detail (MTR3 itself writes
// 0 as "int+" and -1 as "int8" for the same field), so only the category matters.
function cat(t) {
  if (/^(int|uint)/.test(t)) return 'int';
  if (/^f(32|64)$/.test(t)) return 'float';
  return t;
}

// per kind: key -> Set(categories); plus nested "<key>[]" entries.
// rail_connections are split by model_key, because the ANTE/NTE model-rail keys
// (path_mode/pos_start/facing_start/pos_end/facing_end/bezier_curve) are only
// written for rails that carry a model_key.
function collect(save, kind) {
  const files = walkFiles(path.join(save, 'mtr')).filter(f => f.replace(/\\/g, '/').includes('/' + kind + '/'));
  const keys = {}, nested = {};
  let ok = 0, bad = 0, conns = 0;
  for (const f of files) {
    try {
      const doc = top(fs.readFileSync(f));
      ok++;
      for (const [k, node] of Object.entries(doc.fields)) {
        (keys[k] = keys[k] || new Set()).add(cat(node.t));
        if (node.t === 'array' && node.items.length && node.items.every(i => i.t === 'map')) {
          const bucket = nested[k] = nested[k] || { all: {}, emptyModel: {}, withModel: {}, n: 0 };
          for (const item of node.items) {
            conns++;
            bucket.n++;
            const model = item.fields.model_key && item.fields.model_key.t === 'str' ? item.fields.model_key.v : '';
            const target = model === '' ? bucket.emptyModel : bucket.withModel;
            for (const [ik, iv] of Object.entries(item.fields)) {
              (bucket.all[ik] = bucket.all[ik] || new Set()).add(cat(iv.t));
              (target[ik] = target[ik] || new Set()).add(cat(iv.t));
            }
          }
        }
      }
    } catch (e) { bad++; }
  }
  return { files: files.length, ok, bad, keys, nested, conns };
}

function mergeInto(dst, src) {
  for (const [k, v] of Object.entries(src)) {
    (dst[k] = dst[k] || new Set());
    v.forEach(t => dst[k].add(t));
  }
}

const args = process.argv.slice(2);
const sep = args.indexOf('--');
const refs = args.slice(0, sep), ours = args[sep + 1];
const kinds = ['stations', 'platforms', 'routes', 'depots', 'sidings', 'rails', 'lifts', 'signal-blocks'];

const refCols = refs.map(r => Object.fromEntries(kinds.map(k => [k, collect(r, k)])));
const ourCol = Object.fromEntries(kinds.map(k => [k, collect(ours, k)]));

let badKinds = 0;
for (const kind of kinds) {
  const refKeys = {}, refNested = {};
  for (const c of refCols) {
    mergeInto(refKeys, c[kind].keys);
    for (const [nk, nv] of Object.entries(c[kind].nested)) {
      refNested[nk] = refNested[nk] || { all: {}, emptyModel: {}, withModel: {} };
      mergeInto(refNested[nk].all, nv.all);
      mergeInto(refNested[nk].emptyModel, nv.emptyModel);
      mergeInto(refNested[nk].withModel, nv.withModel);
    }
  }
  // "required" = keys every reference save writes; keys only some references write are optional.
  const refKeyCount = {};
  for (const c of refCols) {
    for (const k of Object.keys(c[kind].keys)) refKeyCount[k] = (refKeyCount[k] || 0) + 1;
  }
  const required = k => refKeyCount[k] === refCols.length;
  const our = ourCol[kind];

  if (our.files === 0) {
    console.log(`\n## ${kind}  [N/A]  源存档没有这类对象，输出为空（参照存档有 ${Object.keys(refKeys).length} 个键，但没有实例可比较）`);
    continue;
  }

  const optional = Object.keys(refKeys).filter(k => !required(k));
  const missing = Object.keys(refKeys).filter(k => required(k) && !(k in our.keys));
  const extra = Object.keys(our.keys).filter(k => !(k in refKeys));
  const typeDiff = [];
  const nestedLines = [];
  for (const k of Object.keys(our.keys)) {
    if (!(k in refKeys)) continue;
    const outside = [...our.keys[k]].filter(t => !refKeys[k].has(t));
    if (outside.length) typeDiff.push(`${k}: ours=${outside.join('/')} not in ref={${[...refKeys[k]].join(',')}}`);
  }
  // nested arrays (rail_connections): compare against reference connections that
  // also have an empty model_key; model-rail-only keys are listed separately.
  for (const nk of Object.keys(our.nested)) {
    const ourN = our.nested[nk];
    const refAll = { all: {}, emptyModel: {}, withModel: {} };
    for (const c of refCols) {
      const rn = c[kind].nested[nk];
      if (!rn) continue;
      mergeInto(refAll.all, rn.all);
      mergeInto(refAll.emptyModel, rn.emptyModel);
      mergeInto(refAll.withModel, rn.withModel);
    }
    const refN = refAll.emptyModel;              // reference connections without a model_key
    // Keys MTR3 3.6.4.2 itself reads for one rail connection: `Rail(Map)` string
    // constants (h_1 h_2 k_1 k_2 r_1 r_2 t_start_1 t_end_1 t_start_2 t_end_2 y_start
    // y_end reverse_t_1 is_straight_1 reverse_t_2 is_straight_2 rail_type
    // transport_mode rail_data) + `node_pos` from RailwayDataFileSaveModule$RailEntry.
    const CORE = ['node_pos', 'h_1', 'k_1', 'h_2', 'k_2', 'r_1', 'r_2', 't_start_1', 't_end_1',
      't_start_2', 't_end_2', 'y_start', 'y_end', 'reverse_t_1', 'is_straight_1',
      'reverse_t_2', 'is_straight_2', 'rail_type', 'transport_mode', 'rail_data'];
    const nMissing = CORE.filter(k => !(k in ourN.all));
    const nExtra = Object.keys(ourN.all).filter(k => !(k in refAll.all));
    if (nMissing.length) missing.push(`${nk}[].${nMissing.join(',')}`);
    if (nExtra.length) extra.push(`${nk}[].${nExtra.join(',')}`);
    for (const k of Object.keys(ourN.all)) {
      if (!(k in refAll.all)) continue;
      const outside = [...ourN.all[k]].filter(t => !refAll.all[k].has(t));
      if (outside.length) typeDiff.push(`${nk}[].${k}: ours=${outside.join('/')} not in ref={${[...refAll.all[k]].join(',')}}`);
    }
    nestedLines.push(`  nested ${nk}[]: ours ${Object.keys(ourN.all).length} keys / ${ourN.n} conns`
      + ` | MTR3-core keys ${CORE.length}: missing=${JSON.stringify(nMissing)}`
      + ` | ref-union ${Object.keys(refAll.all).length} keys, addon-only in refs=${JSON.stringify(Object.keys(refAll.all).filter(k => !CORE.includes(k) && !(k in ourN.all)))}`);
  }

  const ok = missing.length === 0 && extra.length === 0 && typeDiff.length === 0;
  if (!ok) badKinds++;
  console.log(`\n## ${kind}  [${ok ? 'MATCH' : 'DIFF'}]  files=${our.files}  requiredKeys=${Object.keys(refKeys).filter(required).length}`);
  console.log(`  missing=${JSON.stringify(missing)}`);
  console.log(`  extra  =${JSON.stringify(extra)}`);
  console.log(`  typeDiff=${JSON.stringify(typeDiff)}`);
  if (optional.length) console.log(`  optionalKeysOnlySomeRefsWrite=${JSON.stringify(optional)}`);
  nestedLines.forEach(l => console.log(l));
}
console.log(`\nSUMMARY kinds differing: ${badKinds} / ${kinds.filter(k => ourCol[k].files > 0).length} compared`);
process.exit(badKinds === 0 ? 0 : 1);
