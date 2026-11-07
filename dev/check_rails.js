// verify the generated MTR3 rail graph: symmetry + endpoint geometry
const fs = require('fs'), path = require('path');
const { execSync } = require('child_process');

function dec(buf, o) { /* minimal decoder copied from dump.js */ 
  const b = buf[o.p++];
  function rs(n){const s=buf.toString('utf8',o.p,o.p+n);o.p+=n;return s;}
  function rm(n){const m={};for(let i=0;i<n;i++){const k=dec(buf,o);const v=dec(buf,o);m[String(k)]=v;}return m;}
  function ra(n){const a=[];for(let i=0;i<n;i++)a.push(dec(buf,o));return a;}
  if(b<=0x7f)return b; if(b>=0xe0)return b-256;
  if((b&0xf0)===0x80)return rm(b&0x0f);
  if((b&0xf0)===0x90)return ra(b&0x0f);
  if((b&0xe0)===0xa0)return rs(b&0x1f);
  switch(b){
    case 0xc0:return null; case 0xc2:return false; case 0xc3:return true;
    case 0xca:{const v=buf.readFloatBE(o.p);o.p+=4;return v;}
    case 0xcb:{const v=buf.readDoubleBE(o.p);o.p+=8;return v;}
    case 0xcc:{const v=buf.readUInt8(o.p);o.p+=1;return v;}
    case 0xcd:{const v=buf.readUInt16BE(o.p);o.p+=2;return v;}
    case 0xce:{const v=buf.readUInt32BE(o.p);o.p+=4;return v;}
    case 0xcf:{const v=buf.readBigUInt64BE(o.p);o.p+=8;return v;}
    case 0xd0:{const v=buf.readInt8(o.p);o.p+=1;return v;}
    case 0xd1:{const v=buf.readInt16BE(o.p);o.p+=2;return v;}
    case 0xd2:{const v=buf.readInt32BE(o.p);o.p+=4;return v;}
    case 0xd3:{const v=buf.readBigInt64BE(o.p);o.p+=8;return v;}
    case 0xd9:return rs(buf[o.p++]);
    case 0xda:{const n=buf.readUInt16BE(o.p);o.p+=2;return rs(n);}
    case 0xdb:{const n=buf.readUInt32BE(o.p);o.p+=4;return rs(n);}
    case 0xdc:{const n=buf.readUInt16BE(o.p);o.p+=2;return ra(n);}
    case 0xdd:{const n=buf.readUInt32BE(o.p);o.p+=4;return ra(n);}
    case 0xde:{const n=buf.readUInt16BE(o.p);o.p+=2;return rm(n);}
    case 0xdf:{const n=buf.readUInt32BE(o.p);o.p+=4;return rm(n);}
    default: throw new Error('opcode '+b.toString(16));
  }
}
function unpack(v){const b=BigInt.asUintN(64,BigInt(v));
  let x=Number(BigInt.asIntN(26,(b>>38n)&0x3FFFFFFn));
  let z=Number(BigInt.asIntN(26,(b>>12n)&0x3FFFFFFn));
  let y=Number(BigInt.asIntN(12,b&0xFFFn));return [x,y,z];}

const root = process.argv[2];
const railsDir = path.join(root, 'mtr/minecraft/overworld/rails');
const nodes = new Map(); const edges = [];
function walk(d){for(const f of fs.readdirSync(d)){const p=path.join(d,f);const st=fs.statSync(p);
  if(st.isDirectory())walk(p); else {
    const buf=fs.readFileSync(p); const v=dec(buf,{p:0});
    const node=String(v.node_pos); nodes.set(node, Number(v.node_pos));
    for(const c of v.rail_connections){ edges.push({a:node,b:String(c.node_pos),c}); }
  }}}
walk(railsDir);
console.log('nodes', nodes.size, 'directed edges', edges.length);
let asym=0;
for(const e of edges){ if(!edges.find(x=>x.a===e.b&&x.b===e.a)) { asym++; console.log('  missing reverse:', e.a,'->',e.b); } }
console.log('asymmetric edges', asym);
// geometry: each edge start/end must be distinct node positions
let bad=0;
for(const e of edges){
  const s=unpack(e.a), t=unpack(e.b), c=e.c;
  const len=Math.abs(c.t_end_2-c.t_start_2)+Math.abs(c.t_end_1-c.t_start_1);
  if(!(len>0)) { bad++; console.log('  zero length', e.a, e.b); }
}
console.log('zero-length', bad);
console.log('rail types', JSON.stringify(edges.reduce((m,e)=>{m[e.c.rail_type]=(m[e.c.rail_type]||0)+1;return m;},{})));
