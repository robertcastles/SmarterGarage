import threading
import unittest
from unittest.mock import patch

from auto_fixture import AutoFixture, validate_snapshot


def snapshot(accepted=(), count=None, status='closed', generation=7):
    return {'generation': generation, 'status': status, 'acceptedCommands': list(accepted),
        'requests': {'commands': len(accepted) if count is None else count}}


def observer(admitted=(), saw_open=False):
    # No constructor: unit tests must never contact/reset a running fixture.
    fixture = AutoFixture.__new__(AutoFixture)
    fixture.generation = 7
    fixture.admitted = list(admitted)
    fixture.saw_open = saw_open
    fixture.error = None
    fixture.lock = threading.Lock()
    return fixture


class AutoFixtureTest(unittest.TestCase):
    def test_valid_delivery_prefixes(self):
        for accepted in ((), ('open',), ('open', 'close')):
            validate_snapshot(snapshot(accepted), 7, ['open', 'close'])

    def test_duplicate_wrong_order_or_unadmitted_delivery_is_refused(self):
        cases = [(snapshot(['open', 'open']), ['open', 'close']),
            (snapshot(['close']), ['open']), (snapshot(['open']), []),
            (snapshot(['open', 'close'], count=3), ['open', 'close']),
            (snapshot(['open'], count=0), ['open'])]
        for state, admitted in cases:
            with self.subTest(state=state), self.assertRaises(RuntimeError):
                validate_snapshot(state, 7, admitted)

    def test_external_reset_is_refused(self):
        with self.assertRaises(RuntimeError):
            validate_snapshot(snapshot(generation=8), 7, [])

    def test_close_requires_observed_open(self):
        fixture = observer(['open'])
        with patch('auto_fixture.control', return_value=snapshot(['open'], status='opening')):
            with self.assertRaises(RuntimeError):
                fixture.admit('close')
        self.assertEqual(['open'], fixture.admitted)

    def test_admission_never_replays_commands(self):
        fixture = observer()
        with patch('auto_fixture.control', return_value=snapshot()):
            fixture.admit('open')
            with self.assertRaises(RuntimeError):
                fixture.admit('open')
        with patch('auto_fixture.control', return_value=snapshot(['open'], status='open')):
            fixture.admit('close')
        with patch('auto_fixture.control', return_value=snapshot(['open', 'close'])):
            with self.assertRaises(RuntimeError):
                fixture.admit('close')

    def test_final_evidence_is_required(self):
        fixture = observer(['open', 'close'], saw_open=True)
        with patch('auto_fixture.control', return_value=snapshot(['open', 'close'])):
            self.assertEqual(2, fixture.finish()['commands'])
        for state in (snapshot(['open']), snapshot(['open', 'close'], status='closing')):
            with patch('auto_fixture.control', return_value=state), self.assertRaises(RuntimeError):
                fixture.finish()
        fixture.saw_open = False
        with patch('auto_fixture.control', return_value=snapshot(['open', 'close'])):
            with self.assertRaises(RuntimeError):
                fixture.finish()


if __name__ == '__main__':
    unittest.main()
