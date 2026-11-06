# External SQL adapter contract

The application-specific reconciliation adapter is not delivered in this repository. It must be implemented, placed outside the repository, independently reviewed, and pinned by `E2E_RECONCILER_SHA256`. Without it, readiness is blocked and the harness cannot complete. This document specifies the code-level contract; it does not claim that the SQL semantics have been implemented or reviewed.

The configured module must be importable as an ES module and export `contractVersion = 1`, `supportedStages`, `sqlQueries`, and the three async functions `preflight`, `reconcileStage`, and `snapshotAll`. `supportedStages` must include exactly the required stages `afterCreate`, `afterMockPay`, `afterStartPicking`, `afterFinishPicking`, and `afterVerify` (additional entries do not replace these).

## Fixed query catalog

`sqlQueries` is a non-empty object keyed by adapter-chosen stable names. Each value has this shape:

```js
{
  sql: 'SELECT ... WHERE tenant_id = ? AND id = ?',
  tables: ['order_table'],
  scope: 'order', // 'fixture' or 'order'
  family: 'order'
}
```

The harness validates that each query is one `SELECT`, contains no SQL comments or statement separators, uses no forbidden write/administrative keywords, and that the declared table list matches its `FROM`/`JOIN` tables. Calls go through the harness-provided `select(name, params)` function; adapter code must not open another connection, accept caller-supplied SQL, interpolate values, or perform writes. `params` must be an array of string, number, bigint, or null values, with one value per `?` placeholder. Values are bound with the MySQL driver.

During `reconcileStage` and `snapshotAll`, the supplied scoped `select` requires the first two bound parameters to be `[tenantId, orderId]`, where `orderId` is the new order created by this run. Further values, such as line or ledger identifiers, follow those two parameters. Preflight uses the catalog-backed `select` too, but its fixture/identity parameter order is adapter-defined and must be documented by the reviewed implementation. Use `scope: 'fixture'` for preflight-only queries and `scope: 'order'` for order-ledger queries.

The ten core ledger families are `order`, `payment`, `paymentExtension`, `inventoryAllocations`, `inventoryFlows`, `sale`, `salePayment`, `points`, `notification`, and the preterminal sentinel `saleAbsent`. Creation additionally requires the stage-specific `orderLines` and `cartCleared` families. Preflight must execute queries in all of these families: `tenant`, `store`, `member`, `employee`, `permissions`, `drug`, `stock`, and `mock`. Family labels and query shape are mechanically checked; their table meaning, tenant scoping, correct joins, ledger completeness, and expected states require substantive code review.

## Preflight

`preflight` receives:

```js
{
  tenantId, storeId, fixtureDrugId, fixtureDrugName, adminTenantName,
  memberId, adminUserId,
  database: { host, port, database, user, identity, grants },
  environmentId, runId, review,
  endpoints: { customer, admin, api },
  select
}
```

It returns `{ checks, fixture }`. `checks` must contain passing entries for all IDs `isolation`, `databaseReadOnly`, `fixtures`, `permissions`, `mockCapability`, and `replayApproval`. These must reflect real, scoped DB and stack checks, not asserted constants. `fixture` must report `drugId`, `storeId`, exact `name`, `rx: false`, `device: false`, `availableQty` at least the configured quantity, and the configured `memberId` and `adminUserId`. Readiness independently checks those values and verifies that all eight preflight query families were actually read.

## Stage reconciliation

`reconcileStage` receives `{ stage, tenantId, storeId, orderId, orderNo, expected, select }`. `expected` contains `memberId`, `status`, `payStatus`, `orderType`, `payableAmountYuan`, `payableAmountFen`, `quantity`, `drugId`, and `finishAt`. Compare amounts exactly; the harness derives integer fen from the UI/API amount and passes both representations.

Return `{ stage, pass: true, checks, observedOrder }`, where `checks` contains passing, meaningful named checks for every required family listed below, and `observedOrder` contains `tenantId`, `memberId`, `storeId`, `id`, `orderNo`, `payableAmountFen`, `status`, and `payStatus`. The harness checks these fields against the API/UI checkpoint and verifies that a query carrying every required family was actually executed.

| Stage | Required ledger query families |
| --- | --- |
| `afterCreate` | `order`, `orderLines`, `cartCleared` |
| `afterMockPay` | `order`, `payment`, `paymentExtension`, `inventoryAllocations`, `inventoryFlows`, `saleAbsent` |
| `afterStartPicking` | `order`, `payment`, `paymentExtension`, `inventoryAllocations`, `inventoryFlows`, `saleAbsent` |
| `afterFinishPicking` | `order`, `payment`, `paymentExtension`, `inventoryAllocations`, `inventoryFlows`, `saleAbsent` |
| `afterVerify` | `order`, `payment`, `paymentExtension`, `inventoryAllocations`, `inventoryFlows`, `sale`, `salePayment`, `points`, `notification` |

The names of SQL catalog entries are adapter-defined; the `family` of the executed entry must match the required family. A family label or passing boolean alone is not semantic reconciliation.

## Full snapshot and boundaries

`snapshotAll` receives `{ tenantId, storeId, orderId, orderNo, select }` and returns an object with all nine keys: `order`, `payment`, `paymentExtension`, `inventoryAllocations`, `inventoryFlows`, `sale`, `salePayment`, `points`, and `notification`. Each returned family must have at least one matching executed SQL read. Snapshot rows must be deterministic and complete for the single new order so the harness can compare canonical values before and after replay.

The harness replays only mock payment and verification for this same completed order, then checks that the full snapshot is unchanged and reads the terminal UIs again. This demonstrates only the reviewed same-order no-op behavior for those operations; it does not establish general idempotency or cover an external payment provider.

The query restrictions constrain how the adapter accesses SQL, not whether its SQL expresses correct business semantics. Independently review the adapter implementation and query results, the account's effective MySQL `SELECT`/`USAGE` grants and privileges, and the isolated stack manifest. The harness inspects reported grants and active roles, but a manager must independently verify the account permissions and review the adapter and stack evidence before approving use. No complete SQL semantic reconciliation adapter is currently provided.
