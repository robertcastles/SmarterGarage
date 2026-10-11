"""Run recorded DHU scenarios on a debug emulator; no third-party Python packages."""
import argparse
import configparser
import json
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlparse
from auto_fixture import AutoFixture, control


def notification_ids(records):
    return set(map(int, re.findall(r'NotificationRecord\([^\n]*pkg=com\.lusion\.sgauto[^\n]*\bid=(\d+)\b', records)))


def exclusive_auto_ids(ids):
    surfaces = ids & {3, 5}
    if len(surfaces) > 1:
        raise RuntimeError('Duplicate arrival and carrier Auto notifications are active')
    return surfaces


def physical_controls(controls):
    result = []
    for item in controls:
        angle = math.radians(item.get('angle', 0))
        cx, cy = item.get('width', 0) / 2, item.get('height', 0) / 2

        def rotate(point):
            x, y = point['x'] - cx, point['y'] - cy
            return dict(point, x=round(cx + x * math.cos(angle) - y * math.sin(angle)),
                y=round(cy + x * math.sin(angle) + y * math.cos(angle)))

        corrected = rotate(item)
        corrected['words'] = [rotate(word) for word in item.get('words', [])]
        result.append(corrected)
    return result


def garage_card_controls(controls):
    titles = [item for item in controls if re.fullmatch('Smarter Garage', item['text'], re.I)]
    if len(titles) != 1:
        raise RuntimeError('Expected one titled Smarter Garage notification card')
    return [item for item in controls if titles[0]['y'] < item['y'] <= titles[0]['y'] + 180]


def notification_buttons(controls):
    candidates = []
    for item in garage_card_controls(controls):
        if item['text'].upper() in ('OPEN', 'CLOSE'):
            candidates.append(item)
        else:
            words = item.get('words', [])
            if words and all(word['text'].upper() in ('OPEN', 'CLOSE') or not re.search(r'\w', word['text']) for word in words):
                candidates.extend(word for word in words if word['text'].upper() in ('OPEN', 'CLOSE'))
    pairs = [(opened, closed) for opened in candidates for closed in candidates
        if opened['text'].upper() == 'OPEN' and closed['text'].upper() == 'CLOSE'
        and abs(opened['y'] - closed['y']) <= 16 and abs(opened['x'] - closed['x']) >= 20]
    if len(pairs) != 1:
        raise RuntimeError('Expected one aligned OPEN/CLOSE notification action pair')
    return {'open': pairs[0][0], 'close': pairs[0][1]}


def notification_status_visible(controls, status):
    buttons = notification_buttons(controls)
    return any(re.fullmatch(r'[^\w]*' + status + r'(?:\s+\d{1,2}:\d{2}:\d{2})?[^\w]*', item['text'], re.I)
        and all(item['y'] < button['y'] - 8 for button in buttons.values())
        for item in garage_card_controls(controls))


def control_center(xml, attribute, label):
    """Reject ambiguous/missing controls instead of guessing or retrying clicks."""
    matches = [node for node in ET.fromstring(xml).iter('node')
        if node.get(attribute) == label]
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one Android Auto control: ' + label)
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', matches[0].get('bounds', ''))
    if not bounds:
        raise RuntimeError('Invalid Android Auto control bounds')
    left, top, right, bottom = map(int, bounds.groups())
    if right <= left or bottom <= top:
        raise RuntimeError('Empty Android Auto control bounds')
    return (left + right) // 2, (top + bottom) // 2


