"""Command-free host tests: python -m unittest discover -s tools -p 'test_*.py'."""
import unittest
from xml.sax.saxutils import quoteattr

from dhu_test import control_center, start_head_unit_server


def screen(*labels):
    return '<hierarchy>' + ''.join(
        '<node package="com.google.android.projection.gearhead" bounds="[10,20][30,40]" '
        + attribute + '=' + quoteattr(label) + '/>' for attribute, label in labels) + '</hierarchy>'


class ServerStartupTest(unittest.TestCase):
    def run_startup(self, menu):
        calls = []
        screens = iter([screen(('content-desc', 'More options')), menu])

        def device(*args):
            calls.append(args)
            return next(screens) if args[:2] == ('shell', 'cat') else ''

        return calls, lambda: start_head_unit_server(device)

    def test_unique_exact_center(self):
        self.assertEqual((20, 30), control_center(
            screen(('text', 'OPEN'), ('text', 'OPENING')), 'text', 'OPEN'))

    def test_missing_or_ambiguous_target_is_refused(self):
        for xml in (screen(), screen(('text', 'OPEN'), ('text', 'OPEN'))):
            with self.assertRaises(RuntimeError):
                control_center(xml, 'text', 'OPEN')

    def test_invalid_or_empty_bounds_are_refused(self):
        for bounds in ('invalid', '[10,20][10,40]', '[30,40][10,20]'):
            with self.assertRaises(RuntimeError):
                control_center(screen(('text', 'OPEN')).replace('[10,20][30,40]', bounds), 'text', 'OPEN')

    def test_stopped_server_is_started_once(self):
        calls, run = self.run_startup(screen(('text', 'Start head unit server')))
        run()
        self.assertEqual(2, sum(call[:3] == ('shell', 'input', 'tap') for call in calls))
        self.assertEqual(('shell', 'input', 'keyevent', 'KEYCODE_HOME'), calls[-1])
        self.assertEqual(('shell', 'rm', '-f', '/data/local/tmp/sg-dhu-server-ui.xml'), calls[-2])

    def test_running_server_is_not_toggled(self):
        calls, run = self.run_startup(screen(('text', 'Stop head unit server')))
        run()
        self.assertEqual(1, sum(call[:3] == ('shell', 'input', 'tap') for call in calls))

    def test_missing_developer_menu_fails_with_cleanup(self):
        calls, run = self.run_startup(screen(('text', 'Help')))
        with self.assertRaises(RuntimeError):
            run()
        self.assertEqual(1, sum(call[:3] == ('shell', 'input', 'tap') for call in calls))
        self.assertEqual(('shell', 'input', 'keyevent', 'KEYCODE_HOME'), calls[-1])


if __name__ == '__main__':
    unittest.main()
