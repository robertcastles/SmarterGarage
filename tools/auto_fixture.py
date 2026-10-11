"""Loopback-only counted fixture ownership for native DHU command acceptance."""
import json
import threading
import urllib.request


def control(path, body=None):
    request = urllib.request.Request('http://127.0.0.1:3001/__test/' + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def validate_snapshot(snapshot, generation, admitted):
    if snapshot['generation'] != generation:
        raise RuntimeError('Fixture reset externally during Auto test')
    accepted = snapshot['acceptedCommands']
    count = snapshot['requests'].get('commands', 0)
    if accepted != admitted[:len(accepted)] or len(accepted) > len(admitted) or \
        count > len(admitted) or count < len(accepted):
        raise RuntimeError('Unexpected or duplicate Auto command delivery')


class AutoFixture:
    def __init__(self):
        control('reset', {'status': 'closed', 'startDelayMs': 8000, 'motionMs': 10000})
        self.generation = control('snapshot')['generation']
        self.admitted = []
        self.saw_open = False
        self.error = None
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._monitor, daemon=True)
        self.thread.start()

    def _monitor(self):
        while not self.stopped.is_set():
            try:
                self.check()
            except Exception as error:
                with self.lock:
                    self.error = error
                return
            self.stopped.wait(.2)

    def check(self):
        # Hold the lock across snapshot/admission to avoid stale snapshot races.
        with self.lock:
            if self.error:
                raise self.error
            snapshot = control('snapshot')
            validate_snapshot(snapshot, self.generation, self.admitted)
            if snapshot['acceptedCommands'] == ['open'] and snapshot['status'] == 'open':
                self.saw_open = True
            return snapshot

    def admit(self, action):
        self.check()
        with self.lock:
            expected = 'open' if not self.admitted else 'close' if self.admitted == ['open'] else None
            if action != expected:
                raise RuntimeError('Auto actions must be exactly OPEN then CLOSE, without replay')
            if action == 'close' and not self.saw_open:
                raise RuntimeError('Fresh intermediate fixture OPEN not observed before CLOSE')
            self.admitted.append(action)

    def finish(self):
        snapshot = self.check()
        if not self.saw_open or self.admitted != ['open', 'close'] or \
            snapshot['acceptedCommands'] != ['open', 'close'] or \
            snapshot['requests'].get('commands') != 2 or snapshot['status'] != 'closed':
            raise RuntimeError('Auto cycle missing intermediate OPEN, exact commands, or final CLOSED')
        return {'commands': 2, 'accepted': ['open', 'close'], 'saw_open': True, 'final_status': 'closed'}

    def stop(self):
        self.stopped.set()
        self.thread.join(timeout=10)
        if self.thread.is_alive():
            raise RuntimeError('Fixture observer did not stop')
