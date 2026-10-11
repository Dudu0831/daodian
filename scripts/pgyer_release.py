#!/usr/bin/env python3
"""Build, publish to Pgyer, verify, stage the backup, then switch latest.json."""
import argparse
import fcntl
from hashlib import md5, sha256
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

ROOT = Path(__file__).resolve().parent.parent
API = 'https://www.pgyer.com/apiv2/app/'
IDENTIFIER = 'com.abc.daodian'
CERTIFICATE = '19be2f072a81c8a2f50928a66b5b8128aca6d30e4f167344234ba5c4104f4eb8'
ANDROID_UA = 'Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36'
# 蒲公英存安装包的几个主机。0.1.5 那次它把下载跳到了 cos3，不在名单里，发布停在了核对那一步
DOWNLOAD_HOSTS = ('oss.pgyer.com', 'cos3.pgyer.com', 'pgyer-apps-cfw.appmeme.com')


class ReleaseError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def save_json(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_json_url(url):
    try:
        with urlopen(Request(url, headers={'Cache-Control': 'no-cache'}), timeout=30) as response:
            data = json.load(response)
        if not isinstance(data, dict):
            raise ValueError()
        return data
    except Exception:
        raise ReleaseError('无法读取线上版本；已停止，不会按版本 0 继续发布') from None


def api_call(api_key, method, fields, get=False):
    body = urlencode({'_api_key': api_key, **fields}).encode()
    request = Request(API + method + ('?' + body.decode() if get else ''),
                      data=None if get else body)
    try:
        with urlopen(request, timeout=45) as response:
            result = json.load(response)
        if not isinstance(result, dict) or not isinstance(result.get('code'), int):
            raise ValueError()
        return result
    except Exception:
        # urllib exceptions can contain the URL with _api_key. Never print them.
        raise ReleaseError(f'蒲公英 {method} 请求失败，请稍后重试') from None


def api_data(result, label):
    if result['code'] != 0 or not isinstance(result.get('data'), dict):
        raise ReleaseError(f'蒲公英 {label} 返回错误码 {result["code"]}')
    return result['data']


def multipart_upload(endpoint, params, apk):
    url = urlsplit(endpoint)
    if (url.scheme != 'https' or not url.hostname or not url.hostname.endswith('.myqcloud.com')
            or url.username or url.password or url.port not in (None, 443)
            or url.query or url.fragment):
        raise ReleaseError('蒲公英返回了非预期的上传地址')
    boundary = 'daodian-' + secrets.token_hex(24)
    chunks = []
    for key in ('key', 'signature', 'x-cos-security-token'):
        value = str(params[key])
        chunks.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n'
                       f'{value}\r\n').encode())
    chunks.append((f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                   'filename="app-release.apk"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n').encode())
    prefix, suffix = b''.join(chunks), f'\r\n--{boundary}--\r\n'.encode()
    connection = http.client.HTTPSConnection(url.hostname, timeout=90, context=ssl.create_default_context())
    try:
        connection.putrequest('POST', url.path or '/')
        connection.putheader('Content-Type', f'multipart/form-data; boundary={boundary}')
        connection.putheader('Content-Length', str(len(prefix) + apk.stat().st_size + len(suffix)))
        connection.endheaders()
        connection.send(prefix)
        with apk.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                connection.send(chunk)
        connection.send(suffix)
        response = connection.getresponse()
        status = response.status
        response.read(65536)
        if status != 204:
            raise ReleaseError(f'安装包上传失败：HTTP {status}')
    except ReleaseError:
        raise
    except Exception:
        raise ReleaseError('安装包上传连接中断；进度已保存，重试会先查询原上传结果') from None
    finally:
        connection.close()


