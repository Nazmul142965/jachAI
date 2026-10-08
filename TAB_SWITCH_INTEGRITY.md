# Exam tab-switch integrity behavior

## Implemented behavior

Talent candidate attempts record a `TAB_HIDDEN` event whenever the browser
reports that the exam page is no longer visible. The third recorded tab leave
returns a warning to the candidate:

> You have left the exam tab three times. This integrity event was reported to
> the examiner.

The warning is produced by the server after counting non-expired telemetry
events for that attempt. It is not based only on a browser-local counter.
The frontend displays the warning without blocking the attempt, so an
examiner can review the evidence before making a decision.

## Examiner report

The existing Talent integrity report now includes:

- `tabSwitchCount`: the number of recorded `TAB_HIDDEN` events;
- `tabSwitchLimitReached`: whether the count is at least three;
- the existing event counts and event timeline.

The report remains evidence for human review, not an automatic cheating
verdict. A candidate can legitimately lose focus because of an operating
system dialog or an accidental browser action, so the application does not
auto-submit or permanently lock an attempt based only on this signal.

## Scope and limitations

- The rule applies to active Talent candidate attempts only.
- Telemetry retention remains 90 days and the existing per-attempt event cap
  remains in force.
- Browser visibility events are client-reported signals; a malicious browser
  can suppress them. Server-side attempt deadlines and answer authorization
  remain authoritative.
- The warning is returned by the telemetry request and shown in the candidate
  UI. The examiner sees it through the existing integrity report endpoint; no
  email or push notification is claimed.

