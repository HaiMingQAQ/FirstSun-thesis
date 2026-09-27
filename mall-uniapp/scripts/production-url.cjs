const { isIP } = require('node:net');

function isDeployableHttpsUrl(value) {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase().replace(/\.+$/, '').replace(/^\[|\]$/g, '');
    return url.protocol === 'https:' && !!host && !url.username && !url.password
      && host !== 'localhost' && !host.endsWith('.localhost') && isIP(host) === 0;
  } catch (_) {
    return false;
  }
}

module.exports = { isDeployableHttpsUrl };
