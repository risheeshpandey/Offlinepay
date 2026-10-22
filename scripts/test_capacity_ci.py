"""Policy/serialization checks, not performance measurements."""
import copy
import json
import unittest
from types import SimpleNamespace
from run_capacity_ci import CapacityRun, RULES, api_result, best_stage, highest_concurrency_stage, delivery_ok, financial_ok, json_rows, public_summary


def summary():
    metrics = {name: {'thresholds': {rule: {'ok': True}}, 'values': {}}
               for name, rule in RULES.items()}
    metrics['business_requests'] = {'values': {'count': 3000, 'rate': 95}}
    metrics['business_failures']['values']['rate'] = 0
    metrics['http_req_failed']['values']['rate'] = 0
    metrics['wallet_read_ms']['values']['p(95)'] = 100
    metrics['transfer_ms']['values']['p(95)'] = 200
    metrics['transfers_accepted'] = {'values': {'count': 900}}
    return {'metrics': metrics, 'offlinepayMetadata': {
        'businessWindowSeconds': 30.1, 'completedBusinessRpsExcludingSetup': 99.67}}


def snapshot():
    return {'core': {'negativeWallets': 0, 'unbalancedOperations': 0, 'duplicateKeys': 0,
        'walletLedgerMismatches': 0, 'failedEvents': 0, 'walletPaise': 10000, 'issuedPaise': 10000,
        'operations': 10, 'outboxEvents': 10, 'unpublished': 0, 'expectedNotices': 19},
        'notifications': {'consumedEvents': 10, 'actualNotices': 19,
                          'duplicateNotices': 0, 'openDeadLetters': 0}}


class CapacityPolicyTest(unittest.TestCase):
    def test_full_stage_passes(self):
        self.assertTrue(api_result(summary(), 0, 30)['apiPassed'])

    def test_missing_threshold_cannot_pass(self):
        data = summary(); data['metrics']['wallet_read_ms'].pop('thresholds')
        self.assertFalse(api_result(data, 0, 30)['apiPassed'])

    def test_nonzero_exit_and_memory_stop_cannot_pass(self):
        self.assertFalse(api_result(summary(), 99, 30)['apiPassed'])
        self.assertFalse(api_result(summary(), 0, 30, 'HOST_MEMORY_LIMIT')['apiPassed'])

    def test_failed_numeric_limits_cannot_hide_behind_threshold_flags(self):
        for name, value, number in [('business_failures', 'rate', 0.01),
                                    ('http_req_failed', 'rate', 0.02),
                                    ('wallet_read_ms', 'p(95)', 1000),
                                    ('transfer_ms', 'p(95)', 2000)]:
            data = summary(); data['metrics'][name]['values'][value] = number
            self.assertFalse(api_result(data, 0, 30)['apiPassed'], name)

    def test_short_aborted_observation_cannot_pass(self):
        data = summary(); data['offlinepayMetadata']['businessWindowSeconds'] = 8
        self.assertFalse(api_result(data, 0, 30)['apiPassed'])

    def test_read_only_run_cannot_pass(self):
        data = summary(); data['metrics'].pop('transfers_accepted')
        self.assertFalse(api_result(data, 0, 30)['apiPassed'])

    def test_failure_budget_is_reported_not_rounded_to_zero(self):
        data = summary(); data['metrics']['business_requests']['values']['count'] = 3275
        data['metrics']['business_failures']['values']['rate'] = 8 / 3275
        self.assertEqual(api_result(data, 0, 30)['failedBusinessRequests'], 8)

    def test_best_means_highest_throughput_not_highest_vus(self):
        rows = [dict(kind='search', passed=True, vus=30, observedBusinessRps=190),
                dict(kind='search', passed=True, vus=50, observedBusinessRps=103),
                dict(kind='search', passed=False, vus=100, observedBusinessRps=500),
                dict(kind='warmup', passed=True, vus=5, observedBusinessRps=900)]
        self.assertEqual(best_stage(rows)['vus'], 30)
        self.assertEqual(highest_concurrency_stage(rows)['vus'], 50)

    def test_no_candidate_if_all_stages_failed(self):
        rows = [dict(kind='search', passed=False, vus=10, observedBusinessRps=500)]
        self.assertIsNone(best_stage(rows))
        self.assertIsNone(highest_concurrency_stage(rows))

    def test_clean_delivery_passes(self):
        self.assertTrue(delivery_ok(snapshot()))

    def test_notification_lag_is_not_a_financial_failure_or_delivery_pass(self):
        data = snapshot(); data['notifications']['actualNotices'] = 18
        self.assertTrue(financial_ok(data)); self.assertFalse(delivery_ok(data))

    def test_financial_and_duplicate_mismatches_fail(self):
        for name in ('negativeWallets', 'unbalancedOperations', 'duplicateKeys', 'walletLedgerMismatches'):
            data = snapshot(); data['core'][name] = 1
            self.assertFalse(delivery_ok(data), name)
        data = snapshot(); data['notifications']['duplicateNotices'] = 1
        self.assertFalse(delivery_ok(data))

    def test_fund_conservation_is_required(self):
        data = snapshot(); data['core']['walletPaise'] = 9999
        self.assertFalse(delivery_ok(data))
        data = snapshot(); data['core'].pop('walletPaise'); data['core'].pop('issuedPaise')
        self.assertFalse(delivery_ok(data))

    def test_export_excludes_tokens_and_unknown_fields_without_mutation(self):
        data = summary(); data['setup_data'] = {'token': 'SECRET_SENTINEL'}; data['unknown'] = 'SECRET_SENTINEL'
        original = copy.deepcopy(data)
        self.assertNotIn('SECRET_SENTINEL', json.dumps(public_summary(data)))
        self.assertEqual(data, original)

    def test_compose_json_array_and_json_lines(self):
        self.assertEqual(json_rows('[{"a":1},{"a":2}]'), json_rows('{"a":1}\n{"a":2}'))


