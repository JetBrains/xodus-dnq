#!/usr/bin/env bash
# Opt-in XD-1310 supervisor. Runtime evidence stays outside the repository.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python3 - "$@" <<'PY'
import argparse
import atexit
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import selectors
import re
import signal
import shutil
import tempfile
import statistics
import subprocess
import sys
import time
import ctypes
import math
import threading

# This guard must execute before preparation, including under python -O.
if sys.flags.optimize:
    raise SystemExit('Optimized Python is unsupported: protective assertions are required')

ROOT = Path.cwd()
LOG = Path('/tmp/dev-logs') / str(ROOT).lstrip('/')
LOG.mkdir(parents=True, exist_ok=True)
p = argparse.ArgumentParser(description='XD-1310 isolated unchanged-product benchmark')
p.add_argument('action', choices=['compile', 'check', 'pilot', 'campaign', 'self-check'])
p.add_argument('--allow-100k', action='store_true', help='requires separate resource permission')
p.add_argument('--membership', required=True, choices=['standalone', 'campaign'])
p.add_argument('--campaign-id')
for name, default in [('query', 600), ('setup', 7200), ('aux', 900), ('compile', 900),
                      ('focused', 900), ('campaign', 172800)]:
    p.add_argument('--' + name + '-seconds', type=float, default=default)
a = p.parse_args()
# Serialize workload supervisors for this workspace without touching other repositories or worktrees.
if a.action != 'self-check':
    workload_lock = (LOG / 'track-2-workload.lock').open('a')
    try:
        fcntl.flock(workload_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        p.error('Another XD-1310 workload supervisor owns the workspace lock')


def rotate(path):
    Path(str(path) + '_10').unlink(missing_ok=True)
    for i in range(9, 0, -1):
        old = Path(str(path) + '_' + str(i))
        if old.exists():
            old.rename(str(path) + '_' + str(i + 1))
    if path.exists():
        path.rename(str(path) + '_1')


def read(path):
    try:
        return Path(path).read_text()
    except OSError as e:
        return str(e)


GiB = 1024 ** 3
DEFAULTS = dict(query=600, setup=7200, aux=900, compile=900, focused=900, campaign=172800)
PSI = ('memoryPressure', 'memory.pressure', 'ioPressure')


def effective_policy(options):
    result = {key: float(getattr(options, key + '_seconds')) for key in DEFAULTS}
    if any(not math.isfinite(v) or v <= 0 or v > DEFAULTS[k] for k, v in result.items()):
        raise ValueError('Budgets must be positive, finite and no greater than approved limits')
    return dict(budgets=result, membership=options.membership, campaignId=options.campaign_id,
                poll=1, maxGap=10, fullPercent=20, fullSeconds=30,
                memoryGiB=[4, 1], diskGiB=[6, 5], continuation='unavailable')


def destinations(command=()):
    # Relative Gradle destinations are relative to the fixed workspace working directory.
    env = os.environ
    home = env.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))
    cache = str(ROOT / '.gradle')
    if command and Path(command[0]).name not in ('gradle', 'gradlew'):
        raise ValueError('Unresolved command write destinations')
    tokens = list(command)
    homes, caches = set(), set()
    import shlex
    tokens += shlex.split(env.get('GRADLE_OPTS', '')) + shlex.split(env.get('JAVA_OPTS', ''))
    tokens += shlex.split(env.get('JAVA_TOOL_OPTIONS', '')) + shlex.split(env.get('JDK_JAVA_OPTIONS', ''))
    for i, token in enumerate(tokens):
        if token in ('-g', '--gradle-user-home', '--project-cache-dir'):
            value = tokens[i + 1] if i + 1 < len(tokens) else ''
            if not value or value.startswith('-'):
                raise ValueError('Unresolved Gradle destination')
            if token == '--project-cache-dir':
                cache = value
                caches.add(value)
            else:
                home = value
                homes.add(value)
        elif token.startswith(('--gradle-user-home=', '-Dgradle.user.home=')):
            home = token.split('=', 1)[1]
            homes.add(home)
        elif token.startswith('--project-cache-dir='):
            cache = token.split('=', 1)[1]
            caches.add(cache)
        elif token.startswith(('-p', '--project-dir', '-c', '--settings-file', '-b', '--build-file',
                               '-Duser.home=', '-Dorg.gradle.project.', '-PbuildDir=', '-Dorg.gradle.jvmargs=')):
            if token != '-Dorg.gradle.jvmargs=-Xmx768m':
                raise ValueError('Unsupported write-destination override: ' + token)
    if len(homes) > 1 or len(caches) > 1:
        raise ValueError('Conflicting Gradle write-destination overrides')
    for props in (ROOT / 'gradle.properties', Path(home).expanduser() / 'gradle.properties'):
        if props.exists() and re.search(r'(?m)(^\s*(gradle\.user\.home|org\.gradle\.projectcachedir|.*buildDir)\s*[=:]|-D(?:user\.home|gradle\.user\.home)=)', props.read_text()):
            raise ValueError('Unsupported destination in ' + str(props))
    paths = {'logs': LOG, 'workspace': ROOT, 'build': ROOT / 'build',
             'project-cache': Path(cache), 'gradle-home': Path(home)}
    for project in ('dnq-open-api', 'dnq-transient-store', 'dnq', 'dnq-xodus-open-api', 'dnq-entity-store',
                    'dnq-query', 'dnq-utils', 'dnq-crypto', 'dnq-compress', 'dnq-migrate'):
        paths[project + '-build'] = ROOT / project / 'build'
    resolved = {}
    for label, path in paths.items():
        if not str(path).strip() or str(path) == '.':
            raise ValueError('Empty destination: ' + label)
        path = (ROOT / path).resolve()
        ancestor = path
        while not ancestor.exists():
            ancestor = ancestor.parent
        if not ancestor.is_dir():
            raise ValueError('Destination ancestor is not a directory')
        resolved[label] = str(path)
    return resolved


def proc_identity(pid):
    text = Path('/proc/' + str(pid) + '/stat').read_text()
    tail = text[text.rindex(')') + 2:].split()
    return (int(tail[1]), int(tail[19]), text[text.index('(') + 1:text.rindex(')')])


def snapshot(pid=None):
    result = {'meminfo': read('/proc/meminfo'), 'memoryPressure': read('/proc/pressure/memory'),
              'ioPressure': read('/proc/pressure/io'), 'load': os.getloadavg(), 'disks': {}, 'processes': []}
    for label, path in monitor.paths.items():
        ancestor = Path(path).resolve()
        while not ancestor.exists():
            ancestor = ancestor.parent
        st = os.statvfs(ancestor)
        result['disks'][label] = {'path': path, 'device': os.stat(ancestor).st_dev,
                                  'filesystemAncestor': str(ancestor), 'available': st.f_bavail * st.f_frsize}
    for entry in Path('/proc').iterdir():
        if entry.name.isdigit():
            try:
                parent, birth, comm = proc_identity(int(entry.name))
                result['processes'].append([int(entry.name), parent, birth, comm])
            except FileNotFoundError:
                pass
    for label, path in [('vmstat', '/proc/vmstat'), ('cgroup', '/proc/self/cgroup')]:
        result[label] = read(path)
    for name in ('cpu.max', 'memory.current', 'memory.max', 'memory.swap.current', 'memory.events', 'memory.pressure'):
        result[name] = read('/sys/fs/cgroup/' + name)
    result['cpus'] = len(os.sched_getaffinity(0))
    return result


