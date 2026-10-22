# Hosted Java 21 validation

The GitHub Actions run on commit `58ed6175bb2cca83eda1fbc8e567710db740b4df` completed all 184 Java test cases with zero failures, errors or skipped tests.

| Module/check | Cases |
| --- | ---: |
| Core unit/application checks | 87 |
| Core PostgreSQL/Redis integration checks | 75 |
| Notification unit/contract/boundary checks | 10 |
| Notification PostgreSQL integration checks | 9 |
| Gateway routing checks | 3 |
| Total | 184 |

The separate Eureka routing/Feign discovery smoke and metrics/tracing/readiness smoke also completed successfully. Their use of H2 for selected wiring checks is not a PostgreSQL performance measurement. Kafka-backed capacity and notification-delivery measurements are reported separately.

Evidence: [push validation run](https://github.com/risheeshpandey/Offlinepay/actions/runs/39761172014), [PR validation run](https://github.com/risheeshpandey/Offlinepay/actions/runs/39761252784). These are repeated validation of the same source; their counts must not be added together as 368 unique tests.

The capacity workflow additionally validates 20 Python policy/selection checks, 4 real-k6 summary checks and 2 real-k6/mock-HTTP wiring cases before starting the disposable full stack. Mock HTTP timings are never capacity evidence. See [capacity methodology](github-capacity-test.md) and the measured-capacity run artifacts for performance results.

The same checks also passed on the polling-configuration commit `a1f05d4087603859bff334af434e5226bf6d664f`: [push run](https://github.com/risheeshpandey/Offlinepay/actions/runs/39766611922) and [PR run](https://github.com/risheeshpandey/Offlinepay/actions/runs/39766617264). No Java financial implementation changed in that commit.

The extended-search commit `103cb588dbc55c8ada5f571dde0a7cb0daf1ef42` also passed [hosted Java CI](https://github.com/risheeshpandey/Offlinepay/actions/runs/39769439778): the same 184 cases, zero failures/errors/skips, plus both smoke checks. Increasing a benchmark VU bound did not change the Java implementation.
