"""Read-only bounded resource/Neon sampling; no credentials or query text in output."""
import argparse
import datetime
import importlib.util
import json
import os
import pathlib
import re
import shlex
import subprocess
import time
import urllib.request

def cpu_ticks():
    return [int(x) for x in pathlib.Path('/proc/stat').read_text().splitlines()[0].split()[1:9]]

def memory():
    return {k: int(v.split()[0]) * 1024 for k, v in
            (line.split(':', 1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines())}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seconds', type=int, required=True)
    args = parser.parse_args()
    if not 10 <= args.seconds <= 3600:
        raise SystemExit('Sampling duration must be 10..3600 seconds')
    config = {}
    for line in pathlib.Path('/etc/dictalab/app.env').read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            config[key] = shlex.split(value)[0]
    spec = importlib.util.spec_from_file_location('backup', '/opt/dictalab/tools/cloud-backup.py')
    backup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    pg_env = backup.connection_environment()
    pg_env['PGCONNECT_TIMEOUT'] = '3'
    pg_env['PGOPTIONS'] = '-c statement_timeout=2000 -c lock_timeout=1000 -c default_transaction_read_only=on'
    query = """SELECT json_build_object(
      'sessions', (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()),
      'active', (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active' AND pid<>pg_backend_pid()),
      'idleInTransaction', (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state LIKE 'idle in transaction%'),
      'lockWaiters', (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'),
      'waits', (SELECT coalesce(json_object_agg(kind,n),'{}'::json) FROM
          (SELECT coalesce(wait_event_type,'CPU_or_running') kind,count(*) n FROM pg_stat_activity
           WHERE datname=current_database() AND pid<>pg_backend_pid() GROUP BY wait_event_type) s),
      'commits', xact_commit, 'rollbacks', xact_rollback, 'deadlocks', deadlocks,
      'blocksRead', blks_read, 'blocksHit', blks_hit, 'tempBytes', temp_bytes,
      'databaseBytes', pg_database_size(datname)) FROM pg_stat_database WHERE datname=current_database();"""
    previous = cpu_ticks()
    last_time = time.monotonic()
    previous_app = None
    previous_pid = None
    deadline = last_time + args.seconds
    index = 0
    while time.monotonic() < deadline:
        now = time.monotonic()
        row = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'cpus': os.cpu_count()}
        current = cpu_ticks()
        delta = [b-a for a,b in zip(previous, current)]
        total = sum(delta)
        row['cpuPercent'] = 100*(total-delta[3]-delta[4])/total if total else None
        row['iowaitPercent'] = 100*delta[4]/total if total else None
        row['stealPercent'] = 100*delta[7]/total if total else None
        previous = current
        mem = memory()
        row.update(ramTotalBytes=mem['MemTotal'], ramAvailableBytes=mem['MemAvailable'],
                   swapUsedBytes=mem['SwapTotal']-mem['SwapFree'])
        row['paging'] = {k:int(v) for k,v in
                         (line.split() for line in pathlib.Path('/proc/vmstat').read_text().splitlines())
                         if k in ('pswpin','pswpout','pgmajfault')}
        try:
            row['memoryEvents'] = {k:int(v) for k,v in
                                   (line.split() for line in pathlib.Path('/sys/fs/cgroup/system.slice/dictalab.service/memory.events').read_text().splitlines())}
        except OSError:
            row['memoryEventsUnavailable'] = True
        row['loadAverage'] = os.getloadavg()
        try:
            broker_cgroup=pathlib.Path('/sys/fs/cgroup/system.slice/rabbitmq-server.service')
            row['rabbitMemoryBytes']=int((broker_cgroup/'memory.current').read_text())
            row['rabbitMemoryEvents']={k:int(v) for k,v in (line.split() for line in (broker_cgroup/'memory.events').read_text().splitlines())}
        except (OSError,ValueError):
            row['rabbitUnavailable']=True
        props = subprocess.run(['systemctl','show','dictalab','-p','MainPID','-p','NRestarts','-p','ActiveState'],
                               capture_output=True,text=True,timeout=3).stdout
        row['service'] = dict(line.split('=',1) for line in props.splitlines() if '=' in line)
        try:
            pid = int(row['service']['MainPID'])
            status = pathlib.Path(f'/proc/{pid}/status').read_text()
            row['appRSSBytes'] = int(re.search(r'^VmRSS:\s+(\d+)',status,re.M)[1])*1024
            row['appSwapBytes'] = int(re.search(r'^VmSwap:\s+(\d+)',status,re.M)[1])*1024
            stat = pathlib.Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()
            app = int(stat[11])+int(stat[12])
            row['appCPUPercent'] = (100*(app-previous_app)/os.sysconf('SC_CLK_TCK')/(now-last_time)/os.cpu_count()
                                    if previous_app is not None and previous_pid == pid and now>last_time else None)
            previous_app, previous_pid = app, pid
        except (OSError,KeyError,TypeError,ValueError):
            row['appUnavailable'] = True
        last_time = now
        try:
            with urllib.request.urlopen('http://127.0.0.1:18183/actuator/prometheus',timeout=2) as response:
                lines = response.read().decode().splitlines()
            row['metrics'] = [line for line in lines if '_bucket{' not in line and re.match(
                r'^(jvm_(memory_|gc_|threads_)|hikaricp_|process_cpu_|system_cpu_|http_server_requests_|dictalab_|spring_rabbit|rabbitmq_)',line)]
        except (OSError,TimeoutError):
            row['metricsUnavailable'] = True
        if index % 3 == 0:
            begin = time.monotonic()
            try:
                result = subprocess.run(['/usr/lib/postgresql/18/bin/psql','-X','-qAt','-v','ON_ERROR_STOP=1','-c',query],
                                        env=pg_env,capture_output=True,text=True,timeout=5)
                row['postgresProbeMs'] = (time.monotonic()-begin)*1000
                row['postgres'] = json.loads(result.stdout) if result.returncode == 0 else {'unavailable': True}
            except (subprocess.TimeoutExpired,ValueError):
                row['postgres'] = {'unavailable': True}
            row['queues'] = {key: config.get(key,'false') for key in
                             ('DICTALAB_AI_QUEUE_ENABLED','DICTALAB_LISTENING_QUEUE_ENABLED','DICTALAB_LEARNING_QUEUE_ENABLED')}
        if index % 12 == 0 and config.get('DICTALAB_LEARNING_QUEUE_ENABLED') == 'true':
            cli_env=dict(os.environ,ERL_FLAGS='+S 1:1 +A 1')
            try:
                result=subprocess.run(['rabbitmqctl','-q','list_queues','-p','dictalab-learning','name','messages_ready','messages_unacknowledged','consumers','--formatter=json'],
                                      env=cli_env,capture_output=True,text=True,timeout=6)
                row['rabbitQueues']=json.loads(result.stdout) if result.returncode==0 else {'unavailable':True}
                alarm=subprocess.run(['rabbitmq-diagnostics','-q','alarms'],env=cli_env,capture_output=True,text=True,timeout=6)
                row['rabbitAlarms']=alarm.stdout.strip() if alarm.returncode==0 else 'unavailable'
            except (subprocess.TimeoutExpired,ValueError,OSError):
                row['rabbitProbeUnavailable']=True
        if index % 6 == 0:
            try:
                probe = subprocess.run(['python3','/home/ubuntu/dictalab-migration/diagnose-redis.py'],
                                       capture_output=True,text=True,timeout=8)
                redis = json.loads(probe.stdout)
                row['redis'] = {key:redis[key] for key in ('stage','pingMs','connectMs','info','infoUnavailable','errorType')
                                if key in redis}
                row['redis']['ok'] = probe.returncode == 0 and redis.get('ping') == 'PONG'
            except (subprocess.TimeoutExpired,ValueError,OSError):
                row['redis'] = {'unavailable':True}
        print(json.dumps(row),flush=True)
        index += 1
        time.sleep(min(5,max(0,deadline-time.monotonic())))

if __name__ == '__main__':
    main()