def counters(text):
    values = {k: int(v) for k, v in (line.split() for line in text.splitlines())}
    if not values or any(v < 0 for v in values.values()):
        raise ValueError('Invalid counters')
    return values


def pressure(text):
    values = {row.split()[0]: dict(pair.split('=') for pair in row.split()[1:]) for row in text.splitlines()}
    for kind in ('some', 'full'):
        row = values[kind]
        for key in ('avg10', 'avg60', 'avg300'):
            if key == 'avg10' or key in row:
                v = float(row[key])
                if not math.isfinite(v) or not 0 <= v <= 100:
                    raise ValueError('Invalid pressure percentage')
        if int(row['total']) < 0:
            raise ValueError('Negative pressure counter')
    return values


def assess(before, current, runtime, timers, now, owned=()):
    reasons, warnings = [], []
    try:
        if current['cgroup'].strip() != '0::/':
            raise ValueError('Only visible unified cgroup root supported')
        mem = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in current['meminfo'].splitlines()}
        needed = (1 if runtime else 4) * GiB
        if mem['MemAvailable'] < 0:
            raise ValueError('Negative memory')
        if mem['MemAvailable'] < needed:
            reasons.append('host-memory-headroom')
        used, swap = int(current['memory.current']), int(current['memory.swap.current'])
        limit = current['memory.max'].strip()
        if min(used, swap) < 0 or (limit != 'max' and int(limit) <= 0):
            raise ValueError('Invalid cgroup memory')
        if limit != 'max' and int(limit) - used < needed:
            reasons.append('cgroup-memory-headroom')
        if not current['disks'] or current['disks'].keys() != before['disks'].keys():
            raise ValueError('Missing filesystem evidence')
        for label, disk in current['disks'].items():
            if disk['available'] < 0 or (disk['path'], disk['device']) != (before['disks'][label]['path'], before['disks'][label]['device']):
                raise ValueError('Invalid or changed filesystem')
            if disk['available'] < (5 if runtime else 6) * GiB:
                reasons.append('disk-reserve-' + label)
        for field, keys in [('vmstat', ('pswpin', 'pswpout')),
                            ('memory.events', ('oom', 'oom_kill', 'max', 'high'))]:
            old, new = counters(before[field]), counters(current[field])
            for key in keys:
                delta = new[key] - old[key]
                if delta < 0:
                    raise ValueError('Counter rollback: ' + field + '-' + key)
                if delta:
                    (reasons if key in ('oom', 'oom_kill', 'max') else warnings).append(field + '-' + key)
        for field in PSI:
            old, new = pressure(before[field]), pressure(current[field])
            for key in ('some', 'full'):
                delta = int(new[key]['total']) - int(old[key]['total'])
                if delta < 0:
                    raise ValueError('Pressure counter rollback')
                if delta or float(new[key]['avg10']) > 0:
                    warnings.append(field + '-' + key)
            if float(new['full']['avg10']) >= 20:
                if timers[field] is None:
                    timers[field] = now
                if now - timers[field] >= 30:
                    reasons.append('sustained-' + field)
            else:
                timers[field] = None
        quota, period = current['cpu.max'].split()
        if int(period) <= 0 or (quota != 'max' and int(quota) <= 0):
            raise ValueError('Invalid CPU quota')
        cpus = current['cpus'] if quota == 'max' else min(current['cpus'], int(quota) / int(period))
        if not math.isfinite(cpus) or cpus <= 0 or len(current['load']) != 3 or any(not math.isfinite(v) or v < 0 for v in current['load']):
            raise ValueError('Invalid CPU evidence')
        if current['load'][0] >= cpus:
            warnings.append('cpu-load')
        if not isinstance(current['processes'], list):
            raise ValueError('Invalid process census')
        for pid, parent, birth, comm in current['processes']:
            if comm.lower() in ('java', 'javac', 'gradle', 'kotlinc') and (pid, birth) not in owned:
                reasons.append('competing-jvm-' + str(pid))
    except (KeyError, ValueError, TypeError, IndexError, ZeroDivisionError) as failure:
        reasons.append('missing-or-invalid-resource-evidence: ' + str(failure))
    return reasons, warnings


def admission():
    monitor.check(admit=True)
    return monitor.previous


class Contract:
    labels = ([f'warmup-{i}' for i in range(3)] + [f'sample-{i}' for i in range(7)] +
              ['diagnostic-control', 'diagnostic-observed'])

    def __init__(self):
        self.index = 0
        self.state = 'setup'
        self.pending = None
        self.observed = False
        self.done = False

    def accept(self, kind, value):
        if kind == 'setup-complete':
            assert self.state == 'setup'
            self.state = 'idle'
        elif kind == 'ready':
            assert self.state == 'idle' and value == self.labels[self.index]
            self.state = 'executing'
        elif kind == 'diagnostics':
            assert self.state == 'validation' and self.labels[self.index] == 'diagnostic-observed'
            assert not self.observed
            self.observed = True
        elif kind == 'operation-complete':
            assert self.state == 'executing'
            if self.index < 10:
                match = re.match(r'^nanos=([1-9][0-9]*)(?: |$)', value)
                assert match, 'Missing positive duration'
                self.pending = int(match[1])
            else:
                assert value == self.labels[self.index]
            self.state = 'validation'
        elif kind == 'validated':
            assert self.state == 'validation', 'Validation without operation'
            assert self.index != 11 or self.observed, 'Missing diagnostic evidence'
            result = self.pending
            self.pending = None
            self.index += 1
            self.state = 'idle' if self.index < len(self.labels) else 'validated-all'
            return result
        elif kind == 'cleanup':
            assert self.state == 'validated-all'
            self.state = 'cleanup'
        elif kind == 'done':
            assert self.state == 'cleanup' and not self.done, 'Incomplete operation manifest'
            self.done = True
            self.state = 'done'
        return None


