// Run the locally locked compiler with this existing project as its input root.
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { isDeployableHttpsUrl } = require('./production-url.cjs');
const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
if (args.includes('build') && args.includes('mp-weixin')) {
  const { loadEnv } = require('vite');
  const env = loadEnv('production', root, 'SHOPRO_');
  const required = ['SHOPRO_BASE_URL', 'SHOPRO_TRIAL_BASE_URL', 'SHOPRO_STATIC_URL', 'SHOPRO_H5_URL'];
  const missing = required.filter((key) => !isDeployableHttpsUrl(env[key]));
  if (missing.length) {
    console.error(`微信发布构建缺少有效的 HTTPS 部署域名：${missing.join(', ')}。请配置 mall-uniapp/.env.production 或同名环境变量；localhost、localhost. 及 IP 地址不可用于发布构建。本机模拟器请使用 npm run dev:mp-weixin。`);
    process.exit(1);
  }
}
const result = spawnSync(process.execPath,
  [require.resolve('@dcloudio/vite-plugin-uni/bin/uni.js'), ...args], {
    cwd: root,
    env: { ...process.env, UNI_INPUT_DIR: root },
    stdio: 'inherit',
  });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
