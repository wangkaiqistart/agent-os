# Contract: Tool Audit Lifecycle

## Storage contract

Table `tool_invocations` stores one row per model tool call.

- Unique key: `(turn_id, iteration, tool_call_id)`.
- Required association: `session_id`、`turn_id`、positive `iteration`、non-empty `tool_call_id`、non-empty `tool_name`、source.
- Input, result and errors are redacted before persistence.
- Status values are lowercase and include `in_progress`、`success`、`failed`、`timeout`、`sandbox_denied`、`interrupted`、`limit_skipped`、`unconfirmed`.
- Every actual attempt has a row. A pre-execution audit failure has no actual tool attempt and must never invoke the delegate; the failed audit confirmation is reported as a terminal audit error rather than a fabricated tool attempt.

## Begin

1. Validate identity, source and current running turn.
2. Persist `in_progress` in an independent short transaction. The row must carry `idempotent=false/true` and starts with `attempts=0`.
3. Retry only transient database failures within the shared audit budget.
4. On unique-key retry, query and compare full identity/payload before confirming.
5. If begin cannot be confirmed, return an audit failure and do not call the delegate.

## Complete

1. Update the original row, never insert a new row for completion.
2. Persist status, safe result/error, original character count, truncation flag, attempts and duration. Ordinary results use the default 10000-character cap unless configured otherwise.
3. If the commit outcome is unknown, query within the remaining cleanup budget.
4. If completion remains unconfirmed, update the original row to lowercase `unconfirmed` when possible, stop the turn and tell the caller the operation may have happened.
5. Only an `idempotent=true` tool may retry a result explicitly classified by the tool as transient with confirmed `executionStarted=false`, at most once by default; the retry must have a fresh remaining budget and increments `attempts`.
6. Never retry when completion is unknown, completion persistence is unconfirmed, the lease is lost, or the tool is non-idempotent.
7. A recovered `interrupted` row cannot be overwritten by a late completion from the old owner.

## Recovery

- Startup/takeover cleanup may transition orphaned running tool rows to `interrupted` when the parent turn has no valid owner lease.
- Valid owner leases are never cleaned by another owner.
- Cleanup is idempotent and uses the same database truth as turn recovery.
