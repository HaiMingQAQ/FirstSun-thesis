function option(args, names) {
  const values = [];
  for (let i = 0; i < args.length; i++) {
    for (const name of names) {
      if (args[i] === name) values.push(args[i + 1]);
      else if (args[i].startsWith(name + '=')) values.push(args[i].slice(name.length + 1));
    }
  }
  if (values.length > 1) throw new Error('测试登录构建不能重复指定平台或模式');
  return values[0];
}

const buildMode = args => option(args, ['--mode']) || (args.includes('build') ? 'production' : 'development');
function localBackend(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash) return '';
    return url.origin;
  } catch { return ''; }
}

function validateH5TestLogin(args, env) {
  if (env.SHOPRO_H5_TEST_LOGIN !== 'true') return false;
  if (option(args, ['-p', '--platform']) !== 'h5' || buildMode(args) !== 'local-acceptance')
    throw new Error('测试登录仅允许 -p h5 --mode local-acceptance；普通、发布及微信构建不得开启 SHOPRO_H5_TEST_LOGIN');
  const backend = localBackend(env.SHOPRO_H5_TEST_BACKEND_URL);
  if (!backend || localBackend(env.SHOPRO_BASE_URL) !== backend || localBackend(env.SHOPRO_DEV_BASE_URL) !== backend)
    throw new Error('测试登录须显式设置 SHOPRO_H5_TEST_BACKEND_URL，SHOPRO_BASE_URL 与 SHOPRO_DEV_BASE_URL 须指向同一无凭据的 HTTP 回环后端');
  return true;
}

module.exports = { buildMode, validateH5TestLogin };
