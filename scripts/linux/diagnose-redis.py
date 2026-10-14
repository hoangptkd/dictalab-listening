"""Read-only Redis transport/auth/INFO probe with redacted errors and verified TLS."""
import json
import pathlib
import shlex
import socket
import ssl
import time
import urllib.parse

settings = {}
for line in pathlib.Path('/etc/dictalab/app.env').read_text().splitlines():
    if line and not line.startswith('#'):
        key, value = line.split('=', 1)
        settings[key] = shlex.split(value)[0]
url = urllib.parse.urlsplit(settings['REDIS_URL'])
if url.scheme != 'rediss':
    raise SystemExit('Verified TLS Redis required')
stage = 'DNS'
server_error = None
try:
    begin = time.monotonic()
    addresses = socket.getaddrinfo(url.hostname,url.port or 6379,type=socket.SOCK_STREAM)
    output = {'dnsMs': (time.monotonic()-begin)*1000, 'addressCount': len(addresses)}
    stage = 'TCP'
    begin = time.monotonic()
    raw = socket.create_connection((url.hostname,url.port or 6379),timeout=4)
    output['connectMs'] = (time.monotonic()-begin)*1000
    stage = 'TLS'
    with ssl.create_default_context().wrap_socket(raw,server_hostname=url.hostname) as connection:
        stream = connection.makefile('rb')
        def command(*parts):
            global server_error
            wire = ('*'+str(len(parts))+'\r\n').encode()
            for part in parts:
                value = part.encode()
                wire += ('$'+str(len(value))+'\r\n').encode()+value+b'\r\n'
            connection.sendall(wire)
            header = stream.readline(8192)
            if header[:1] == b'-':
                code = header[1:].split(b' ',1)[0].decode(errors='replace').strip()
                message = header[1:].decode(errors='replace').strip()
                for secret in (settings['REDIS_URL'],url.password,urllib.parse.unquote(url.password or '')):
                    if secret:
                        message = message.replace(secret,'[REDACTED]')
                server_error = message[:300]
                allowed = {'ERR','OOM','WRONGPASS','NOPERM','NOAUTH','LOADING','MASTERDOWN','CLUSTERDOWN','BUSY'}
                raise ValueError(code if code in allowed else 'REDACTED')
            if header[:1] in (b'+',b':'):
                return header[1:].decode().strip()
            if header[:1] == b'$':
                length = int(header[1:])
                if not 0 <= length <= 1048576:
                    raise ValueError('REDACTED')
                data = stream.read(length)
                stream.read(2)
                return data.decode()
            raise ValueError('REDACTED')
        stage = 'AUTH'
        username = urllib.parse.unquote(url.username or '')
        password = urllib.parse.unquote(url.password or '')
        command(*(['AUTH',username,password] if username else ['AUTH',password]))
        stage = 'SELECT'
        command('SELECT',url.path.strip('/') or '0')
        stage = 'PING'
        begin = time.monotonic()
        output['ping'] = command('PING')
        output['pingMs'] = (time.monotonic()-begin)*1000
        stage = 'INFO'
        try:
            info = command('INFO')
            allow = {'used_memory','maxmemory','connected_clients','blocked_clients','rejected_connections',
                     'total_commands_processed','instantaneous_ops_per_sec','total_net_input_bytes',
                     'total_net_output_bytes','evicted_keys','keyspace_hits','keyspace_misses','uptime_in_seconds'}
            output['info'] = {key:value for key,value in
                              (line.split(':',1) for line in info.splitlines() if ':' in line)
                              if key in allow}
        except ValueError:
            output['infoUnavailable'] = True
        print(json.dumps({'stage':'COMPLETE',**output}))
except (OSError,ValueError) as error:
    print(json.dumps({'stage':stage,'errorType':type(error).__name__,
                      'errorCode':str(error) if isinstance(error,ValueError) else 'REDACTED',
                      'serverErrorRedacted':server_error}))
    raise SystemExit(1)