def matrix(configs, launch, clock, deadline, record, rows=None):
    if rows is None:
        rows = [{'config': list(config), 'fork': fork, 'status': 'pending'}
                for config in configs for fork in range(2)]
    record('manifest', rows)
    try:
        for index, row in enumerate(rows):
            if row['status'] != 'pending':
                continue
            if clock() >= deadline:
                record('campaign-timeout', rows)
                break
            row['status'] = 'running'
            record('fork-start', rows)
            outcome = launch(row)
            assert outcome in ('completed', 'query-timeout', 'paused', 'blocked')
            row['status'] = outcome
            record('fork-outcome', rows)
            if outcome == 'query-timeout':
                for later in rows[index + 1:]:
                    if later['config'] == row['config']:
                        later['status'] = 'stopped-after-query-timeout'
                # Skip the remaining fork of this configuration, not the next configuration.
            elif outcome == 'paused':
                break
    except BaseException as failure:
        for row in rows:
            if row['status'] == 'running':
                row.update(status='paused-error', reason=repr(failure))
        raise
    finally:
        for row in rows:
            if row['status'] in ('pending', 'running'):
                row['status'] = 'paused-unexecuted'
        record('action-result', rows)
    return rows


def durable(path, obj):
    with path.open('a') as f:
        f.write(json.dumps({'monotonic': time.monotonic(), 'wall': time.time(), **obj}) + '\n')
        f.flush()
        os.fsync(f.fileno())