def start_head_unit_server(device):
    """Requires already enabled developer mode; never changes onboarding/settings."""
    path = '/data/local/tmp/sg-dhu-server-ui.xml'

    def snapshot():
        device('shell', 'uiautomator', 'dump', path)
        xml = device('shell', 'cat', path)
        if not any(node.get('package') == 'com.google.android.projection.gearhead'
            for node in ET.fromstring(xml).iter('node')):
            raise RuntimeError('Android Auto settings are not visible')
        return xml

    try:
        device('shell', 'am', 'start', '-W', '-n',
            'com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity')
        x, y = control_center(snapshot(), 'content-desc', 'More options')
        device('shell', 'input', 'tap', str(x), str(y))
        xml = snapshot()
        labels = [node.get('text') for node in ET.fromstring(xml).iter('node')]
        if 'Stop head unit server' not in labels:
            x, y = control_center(xml, 'text', 'Start head unit server')
            device('shell', 'input', 'tap', str(x), str(y))
    finally:
        device('shell', 'rm', '-f', path)
        device('shell', 'input', 'keyevent', 'KEYCODE_HOME')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--scenario', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/dhu-tests'))
    parser.add_argument('--port', type=int, default=5277)
    parser.add_argument('--start-head-unit-server', action='store_true',
        help='Start through the English Android Auto developer menu; developer mode must already be enabled')
    parser.add_argument('--counted-auto-cycle', action='store_true',
        help='Explicitly allow exactly OPEN then CLOSE via native notification taps; own fixture reset and disconnect checks')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME') or Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    adb = sdk / 'platform-tools/adb.exe'
    dhu = sdk / 'extras/google/auto/desktop-head-unit.exe'
    if not args.serial.startswith('emulator-'):
        raise RuntimeError('This runner intentionally supports test emulators only')
    if not args.config.is_file():
        raise RuntimeError('DHU configuration does not exist')

    def device(*command):
        return subprocess.check_output([str(adb), '-s', args.serial, *command], text=True, timeout=60)

    # Read preferences privately; never persist or print their content/credentials.
    prefs = ET.fromstring(device('shell', 'run-as', 'com.lusion.sgauto', 'cat',
        '/data/user_de/0/com.lusion.sgauto/shared_prefs/com.lusion.sgauto_preferences.xml'))
    values = {node.attrib.get('name'): node for node in prefs}
    if values.get('debug_switch') is None or values['debug_switch'].attrib.get('value') != 'true':
        raise RuntimeError('Refusing commands: app must have fake-server debug routing enabled')
    url = urlparse(values['debug_server'].text or '')
    if url.scheme != 'https' or url.hostname not in ('10.0.2.2', '127.0.0.1', 'localhost'):
        raise RuntimeError('Refusing commands: runner requires an emulator-local HTTPS fake server')
    if values.get('garage_device_id') is None or values['garage_device_id'].text in (None, '', 'NONE'):
        raise RuntimeError('Select a fake garage device before testing')
    steps = json.loads(args.scenario.read_text(encoding='utf-8')) if args.scenario else []
    actions = [step['garage_action'] for step in steps if 'garage_action' in step]
    moving_disconnect = any(step.get('disconnect_while_closing') for step in steps)
    if actions and not args.counted_auto_cycle:
        raise RuntimeError('Garage actions require --counted-auto-cycle')
    if args.counted_auto_cycle:
        if actions != ['open', 'close'] or not steps or not steps[-1].get('disconnect'):
            raise RuntimeError('Counted Auto scenario requires OPEN, CLOSE, then explicit disconnect')
        config = configparser.ConfigParser()
        config.read(args.config, encoding='utf-8-sig')
        if config.get('general', 'resolution', fallback='') != '1280x720':
            raise RuntimeError('Counted notification targets are calibrated only for 1280x720')
        if device('shell', 'getprop', 'ro.hardware').strip() not in ('ranchu', 'goldfish') or \
            device('shell', 'getprop', 'sys.boot_completed').strip() != '1' or \
            'activity: [android.app.IActivityManager]' not in device('shell', 'service', 'list'):
            raise RuntimeError('Counted Auto commands require a ready Android emulator')
        if url.port != 3000 or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
            raise RuntimeError('Counted Auto test requires the exact local fixture origin on port 3000')
        if values.get('debug_access_token') is not None and values['debug_access_token'].text:
            raise RuntimeError('Counted fixture tests require an empty debug token')
        hosts = subprocess.check_output(['tasklist', '/FI', 'IMAGENAME eq desktop-head-unit.exe', '/FO', 'CSV'], text=True, timeout=10)
        if 'desktop-head-unit.exe' in hosts.lower():
            raise RuntimeError('Close the existing DHU before exclusive counted testing')
    allowed = ('tap ', 'keycode ', 'dpad ', 'restrict ', 'day', 'night', 'focus ')
    for step in steps:
        if 'command' in step and not step['command'].startswith(allowed):
            raise RuntimeError('Unsupported scenario command')
        if float(step.get('wait', 1)) < 0 or float(step.get('wait', 1)) > 300:
            raise RuntimeError('Scenario waits must be between 0 and 300 seconds')
        for pattern in step.get('expect_log', []):
            re.compile(pattern)
        if 'garage_action' in step and (step.get('tap_text') != '^' + step['garage_action'].upper() + '$' or
            'command' in step or 'Smarter Garage' not in step.get('before_text', [])):
            raise RuntimeError('Garage actions require a unique exact label and visible Smarter Garage notification')
        if step.get('disconnect_while_closing') and (step.get('garage_action') != 'close' or not args.counted_auto_cycle):
            raise RuntimeError('Moving disconnect requires the counted single CLOSE action')
        if step.get('disconnect') and (step is not steps[-1] or 'command' in step or 'tap_text' in step):
            raise RuntimeError('Disconnect must be the final assertion-only step')
        if 'navigate_text' in step:
            navigation = re.fullmatch(r'tap (\d+) (\d+)', step.get('command', ''))
            if not navigation or not 0 < int(navigation[1]) < 80 or not 0 < int(navigation[2]) < 720 or \
                'garage_action' in step or 'tap_text' in step:
                raise RuntimeError('Navigation retry is restricted to the fixed host toolbar, never garage actions')
            re.compile(step['navigate_text'])

    if args.start_head_unit_server:
        start_head_unit_server(device)
    args.output.mkdir(parents=True, exist_ok=True)
    device('forward', f'tcp:{args.port}', f'tcp:{args.port}')
    fixture = None
    if args.counted_auto_cycle:
        device('shell', 'am', 'force-stop', 'com.lusion.sgauto')
        fixture = AutoFixture(motion_ms=30000 if moving_disconnect else 10000)
    messages = queue.Queue()
    observed = []
    try:
        process = subprocess.Popen([str(dhu), '-c', str(args.config.resolve()), f'--adb={args.port}'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1)
    except Exception:
        if fixture:
            fixture.stop()
            device('shell', 'am', 'force-stop', 'com.lusion.sgauto')
            control('reset', {'status': 'closed'})
        raise

    def read_output():
        for line in process.stdout:
            observed.append(line)
            messages.put(line)

    threading.Thread(target=read_output, daemon=True).start()

    def send(command):
        if fixture:
            fixture.check()
        if process.poll() is not None:
            raise RuntimeError('DHU exited unexpectedly')
        process.stdin.write(command + '\n')
        process.stdin.flush()

    def screenshot(index):
        path = (args.output / f'{index:02d}.png').resolve()
        path.unlink(missing_ok=True)
        send('screenshot ' + path.as_posix())
        deadline = time.monotonic() + 10
        while not path.exists() and time.monotonic() < deadline:
            time.sleep(0.2)
        if not path.exists():
            raise RuntimeError('No screenshot: DHU may lack video focus or setup is blocked')
        return path

    def text_controls(image):
        script = Path(__file__).with_name('dhu_ocr.ps1')
        text = subprocess.check_output(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', str(script.resolve()), '-ImagePath', str(image)], text=True, encoding='utf-8', timeout=30)
        return physical_controls(json.loads(text))

    def wait_for_logs(patterns, since, timeout):
        deadline = time.monotonic() + timeout
        while True:
            # Capture only selected app tags in memory; persist no device log or credentials.
            logs = device('logcat', '-d', '-T', since, '-v', 'brief',
                'DataRequestReceiver:I', 'GarageAutoFGService:D', 'GarageCommandWorker:I',
                'GarageUtility:I', 'SGScreen:I', 'SGAutoApp:I', 'NotificationUtility:D', 'AndroidRuntime:E', '*:S')
            if fixture:
                fixture.check()
                assert_auto_surface()
            if 'FATAL EXCEPTION' in logs:
                raise RuntimeError('Runtime crash occurred during scenario')
            if fixture and re.search(r'ForegroundServiceStartNotAllowedException|Phase 1: Command result: accepted=false|Watchdog fallback', logs):
                raise RuntimeError('Auto foreground command acceptance failed')
            if all(re.search(pattern, logs) for pattern in patterns):
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Expected app events not observed: ' + ', '.join(patterns))
            time.sleep(1)

    surfaces_seen = set()

    def auto_ids():
        records = device('shell', 'dumpsys', 'notification')
        return notification_ids(records)

    def assert_auto_surface():
        ids = exclusive_auto_ids(auto_ids())
        surfaces_seen.update(ids)

    def checked_wait(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if fixture:
                fixture.check()
                assert_auto_surface()
            time.sleep(min(.5, max(0, deadline - time.monotonic())))

    def reconnect(index):
        nonlocal process, messages
        messages = queue.Queue()
        process = subprocess.Popen([str(dhu), '-c', str(args.config.resolve()), f'--adb={args.port}'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=read_output, daemon=True).start()
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                if 'SSL negotiation finished successfully' in messages.get(timeout=1): break
            except queue.Empty:
                if process.poll() is not None: raise RuntimeError('Reconnect host exited before handshake')
        else:
            raise RuntimeError('Reconnect handshake timed out')
        send('focus video lock')
        checked_wait(7)
        deadline = time.monotonic() + 30
        while True:
            assert_auto_surface()
            if auto_ids() & {3, 5}: break
            if time.monotonic() >= deadline: raise RuntimeError('Auto status carrier did not recover on reconnect')
            fixture.check()
            time.sleep(.5)
        # Status-area navigation only; never resend a garage action.
        for attempt in range(3):
            image = screenshot(index)
            try:
                if notification_status_visible(text_controls(image), 'CLOSED'): break
            except RuntimeError:
                pass
            send('tap 40 62')
            checked_wait(5)
            image = screenshot(index)
            try:
                if notification_status_visible(text_controls(image), 'CLOSED'): break
            except RuntimeError:
                pass
        else:
            raise RuntimeError('Latest CLOSED caption did not recover on reconnected Auto host')

    report = {'serial': args.serial, 'steps_passed': 0, 'passed': False}
    try:
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                if 'SSL negotiation finished successfully' in messages.get(timeout=1):
                    break
            except queue.Empty:
                if process.poll() is not None:
                    raise RuntimeError('DHU exited before handshake')
        else:
            raise RuntimeError('DHU handshake timeout: start head-unit server and resolve setup prompts')
        time.sleep(5)
        send('help')
        send('focus video lock')
        time.sleep(2)
        last_image = screenshot(0)
        for index, step in enumerate(steps, 1):
            since = device('shell', "date '+%m-%d %H:%M:%S.000'").strip()
            if step.get('disconnect'):
                if not fixture:
                    raise RuntimeError('Disconnect assertions require counted fixture ownership')
                report['fixture'] = fixture.finish()
                send('quit')
                process.wait(timeout=10)
                wait_for_logs([r'Car connection lost\.',
                    r'Car disconnect: garage CLOSED, stopping foreground service'], since, 30)
                deadline = time.monotonic() + 30
                while True:
                    notifications = device('shell', 'dumpsys', 'notification')
                    services = device('shell', 'dumpsys', 'activity', 'services', 'com.lusion.sgauto')
                    active = re.search(r'NotificationRecord\([^\n]*pkg=com\.lusion\.sgauto[^\n]*\bid=(2|3|5)\b', notifications)
                    if not active and not re.search(r'ServiceRecord\{[^\n]*GarageAutoForegroundService', services):
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Auto carrier or foreground monitor remained after CLOSED disconnect')
                    fixture.check()
                    time.sleep(.5)
                report.update(disconnected=True, carrier_removed=True, monitor_stopped=True)
                report['steps_passed'] += 1
                continue
            last_image = screenshot(index)
            if fixture:
                assert_auto_surface()
            if step.get('before_text'):
                visible = '\n'.join(item['text'] for item in text_controls(last_image))
                if not all(re.search(pattern, visible, re.I | re.M) for pattern in step['before_text']):
                    raise RuntimeError('Required host context missing before input')
            if 'tap_text' in step:
                controls = text_controls(last_image)
                if 'garage_action' in step:
                    expected = 'CLOSED' if step['garage_action'] == 'open' else 'OPEN'
                    if not notification_status_visible(controls, expected):
                        raise RuntimeError('Expected distinct notification status caption before garage action')
                    matches = [notification_buttons(controls)[step['garage_action']]]
                else:
                    matches = [item for item in controls if re.search(step['tap_text'], item['text'], re.I)]
                if len(matches) != 1:
                    raise RuntimeError('Text target must match exactly one visible control: ' + step['tap_text'])
                if 'garage_action' in step:
                    current = ET.fromstring(device('shell', 'run-as', 'com.lusion.sgauto', 'cat',
                        '/data/user_de/0/com.lusion.sgauto/shared_prefs/com.lusion.sgauto_preferences.xml'))
                    live = {node.attrib.get('name'): node for node in current}
                    if live['debug_switch'].get('value') != 'true' or \
                        live['debug_server'].text != values['debug_server'].text or \
                        live['garage_device_id'].text != values['garage_device_id'].text:
                        raise RuntimeError('Routing changed before Auto command; refusing input')
                    fixture.admit(step['garage_action'])
                send(f"tap {matches[0]['x']} {matches[0]['y']}")
            if 'command' in step:
                send(step['command'])
            checked_wait(float(step.get('wait', 1)))
            if 'navigate_text' in step:
                for attempt in range(3):
                    last_image = screenshot(index)
                    matches = [item for item in text_controls(last_image)
                        if re.search(step['navigate_text'], item['text'], re.I)]
                    if len(matches) == 1:
                        break
                    if len(matches) > 1 or attempt == 2:
                        raise RuntimeError('Unique navigation destination not visible: ' + step['navigate_text'])
                    send(step['command'])
                    checked_wait(float(step.get('wait', 1)))
            if step.get('expect_log'):
                wait_for_logs(step['expect_log'], since, float(step.get('timeout', 60)))
            if 'garage_action' in step:
                action = step['garage_action'].upper()
                final = 'OPEN' if action == 'OPEN' else 'CLOSED'
                wait_for_logs([f'handleGarageCommand: action={action}, source=AUTO, command={action}'], since, 20)
                if step.get('disconnect_while_closing'):
                    wait_for_logs([r'startForeground succeeded', r'Phase 1: Command result: accepted=true',
                        r'Status=CLOSING, transitional=true'], since, 30)
                    if fixture.check()['status'] != 'closing':
                        raise RuntimeError('Fixture not CLOSING at disconnect checkpoint')
                    detached_since = device('shell', "date '+%m-%d %H:%M:%S.000'").strip()
                    send('quit')
                    process.wait(timeout=10)
                    wait_for_logs([r'Car connection lost\.', r'watchUntilClosed=true'], detached_since, 20)
                    deadline = time.monotonic() + 15
                    while auto_ids() & {3, 5}:
                        if time.monotonic() >= deadline: raise RuntimeError('Auto notifications survived moving disconnect')
                        fixture.check()
                        time.sleep(.5)
                    if fixture.check()['status'] != 'closing' or 2 not in auto_ids():
                        raise RuntimeError('Detached CLOSING did not retain foreground monitoring')
                    wait_for_logs([r'stopMonitoring: confirming final status=CLOSED on status card'], detached_since, 90)
                    report['fixture'] = fixture.finish()
                    report['moving_disconnect'] = True
                    deadline = time.monotonic() + 15
                    while 2 in auto_ids() or re.search(r'ServiceRecord\{[^\n]*GarageAutoForegroundService',
                        device('shell', 'dumpsys', 'activity', 'services', 'com.lusion.sgauto')):
                        if time.monotonic() >= deadline: raise RuntimeError('Detached monitor did not stop after fresh CLOSED')
                        fixture.check()
                        time.sleep(.5)
                    report['detached_monitor_stopped_after_closed'] = True
                    reconnect(index)
                    report['reconnected_closed'] = True
                    report['steps_passed'] += 1
                    continue
                wait_for_logs([r'startForeground succeeded', r'Phase 1: Command result: accepted=true',
                    rf'Garage completed transition to final state \({final}\), stopping',
                    f'stopMonitoring: confirming final status={final} on status card'], since, 90)
                deadline = time.monotonic() + 20
                while True:
                    last_image = screenshot(index)
                    controls = text_controls(last_image)
                    visible = '\n'.join(item['text'] for item in controls)
                    try:
                        confirmed = notification_status_visible(controls, final)
                    except RuntimeError:
                        confirmed = False
                    if confirmed:
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Fresh app status did not appear as a distinct Auto notification caption')
                    fixture.check()
                    time.sleep(.5)
            last_image = screenshot(index)
            if step.get('expect_text'):
                visible = '\n'.join(control['text'] for control in text_controls(last_image))
                for pattern in step['expect_text']:
                    if not re.search(pattern, visible, re.I | re.M):
                        raise RuntimeError('Expected DHU text not visible: ' + pattern)
            report['steps_passed'] += 1
        if fixture:
            report['fixture'] = fixture.finish()
            report['auto_surfaces_seen'] = sorted(surfaces_seen)
            report['no_overlapping_auto_notifications'] = True
        report['passed'] = True
        print('PASS: DHU handshake, screenshots, and scenario assertions')
    except Exception as error:
        report['passed'] = False
        report['error'] = str(error)
        if fixture:
            try:
                failed = control('snapshot')
                report['observed'] = {'commands': failed['requests'].get('commands', 0),
                    'accepted': failed['acceptedCommands'], 'status': failed['status']}
            except Exception:
                report['evidence_unavailable'] = True
        raise
    finally:
        if process.poll() is None:
            try:
                process.stdin.write('quit\n')
                process.stdin.flush()
                process.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                process.wait(timeout=5)
        if fixture:
            try:
                fixture.stop()
                # Reset only after confirmed app stop: no delayed job may contaminate another run.
                device('shell', 'am', 'force-stop', 'com.lusion.sgauto')
                control('reset', {'status': 'closed'})
            except Exception:
                report['passed'] = False
                report['cleanup_failed'] = True
                raise
            finally:
                (args.output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        (args.output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
