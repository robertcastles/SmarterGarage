"""Command-free host tests: python -m unittest discover -s tools -p 'test_*.py'."""
import unittest
from xml.sax.saxutils import quoteattr

from dhu_test import control_center, notification_buttons, notification_status_visible, physical_controls, start_head_unit_server


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


class NotificationTargetTest(unittest.TestCase):
    def test_ocr_centers_rotate_back_about_image_center(self):
        point = {'text': 'OPEN', 'x': 326, 'y': 179, 'angle': 5, 'width': 1280, 'height': 720,
            'words': [{'text': 'OPEN', 'x': 326, 'y': 179}]}
        corrected = physical_controls([point])[0]
        self.assertEqual((343, 152), (corrected['x'], corrected['y']))
        self.assertEqual((343, 152), (corrected['words'][0]['x'], corrected['words'][0]['y']))
        self.assertEqual(326, point['x'])

    def test_zero_angle_does_not_change_coordinates(self):
        point = {'text': 'OPEN', 'x': 200, 'y': 200}
        corrected = physical_controls([point])[0]
        self.assertEqual((200, 200), (corrected['x'], corrected['y']))

    def controls(self, status='CLOSED'):
        return [{'text': 'Smarter Garage', 'x': 200, 'y': 60}, {'text': status, 'x': 200, 'y': 100},
            {'text': 'OPEN', 'x': 200, 'y': 200}, {'text': 'CLOSE', 'x': 350, 'y': 200}]

    def test_status_caption_is_not_the_open_button(self):
        controls = self.controls('OPEN')
        self.assertEqual(200, notification_buttons(controls)['open']['y'])
        self.assertTrue(notification_status_visible(controls, 'OPEN'))

    def test_action_labels_alone_do_not_prove_status(self):
        controls = self.controls()
        self.assertFalse(notification_status_visible(controls[:1] + controls[2:], 'OPEN'))

    def test_decorated_status_caption_remains_distinct_from_actions(self):
        self.assertTrue(notification_status_visible(self.controls('\u2022 OPEN'), 'OPEN'))
        self.assertFalse(notification_status_visible(self.controls('OPENING'), 'OPEN'))

    def test_merged_button_line_uses_word_centers(self):
        controls = self.controls()
        merged = controls[:2] + [{'text': 'OPEN CLOSE', 'x': 275, 'y': 200, 'words': controls[2:]}]
        self.assertEqual(200, notification_buttons(merged)['open']['x'])
        self.assertEqual(350, notification_buttons(merged)['close']['x'])

    def test_missing_or_ambiguous_pair_is_refused(self):
        for controls in (self.controls()[:2], self.controls() + [
            {'text': 'OPEN', 'x': 200, 'y': 230}, {'text': 'CLOSE', 'x': 350, 'y': 230}]):
            with self.assertRaises(RuntimeError):
                notification_buttons(controls)

    def test_unrelated_card_above_title_is_ignored(self):
        controls = [{'text': 'OPEN', 'x': 200, 'y': 20}, {'text': 'CLOSE', 'x': 350, 'y': 20}] + self.controls()
        self.assertEqual(200, notification_buttons(controls)['open']['y'])

    def test_duplicate_or_missing_titles_are_refused(self):
        controls = self.controls()
        for items in (controls[1:], controls + [controls[0]]):
            with self.assertRaises(RuntimeError):
                notification_buttons(items)

    def test_decorative_bullet_does_not_become_the_tap_center(self):
        controls = self.controls()
        controls[2] = {'text': '\u2022 OPEN', 'x': 175, 'y': 207, 'words': [
            {'text': '\u2022', 'x': 150, 'y': 204}, {'text': 'OPEN', 'x': 200, 'y': 209}]}
        self.assertEqual(200, notification_buttons(controls)['open']['x'])

    def test_opening_is_not_an_open_action(self):
        controls = self.controls()
        controls[2] = {'text': 'OPENING', 'x': 200, 'y': 200, 'words': [
            {'text': 'OPENING', 'x': 200, 'y': 200}]}
        with self.assertRaises(RuntimeError):
            notification_buttons(controls)


if __name__ == '__main__':
    unittest.main()