class Ownership:
    def __init__(self):
        self.lock = threading.RLock()
        self.handles = {}
        self.root = self.last_child = self.last_result = None
        self.failed = self.cleanup_failed = False
        self.cleanup_faults = []
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
            raise OSError(ctypes.get_errno(), 'Child subreaper unavailable')
        for name in ('pidfd_open', 'waitid', 'P_PIDFD', 'WNOWAIT'):
            if not hasattr(os, name):
                raise RuntimeError('Ownership primitive unavailable: ' + name)
        if not hasattr(signal, 'pidfd_send_signal'):
            raise RuntimeError('Identity-bound signalling unavailable')

    def register(self, pid, identity):
        fd = os.pidfd_open(pid)
        try:
            observed = proc_identity(pid)
            if observed[1] != identity[1]:
                return False
            parent = observed[0]
            ancestor = self.handles.get(parent)
            if parent != os.getpid() and (not ancestor or proc_identity(parent)[1] != ancestor[1]):
                return False
            old = self.handles.get(pid)
            self.handles[pid] = (fd, identity[1])
            fd = None
            if old:
                os.close(old[0])
            return True
        finally:
            if fd is not None:
                os.close(fd)

    def launch(self, command, **kwargs):
        with self.lock:
            if self.root is not None or self.handles:
                raise RuntimeError('Previous owned tree not cleared')
            self.root = self.last_child = subprocess.Popen(command, **kwargs)
            self.register(self.root.pid, proc_identity(self.root.pid))
            return self.root

    def refresh(self, census=None):
        with self.lock:
            rows = {} if census is None else {pid: (parent, birth, comm) for pid, parent, birth, comm in census}
            if census is None:
                for entry in Path('/proc').iterdir():
                    if entry.name.isdigit():
                        try:
                            rows[int(entry.name)] = proc_identity(int(entry.name))
                        except FileNotFoundError:
                            pass
            # Direct children belong to this dedicated subreaper. No helper forks occur here.
            for _ in range(len(rows) + 1):
                added = False
                for pid, identity in rows.items():
                    parent = identity[0]
                    handle = self.handles.get(pid)
                    if (handle and handle[1] == identity[1]) or parent == pid:
                        continue
                    ancestor = self.handles.get(parent)
                    valid = parent == os.getpid() or (ancestor is not None and parent in rows and
                            rows[parent][1] == ancestor[1])
                    if valid:
                        try:
                            # Registration validates current birth and ancestry, including adoption.
                            added = self.register(pid, identity) or added
                        except ProcessLookupError:
                            pass
                        except FileNotFoundError:
                            pass
                if not added:
                    break
            return {(pid, birth) for pid, (fd, birth) in self.handles.items()
                    if pid in rows and rows[pid][1] == birth}

    def status(self, child):
        with self.lock:
            # Only Popen reaps its root. Adopted reaping must not steal this status.
            return child.poll()

    def cleanup(self, deadline):
        try:
            # Even an exhausted allowance owes one identity-safe, non-waiting stop sweep.
            # It grants no work allowance and cannot certify an over-budget cleanup as success.
            while True:
                with self.lock:
                    self.refresh()
                    for fd, birth in self.handles.values():
                        try:
                            signal.pidfd_send_signal(fd, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    if self.root is not None:
                        self.status(self.root)
                    try:
                        status = os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    except ChildProcessError:
                        for fd, birth in self.handles.values():
                            os.close(fd)
                        self.handles.clear()
                        self.root = None
                        return time.monotonic() <= deadline  # ECHILD is the completeness certificate.
                    if status is not None:
                        if self.root is not None and self.root.returncode is None and status.si_pid == self.root.pid:
                            self.status(self.root)
                        else:
                            os.waitpid(status.si_pid, 0)
                if time.monotonic() >= deadline:
                    return False
                time.sleep(min(.01, max(0, deadline - time.monotonic())))
        except Exception as failure:
            durable(events, {'event': 'cleanup-fault', 'reason': repr(failure)})
            return False


class Monitor:
    def __init__(self, policy, paths, sampler=snapshot, clock=time.monotonic):
        self.policy, self.paths, self.sampler, self.clock = policy, paths, sampler, clock
        self.previous = None
        self.last = None
        self.timers = dict.fromkeys(PSI)
        self.reasons, self.warnings = [], set()
        self.lock = threading.RLock()
        self.closed = threading.Event()
        self.phase = 'preparation'
        self.thread = None
        self.deadline = clock() + policy['budgets']['aux']

    def enter(self, phase, seconds):
        self.phase = phase
        self.deadline = min(cap, self.clock() + seconds)

    def sample(self, admit=False):
        with self.lock:
            now = self.clock()
            try:
                current = self.sampler()
                owner.refresh(current.get('processes', []))
                # Bind census exemptions to live pidfds, not a cached PPID closure.
                owned_ids = set()
                with owner.lock:
                    for pid, parent, birth, comm in current.get('processes', []):
                        handle = owner.handles.get(pid)
                        if handle and handle[1] == birth:
                            try:
                                signal.pidfd_send_signal(handle[0], 0)
                                owned_ids.add((pid, birth))
                            except ProcessLookupError:
                                pass
                now = self.clock()
                reasons, warnings = assess(self.previous or current, current, not admit,
                                            self.timers, now, owned_ids)
                if self.last is not None and now - self.last > 10:
                    reasons.append('monitor-gap')
                if self.phase == 'preparation' and now >= self.deadline:
                    reasons.append('preparation-timeout')
                self.previous, self.last = current, now
                self.warnings.update(warnings)
                self.reasons.extend(r for r in reasons if r not in self.reasons)
                durable(events, {'event': 'resources', 'phase': self.phase, 'resources': current,
                                 'reasons': reasons, 'warnings': warnings, 'timers': self.timers.copy()})
            except Exception as failure:
                self.reasons.append('supervisor-fault: ' + repr(failure))

    def gate(self):
        if SIGNAL_STATE[0] and 'interrupted-' + str(SIGNAL_STATE[0]) not in self.reasons:
            self.reasons.append('interrupted-' + str(SIGNAL_STATE[0]))
        now = self.clock()
        if self.last is not None and now - self.last > 10 and 'monitor-gap' not in self.reasons:
            self.reasons.append('monitor-gap')
        if self.phase == 'preparation' and now >= self.deadline:
            self.reasons.append('preparation-timeout')
        if self.reasons:
            raise RuntimeError('Supervision stopped: ' + repr(self.reasons))

    def check(self, admit=False):
        self.sample(admit)
        self.gate()

    def start(self):
        self.check(admit=True)
        def loop():
            while not self.closed.wait(1):
                self.sample()
        self.thread = threading.Thread(target=loop, name='capacity-monitor', daemon=True)
        self.thread.start()

    def close(self):
        self.closed.set()
        if self.thread:
            self.thread.join(timeout=10)
            if self.thread.is_alive():
                raise RuntimeError('Monitor did not close')
        self.sample()
        try:
            self.gate()
        except RuntimeError:
            pass


def cleanup_attempt(boundary):
    clean = False
    try:
        clean = owner.cleanup(min(cap, time.monotonic() + policy['budgets']['aux']))
        return clean
    except Exception as failure:
        owner.cleanup_faults.append(repr(failure))
        durable(events, {'event': 'cleanup-fault', 'boundary': boundary, 'reason': repr(failure)})
        return False
    finally:
        owner.cleanup_failed |= not clean
        owner.failed |= not clean
        durable(events, {'event': 'cleanup-result', 'boundary': boundary, 'cleanupVerified': clean})


def run_command(command, limit, label, capture=False):
    monitor.enter(label, limit)
    child, fault, stopped, clean = None, None, None, False
    path = run_dir / (label + '-' + str(time.monotonic_ns()) + '.log')
    deadline = min(time.monotonic() + limit, cap)
    try:
        admission()
        if time.monotonic() >= deadline:
            stopped = 'allowance-exhausted-before-launch'
        else:
            with path.open('wb') as out:
                child = owner.launch(command, stdout=out, stderr=subprocess.STDOUT)
                while True:
                    status = owner.status(child)
                    monitor.gate()
                    if monitor.reasons:
                        stopped = list(monitor.reasons)
                        break
                    if status is not None:
                        break
                    if time.monotonic() >= deadline:
                        stopped = 'allowance-exhausted' if time.monotonic() >= cap else label + '-timeout'
                        break
                    time.sleep(.05)
    except Exception as failure:
        if monitor.reasons:
            stopped = list(monitor.reasons)
        else:
            fault = repr(failure)
    finally:
        natural_status = owner.status(child) if child else None
        clean = cleanup_attempt('command-' + label)
        monitor.sample()
        try:
            monitor.gate()
        except RuntimeError:
            pass
        if monitor.reasons and stopped is None:
            stopped = list(monitor.reasons)
        result = {'event': 'command-result', 'label': label, 'command': command, 'log': str(path),
                  'returncode': child.returncode if child else None, 'naturalReturncode': natural_status,
                  'supervisorKilled': child is not None and natural_status is None,
                  'supervisorFault': fault,
                  'stop': stopped, 'cleanupVerified': clean,
                  'qualification': 'contended' if monitor.warnings else 'no-observed-contention',
                  'latencyEvidence': False}
        reasons = stopped if isinstance(stopped, list) else [stopped] if stopped else []
        result['outcome'] = stop_kind(reasons) or ('supervisor-fault' if fault else
                            'cleanup-unverified' if not clean else 'child-failure' if child and child.returncode else 'completed')
        owner.last_result = result
        owner.failed |= bool(fault or stopped or not clean or child is None or child.returncode)
        durable(events, result)
    if fault or stopped or not clean or child is None or child.returncode:
        failure = (RuntimeError('Supervisor command failure: ' + repr(result)) if fault or stopped or not clean
                   else subprocess.CalledProcessError(child.returncode if child else 1, command))
        failure.command_result = result
        raise failure
    monitor.enter('preparation', policy['budgets']['aux'])
    return path.read_bytes() if capture else child


# Explicit AST export contract for the external focused caller. No dependency resolver.
EXPORTS = ('GiB', 'DEFAULTS', 'PSI', 'rotate', 'read', 'effective_policy', 'destinations',
           'proc_identity', 'snapshot', 'counters', 'pressure', 'assess', 'admission', 'durable',
           'Ownership', 'Monitor', 'cleanup_attempt', 'run_command', 'SIGNAL_STATE', 'interrupted', 'stop_kind',
           'finalize', 'publish_exit')
policy = effective_policy(a)
run_dir = Path(tempfile.mkdtemp(prefix='track-3-' + a.action + '-', dir=LOG))
events = run_dir / 'events.jsonl'
start = time.monotonic()
# This finite invocation ceiling is not a campaign allowance or continuation authority.
cap = start + policy['budgets']['campaign']
owned = None
owner = None
monitor = None
print(run_dir, flush=True)
durable(events, {'event': 'policy', 'policy': policy,
                 'sourceSha256': hashlib.sha256((ROOT / 'scripts/benchmarks/xd-1310.sh').read_bytes()).hexdigest()})


def stop():
    global owned
    clean = cleanup_attempt('worker')
    owned = None
    return clean


SIGNAL_STATE = [0]


def interrupted(signum, frame):
    SIGNAL_STATE[0] = signum


def stop_kind(reasons):
    if any(r.startswith('interrupted-') for r in reasons):
        return 'interrupted'
    if any(r.startswith('supervisor-fault') or r == 'monitor-gap' for r in reasons):
        return 'supervisor-fault'
    if any('timeout' in r or 'allowance-exhausted' in r for r in reasons):
        return 'deadline'
    return 'resource-paused' if reasons else None


def finalize(code):
    clean, faults = owner is None, []
    try:
        if owner:
            clean = cleanup_attempt('final')
    except BaseException as failure:
        faults.append('cleanup: ' + repr(failure))
    try:
        if monitor:
            monitor.close()
    except BaseException as failure:
        faults.append('monitor-close: ' + repr(failure))
    if owner:
        faults.extend(owner.cleanup_faults)
    reasons = list(monitor.reasons) if monitor else []
    failed = bool(code or faults or not clean or reasons or (owner and owner.failed))
    result = {'event': 'invocation-result', 'cleanupVerified': clean,
              'requiredCleanupFailed': bool(owner and owner.cleanup_failed), 'stop': reasons,
              'supervisorFault': faults, 'qualification': 'contended' if monitor and monitor.warnings else 'no-observed-contention',
              'latencyEvidence': False, 'activeSeconds': time.monotonic() - start}
    return (code or 1 if failed else 0), result


def publish_exit(code, result, exit_process=True):
    # Handlers only latch and cannot unwind publication. The last latch read after
    # durable publication is the terminal commit boundary. Later signals cannot
    # cancel committed completion. Only a failure amendment and process exit follow.
    def pending():
        return [SIGNAL_STATE[0]] if SIGNAL_STATE[0] else []
    try:
        for signum in pending():
            reason = 'interrupted-' + str(signum)
            if reason not in result['stop']:
                result['stop'].append(reason)
        incomplete = any(row['status'] != 'completed' for row in globals().get('ledger', []))
        code = code or int(bool(result['stop'] or incomplete))
        result['outcome'] = 'failed' if code else 'completed'
        durable(events, result)
        late = {'interrupted-' + str(s) for s in pending()} - set(result['stop'])
        if late:
            result['stop'] = sorted(set(result['stop']) | late)
            result['outcome'] = 'failed'
            code = code or 1
            durable(events, result)
    except BaseException:
        code = 1
    if exit_process or code:
        os._exit(code)


def exit_invocation(code):
    global requested_exit
    requested_exit = code
    sys.exit(code)


def finish_invocation():
    incomplete = any(row['status'] != 'completed' for row in globals().get('ledger', []))
    code = globals().get('requested_exit', 0) or int(bool(incomplete or getattr(sys, 'last_type', None)))
    # A healthy callback returns so Python preserves an explicit SystemExit status.
    publish_exit(*finalize(code), exit_process=False)


def report_exception(kind, failure, traceback):
    if owner:
        owner.failed = True
    classified = getattr(failure, 'command_result', None)
    event = (classified['outcome'] if classified else
             'child-failure' if isinstance(failure, subprocess.CalledProcessError) else
             stop_kind(monitor.reasons) if monitor and monitor.reasons else 'supervisor-fault')
    durable(events, {'event': event, 'reason': repr(failure),
                     'returncode': getattr(failure, 'returncode', None)})
    sys.__excepthook__(kind, failure, traceback)


sys.excepthook = report_exception
atexit.register(finish_invocation)
signal.signal(signal.SIGTERM, interrupted)
signal.signal(signal.SIGINT, interrupted)
try:
    if a.membership == 'campaign' or a.campaign_id or a.action == 'campaign':
        raise RuntimeError('Track 3 campaign execution/continuation unavailable until Track 4 authoritative accounting')
    owner = Ownership()
    monitor = Monitor(policy, destinations())
    if a.action != 'self-check':
        monitor.start()
    else:
        monitor.sampler = lambda: {}  # Real sampling is forbidden in mechanical checks.
except Exception as failure:
    durable(events, {'event': 'supervisor-fault', 'phase': 'preparation', 'reason': repr(failure)})
    raise


def supervise(command, directory, setup=None, operation=None, other=None, stub=False):
    global owned
    assert not stub or (a.action == 'self-check' and command[0] == sys.executable)
    setup = policy['budgets']['setup'] if setup is None else setup
    operation = policy['budgets']['query'] if operation is None else operation
    other = policy['budgets']['aux'] if other is None else other
    if not stub:
        admission()
    if time.monotonic() >= cap:
        durable(events, {'event': 'campaign-timeout', 'censored': False, 'boundary': 'before-launch'})
        owner.failed = True
        return False, False
    directory.mkdir()
    durable(events, {'event': 'launch', 'command': command, 'directory': str(directory)})
    with (directory / 'stdout.log').open('wb') as out:
        owned = owner.launch(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             bufsize=0)
        child = owned
        sel = selectors.DefaultSelector()
        sel.register(child.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + setup
        monitor.enter('setup', setup)
        phase = 'setup'
        label = None
        buffer = b''
        contract = Contract()
        done = False
        failed = False
        timed_out = False
        query_timeout = False
        samples = []
        warmups = []
        success = clean = False
        cleanup_started = False
        natural_status = None
        try:
            while True:
                now = time.monotonic()
                if now >= min(deadline, cap):
                    outcome = 'campaign-timeout' if now >= cap else phase + '-timeout'
                    current = None
                    reasons = [] if stub else list(monitor.reasons)
                    durable(events, {'event': 'resource-paused' if reasons else outcome, 'label': label,
                                     'censored': not reasons and now < cap and phase in ('query', 'diagnostic'),
                                     'resources': current, 'reasons': reasons})
                    if reasons:
                        failed = True
                    else:
                        timed_out = True
                        query_timeout = now < cap and phase in ('query', 'diagnostic')
                    break
                if not stub:
                    try:
                        monitor.gate()
                    except RuntimeError:
                        pass  # The classified stop below also publishes the worker summary.
                    monitor.phase = phase
                    if monitor.reasons:
                        durable(events, {'event': stop_kind(monitor.reasons), 'phase': phase, 'label': label,
                                         'reasons': list(monitor.reasons)})
                        failed = True
                        break
                ready = sel.select(timeout=min(.1, max(0, deadline - now)))
                if not ready:
                    if owner.status(child) is not None:
                        break
                    continue
                chunk = os.read(child.stdout.fileno(), 65536)
                if not chunk:
                    break
                out.write(chunk)
                out.flush()
                buffer += chunk
                while b'\n' in buffer:
                    line, buffer = buffer.split(b'\n', 1)
                    if not line.startswith(b'@@\t'):
                        continue
                    fields = line.decode(errors='replace').split('\t', 2)
                    kind, value = fields[1], fields[2] if len(fields) > 2 else ''
                    durable(events, {'event': kind, 'value': value, 'label': label})
                    try:
                        nanos = contract.accept(kind, value)
                    except (AssertionError, IndexError, ValueError) as failure:
                        durable(events, {'event': 'protocol-failure', 'reason': str(failure)})
                        failed = True
                        break
                    if kind == 'ready':
                        label = value
                        phase = 'diagnostic' if value.startswith('diagnostic') else 'query'
                        # Arm before GO. Startup and setup never consume the operation deadline.
                        deadline = time.monotonic() + operation
                        monitor.enter(phase, operation)
                        durable(events, {'event': 'acknowledged-start', 'label': label, 'deadline': deadline})
                        child.stdin.write(b'GO\n')
                        child.stdin.flush()
                    elif kind in ('setup-complete', 'operation-complete', 'validated', 'cleanup'):
                        if kind == 'validated' and nanos is not None:
                            (samples if label.startswith('sample-') else warmups).append(nanos)
                            durable(events, {'event': 'completed', 'label': label, 'nanos': nanos})
                        phase = 'diagnostic-preparation' if kind == 'validated' else 'harness'
                        deadline = time.monotonic() + other
                        monitor.enter(phase, other)
                    elif kind in ('assertion-failure', 'observer-failure'):
                        failed = True
                    elif kind == 'done':
                        done = True
                if failed:
                    break
            if not timed_out and not failed:
                exit_deadline = min(cap, time.monotonic() + other)
                while owner.status(child) is None and time.monotonic() < exit_deadline:
                    if not stub and monitor.reasons:
                        failed = True
                        break
                    time.sleep(.05)
                if child.returncode is None:
                    timed_out = True
                    durable(events, {'event': 'exit-wait-timeout', 'censored': False})
            natural_status = owner.status(child)
            cleanup_started = True
            clean = stop()
            if not stub:
                monitor.sample()
            reasons = [] if stub else list(monitor.reasons)
            if reasons:
                durable(events, {'event': stop_kind(reasons), 'boundary': 'worker-exit', 'reasons': reasons})
                failed = True
                query_timeout = False
            success = (done and contract.done and len(warmups) == 3 and len(samples) == 7
                       and not failed and not timed_out and clean and child.returncode == 0)
        except Exception as failure:
            durable(events, {'event': 'supervisor-fault', 'phase': 'worker', 'reason': repr(failure)})
            success = query_timeout = False
        finally:
            if not cleanup_started:
                # Observe before cleanup can kill the worker. No earlier attempt failed.
                natural_status = owner.status(child)
                clean = True
            final_clean = stop()
            clean = clean and final_clean
            try:
                sel.close()
            except Exception as failure:
                durable(events, {'event': 'supervisor-fault', 'phase': 'selector-close', 'reason': repr(failure)})
                success = False
            success = success and clean
            owner.failed |= not success
            durable(events, {'event': 'worker-result', 'completed': success, 'returncode': child.returncode,
                             'naturalReturncode': natural_status, 'supervisorKilled': natural_status is None,
                             'crash': not done and not failed and not timed_out,
                             'warmups': warmups, 'samples': samples,
                             'medianNanos': statistics.median(samples) if samples else None,
                             'resources': None if stub else monitor.previous, 'cleanupVerified': clean,
                             'stop': [] if stub else list(monitor.reasons),
                             'qualification': 'contended' if monitor.warnings else 'no-observed-contention'})
        return success, query_timeout


HARNESS = {'dnq/src/test/kotlin/kotlinx/dnq/query/coverage/TypedFilterBenchmark.kt',
           'scripts/benchmarks/xd-1310.sh'}
ORIGINAL = 'db333a2c3e6addc84aa1dedc805d9d75ed6ea69b'


def input_state(root=ROOT, baseline=ORIGINAL):
    def git(*args):
        return run_command(['git', '-C', str(root), *args], policy['budgets']['aux'], 'input-git', True).decode()
    changed = set(filter(None, git('diff', '--name-only', baseline, '--').splitlines()))
    # Include staged and unstaged changes, including a checkout reverted to baseline over a changed HEAD.
    changed.update(filter(None, git('diff', '--name-only', 'HEAD', '--').splitlines()))
    changed.update(filter(None, git('diff', '--cached', '--name-only', 'HEAD', '--').splitlines()))
    extras = git('ls-files', '--others', '--exclude-standard', '-z').split('\0')
    ignored = git('ls-files', '--others', '--ignored', '--exclude-standard', '-z').split('\0')
    def metadata(path):
        return path.startswith(('.pi/', 'slate-changes/'))
    extras = [s for s in extras if s and not metadata(s)]
    # Ignored generated build output is not source. Ignored input files are still rejected.
    def generated(path):
        parts = Path(path).parts
        return parts[0] in ('build', '.gradle', '.idea') or (len(parts) > 1 and parts[1] == 'build')
    extras += [s for s in ignored if s and not metadata(s) and not generated(s)]
    state = {'revision': git('rev-parse', 'HEAD').strip(), 'originalProductRevision': baseline,
             'status': git('status', '--porcelain=v1', '--untracked-files=all'),
             'diff': git('diff', '--binary', 'HEAD', '--'),
             'stagedDiff': git('diff', '--cached', '--binary', 'HEAD', '--'),
             'productDiff': git('diff', '--binary', baseline, '--', '.',
                                *[':(exclude)' + s for s in sorted(HARNESS)]),
             'trackedHashes': {s: file_hash(root / s)
                               for s in git('ls-files', '-z').split('\0') if s and (root / s).is_file()}}
    if changed - HARNESS or extras or state['productDiff']:
        raise RuntimeError('Not an unchanged-product input: ' + repr(sorted(changed - HARNESS) + extras))
    return state


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while True:
            monitor.gate()
            chunk = stream.read(1024 * 1024)
            if not chunk:
                return digest.hexdigest()
            digest.update(chunk)


def executable_state(paths):
    result = {}
    for name in paths:
        path = Path(name)
        if not path.exists():
            result[name] = {'absent': True}
        elif path.is_dir():
            result[name] = {str(f.relative_to(path)): file_hash(f)
                            for f in sorted(path.rglob('*')) if f.is_file()}
        else:
            result[name] = file_hash(path)
    return result


if a.action == 'self-check':
    # Only Python protocol stubs use the bounded profile. No JVM admission bypass exists.
    def emit(kind, value=''):
        return f'print({("@@" + chr(9) + kind + chr(9) + value)!r},flush=True)\n'
    prefix = 'import time\ntime.sleep(.3)\n' + emit('setup-complete')
    good = prefix
    for label in Contract.labels:
        good += emit('ready', label) + 'assert input()=="GO"\n'
        good += emit('operation-complete', 'nanos=1' if label.startswith(('sample', 'warmup')) else label)
        # The worker checks ordered identities, verifies evidence, then emits validated.
        if label == 'diagnostic-observed':
            good += emit('diagnostics', 'native sources and plans')
        good += emit('validated')
    good += emit('cleanup') + emit('done')
    diagnostics = emit('diagnostics', 'native sources and plans')
    observed_complete = emit('operation-complete', 'diagnostic-observed')
    control_complete = emit('operation-complete', 'diagnostic-control')
    hang = prefix + emit('ready', 'warmup-0') + 'assert input()=="GO"\ntime.sleep(60)\n'
    first = prefix + emit('ready', 'warmup-0') + 'assert input()=="GO"\n' + emit('operation-complete', 'nanos=1')
    cases = [
        ('valid-startup-is-not-query', good, True, False, 2),
        ('query-deadline', hang, False, True, 2),
        ('setup-timeout', 'import time\ntime.sleep(60)', False, False, .1),
        ('crash', 'raise SystemExit(7)', False, False, 2),
        ('assertion', emit('assertion-failure'), False, False, 2),
        ('observer', emit('observer-failure'), False, False, 2),
        ('diagnostic-timeout', good[:good.index(emit('ready', 'diagnostic-control'))] +
         emit('ready', 'diagnostic-control') + 'assert input()=="GO"\ntime.sleep(60)', False, True, 2),
        ('done-without-validation', first + emit('done'), False, False, 2),
        ('pending-validation', first, False, False, 2),
        ('missing-sample', good.replace(emit('ready', 'sample-6'), emit('ready', 'diagnostic-control')), False, False, 2),
        ('wrong-sample-count', good.replace(emit('ready', 'sample-6'), emit('ready', 'sample-7')), False, False, 2),
        ('missing-diagnostics', good.replace(diagnostics, ''), False, False, 2),
        ('diagnostics-before-setup', diagnostics + good, False, False, 2),
        ('diagnostics-before-ready', good.replace(emit('ready', 'diagnostic-observed'),
                                               diagnostics + emit('ready', 'diagnostic-observed')), False, False, 2),
        ('diagnostics-before-operation-complete', good.replace(observed_complete + diagnostics,
                                                             diagnostics + observed_complete), False, False, 2),
        ('diagnostics-during-control-validation', good.replace(control_complete,
                                                              control_complete + diagnostics), False, False, 2),
        ('diagnostics-after-validation', good.replace(diagnostics + emit('validated'),
                                                    emit('validated') + diagnostics), False, False, 2),
        ('duplicate-diagnostics', good.replace(diagnostics, diagnostics * 2), False, False, 2),
        ('missing-warmup', good.replace(emit('ready', 'warmup-2'), emit('ready', 'sample-0')), False, False, 2),
        ('duplicate-operation-complete', good.replace(observed_complete, observed_complete * 2), False, False, 2),
        ('missing-control-validation', good.replace(control_complete + emit('validated'),
                                                  control_complete), False, False, 2),
        ('duplicate-validation', good.replace(diagnostics + emit('validated'),
                                             diagnostics + emit('validated') * 2), False, False, 2),
        ('validation-before-operation-complete', good.replace(observed_complete,
                                                            emit('validated') + observed_complete), False, False, 2),
        ('missing-cleanup', good.replace(emit('cleanup'), ''), False, False, 2),
        ('extra-sample', good.replace(emit('cleanup'), emit('ready', 'sample-7')), False, False, 2),
    ]
    for name, code, expected, censored, setup in cases:
        actual = supervise([sys.executable, '-c', code], run_dir / name, setup=setup, operation=.1, stub=True)
        assert actual == (expected, censored), (name, actual)
    # Exercise every cap boundary before/between/after forks and configurations.
    configs = [('account', 10000, 25, 'false'), ('catalog', 50000, 25, 'true')]
    for cutoff in range(5):
        launches = []
        rows = matrix(configs, lambda row: launches.append(row.copy()) or 'completed',
                      lambda: len(launches), cutoff, lambda *args: None)
        assert len(launches) == cutoff
        assert len(rows) == 4 and all(r['status'] != 'pending' for r in rows)
        assert all(r['status'] == 'completed' for r in rows) == (cutoff == 4)
    for fault in ('query-timeout', 'paused', 'blocked', 'exception'):
        launches = []
        records = []
        def launch(row):
            launches.append(row.copy())
            if len(launches) == 1:
                if fault == 'exception':
                    raise RuntimeError('injected')
                return fault
            return 'completed'
        try:
            rows = matrix(configs, launch, lambda: 0, 1, lambda event, rows: records.append(copy.deepcopy(rows)))
        except RuntimeError:
            assert fault == 'exception'
        assert len(records[-1]) == 4 and all(r['status'] not in ('pending', 'running') for r in records[-1])
        assert len(launches) == (3 if fault == 'query-timeout' else 4 if fault == 'blocked' else 1)
    healthy = {'cgroup': '0::/\n', 'meminfo': 'MemAvailable: 8388608 kB\n',
               'memory.current': str(8 * GiB), 'memory.max': str(16 * GiB), 'memory.swap.current': '100',
               'memory.events': 'high 0\nmax 0\noom 0\noom_kill 0\n', 'vmstat': 'pswpin 10\npswpout 10\n',
               'processes': [], 'load': [0, 0, 0], 'cpus': 2, 'cpu.max': 'max 100000',
               'disks': {k: {'path': v, 'device': 1, 'available': 8 * GiB} for k, v in monitor.paths.items()}}
    zero = 'some avg10=0 total=0\nfull avg10=0 total=0\n'
    healthy.update(dict.fromkeys(PSI, zero))
    def evaluate(current, runtime=False, timers=None, now=0):
        return assess(healthy, current, runtime, dict.fromkeys(PSI) if timers is None else timers, now)
    assert evaluate(healthy) == ([], [])
    for runtime, memory, disk in [(False, 4, 6), (True, 1, 5)]:
        for delta in (0, -1):
            row = copy.deepcopy(healthy)
            row['meminfo'] = 'MemAvailable: ' + str(memory * GiB // 1024 + delta) + ' kB'
            row['memory.current'] = str(16 * GiB - memory * GiB - delta)
            assert bool(evaluate(row, runtime)[0]) == (delta < 0)
            for label in row['disks']:
                item = copy.deepcopy(healthy)
                item['disks'][label]['available'] = disk * GiB + delta
                assert bool(evaluate(item, runtime)[0]) == (delta < 0)
    for key in healthy:
        row = healthy.copy()
        del row[key]
        assert evaluate(row)[0], ('missing', key)
    for field in PSI:
        for bad in ('NaN', 'inf', '-1', '101'):
            assert evaluate({**healthy, field: zero.replace('avg10=0', 'avg10=' + bad)})[0]
        timers = dict.fromkeys(PSI)
        row = {**healthy, field: zero.replace('full avg10=0', 'full avg10=20')}
        assert not evaluate(row, timers=timers)[0]
        assert not evaluate(row, timers=timers, now=29.999)[0]
        assert evaluate(row, timers=timers, now=30)[0] == ['sustained-' + field]
        evaluate(healthy, timers=timers, now=31)
        assert timers[field] is None
        assert not evaluate(row, timers=timers, now=100)[0]
    for field, value in [('vmstat', 'pswpin 11\npswpout 10\n'),
                         ('memoryPressure', zero.replace('total=0', 'total=17')),
                         ('ioPressure', zero.replace('avg10=0', 'avg10=1.4')),
                         ('load', [3, 0, 0]), ('memory.events', healthy['memory.events'].replace('high 0', 'high 1'))]:
        reasons, warnings = evaluate({**healthy, field: value})
        assert not reasons and warnings, (field, reasons, warnings)
    for field in ('oom', 'oom_kill', 'max'):
        assert evaluate({**healthy, 'memory.events': healthy['memory.events'].replace(field + ' 0', field + ' 1')})[0]
    assert evaluate({**healthy, 'vmstat': 'pswpin 9\npswpout 10\n'})[0]
    assert evaluate({**healthy, 'processes': [[123, 1, 1, 'java']]})[0]
    # No real capacity samples. The shared monitor consumes only this injected fixture.
    monitor.sampler = lambda: copy.deepcopy(healthy)
    monitor.check(admit=True)
    real_cap = cap
    cap = time.monotonic() - 1
    assert supervise([sys.executable, '-c', hang], run_dir / 'cap-before-launch') == (False, False)
    cap = real_cap
    # Isolated Git fixture. Never mutate the actual checkout for a negative control.
    with tempfile.TemporaryDirectory(dir=run_dir) as temp:
        root = Path(temp)
        def git(*args):
            return run_command(['git', '-C', temp, *args], policy['budgets']['aux'], 'fixture-git', True).decode().strip()
        git('init')
        (root / 'product.kt').write_text('original')
        for path in HARNESS:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('harness')
        git('add', '.')
        git('-c', 'user.name=fixture', '-c', 'user.email=fixture@invalid', 'commit', '-m', 'fixture')
        base = git('rev-parse', 'HEAD')
        input_state(root, base)
        (root / sorted(HARNESS)[0]).write_text('allowed development change')
        input_state(root, base)
        for staged in (False, True):
            (root / 'product.kt').write_text('dirty')
            if staged:
                git('add', 'product.kt')
            try:
                input_state(root, base)
                raise AssertionError('Dirty product accepted')
            except RuntimeError:
                pass
        git('-c', 'user.name=fixture', '-c', 'user.email=fixture@invalid', 'commit', '-m', 'after product')
        try:
            input_state(root, base)
            raise AssertionError('After-product revision accepted as original')
        except RuntimeError:
            pass
        # The worktree can match HEAD while the index contains a product change.
        (root / 'product.kt').write_text('staged-only')
        git('add', 'product.kt')
        (root / 'product.kt').write_text('dirty')
        try:
            input_state(root, git('rev-parse', 'HEAD'))
            raise AssertionError('Staged-only product change accepted')
        except RuntimeError:
            pass
        runtime = root / 'runtime'
        runtime.mkdir()
        (runtime / 'worker.class').write_bytes(b'first')
        hashes = executable_state([str(runtime)])
        (runtime / 'worker.class').write_bytes(b'changed')
        assert executable_state([str(runtime)]) != hashes
    # Negative fixtures are expected check inputs, not required workload failures.
    owner.failed = owner.cleanup_failed = False
    durable(events, {'event': 'self-check-passed', 'protocolCases': len(cases),
                     'checks': 'cap boundaries, fault ledger, resource negatives, original/dirty/after inputs, runtime drift'})
    exit_invocation(0)

if a.action == 'pilot':
    configs = [('account', 10000, 25, 'false')]
elif a.action == 'check':
    configs = [(d, 200, pct, mode) for d, pct in [('account', 1), ('account', 25), ('account', 75),
                                               ('catalog', 1), ('catalog', 25)] for mode in ['true', 'false']]
elif a.action == 'campaign':
    # Required 100k cases remain in the ledger even without launch permission.
    configs = [(d, size, pct, mode) for size in [10000, 50000, 100000]
               for d, pct in [('account', 1), ('account', 25), ('account', 75), ('catalog', 1), ('catalog', 25)]
               for mode in ['true', 'false']]
else:
    configs = []
ledger = [{'config': list(config), 'fork': fork, 'status': 'pending'} for config in configs for fork in range(2)]
finalized = False


def record_matrix(event, rows):
    global finalized
    if event == 'action-result':
        finalized = True
    durable(events, {'event': event, 'cases': rows,
                     'incomplete': [row for row in rows if row['status'] != 'completed'],
                     'baselineComplete': a.action == 'campaign' and len(rows) == 60 and
                                         all(row['status'] == 'completed' for row in rows)})


def finish_unexpected():
    if not finalized:
        for row in ledger:
            if row['status'] in ('pending', 'running'):
                row['status'] = 'paused-unexecuted'
        record_matrix('action-result', ledger)


atexit.register(finish_unexpected)
record_matrix('manifest', ledger)
# Capture evidence even when the input guard refuses compilation.
(run_dir / 'tracked-status.txt').write_bytes(run_command(['git', 'status', '--porcelain=v1'], policy['budgets']['aux'], 'status', True))
(run_dir / 'tracked-diff.patch').write_bytes(run_command(['git', 'diff', '--binary', 'HEAD', '--'], policy['budgets']['aux'], 'diff', True))
frozen_input = input_state()
(run_dir / 'inputs.json').write_text(json.dumps(frozen_input, indent=2))
admission()
# External init script supplies a runtime classpath without changing repository build configuration.
init = run_dir / 'classpath.gradle'
init.write_text('''gradle.projectsEvaluated {
    def p = gradle.rootProject.project(':dnq')
    p.tasks.register('xd1310Classpath') {
        dependsOn p.tasks.named('testClasses')
        doLast { new File(System.getProperty('xd1310.classpath')).text = p.sourceSets.test.runtimeClasspath.asPath }
    }
}
''')
classpath = run_dir / 'classpath.txt'
command = ['./gradlew', '--offline', '--no-daemon', '--rerun-tasks', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process',
           '-Dorg.gradle.jvmargs=-Xmx768m', '-Dxd1310.classpath=' + str(classpath), '-I', str(init), ':dnq:xd1310Classpath']
monitor.paths = destinations(command)
build = run_command(command, policy['budgets']['compile'], 'compile')
def recheck_inputs():
    current = input_state()
    # Metadata may grow while the agent runs. Preserve it without using it as product input.
    assert {k: v for k, v in current.items() if k != 'status'} == {
        k: v for k, v in frozen_input.items() if k != 'status'}, 'Source drift since compilation'


recheck_inputs()
java = str(Path(shutil.which('java')).resolve())
runtime_paths = classpath.read_text().split(os.pathsep) + [java]
frozen_runtime = executable_state(runtime_paths)
provenance = {'inputs': frozen_input, 'java': run_command([java, '-version'], policy['budgets']['aux'], 'java-version', True).decode(),
              'classpath': classpath.read_text(), 'executables': frozen_runtime,
              'protocol': {'forks': 2, 'warmups': 3, 'samples': 7, 'pageSize': 51, 'seed': 1310,
                           'heap': '2GiB', 'storage': 'MEMORY', 'effectivePolicy': policy}}
(run_dir / 'provenance.json').write_text(json.dumps(provenance, indent=2))
for source in sorted(HARNESS) + ['dnq/src/test/kotlin/kotlinx/dnq/query/coverage/TypedFilterDataset.kt']:
    (run_dir / Path(source).name).write_bytes(Path(source).read_bytes())
if a.action == 'compile':
    durable(events, {'event': 'compile-passed'})
    exit_invocation(0)


def launch(row):
    domain, size, pct, mode = row['config']
    if size == 100000 and not a.allow_100k:
        return 'blocked'
    recheck_inputs()
    assert executable_state(runtime_paths) == frozen_runtime, 'Executable drift since compilation'
    durable(events, {'event': 'provenance-rechecked', 'config': row['config'], 'fork': row['fork']})
    directory = run_dir / (f'{domain}-{size}-{pct}-{mode}-fork-' + str(row['fork']))
    command = [java, '-Xms256m', '-Xmx2g', '-Djava.io.tmpdir=' + str(run_dir),
               '-Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=' + mode,
               '-cp', provenance['classpath'], 'kotlinx.dnq.query.coverage.TypedFilterBenchmark',
               domain, str(size), str(pct), str(directory), 'false']
    ok, query_timeout = supervise(command, directory)
    recheck_inputs()
    assert executable_state(runtime_paths) == frozen_runtime, 'Executable drift during workload'
    return 'completed' if ok else 'query-timeout' if query_timeout else 'paused'


matrix(configs, launch, time.monotonic, cap, record_matrix, ledger)
exit_invocation(0 if all(row['status'] == 'completed' for row in ledger) else 1)
PY
