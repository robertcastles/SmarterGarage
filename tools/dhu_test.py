"""Run recorded DHU scenarios on a debug emulator; no third-party Python packages."""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--scenario', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/dhu-tests'))
    parser.add_argument('--port', type=int, default=5277)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME') or Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    adb = sdk / 'platform-tools/adb.exe'
    dhu = sdk / 'extras/google/auto/desktop-head-unit.exe'
    if not args.serial.startswith('emulator-'):
        raise RuntimeError('This runner intentionally supports test emulators only')
    if not args.config.is_file():
        raise RuntimeError('DHU configuration does not exist')

    def device(*command):
        return subprocess.check_output([str(adb), '-s', args.serial, *command], text=True)

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
    allowed = ('tap ', 'keycode ', 'dpad ', 'restrict ', 'day', 'night', 'focus ')
    for step in steps:
        if 'command' in step and not step['command'].startswith(allowed):
            raise RuntimeError('Unsupported scenario command')
        if float(step.get('wait', 1)) < 0 or float(step.get('wait', 1)) > 300:
            raise RuntimeError('Scenario waits must be between 0 and 300 seconds')
        for pattern in step.get('expect_log', []):
            re.compile(pattern)

    args.output.mkdir(parents=True, exist_ok=True)
    device('forward', f'tcp:{args.port}', f'tcp:{args.port}')
    messages = queue.Queue()
    observed = []
    process = subprocess.Popen([str(dhu), '-c', str(args.config.resolve()), f'--adb={args.port}'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1)

    def read_output():
        for line in process.stdout:
            observed.append(line)
            messages.put(line)

    threading.Thread(target=read_output, daemon=True).start()

    def send(command):
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
            '-File', str(script.resolve()), '-ImagePath', str(image)], text=True)
        return json.loads(text)

    def wait_for_logs(patterns, since, timeout):
        deadline = time.monotonic() + timeout
        while True:
            # Capture only selected app tags in memory; persist no device log or credentials.
            logs = device('logcat', '-d', '-T', since, '-v', 'brief',
                'DataRequestReceiver:I', 'GarageAutoFGService:D', 'GarageCommandWorker:I',
                'GarageUtility:I', 'SGScreen:I', 'SGAutoApp:I', 'AndroidRuntime:E', '*:S')
            if 'FATAL EXCEPTION' in logs:
                raise RuntimeError('Runtime crash occurred during scenario')
            if all(re.search(pattern, logs) for pattern in patterns):
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Expected app events not observed: ' + ', '.join(patterns))
            time.sleep(1)

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
        send('focus video on')
        time.sleep(2)
        last_image = screenshot(0)
        for index, step in enumerate(steps, 1):
            since = device('shell', "date '+%m-%d %H:%M:%S.000'").strip()
            if 'tap_text' in step:
                controls = text_controls(last_image)
                matches = [control for control in controls if re.search(step['tap_text'], control['text'], re.I)]
                if len(matches) != 1:
                    raise RuntimeError('Text target must match exactly one visible control: ' + step['tap_text'])
                send(f"tap {matches[0]['x']} {matches[0]['y']}")
            if 'command' in step:
                send(step['command'])
            time.sleep(float(step.get('wait', 1)))
            if step.get('expect_log'):
                wait_for_logs(step['expect_log'], since, float(step.get('timeout', 60)))
            last_image = screenshot(index)
            if step.get('expect_text'):
                visible = '\n'.join(control['text'] for control in text_controls(last_image))
                for pattern in step['expect_text']:
                    if not re.search(pattern, visible, re.I):
                        raise RuntimeError('Expected DHU text not visible: ' + pattern)
            report['steps_passed'] += 1
        report['passed'] = True
        print('PASS: DHU handshake, screenshots, and scenario assertions')
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        if process.poll() is None:
            try:
                send('quit')
                process.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                process.wait(timeout=5)
        (args.output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()