class FakeRun(CapacityRun):
    """Only exercises selection/stop decisions; never produces a benchmark report."""
    def __init__(self, levels, fail_confirmation=False, fail_warmup=False):
        self.args = SimpleNamespace(levels=levels, seconds=30, confirm_seconds=180)
        self.report = {'stages': [], 'confirmation': None, 'concurrencyConfirmation': None}
        self.calls = []
        self.fail_confirmation, self.fail_warmup = fail_confirmation, fail_warmup

    def start(self): pass
    def isolation_probe(self): self.report['isolationPreflightPassed'] = True
    def wait_delivery(self): return snapshot(), 0
    def save(self): pass

    def run_k6(self, name, vus, seconds, script='ci-mixed.js'):
        self.calls.append(name)
        if name == 'replay': return
        passing = vus < 100 and not (name.startswith('confirm') and self.fail_confirmation)
        if name == 'warmup' and self.fail_warmup: passing = False
        return {'name': name, 'kind': 'confirmation' if name.startswith('confirm') else
                'warmup' if name == 'warmup' else 'search', 'passed': passing, 'stopReason': None,
                'vus': vus, 'observedBusinessRps': {5: 50, 10: 120, 25: 190, 50: 103}.get(vus, 90)}


class StageSelectionTest(unittest.TestCase):
    def test_stops_after_failure_and_confirms_rate_and_concurrency_separately(self):
        run = FakeRun([10, 25, 50, 100, 200])
        self.assertTrue(run.execute())
        self.assertNotIn('search-200', run.calls)
        self.assertEqual(run.report['highestObservedPassingStage']['vus'], 25)
        self.assertEqual(run.report['highestPassingConcurrencyStage']['vus'], 50)
        self.assertIn('confirm-throughput-25', run.calls)
        self.assertIn('confirm-concurrency-50', run.calls)
        self.assertFalse(run.report['stages'][-1]['passed'])

    def test_one_confirmation_when_candidates_have_same_vus(self):
        run = FakeRun([10, 25])
        self.assertTrue(run.execute())
        self.assertEqual(len([name for name in run.calls if name.startswith('confirm')]), 1)
        self.assertTrue(run.report['searchCeilingReached'])

    def test_failed_confirmation_is_not_promoted(self):
        run = FakeRun([10, 25, 50], fail_confirmation=True)
        self.assertFalse(run.execute())
        self.assertEqual(run.report['status'], 'CONFIRMATION_FAILED')
        self.assertNotIn('confirm-concurrency-50', run.calls)

    def test_failed_warmup_does_not_start_search(self):
        run = FakeRun([10, 25], fail_warmup=True)
        with self.assertRaisesRegex(RuntimeError, 'WARMUP_FAILED'): run.execute()
        self.assertEqual(run.calls, ['replay', 'warmup'])


if __name__ == '__main__':
    unittest.main()
