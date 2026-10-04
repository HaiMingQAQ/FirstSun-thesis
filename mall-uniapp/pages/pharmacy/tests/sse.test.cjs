const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../sheep/api/pharmacy/sse.js'), 'utf8');
const decoderSource = source.split('export function streamConsult')[0];
const loadDecoder = () => new Function(decoderSource.replace('export function', 'function') + '; return createSseDecoder;')();
test('split every UTF-8 byte and SSE boundary including Chinese, emoji, CRLF and multiline JSON', () => {
  const events=[]; const decoder=loadDecoder()((event,data)=>events.push([event,data]));
  const bytes=Buffer.from('event: delta\r\ndata: {"text":"感冒🙂"}\r\n\r\nevent: done\ndata: {"status":\ndata: "SUCCESS"}\n\n');
  for(const b of bytes)decoder.push(Uint8Array.of(b).buffer);
  decoder.finish();assert.deepEqual(events,[['delta',{text:'感冒🙂'}],['done',{status:'SUCCESS'}]]);
});
test('rejects truncated response, invalid UTF-8 and malformed JSON without exposing contents', () => {
  const make=()=>loadDecoder()(()=>{});
  const truncated=make();truncated.push('event: delta\ndata: {"text":"一半"}\n\n');assert.throws(()=>truncated.finish(),/回复中断/);
  assert.throws(()=>make().push(Uint8Array.of(0xff).buffer),/编码/);
  assert.throws(()=>make().push('data: private-invalid-json\n\n'),error=>!error.message.includes('private-invalid-json'));
});
function moduleFor(platform, timers = { setTimeout, clearTimeout }) {
  const lines=source.split('\n'), output=[];let include=true;
  for(const line of lines){
    if(line.includes('// #ifdef H5')){include=platform==='H5';continue;}
    if(line.includes('// #ifndef H5')){include=platform!=='H5';continue;}
    if(line.includes('// #endif')){include=true;continue;}
    if(include)output.push(line);
  }
  return new Function('setTimeout', 'clearTimeout', output.join('\n').replaceAll('export function','function')+';return {streamConsult,createStreamController};')(timers.setTimeout, timers.clearTimeout);
}
test('WeChat transport sends auth, receives incremental chunks and avoids parsing duplicate final body', async () => {
  const events=[];const {streamConsult}=moduleFor('MP');let handler,aborted=false;
  global.uni={request(options){
    assert.equal(options.enableChunked,true);assert.equal(options.header.Authorization,'Bearer test');
    setTimeout(()=>{
      const a=Uint8Array.from(Buffer.from('event: delta\ndata: {"text":"正在"}\n\n'));handler({data:a.buffer});
      const b=Uint8Array.from(Buffer.from('event: done\ndata: {"status":"SUCCESS","answer":"正在"}\n\n'));handler({data:b.buffer});
      options.success({statusCode:200,data:b.buffer});
    },0);
    return {onChunkReceived(fn){handler=fn;},abort(){aborted=true;}};
  }};
  const result=await streamConsult('/test',{content:'问药'},{Authorization:'Bearer test'},(event)=>events.push(event));
  assert.equal(result.status,'SUCCESS');assert.deepEqual(events,['delta','done']);assert.equal(aborted,true);
});
test('WeChat cancellation aborts request and rejects without accepting late data', async () => {
  const {streamConsult,createStreamController}=moduleFor('MP');let aborted=false,handler;
  global.uni={request(){return {onChunkReceived(fn){handler=fn;},abort(){aborted=true;}};}};
  const controller=createStreamController();const events=[];
  const result=streamConsult('/test',{}, {},event=>events.push(event),controller.signal);
  controller.abort();await assert.rejects(result,/已停止/);
  handler({data:Uint8Array.from(Buffer.from('event: done\ndata: {}\n\n')).buffer});
  assert.equal(aborted,true);assert.deepEqual(events,[]);
});
test('H5 consumes a readable stream incrementally and rejects missing completion', async () => {
  const {streamConsult}=moduleFor('H5');const events=[];
  global.fetch=async()=>new Response(new ReadableStream({start(controller){
    controller.enqueue(Uint8Array.from(Buffer.from('event: delta\ndata: {"text":"你好"}\n\n')));
    controller.enqueue(Uint8Array.from(Buffer.from('event: done\ndata: {"status":"SUCCESS"}\n\n')));controller.close();
  }}),{headers:{'content-type':'text/event-stream'}});
  assert.equal((await streamConsult('/test',{}, {},event=>events.push(event))).status,'SUCCESS');assert.deepEqual(events,['delta','done']);
  global.fetch=async()=>new Response('event: delta\ndata: {"text":"中断"}\n\n',{headers:{'content-type':'text/event-stream'}});
  await assert.rejects(streamConsult('/test',{}, {},()=>{}),/中断/);
});

