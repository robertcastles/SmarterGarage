"""Repeat a real phone UI open/close test against the local deterministic simulator."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--repeat', type=int, default=3)
    parser.add_argument('--output', type=Path, default=Path('build/phone-command-tests/result.json'))
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') or not 1 <= args.repeat <= 10:
        raise RuntimeError('Use an emulator and 1..10 repetitions')
    sdk = Path(os.environ.get('ANDROID_HOME') or Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    adb = sdk / 'platform-tools/adb.exe'

    def device(*parts):
        return subprocess.check_output([str(adb), '-s', args.serial, *parts], text=True, timeout=60)

    def control(path, body=None):
        payload = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request('http://127.0.0.1:3001/__test/' + path,
            data=payload, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    if device('shell', 'getprop', 'sys.boot_completed').strip() != '1' or \
        'activity: [android.app.IActivityManager]' not in device('shell', 'service', 'list'):
        raise RuntimeError('Emulator Android framework is not ready; restart/wait before command testing')

    # Read privately; never put credentials or complete preferences in diagnostics.
    prefs = ET.fromstring(device('shell', 'run-as', 'com.lusion.sgauto', 'cat',
        '/data/user_de/0/com.lusion.sgauto/shared_prefs/com.lusion.sgauto_preferences.xml'))
    values = {node.attrib.get('name'): node for node in prefs}
    endpoint = values['debug_server'].text
    if values['debug_switch'].attrib.get('value') != 'true' or endpoint not in (
        'https://10.0.2.2:3000', 'https://10.0.2.2:3000/', 'https://127.0.0.1:3000', 'https://localhost:3000'):
        raise RuntimeError('Refusing commands: configure emulator-local HTTPS fixture on port 3000')
    fixture_device = values['garage_device_id'].text
    if not fixture_device or fixture_device == 'NONE':
        raise RuntimeError('Select a fake device')
    displays = device('shell', 'dumpsys', 'display')
    if 'GhFacetBar' in displays or 'GhostActivityDisplay' in displays:
        raise RuntimeError('Disconnect DHU before isolated phone tests')
    control('snapshot')
    reports = []
    try:
        for index in range(args.repeat):
            process = None
            run = {'iteration': index + 1, 'passed': False}
            reports.append(run)
            command = [str(adb), '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', 'com.lusion.sgauto.PhoneCommandTest#openThenCloseWithFreshConfirmation',
                '-e', 'allowFakeCommands', 'true', '-e', 'expectedEndpoint', endpoint,
                '-e', 'expectedDevice', fixture_device,
                'com.lusion.sgauto.test/androidx.test.runner.AndroidJUnitRunner']
            try:
                device('shell', 'am', 'force-stop', 'com.lusion.sgauto')
                control('reset', {'status': 'closed', 'startDelayMs': 8000, 'motionMs': 10000})
                generation = control('snapshot')['generation']
                process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                lines = []
                reader = threading.Thread(target=lambda: lines.extend(process.stdout), daemon=True)
                reader.start()
                deadline = time.monotonic() + 240
                saw_open = False
                while process.poll() is None:
                    snapshot = control('snapshot')
                    if snapshot['generation'] != generation:
                        raise RuntimeError('Fixture reset externally during test')
                    accepted = snapshot['acceptedCommands']
                    count = snapshot['requests'].get('commands', 0)
                    if accepted not in ([], ['open'], ['open', 'close']) or count > 2:
                        raise RuntimeError('Unexpected or duplicate command delivery')
                    if accepted == ['open'] and snapshot['status'] == 'open':
                        saw_open = True
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Instrumentation timeout; command delivery may be uncertain')
                    time.sleep(.2)
                reader.join(timeout=10)
                output = ''.join(lines)
                if process.returncode != 0 or not re.search(r'OK \(1 test\)', output) or 'FAILURES' in output:
                    # Do not persist raw instrumentation output: it may include app diagnostics.
                    run['process_crashed'] = 'Process crashed' in output
                    run['assertion_failed'] = 'AssertionError' in output
                    run['observed_commands'] = control('snapshot')['requests'].get('commands', 0)
                    raise RuntimeError('Instrumentation did not report successful completion')
                snapshot = control('snapshot')
                if snapshot['generation'] != generation or not saw_open or snapshot['status'] != 'closed' or \
                    snapshot['acceptedCommands'] != ['open', 'close'] or snapshot['requests'].get('commands') != 2:
                    raise RuntimeError('Missing intermediate OPEN, final CLOSED, or exact ordered command counts')
                run.update(passed=True, commands=2, accepted=['open', 'close'], saw_open=True, final_status='closed')
                print(f'PASS iteration {index + 1}: fresh UI cycle, exactly OPEN then CLOSE', flush=True)
            except Exception as error:
                run['error'] = ('ADB operation timed out' if isinstance(error, subprocess.TimeoutExpired) else
                    'ADB operation failed' if isinstance(error, subprocess.CalledProcessError) else str(error))
                raise  # No automatic replay of a failed or uncertain command cycle.
            finally:
                try:
                    device('shell', 'am', 'force-stop', 'com.lusion.sgauto')
                    control('reset', {'status': 'closed'})
                except Exception:
                    run['cleanup_failed'] = True
                    raise
                finally:
                    if process is not None and process.poll() is None:
                        process.terminate()
                        process.wait(timeout=10)
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(reports, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()