def wait_for_build(api_key, upload_key, call=api_call, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + 600
    while clock() < deadline:
        result = call(api_key, 'buildInfo', {'buildKey': upload_key}, get=True)
        if result['code'] == 0:
            return api_data(result, 'buildInfo')
        if result['code'] != 1247:
            raise ReleaseError(f'蒲公英发布未完成：错误码 {result["code"]}；未切换线上版本')
        sleep(5)
    raise ReleaseError('蒲公英处理超过 10 分钟；重跑同一命令会继续查询，不会重复上传')


def validate_build(build, artifact, shortcut):
    try:
        valid = (build['buildIdentifier'] == IDENTIFIER
                 and build['buildVersion'] == artifact['version_name']
                 and int(build['buildVersionNo']) == artifact['version_code']
                 and int(build['buildFileSize']) == artifact['expected_size']
                 and build['buildShortcutUrl'] == shortcut
                 and re.fullmatch(r'[a-f0-9]{32}', build['buildKey']))
    except (KeyError, TypeError, ValueError):
        valid = False
    if not valid:
        raise ReleaseError('蒲公英包名、版本、大小或应用归属不一致；未切换线上版本')


def validate_download(url, api_key):
    try:
        parsed = urlsplit(url)
        if (parsed.scheme != 'https' or parsed.hostname not in DOWNLOAD_HOSTS
                or parsed.username or parsed.password or parsed.port not in (None, 443)
                or not parsed.path.lower().endswith('.apk') or parsed.fragment
                or '\r' in url or '\n' in url or '_api_key' in parsed.query or api_key in url):
            raise ValueError()
    except ValueError:
        raise ReleaseError('非预期的安装包下载地址') from None


def install_target(api_key, build_key):
    request = Request(API + 'install?' + urlencode({'_api_key': api_key, 'buildKey': build_key}),
                      headers={'User-Agent': ANDROID_UA})
    try:
        with build_opener(NoRedirect()).open(request, timeout=45):
            raise ReleaseError('蒲公英安装接口没有返回下载跳转')
    except HTTPError as response:
        try:
            if response.code != 302:
                raise ReleaseError(f'蒲公英安装接口 HTTP {response.code}')
            target = response.headers.get('Location', '')
        finally:
            response.close()
    except ReleaseError:
        raise
    except Exception:
        raise ReleaseError('蒲公英安装接口连接失败') from None
    validate_download(target, api_key)
    return target


def verify_download(api_key, build_key, artifact):
    target = install_target(api_key, build_key)
    sha, md, size = sha256(), md5(), 0
    try:
        with build_opener(NoRedirect()).open(Request(target, headers={
                'User-Agent': ANDROID_UA, 'Accept-Encoding': 'identity'}), timeout=90) as response:
            if response.status != 200:
                raise ReleaseError('蒲公英安装包未能完整下载')
            for chunk in iter(lambda: response.read(1024 * 1024), b''):
                size += len(chunk)
                if size > artifact['expected_size']:
                    raise ReleaseError('蒲公英安装包大小不一致')
                sha.update(chunk)
                md.update(chunk)
    except ReleaseError:
        raise
    except Exception:
        raise ReleaseError('蒲公英安装包下载校验失败') from None
    if (size != artifact['expected_size'] or sha.hexdigest() != artifact['expected_sha256']
            or md.hexdigest() != artifact['expected_md5']):
        raise ReleaseError('蒲公英安装包校验和不一致；未切换线上版本')


def probe_public(url, api_key, artifact, apk):
    try:
        with build_opener(NoRedirect()).open(Request(url, method='HEAD', headers={
                'User-Agent': 'AndroidDownloadManager'}), timeout=65):
            raise ReleaseError('新版本转接未生效；未切换线上版本')
    except HTTPError as response:
        try:
            if response.code != 302:
                raise ReleaseError(f'新版本转接检查失败：HTTP {response.code}')
            target = response.headers.get('Location', '')
            validate_download(target, api_key)
        finally:
            response.close()
    except ReleaseError:
        raise
    except Exception:
        raise ReleaseError('新版本下载入口连接失败；未切换线上版本') from None
    # Exercise the same User-Agent used by Android's system downloader, including resume.
    start = 1048576 if artifact['expected_size'] > 2097152 else 0
    end = min(start + 1023, artifact['expected_size'] - 1)
    try:
        with build_opener(NoRedirect()).open(Request(target, headers={
                'User-Agent': 'AndroidDownloadManager', 'Accept-Encoding': 'identity',
                'Range': f'bytes={start}-{end}'}), timeout=65) as response:
            if (response.status != 206 or response.headers.get('Content-Range') !=
                    f'bytes {start}-{end}/{artifact["expected_size"]}'):
                raise ReleaseError('系统下载器的断点续传检查失败')
            actual = response.read(2048)
        with apk.open('rb') as stream:
            stream.seek(start)
            expected = stream.read(end - start + 1)
        if actual != expected:
            raise ReleaseError('公网下载内容与新安装包不一致')
    except ReleaseError:
        raise
    except Exception:
        raise ReleaseError('系统下载器的公网分段下载失败；未切换线上版本') from None


class Server:
    def __init__(self, host, key):
        if not re.fullmatch(r'[A-Za-z0-9.-]+', host):
            raise ReleaseError('Invalid DEPLOY_HOST')
        self.host, self.key = host, key
        self.ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                    '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3', '-i', key, f'root@{host}']

    def run(self, command, payload=None):
        result = subprocess.run(self.ssh + [command], input=payload, text=True, capture_output=True)
        if result.returncode:
            # Never emit unknown remote output: it may contain credentials.
            try:
                message = json.loads(result.stdout).get('error', '服务器发布步骤失败')
            except ValueError:
                message = '服务器连接或发布步骤失败'
            raise ReleaseError(message)
        return result.stdout

    def check(self):
        command = "test -x /usr/local/lib/daodian-pgyer/register_release.py && python3 -c " + shlex.quote(
            "from urllib.request import urlopen; "
            "assert urlopen('http://127.0.0.1:8734/healthz', timeout=3).read() == b'OK registry-v2\\n'")
        self.run(command)

    def upload(self, apk, token):
        self.run('install -d -m 700 /srv/daodian-app/.incoming')
        result = subprocess.run(['scp', '-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                                 '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
                                 '-i', self.key, str(apk), f'root@{self.host}:/srv/daodian-app/.incoming/{token}.apk'])
        if result.returncode:
            raise ReleaseError('服务器备份上传失败；线上版本未切换')

    def action(self, payload):
        return json.loads(self.run('python3 /usr/local/lib/daodian-pgyer/register_release.py',
                                   json.dumps(payload)))


