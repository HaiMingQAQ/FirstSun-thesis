
const FORBIDDEN_SQL = /\b(insert|update|delete|replace|merge|create|alter|drop|truncate|grant|revoke|call|execute|prepare|deallocate|outfile|dumpfile|load_file|lock|unlock|set|use|handler|into)\b/i
const IDENTITY_SQL = 'SELECT DATABASE() AS databaseName, CURRENT_USER() AS account, @@hostname AS serverHost, @@port AS serverPort, @@server_uuid AS serverUuid'
const GRANTS_SQL = `SELECT PRIVILEGE_TYPE AS privilegeType, IS_GRANTABLE AS isGrantable FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',1)),'@',QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',-1)))
UNION ALL SELECT PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',1)),'@',QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',-1)))
UNION ALL SELECT PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',1)),'@',QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',-1)))
UNION ALL SELECT PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE=CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',1)),'@',QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',-1)))`

function validateQueryCatalog(catalog) {
  if (!catalog || typeof catalog !== 'object' || Array.isArray(catalog) || !Object.keys(catalog).length) throw new Error('SQL adapter must declare a fixed named sqlQueries catalog')
  const verified = new Map()
  for (const [name, spec] of Object.entries(catalog)) {
    if (!/^[a-z][a-zA-Z0-9]{1,63}$/.test(name) || typeof spec?.sql !== 'string' || !Array.isArray(spec.tables) || !['fixture', 'order'].includes(spec.scope) || typeof spec.family !== 'string') throw new Error('Invalid named SQL allowlist entry')
    const sql = spec.sql.trim()
    if (!/^select\b/i.test(sql) || /;|--|#|\/\*/.test(sql) || FORBIDDEN_SQL.test(stripLiterals(sql))) throw new Error(`SQL allowlist entry ${name} is not a single read-only SELECT`)
    if (spec.tables.some(table => !/^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)?$/.test(table))) throw new Error(`SQL allowlist entry ${name} has an invalid table name`)
    const foundTables = [...stripLiterals(sql).matchAll(/\b(?:from|join)\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)?)/gi)].map(match => match[1].toLowerCase())
    const expectedTables = spec.tables.map(table => table.toLowerCase())
    if (foundTables.some(table => !expectedTables.includes(table)) || expectedTables.some(table => !foundTables.includes(table)) || (expectedTables.length === 0 && foundTables.length !== 0)) throw new Error(`SQL allowlist entry ${name} table list does not match its query`)
    verified.set(name, sql)
  }
  return verified
}

function stripLiterals(sql) { return sql.replace(/'(?:''|[^'])*'|"(?:""|[^"])*"/g, "''") }

async function createSqlAccess(config, adapter) {
  const catalog = validateQueryCatalog(adapter.sqlQueries)
  const connection = await require('mysql2/promise').createConnection({
    host: config.db.host, port: config.db.port, database: config.db.database,
    user: config.db.user, password: config.db.password, connectTimeout: config.timeoutMs,
    multipleStatements: false, namedPlaceholders: false
  })
  const [identityRows] = await connection.execute(IDENTITY_SQL)
  const identity = identityRows[0]
  if (!identity || identity.databaseName !== config.db.database || !identity.serverUuid || !identity.account) {
    await connection.end()
    throw new Error('Connected MySQL server identity did not match configured database and port')
  }
  const [grantRows] = await connection.execute(GRANTS_SQL)
  const [roleRows] = await connection.execute('SELECT CURRENT_ROLE() AS activeRoles')
  const grants = [...new Set(grantRows.map(row => String(row.privilegeType).toUpperCase()))]
  if (roleRows[0]?.activeRoles !== 'NONE' || !grants.includes('SELECT') || grants.some(privilege => privilege !== 'SELECT' && privilege !== 'USAGE') || grantRows.some(row => row.isGrantable === 'YES')) {
    await connection.end()
    throw new Error('Database account has a privilege outside SELECT/USAGE')
  }
  const reads = []
  const select = async (name, params = []) => {
    const sql = catalog.get(name)
    if (!sql) throw new Error(`SQL query is not allowlisted: ${name}`)
    if (!Array.isArray(params) || params.some(value => !['string', 'number', 'bigint'].includes(typeof value) && value !== null)) throw new Error(`SQL query parameters are invalid: ${name}`)
    if ((stripLiterals(sql).match(/\?/g) || []).length !== params.length) throw new Error(`SQL parameter count mismatch: ${name}`)
    const [rows] = await connection.execute({ sql, timeout: config.timeoutMs }, params)
    reads.push({ name, scope: adapter.sqlQueries[name].scope, family: adapter.sqlQueries[name].family, params, rowCount: rows.length })
    return rows
  }
  return { select, reads, identity, grants, close: () => connection.end() }
}

module.exports = { validateQueryCatalog, createSqlAccess }
