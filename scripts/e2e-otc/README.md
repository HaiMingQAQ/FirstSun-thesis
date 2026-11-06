# OTC UI acceptance harness

This harness is a fail-closed Playwright flow for one ordinary OTC, store-pickup order in an explicitly isolated local stack. It is scoped to tenant `901` and store `9101`; payment is mock-only. Customer and admin use separate browser contexts and perform real UI login. The harness does not use `storageState`, token login, or `data-testid` selectors. It is not a production payment or general-purpose acceptance runner.

The implementation has not been run end to end against a browser or application stack, and no `PASS_OTC_UI` result has been produced. A successful static check is not a readiness result. The application-specific SQL reconciliation adapter and the independently reviewed isolated-stack manifest are not included here. Without both, precheck is blocked and the full flow cannot run; do not describe the stack as ready based on this repository alone.

## Setup and commands

Use Node.js `>=24 <25`. Copy `env.example` outside the repository, replace every placeholder, ensure the target file is owner-only (`chmod 600` on Unix), and invoke commands with its absolute path. Do not commit the populated environment file. The configured evidence root must also be absolute and outside the repository.

```sh
cd scripts/e2e-otc
npm ci
npm run setup -- --env-file /absolute/path/to/otc.env
npm run readiness -- --env-file /absolute/path/to/otc.env
npm run oneclick -- --env-file /absolute/path/to/otc.env
npm run lint
```

`setup` accepts the CLI env-file option and installs the Playwright Chromium browser; it does not validate readiness. `readiness` checks the reviewed stack, endpoints, database account, fixture and login UI without running the order flow. `oneclick` repeats readiness and then runs the UI flow. `lint` checks JavaScript syntax. These commands have not been run against a real local stack in this change. On another machine paths and available loopback ports can differ; the current coder's test environment is this local machine, and its readiness has not been verified.

For CLI commands, exit `0` means the requested command completed successfully (`oneclick` emits `PASS_OTC_UI` only after all required evidence is present); `1` means flow/assertion failure; `2` means configuration or precheck blocked; `3` means required evidence could not be created or verified. `setup` and `lint` have their own command outcomes and do not establish readiness.

## Isolation and configuration

The env file requires an explicit environment identity and write confirmation, customer/admin/API loopback URLs, fixed tenant/store IDs, a unique `otc-` run ID, an external artifact root, exact ordinary OTC fixture ID/name/quantity, dedicated member and admin IDs, customer and admin login credentials, admin tenant name, read-only loopback database connection, adapter module and SHA-256, stack review file, and same-order replay confirmation. `env.example` lists all required settings and optional timeout/health settings.

Example endpoint and database ports in `env.example` are illustrative, not selected or approved. Shared service ports and readiness-check endpoint ports must be inventoried separately. A port being absent from the deny list does not prove isolation. Actual endpoints and proxy targets must be independently checked and recorded in the stack review JSON manifest, which must match the configured environment, database, endpoints, MySQL server UUID, and reviewed LoginForm asset. Recorded isolated ports require the explicit opt-in plus manual stack review. No port example here constitutes approval or a ready-state claim.

The DB login is expected to have only `SELECT` and `USAGE`, with no active roles or grant option. The harness inspects reported grants and restricts its own adapter query interface, but the account's effective privileges and MySQL grants still require independent verification and manager review. The populated env file and credentials must remain outside Git. The local `.gitignore` excludes dependency output, Playwright output, logs, `.env`, and `.env.*`, with `.env.example` excepted; the non-secret template here is named `env.example`. Evidence must stay under the configured external root. Treat screenshots, traces, reports, and flow data as identity-sensitive: the CLI uses a restrictive umask, run directories use mode `0700`, and JSON/log artifacts use mode `0600`; keep screenshots and traces permission `0600` and restrict access to the evidence root.

Playwright is configured for one worker and zero retries. An order is created exactly once through the customer UI; failures stop subsequent business writes, preserve the last confirmed checkpoint, and leave the failed order for inspection. The harness will not automatically delete, cancel, or clean up a failed order or cart state.

## SQL reconciliation and replay

The separately reviewed adapter contract is in [adapter-contract.md](adapter-contract.md). The adapter is loaded only from an absolute path outside the repository and its configured SHA-256 is checked. SQL stage checks use a fixed named catalog of parameterized read-only queries. Stage reconciliation checks UI/API-observed order state against SQL; the full ledger snapshot is captured before and after replay.

Replay is limited to the same order created by this run: mock payment and completed pickup verification are invoked again through the authenticated UI session's observed headers. It does not create another order or replay a different order. The adapter's full ledger snapshot must remain canonically unchanged, and terminal UI state is read back again. This narrow idempotency check does not prove general replay safety or external payment behavior.

The staged checkpoints are `afterCreate`, `afterMockPay`, `afterStartPicking`, `afterFinishPicking`, and `afterVerify`. On failure, inspect the last confirmed checkpoint and restricted evidence; do not infer completion from an earlier stage.
