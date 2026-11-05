'use strict';

function mysqlDiagnostics(result) {
  const stderr = String(result.stderr || '');
  const match = stderr.match(/\bERROR\s+(\d{3,5})\s+\(([0-9A-Z]{5})\)/i);
  const processCode = result.error && result.error.code;
  const signal = result.signal;
  return {
    exitCode: Number.isInteger(result.status) ? result.status : null,
    ...(typeof processCode === 'string' && /^[A-Z0-9_]{1,24}$/.test(processCode)
      ? { processErrorCode: processCode } : {}),
    ...(typeof signal === 'string' && /^[A-Z0-9]{1,16}$/.test(signal)
      ? { signal } : {}),
    ...(match ? { mysqlErrorCode: Number(match[1]), sqlState: match[2].toUpperCase() } : {})
  };
}

module.exports = { mysqlDiagnostics };
