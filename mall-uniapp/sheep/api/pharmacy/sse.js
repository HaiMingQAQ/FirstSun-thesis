// Incremental UTF-8 + SSE decoder. WeChat does not always provide TextDecoder.
export function createSseDecoder(onEvent) {
  let bytes = [], text = '', event = 'message', data = [], finished = false;
  function line(value) {
    if (!value) {
      if (data.length) {
        let payload;
        try { payload = JSON.parse(data.join('\n')); } catch { throw new Error('回复格式异常，请重试'); }
        onEvent(event, payload);
        if (event === 'done') finished = true;
      }
      event = 'message'; data = [];
    } else if (value.startsWith('event:')) event = value.slice(6).trim();
    else if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''));
  }
  function push(chunk) {
    if (typeof chunk === 'string') text += chunk;
    else {
      bytes = bytes.concat(Array.from(new Uint8Array(chunk)));
      let i = 0;
      while (i < bytes.length) {
        const first = bytes[i], length = first < 128 ? 1 : first >= 194 && first <= 223 ? 2 : first >= 224 && first <= 239 ? 3 : first >= 240 && first <= 244 ? 4 : 0;
        if (!length) throw new Error('回复编码无效');
        if (i + length > bytes.length) break;
        let point = first & (length === 1 ? 127 : length === 2 ? 31 : length === 3 ? 15 : 7);
        for (let j = 1; j < length; j++) {
          if ((bytes[i + j] & 192) !== 128) throw new Error('回复编码无效');
          point = (point << 6) | (bytes[i + j] & 63);
        }
        if ((length === 2 && point < 128) || (length === 3 && point < 2048) || (length === 4 && point < 65536) || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) throw new Error('回复编码无效');
        text += point > 65535 ? String.fromCharCode(0xd800 + ((point - 65536) >> 10), 0xdc00 + ((point - 65536) & 1023)) : String.fromCharCode(point);
        i += length;
      }
      bytes = bytes.slice(i);
    }
    if (text.length > 50000) throw new Error('回复分块过长');
    let newline;
    while ((newline = text.indexOf('\n')) >= 0) {
      line(text.slice(0, newline).replace(/\r$/, '')); text = text.slice(newline + 1);
    }
  }
  return { push, finish() { if (bytes.length || !finished) throw new Error('回复中断，请重试'); }, get finished() { return finished; }, get pendingText() { return text; } };
}

export function streamConsult(url, body, headers, onEvent, signal) {
  const requestError = code => {
    const error = new Error(code === 401 ? '登录已失效，请重新登录' : code === 403 ? '没有权限使用当前门店的咨询' : '咨询请求失败，请重试');
    error.code = code; return error;
  };
  return new Promise((resolve, reject) => {
    let result, task, settled = false, streamed = false;
    const finish = (error) => {
      if (settled) return;
      settled = true; signal?.removeEventListener('abort', abort);
      if (error) reject(error); else resolve(result);
    };
    const decoder = createSseDecoder((event, data) => {
      if (signal?.aborted) return;
      if (event === 'error') throw new Error('咨询暂不可用，请稍后重试');
      if (event === 'done') result = data;
      onEvent(event, data);
    });
    const abort = () => { task?.abort?.(); finish(new Error('已停止生成')); };
    if (signal?.aborted) { abort(); return; }
    signal?.addEventListener('abort', abort);
    // #ifdef H5
    (async () => {
      try {
        const response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), signal });
        if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) { const body = await response.json().catch(() => ({})); throw requestError(body.code || response.status); }
        const reader = response.body.getReader();
        try {
          while (true) { const { done, value } = await reader.read(); if (done) break; decoder.push(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength)); }
          if (!decoder.finished) {
            let body; try { body = JSON.parse(decoder.pendingText); } catch { }
            if (body?.code) throw requestError(body.code);
          }
          decoder.finish(); finish();
        } finally { await reader.cancel().catch(() => {}); }
      } catch (error) { finish(signal?.aborted ? new Error('已停止生成') : error); }
    })();
    return;
    // #endif
    // #ifndef H5
    task = uni.request({ url, method: 'POST', data: body, header: headers, timeout: 90000, enableChunked: true, responseType: 'arraybuffer',
      success(response) {
        try {
          if (response.statusCode !== 200) throw requestError(response.statusCode);
          if (!streamed && response.data) decoder.push(response.data);
          if (!decoder.finished) {
            let body; try { body = JSON.parse(decoder.pendingText); } catch { }
            if (body?.code) throw requestError(body.code);
          }
          decoder.finish(); finish();
        } catch (error) { finish(error); }
      },
      fail() { finish(new Error(signal?.aborted ? '已停止生成' : '网络异常，请重试')); },
    });
    if (!task.onChunkReceived) { task.abort(); finish(new Error('当前基础库不支持流式回复，请升级微信开发工具')); return; }
    task.onChunkReceived(({ data }) => {
      if (settled) return;
      try { streamed = true; decoder.push(data); }
      catch (error) { finish(error); task.abort(); }
    });
    // #endif
  });
}

export function createStreamController() {
  if (typeof AbortController !== 'undefined') return new AbortController();
  const listeners = new Set();
  const signal = { aborted: false, addEventListener: (_, fn) => listeners.add(fn), removeEventListener: (_, fn) => listeners.delete(fn) };
  return { signal, abort() { if (!signal.aborted) { signal.aborted = true; for (const fn of listeners) fn(); listeners.clear(); } } };
}
