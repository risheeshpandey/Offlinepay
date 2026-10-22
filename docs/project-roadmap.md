# Agreed learning order and feature backlog

Updated: 22 October 2026 (Asia/Kolkata).

This document records the current plan agreed with Risheesh. Earlier milestone labs remain historical references; they do not require separate reviews of older source versions.

## 1. Review the current M6 project first

Use the latest combined codebase. Teach M4 event/resilience concepts and M5 service-boundary concepts where they live now, alongside the M6 observability additions.

Reading order:

1. Gateway entry and routing: GatewayApplication, BoundaryFilter, configuration and Eureka service lookup.
2. Core authentication and financial request flow; revisit already-understood wallet/offline logic only where a change matters.
3. Payment transaction to outbox: event creation, same-transaction persistence, relay claims/leases and Kafka publication.
4. Notification service: Kafka consumption, transactional inbox, duplicate prevention, retries, DLT and notification ownership.
5. OpenFeign identity validation and cross-service failure behavior; Redis request protection and existing resilience mechanisms.
6. Structured logs, request IDs, traces, Micrometer/Prometheus, resource settings and the measured k6 results.

Read one class or small flow at a time using concrete examples. Explain annotations, transaction boundaries and failure behavior. Do not restart an independent M4 or M5 walkthrough from an old ZIP.

## 2. Milestone 7 after the current-code review

Build the Android/mobile demonstration on the reviewed backend:

- Login and wallet/allowance screens.
- Device keys in Android Keystore and durable pending-proof storage in Room.
- Offline proof exchange using QR first.
- Display provisional verification separately from confirmed server settlement.
- Reconnection, receipt/status display and end-to-end demonstration using the existing authorization contract.
- Extend phone transport to BLE/NFC after the QR flow works.

Do not begin M7 implementation before the agreed current-code walkthrough. The final product is intended to sync automatically; the accepted sync/reminder and protocol enhancements below are scheduled after the M7 foundation. Review dependencies before changing a signed contract or deployed client format.

## 3. Deferred enhancements after M7

These are accepted future work, not claims about the current release.

| Item | Agreed behavior / decision |
| --- | --- |
| Longer sync window | Extend configurable grace after selecting an explicit duration; account for longer sender reserve holds. |
| Signed sync deadline | Include the deadline in a compatible versioned certificate/protocol so a receiver can verify the submission cutoff offline. |
| Either-party submission | Allow the sender OR receiver to submit the same signed proof when online, with authorization, identity binding, durable replay protection and no duplicate credit. |
| Automatic synchronization | Persist pending proofs, automatically upload on connectivity, retry safely and reconcile authoritative receipts/status. This is the intended normal mobile behavior. |
| Local reminders | Schedule a receiver-device reminder 12 hours before the known submission deadline, with suitable behavior when less time remains. |
| Opposite-party updates | When a known payment is synced, update the other party's authoritative status. Notify the opposite party only when the server knows the proof/status and has a usable contact or device channel. Do not assume it knows a completely offline claim. |
| Real push/SMS adapter | Add a chosen provider and test idempotent delivery, retries and failure handling; current in-app notices are not proof of SMS/push delivery. |
| UPI integration | Confirmed planned scope after the M7 foundation. Define the exact funding/payment flow and supported provider sandbox, then implement verified confirmation, idempotency and reconciliation. No live UPI/bank integration is currently implemented. |
| Late claims, disputes and recovery | Define adjudication, reversal and operator recovery policies explicitly; do not silently accept claims against already-released money. |
| Reserve release policy | Existing explicit deadline-gated release remains. Review any future automated release only with a complete sync/grace/settlement policy and tests. |
| Further capacity work | Deferred until after learning and feature work. Test longer durations and other wallet distributions; record actual hardware, failures and delivery backlog. Runtime horizontal scaling/HPA is not yet measured. |

Rejected ideas remain rejected: no INR100 fine per unsynced transaction; no early reserve release merely because the server has not seen a payment. An unknown offline receiver claim can still exist.

## Already implemented: review and preserve

JWT access tokens, rotating HttpOnly refresh cookies with no refresh token in the JSON body, CSRF protection, request correlation/logs, IST human-readable timestamps, Kafka/outbox/inbox delivery, Redis request limits, Gateway/Eureka/OpenFeign and observability are present. Do not rebuild them as if they were missing; review their current implementation and address specific gaps if found.

## Working preferences and evidence

- Keep code simple and readable, using annotations/JPA/Hibernate and clear names where appropriate.
- Continue the same project; keep private keys, environment values, proofs, database volumes and personal learning comments.
- Use additive, compatible migrations and explicit protocol versioning for new contracts.
- Explain concepts with examples and manual API checks while learning; automation is appropriate for repeatable benchmark and CI execution.
- Update GitHub, the concept-to-code guide/PDF and validation evidence as features are completed.
- Add only implemented features and measured numbers to completed-work CV bullets. UPI/mobile follow-ups remain labelled as planned until implemented and tested.

## Existing benchmark checkpoint

The hosted run confirmed 300 virtual users at about754 mixed API requests/sec over180 seconds on4 assigned vCPUs and approximately16GiB RAM, with zero request failures and about745ms transfer p95. The500-VU stage breached the read-p95 rule; intermediate counts are untested. Workload: approximately70% reads /30% online transfers, one shared sender. Eventual notification delivery completed after320.25 seconds of drain.

Full evidence and scope: [capacity results](capacity-results.md). The benchmark is not an unconditional maximum, an offline signature-verification throughput test or proof of continuous notification processing at the API request rate.
