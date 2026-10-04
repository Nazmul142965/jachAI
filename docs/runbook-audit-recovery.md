# Runbook — membership and ownership audit recovery

For a verified platform administrator. Recovers audit events that a tenant's own records prove
should exist, after a write that failed between updating a record and recording its history.

## What this fixes, and what it cannot

Membership and ownership changes write the record first and its audit event second. If the
process dies in between, the change is applied but unrecorded. The transition is not lost: it is
still written on the record itself, in `lastChangeId`, `lastChangeActorId`, `lastChangedAt`,
`previousRole` and `previousActive` for a membership, and in the `ownershipTransfer*` fields for
an organization.

`MembershipService` and `OrganizationService` already replay from those markers the next time the
same record is written. This workflow exists because that is opportunistic: a membership nobody
touches again never recovers, and nothing surfaces that the event is missing.

**It cannot recover a transition whose markers are gone.** Each record holds the markers for its
*latest* transition only. If a membership changed twice and the first audit write failed, the
second change overwrites the marker — the first transition is unrecoverable here and must be
established from an external source (application logs, backups). Those records are reported as
`AMBIGUOUS` when the markers are partial; a fully overwritten marker is not detectable at all.

## Step 1 — look, change nothing

```
GET /api/v1/platform/organizations/{organizationId}/audit-recovery?page=0
```

Writes nothing. Returns one page of up to 50 memberships, plus the organization's single
ownership marker on page `0` only.

Read these fields first:

| Field | Meaning |
| --- | --- |
| `recoverableCount` | events repair would write |
| `ambiguousCount` | records repair will refuse to touch |
| `membershipPage` / `membershipTotalPages` | where the scan reached |
| `membershipsScanned` | total memberships in the tenant |
| `repaired` | `false` on a read, always |

**A clean page zero does not mean a clean tenant.** If `membershipTotalPages` is greater than 1,
walk every page before concluding anything.

Each entry carries `status`:

- `RECOVERABLE` — every marker is present. `action`, `actorUserId` and `occurredAt` are read from
  the record. `expectedEventId` is the deterministic id the owning service would have used.
- `AMBIGUOUS` — a change was recorded but its markers are incomplete, so the actor or the previous
  state cannot be established. `action` is `null`. **Repair will not write these.** Resolve them
  from an external record, or accept the gap; do not reconstruct one by hand from guesswork.

## Step 2 — repair, one page at a time

```
POST /api/v1/platform/organizations/{organizationId}/audit-recovery?page=0
```

Returns the same shape with `repaired: true`. Repeat per page, matching the pages you reviewed.

Properties you can rely on:

- **Idempotent.** Every event uses the deterministic id its markers imply, so a second run writes
  nothing. Re-running is always safe.
- **Safe under concurrent change.** Each record is re-read immediately before its event is
  written. If a membership changed between your report and your repair, the event recorded is the
  one the record now holds, not the stale one you saw.
- **Safe to retry after a partial failure.** Each insert stands alone. If the run stops halfway,
  the events already written stay written, and a retry completes only what remains.
- **Insert only.** Nothing is updated, overwritten or deleted. The actor and timestamp always come
  from the record, never from the operator running the repair.

Each entry's `detail` says what happened:

- `Recovered from the record's durable markers.` — written by this run.
- `Already recorded by a concurrent run; nothing was overwritten.` — another run or the owning
  service's own replay got there first. Not an error.

## Step 3 — confirm

Re-run the **GET** for each page. A repaired tenant reports `recoverableCount: 0`.

`ambiguousCount` will not fall. That is the intended outcome: those records need a human, and the
count is the standing list of what this tool has deliberately refused to invent.

## If something looks wrong

| Symptom | Reading |
| --- | --- |
| `403 PLATFORM_ADMIN_REQUIRED` | The caller is not a verified platform admin. |
| `404 NOT_FOUND` | No such organization. The tenant id is wrong. |
| `400 INVALID_PAGE` | Page is outside `0..500`. |
| `recoverableCount` stays above zero after a repair | Repair is reading a different page than the report. Match the `page` parameter. |
| An entry's `occurredAt` is older than you expect | Correct. The event is dated when the change happened, not when it was recovered. |

Recovered events carry `recoveredBy: AUDIT_RECOVERY` in their details, so a recovered entry is
distinguishable from one written at the time by the owning service.

## What is not covered

- Audit events for certificates, Talent reviews, manual grading and feedback. Those have their own
  recovery markers and are out of scope here.
- Transitions whose markers were overwritten by a later change to the same record.
- Any cross-tenant or whole-platform sweep. Every call is scoped to one organization, deliberately.
