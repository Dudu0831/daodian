"""Test API state handling and stop publication on unavailable/wrong metadata."""
import unittest
from contextlib import ExitStack
from hashlib import md5, sha256
import json
from pathlib import Path
import tempfile
import io
from unittest.mock import patch
import pgyer_release as release


class UploadTests(unittest.TestCase):
    def test_streaming_upload_sends_a_complete_multipart_with_exact_content_length(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app-release.apk'
            content = b'APK binary\x00\xff'
            apk.write_bytes(content)
            connection = unittest.mock.Mock()
            connection.getresponse.return_value.status = 204
            sent, headers = [], {}
            connection.send.side_effect = sent.append
            connection.putheader.side_effect = lambda key, value: headers.update({key: value})
            with patch.object(release.http.client, 'HTTPSConnection', return_value=connection):
                release.multipart_upload('https://upload.cos.ap-shanghai.myqcloud.com',
                    {'key': 'apps/new.apk', 'signature': 'signed', 'x-cos-security-token': 'token'}, apk)
            body = b''.join(sent)
            self.assertEqual(int(headers['Content-Length']), len(body))
            self.assertIn(content, body)
            self.assertIn(b'name="signature"\r\n\r\nsigned', body)
            self.assertIn(b'name="x-cos-security-token"\r\n\r\ntoken', body)
            self.assertIn(b'name="file"; filename="app-release.apk"', body)
            boundary = headers['Content-Type'].split('boundary=')[1].encode()
            self.assertTrue(body.endswith(b'--' + boundary + b'--\r\n'))

    def test_poll_uses_upload_key_then_returns_final_build_key(self):
        replies = iter([{'code': 1247}, {'code': 0, 'data': {'buildKey': 'final-build'}}])
        seen = []
        def call(key, method, fields, get):
            seen.append((method, fields, get))
            return next(replies)
        result = release.wait_for_build('private', 'apps/upload-key.apk', call=call,
                                        sleep=lambda _: None, clock=lambda: 0)
        self.assertEqual(result['buildKey'], 'final-build')
        self.assertEqual(seen, [('buildInfo', {'buildKey': 'apps/upload-key.apk'}, True)] * 2)

    def test_failed_processing_stops(self):
        with self.assertRaises(release.ReleaseError):
            release.wait_for_build('private', 'upload-key', call=lambda *a, **k: {'code': 1216},
                                   clock=lambda: 0)

    def test_unavailable_feed_never_becomes_version_zero(self):
        with patch.object(release, 'urlopen', side_effect=OSError('network failure')):
            with self.assertRaises(release.ReleaseError):
                release.read_json_url('https://example.test/app/latest.json')

    def test_wrong_app_version_and_shortcut_are_rejected(self):
        artifact = {'version_name': '0.1.5', 'version_code': 7, 'expected_size': 100}
        build = {'buildIdentifier': release.IDENTIFIER, 'buildVersion': '0.1.5',
                 'buildVersionNo': '7', 'buildFileSize': '100', 'buildShortcutUrl': 'existing-app',
                 'buildKey': 'a' * 32}
        release.validate_build(build, artifact, 'existing-app')
        for field, value in [('buildIdentifier', 'other.app'), ('buildVersionNo', '6'),
                             ('buildShortcutUrl', 'new-app'), ('buildFileSize', '99')]:
            with self.subTest(field=field):
                with self.assertRaises(release.ReleaseError):
                    release.validate_build({**build, field: value}, artifact, 'existing-app')

    def test_known_pgyer_storage_hosts_are_accepted(self):
        for host in release.DOWNLOAD_HOSTS:
            release.validate_download(f'https://{host}/apps/file.apk?sign=signed&t=1', 'private')
        self.assertIn('cos3.pgyer.com', release.DOWNLOAD_HOSTS)

    def test_credentials_or_unexpected_targets_are_never_public(self):
        for url in ['https://attacker.test/file.apk', 'http://oss.pgyer.com/file.apk',
                    'https://oss.pgyer.com/file.apk?_api_key=private',
                    'https://oss.pgyer.com/file.apk?secret=private']:
            with self.assertRaises(release.ReleaseError):
                release.validate_download(url, 'private')


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'app').mkdir()
        (self.root / 'app/build.gradle.kts').write_text('versionCode = 7\nversionName = "0.1.5"\n')
        sdk = self.root / 'sdk'
        (sdk / 'build-tools/35.0.0').mkdir(parents=True)
        (sdk / 'build-tools/35.0.0/apksigner').touch()
        config = self.root / 'config.json'
        config.write_text(json.dumps({'api_key': 'private', 'app_page_url': 'https://www.pgyer.com/existing-app'}))
        self.apk = self.root / 'app-release.apk'
        content = b'signed test APK'
        self.apk.write_bytes(content)
        self.artifact = {'version_code': 7, 'version_name': '0.1.5', 'identifier': release.IDENTIFIER,
                         'original_path': '/app/daodian-0.1.5.apk', 'expected_size': len(content),
                         'expected_sha256': sha256(content).hexdigest(), 'expected_md5': md5(content).hexdigest()}
        self.build = {'buildIdentifier': release.IDENTIFIER, 'buildVersion': '0.1.5',
                      'buildVersionNo': 7, 'buildFileSize': len(content), 'buildShortcutUrl': 'existing-app',
                      'buildKey': 'a' * 32}
        self.online = {'versionCode': 6, 'versionName': '0.1.4'}
        self.events = []
        self.feed = None
        self.patches = ExitStack()
        self.server = unittest.mock.Mock()
        self.server.check.side_effect = lambda: self.events.append('check')
        self.server.upload.side_effect = lambda *a: self.events.append('backup')
        def action(payload):
            self.events.append(payload['action'])
            if payload['action'] == 'stage':
                self.feed = payload['feed']
                self.assertEqual(payload['record']['build_key'], 'a' * 32)
            if payload['action'] == 'commit':
                self.online = self.feed
            return {'status': payload['action']}
        self.server.action.side_effect = action
        def api(key, method, fields, get=False):
            self.events.append(method)
            if method == 'getByShortcut': return {'code': 0, 'data': {'buildIdentifier': release.IDENTIFIER}}
            if method == 'getCOSToken': return {'code': 0, 'data': {'key': 'upload-key', 'endpoint': 'https://upload.myqcloud.com', 'params': {}}}
            if method == 'buildInfo':
                self.assertEqual(fields['buildKey'], 'upload-key')
                return {'code': 0, 'data': self.build}
            raise AssertionError(method)
        patches = [patch.object(release, 'ROOT', self.root),
                   patch.dict(release.os.environ, {'PGYER_CONFIG': str(config), 'ANDROID_HOME': str(sdk)}),
                   patch.object(release.sys, 'argv', ['publish.sh', 'notes']),
                   patch.object(release.sys, 'stdout', io.StringIO()),
                   patch.object(release, 'Server', return_value=self.server),
                   patch.object(release, 'api_call', side_effect=api),
                   patch.object(release, 'wait_for_build', side_effect=lambda key, upload: release.api_data(api(key, 'buildInfo', {'buildKey': upload}, True), 'buildInfo')),
                   patch.object(release, 'read_json_url', side_effect=lambda url: self.online),
                   patch.object(release, 'build_apk', return_value=(self.apk, self.artifact)),
                   patch.object(release, 'multipart_upload', side_effect=lambda *a: self.events.append('upload')),
                   patch.object(release, 'verify_download', side_effect=lambda *a: self.events.append('checksum')),
                   patch.object(release, 'probe_public', side_effect=lambda *a: self.events.append('probe'))]
        for context in patches: self.patches.enter_context(context)

    def tearDown(self):
        self.patches.close()
        self.temp.cleanup()

    def test_complete_workflow_switches_only_after_all_checks(self):
        release.main()
        self.assertLess(self.events.index('checksum'), self.events.index('stage'))
        self.assertLess(self.events.index('stage'), self.events.index('probe'))
        self.assertLess(self.events.index('probe'), self.events.index('commit'))
        self.assertEqual(self.online['versionCode'], 7)

    def test_checksum_failure_stops_before_server_changes(self):
        with patch.object(release, 'verify_download', side_effect=release.ReleaseError('mismatch')):
            with self.assertRaises(release.ReleaseError): release.main()
        self.assertNotIn('backup', self.events)
        self.assertNotIn('commit', self.events)
        self.assertEqual(self.online['versionCode'], 6)

    def test_failed_probe_can_resume_without_duplicate_upload(self):
        with patch.object(release, 'probe_public', side_effect=release.ReleaseError('unavailable')):
            with self.assertRaises(release.ReleaseError): release.main()
        self.assertEqual(self.online['versionCode'], 6)
        self.assertNotIn('commit', self.events)
        release.main()
        self.assertEqual(self.events.count('upload'), 1)
        self.assertEqual(self.events.count('getCOSToken'), 1)
        self.assertEqual(self.events.count('backup'), 1)
        self.assertEqual(self.online['versionCode'], 7)

    def test_confirmed_failed_publication_is_uploaded_again_on_retry(self):
        original = release.api_call.side_effect
        statuses = iter([1216, 1216, 0])
        def changing_api(key, method, fields, get=False):
            if method == 'buildInfo':
                status = next(statuses)
                if status: return {'code': status}
            return original(key, method, fields, get)
        # The polling mock must use the patched API entrypoint for this scenario.
        with patch.object(release, 'api_call', side_effect=changing_api), patch.object(
                release, 'wait_for_build', side_effect=lambda key, upload: release.api_data(
                    release.api_call(key, 'buildInfo', {'buildKey': upload}, get=True), 'buildInfo')):
            with self.assertRaises(release.ReleaseError): release.main()
            self.assertEqual(self.online['versionCode'], 6)
            release.main()
        self.assertEqual(self.events.count('upload'), 2)
        self.assertEqual(self.online['versionCode'], 7)

    def test_lost_commit_reply_recovers_without_republishing(self):
        action = self.server.action.side_effect
        def interrupted(payload):
            result = action(payload)
            if payload['action'] == 'commit': raise release.ReleaseError('connection lost')
            return result
        self.server.action.side_effect = interrupted
        with self.assertRaises(release.ReleaseError): release.main()
        self.assertEqual(self.online['versionCode'], 7)
        self.server.action.side_effect = action
        release.main()
        self.assertEqual(self.events.count('upload'), 1)
        self.assertEqual(self.events.count('getCOSToken'), 1)


if __name__ == '__main__':
    unittest.main()