test('SSE done completes both transports without waiting for a delayed HTTP close', async () => {
  let cancelled = false;
  global.fetch = async () => new Response(new ReadableStream({ start(controller) {
    controller.enqueue(Uint8Array.from(Buffer.from('event: done\ndata: {"status":"FAILED","answer":"部分回复"}\n\n')));
  }, cancel() { cancelled = true; } }), { headers: { 'content-type': 'text/event-stream' } });
  assert.equal((await moduleFor('H5').streamConsult('/test', {}, {}, () => {})).status, 'FAILED');
  await new Promise(resolve => setImmediate(resolve)); assert.equal(cancelled, true);
  let receive, aborted = false;
  global.uni = { request() { return { onChunkReceived(fn) { receive = fn; }, abort() { aborted = true; } }; } };
  const pending = moduleFor('MP').streamConsult('/test', {}, {}, () => {});
  receive({ data: Uint8Array.from(Buffer.from('event: done\ndata: {"status":"SUCCESS"}\n\n')).buffer });
  assert.equal((await pending).status, 'SUCCESS'); assert.equal(aborted, true);
});

test('H5 hanging stream times out and aborts the network request', async () => {
  let expire, aborted = false, cleared = false;
  global.fetch = (_, options) => new Promise((_, reject) => options.signal.addEventListener('abort', () => { aborted = true; reject(new Error('private transport error')); }));
  const timers = { setTimeout(fn, delay) { assert.equal(delay, 90000); expire = fn; return 1; }, clearTimeout(id) { cleared = id === 1; } };
  const pending = moduleFor('H5', timers).streamConsult('/test', {}, {}, () => {});
  expire(); await assert.rejects(pending, /等待超时/); assert.equal(aborted, true); assert.equal(cleared, true);
});

test('expired login and forbidden access retain safe status codes on H5 and WeChat', async () => {
  global.fetch = async () => new Response(JSON.stringify({ code: 401, msg: 'private error' }), { headers: { 'content-type': 'application/json' } });
  await assert.rejects(moduleFor('H5').streamConsult('/test', {}, {}, () => {}), e => e.code === 401 && !e.message.includes('private'));
  global.uni = { request(options) {
    setTimeout(() => options.success({ statusCode: 200, data: Uint8Array.from(Buffer.from(JSON.stringify({ code: 403 }))).buffer }), 0);
    return { onChunkReceived() {}, abort() {} };
  } };
  await assert.rejects(moduleFor('MP').streamConsult('/test', {}, {}, () => {}), e => e.code === 403);
});

test('late SSE 401 from an old account or token does not clear the current session', async () => {
  const server = fs.readFileSync(path.join(__dirname, '../../../sheep/api/pharmacy/server.js'), 'utf8');
  const method = server.slice(server.indexOf('  async consultStream('), server.indexOf('  async prescriptions(')).trim().replace(/,$/, '');
  for (const change of ['account', 'token', 'none']) {
    let owner = 'A', token = 'token-A', cleared = false, reject;
    const api = { session: () => ({ id: owner }) };
    const pending = new Promise((_, fail) => { reject = fail; });
    const run = new Function('api', 'draftOwner', 'ensureStore', 'streamConsult', 'uni', 'clearAuthSession', 'baseUrl', 'apiPath', 'tenantId', `return ({${method}}).consultStream;`)(api, () => owner, async () => ({ id: 407 }), () => pending, { getStorageSync: key => key === 'token' ? token : 163 }, () => { cleared = true; }, '', '', 163);
    const result = run({ content: '查询', clientMessageId: 'test' }, () => {});
    await Promise.resolve();
    if (change === 'account') owner = 'B';
    if (change !== 'none') token = 'token-B';
    reject(Object.assign(new Error('expired'), { code: 401 }));
    await assert.rejects(result);
    assert.equal(cleared, change === 'none');
  }
});