def planned_version():
    source = (ROOT / 'app/build.gradle.kts').read_text()
    code = re.search(r'^\s*versionCode\s*=\s*(\d+)', source, re.MULTILINE)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.MULTILINE)
    if not code or not name or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', name[1]):
        raise ReleaseError('无法读取有效的 versionCode/versionName')
    return int(code[1]), name[1]


def build_apk(sdk, code, name):
    print('正在构建签名安装包…', flush=True)
    subprocess.run([str(ROOT / 'gradlew'), ':app:assembleRelease', '-q'], cwd=ROOT, check=True)
    out = ROOT / 'app/build/outputs/apk/release'
    apk = out / 'app-release.apk'
    output = json.loads((out / 'output-metadata.json').read_text())
    if output.get('applicationId') != IDENTIFIER:
        raise ReleaseError('打包结果的应用包名不正确')
    metadata = output['elements'][0]
    if metadata['versionCode'] != code or metadata['versionName'] != name:
        raise ReleaseError('打包结果与计划版本不一致')
    # Freeze the artifact before validation/upload; another Gradle build may replace its output.
    archive = ROOT / '.local/pgyer-apks'
    archive.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.build-', suffix='.apk', dir=archive)
    os.close(fd)
    shutil.copyfile(apk, temporary)
    apk = Path(temporary)
    result = subprocess.run([str(sdk / 'build-tools/35.0.0/apksigner'), 'verify', '--print-certs', str(apk)],
                            capture_output=True, text=True)
    fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', result.stdout)
    if result.returncode or fingerprints != [CERTIFICATE]:
        apk.unlink(missing_ok=True)
        raise ReleaseError('安装包签名证书不正确，已停止发布')
    sha, md = sha256(), md5()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            sha.update(chunk); md.update(chunk)
    snapshot = archive / (sha.hexdigest() + '.apk')
    os.replace(apk, snapshot)
    apk = snapshot
    return apk, {'version_code': code, 'version_name': name, 'identifier': IDENTIFIER,
                 'original_path': f'/app/daodian-{name}.apk', 'expected_size': apk.stat().st_size,
                 'expected_sha256': sha.hexdigest(), 'expected_md5': md.hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='只检查配置和连接，不打包、不上传、不发布')
    parser.add_argument('notes', nargs='?', help='这一版的更新说明')
    args = parser.parse_args()
    if not args.check and not (args.notes and args.notes.strip()):
        parser.error('请填写更新说明，或使用 --check')
    config_path = Path(os.environ.get('PGYER_CONFIG', str(ROOT.parent / 'daodian-web/.local/pgyer-download.json')))
    config = json.loads(config_path.read_text())
    api_key = str(config['api_key']).strip()
    page = urlsplit(config['app_page_url'])
    shortcut = page.path.strip('/')
    if (not api_key or page.scheme != 'https' or page.hostname != 'www.pgyer.com'
            or not re.fullmatch(r'[A-Za-z0-9_-]+', shortcut) or page.query or page.fragment
            or page.username or page.password or page.port not in (None, 443)):
        raise ReleaseError('蒲公英私有配置不完整或应用页面地址无效')
    host = os.environ.get('DEPLOY_HOST', '104.168.64.160')
    site = os.environ.get('DEPLOY_SITE', f'https://{host.replace(".", "-")}.sslip.io').rstrip('/')
    site_url = urlsplit(site)
    if (site_url.scheme != 'https' or not site_url.hostname or site_url.path or site_url.query or site_url.fragment
            or site_url.username or site_url.password or site_url.port not in (None, 443)):
        raise ReleaseError('DEPLOY_SITE 必须是 HTTPS 站点地址')
    server = Server(host, os.environ.get('DEPLOY_ROOT_KEY', str(Path.home() / '.ssh/id_ed25519')))
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    os.environ['ANDROID_HOME'] = str(sdk)
    if not (sdk / 'build-tools/35.0.0/apksigner').is_file():
        raise ReleaseError('找不到 Android build-tools 35.0.0 的 apksigner')
    code, name = planned_version()
    online = read_json_url(site + '/app/latest.json')
    if not isinstance(online.get('versionCode'), int) or online['versionCode'] <= 0:
        raise ReleaseError('线上版本文件无效，已停止发布')
    server.check()
    current = api_data(api_call(api_key, 'getByShortcut', {'buildShortcutUrl': shortcut}), 'getByShortcut')
    if current.get('buildIdentifier') != IDENTIFIER:
        raise ReleaseError('蒲公英应用包名不一致')
    install_type = int(config.get('build_install_type', 1))
    if install_type != 1:
        raise ReleaseError('当前下载转接使用公开安装；其他安装方式需要单独配置')
    if args.check:
        print(f'配置检查通过：蒲公英应用、服务器多版本转接、签名工具均可用。线上 {online["versionName"]}（{online["versionCode"]}）。')
        print(f'本地计划 {name}（{code}）' + ('；可以发布。' if code > online['versionCode'] else '；发布前请增加版本号。'))
        return
    state_dir = ROOT / '.local/pgyer-releases'
    # Check eligibility before an expensive build or any upload.
    if code < online['versionCode']:
        raise ReleaseError('本地版本低于线上版本；请增加 versionCode')
    if code == online['versionCode']:
        previous = list(state_dir.glob('*.json')) if state_dir.exists() else []
        for path in previous:
            state = json.loads(path.read_text())
            if state.get('feed') == online and state.get('notes') == args.notes and state.get('phase') in ('staged', 'committed'):
                server.action({'action': 'commit', 'token': state['token']})
                state['phase'] = 'committed'; save_json(path, state)
                print(f'这个版本已发布成功：{name}（{code}），无需重复上传。')
                return
        raise ReleaseError(f'versionCode {code} 不比线上 {online["versionCode"]} 大；请先增加版本号')
    apk, artifact = build_apk(sdk, code, name)
    state_path = state_dir / (artifact['expected_sha256'] + '.json')
    if state_path.exists():
        state = json.loads(state_path.read_text())
        if state['artifact'] != artifact or state['notes'] != args.notes:
            raise ReleaseError('同一个安装包已有发布进度，请使用原更新说明继续')
    else:
        state = {'artifact': artifact, 'notes': args.notes, 'token': secrets.token_hex(16), 'phase': 'new'}
    if state_path.exists() and state['phase'] == 'upload_started' and 'build' not in state:
        # A confirmed failed publication can be retried; an ambiguous result must not duplicate it.
        result = api_call(api_key, 'buildInfo', {'buildKey': state['upload_key']}, get=True)
        if result['code'] == 1216:
            state['phase'] = 'new'
            state.pop('upload_key', None)
            save_json(state_path, state)
            print('蒲公英确认上次发布失败，重新上传同一个安装包…', flush=True)
        elif result['code'] == 0:
            state['build'] = api_data(result, 'buildInfo')
            save_json(state_path, state)
        elif result['code'] != 1247:
            raise ReleaseError(f'原上传状态暂时无法确认（错误码 {result["code"]}）；保留进度：{state_path}')
    if state['phase'] == 'new':
        print('正在取得蒲公英上传地址…', flush=True)
        token = api_data(api_call(api_key, 'getCOSToken', {'buildType': 'android', 'buildInstallType': 1,
                                                         'buildUpdateDescription': args.notes}), 'getCOSToken')
        state['upload_key'] = token['key']
        state['phase'] = 'upload_started'
        # Persist before sending the file so a lost response cannot trigger duplicate uploads.
        save_json(state_path, state)
        print('正在上传安装包到蒲公英…', flush=True)
        multipart_upload(token['endpoint'], token['params'], apk)
    if 'build' not in state:
        print('等待蒲公英处理并发布（通常约一分钟）…', flush=True)
        state['build'] = wait_for_build(api_key, state['upload_key'])
        save_json(state_path, state)
    validate_build(state['build'], artifact, shortcut)
    record = {**artifact, 'build_key': state['build']['buildKey']}
    feed = {'versionCode': code, 'versionName': name, 'url': site + artifact['original_path'],
            'size': artifact['expected_size'], 'notes': args.notes}
    if state['phase'] != 'staged':
        print('正在下载核对蒲公英安装包的 SHA-256…', flush=True)
        verify_download(api_key, record['build_key'], artifact)
        print('正在上传服务器备份并登记版本转接…', flush=True)
        server.upload(apk, state['token'])
        server.action({'action': 'stage', 'token': state['token'], 'record': record, 'feed': feed,
                       'online_version': online['versionCode']})
        state['phase'], state['feed'] = 'staged', feed
        save_json(state_path, state)
    print('正在检查原下载地址的转接…', flush=True)
    probe_public(feed['url'], api_key, artifact, apk)
    server.action({'action': 'commit', 'token': state['token']})
    state['phase'] = 'committed'; save_json(state_path, state)
    if read_json_url(site + '/app/latest.json') != feed:
        raise ReleaseError('服务器已提交，但公网版本读取不一致；请先检查，勿重复上传')
    print(f'发好了：{name}（{code}）\n下载地址：{feed["url"]}\n蒲公英页面：{config["app_page_url"]}')


if __name__ == '__main__':
    try:
        lock_dir = ROOT / '.local'
        lock_dir.mkdir(mode=0o700, exist_ok=True)
        with (lock_dir / 'pgyer-publish.lock').open('a') as lock:
            os.chmod(lock.name, 0o600)
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ReleaseError('已有发布命令正在运行，请等待它结束') from None
            main()
    except ReleaseError as error:
        print(f'发布停止：{error}', file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError):
        print('发布停止：本地配置、构建或发布进度异常；线上版本不会因未完成的前置步骤切换。', file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print('\n已中断；重跑同一命令会读取已保存的发布进度。', file=sys.stderr)
        sys.exit(130)
