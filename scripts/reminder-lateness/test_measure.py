import tempfile
import unittest
from pathlib import Path

from measure import summarize


class SummaryTest(unittest.TestCase):
    def test_reports_each_lateness_and_the_median_and_worst(self):
        self.assertEqual(summarize([0.2, 0.1, 1.0, -0.1, 0.3]), (0.2, 1.0))

    def test_rejects_an_empty_run_set(self):
        with self.assertRaises(ValueError):
            summarize([])

    def test_rejects_nonfinite_measurements(self):
        for value in (float('nan'), float('inf'), float('-inf')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                summarize([value])

class PreferencesTest(unittest.TestCase):
    def test_seeds_a_due_fake_card_and_the_picked_minute(self):
        import json
        import xml.etree.ElementTree as ET
        from measure import prepare_preferences

        original = b'<map><string name="unrelated">keep</string><int name="practice_reminder_ignored" value="3" /></map>'
        root = ET.fromstring(prepare_preferences(original, 125))
        entries = {item.get('name'): item for item in root}
        self.assertEqual(entries['unrelated'].text, 'keep')
        self.assertEqual(entries['practice_reminder_minute'].get('value'), '125')
        self.assertEqual(entries['practice_reminder_on'].get('value'), 'true')
        self.assertEqual(entries['practice_reminder_ignored'].get('value'), '0')
        self.assertEqual(entries['practice_reminder_done_at'].get('value'), '0')
        self.assertEqual(json.loads(entries['card_types'].text), [])
        card = json.loads(entries['saved_lines'].text)[0]
        self.assertTrue(card['translation'])
        self.assertEqual(card['due'], 0)

    def test_rejects_invalid_minutes_and_preferences(self):
        from measure import prepare_preferences

        for minute in (-1, 1440):
            with self.subTest(minute=minute), self.assertRaises(ValueError):
                prepare_preferences(b'<map />', minute)
        with self.assertRaises(ValueError):
            prepare_preferences(b'<other />', 1)


class TargetTest(unittest.TestCase):
    def test_picks_a_whole_minute_at_least_the_requested_time_ahead(self):
        from measure import target_time

        target, minute = target_time(1_700_000_000_000, 20, 'America/Argentina/Buenos_Aires')
        self.assertEqual(target, 1_700_001_240_000)
        self.assertEqual(minute, 19 * 60 + 34)

    def test_rejects_zero_or_negative_lead(self):
        from measure import target_time

        for lead in (0, -1):
            with self.subTest(lead=lead), self.assertRaises(ValueError):
                target_time(1_700_000_000_000, lead, 'UTC')


class DeviceTest(unittest.TestCase):
    def test_selects_only_the_online_samsung(self):
        from measure import samsung_serial

        listing = (
            'List of devices attached\n'
            'fake-poco device product:x model:2510EPC8BG device:x transport_id:1\n'
            'fake-samsung device product:b0qxxx model:SM_S908E device:b0q transport_id:2\n'
            'fake-offline offline model:SM_S908E transport_id:3\n'
        )
        self.assertEqual(samsung_serial(listing), 'fake-samsung')

    def test_accepts_a_serial_with_spaces_from_mdns(self):
        from measure import samsung_serial

        listing = 'fake-device (2)._adb-tls._tcp device product:x model:SM_S908E device:x transport_id:1\n'
        self.assertEqual(samsung_serial(listing), 'fake-device (2)._adb-tls._tcp')

    def test_rejects_absent_or_ambiguous_samsung(self):
        from measure import samsung_serial

        for listing in ('List of devices attached\n',
                        'a device model:SM_S908E\nb device model:SM_S908E\n'):
            with self.subTest(listing=listing), self.assertRaises(ValueError):
                samsung_serial(listing)


class NotificationTest(unittest.TestCase):
    def test_picks_the_reminder_creation_time_from_its_own_record(self):
        from measure import notification_posted_at

        dump = (
            'Notification List:\n'
            '  NotificationRecord(0x123: pkg=com.astrovm.crosstune.dev user=UserHandle{0} id=4 tag=null)\n'
            '    mCreationTimeMs=1700000000000\n'
            '  NotificationRecord(0x456: pkg=com.astrovm.crosstune.dev user=UserHandle{0} id=5 tag=null)\n'
            '    mCreationTimeMs=1700000123456\n'
            '  NotificationRecord(0x789: pkg=other user=UserHandle{0} id=5 tag=null)\n'
            '    mCreationTimeMs=1700000999999\n'
        )
        self.assertEqual(notification_posted_at(dump), 1700000123456)
        summary = 'NotificationRecord(0x456: pkg=com.astrovm.crosstune.dev user=0 id=5 tag=null)\n'
        self.assertEqual(notification_posted_at(summary + dump), 1700000123456)

    def test_rejects_incomplete_and_ambiguous_records(self):
        from measure import notification_posted_at

        record = 'NotificationRecord(0x1: pkg=com.astrovm.crosstune.dev user=0 id=5 tag=null)\n'
        self.assertIsNone(notification_posted_at('Notification List:\n'))
        self.assertIsNone(notification_posted_at(
            'NotificationRecord(0x1: pkg=com.astrovm.crosstune.dev.other user=0 id=5 tag=null)\n'
            ' mCreationTimeMs=1700000000000\n'))
        with self.assertRaisesRegex(ValueError, 'when=1700000000000'):
            notification_posted_at(record + '  when=1700000000000\n')
        with self.assertRaises(ValueError):
            notification_posted_at((record + ' mCreationTimeMs=1700000000000\n') * 2)


class PermissionTest(unittest.TestCase):
    def test_reads_denial_and_user_flags_from_package_dump(self):
        from measure import notification_permission

        dump = ('runtime permissions:\n'
                '  android.permission.POST_NOTIFICATIONS: granted=false, '
                'flags=[ USER_SET|USER_FIXED|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n')
        self.assertEqual(notification_permission(dump), (False, {'USER_SET', 'USER_FIXED',
                         'USER_SENSITIVE_WHEN_GRANTED', 'USER_SENSITIVE_WHEN_DENIED'}))

    def test_rejects_missing_and_duplicate_permission_state(self):
        from measure import notification_permission

        row = 'android.permission.POST_NOTIFICATIONS: granted=true, flags=[]\n'
        for dump in ('', row * 2):
            with self.subTest(dump=dump), self.assertRaises(ValueError):
                notification_permission(dump)


class PreferenceTransactionTest(unittest.TestCase):
    def test_restores_original_preferences_when_a_run_fails(self):
        from measure import temporary_preferences

        class FakePhone:
            data = b'<map><string name="untouched">original</string></map>'
            launches = 0

            def stop(self):
                pass

            def read_preferences(self):
                return self.data

            def write_preferences(self, data):
                self.data = data

            def launch(self):
                self.launches += 1

        phone = FakePhone()
        original = phone.data
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / 'original.xml'
            with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                with temporary_preferences(phone, 120, backup):
                    self.assertNotEqual(phone.data, original)
                    self.assertEqual(backup.read_bytes(), original)
                    raise RuntimeError('interrupted')
            self.assertFalse(backup.exists())
        self.assertEqual(phone.data, original)
        self.assertEqual(phone.launches, 2)

    def test_retains_private_backup_when_restoration_fails(self):
        from measure import temporary_preferences

        class BrokenPhone:
            data = b'<map />'
            writes = 0

            def stop(self):
                pass

            def read_preferences(self):
                return self.data

            def write_preferences(self, data):
                self.writes += 1
                if self.writes == 2:
                    raise OSError('phone disconnected')
                self.data = data

            def launch(self):
                pass

        phone = BrokenPhone()
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / 'original.xml'
            with self.assertRaisesRegex(OSError, 'phone disconnected'):
                with temporary_preferences(phone, 120, backup):
                    pass
            self.assertEqual(backup.read_bytes(), b'<map />')
            self.assertEqual(backup.stat().st_mode & 0o777, 0o600)


class PhoneCommandTest(unittest.TestCase):
    def test_error_does_not_expose_device_address(self):
        from unittest.mock import patch
        from measure import Phone

        class Failure:
            returncode = 1
            stdout = b''
            stderr = b'connection to fake-address failed'

        with patch('subprocess.run', return_value=Failure()):
            with self.assertRaises(RuntimeError) as error:
                Phone('/sdk/adb', 'fake-address').shell('date')
        self.assertNotIn('fake-address', str(error.exception))
        self.assertIn('[device]', str(error.exception))

    def test_timeout_does_not_expose_device_address(self):
        import subprocess
        from unittest.mock import patch
        from measure import Phone

        timeout = subprocess.TimeoutExpired(['adb', '-s', 'fake-address'], 30)
        with patch('subprocess.run', side_effect=timeout):
            with self.assertRaises(RuntimeError) as error:
                Phone('/sdk/adb', 'fake-address').shell('date')
        self.assertNotIn('fake-address', str(error.exception))


class AppOpTest(unittest.TestCase):
    def test_reads_exact_alarm_mode(self):
        from measure import exact_alarm_appop

        self.assertEqual(exact_alarm_appop('No operations.\nDefault mode: default\n'), 'default')
        self.assertEqual(exact_alarm_appop('SCHEDULE_EXACT_ALARM: allow; time=+1m\n'), 'allow')
        self.assertEqual(exact_alarm_appop('SCHEDULE_EXACT_ALARM: ignore\n'), 'ignore')

    def test_rejects_unknown_or_ambiguous_mode(self):
        from measure import exact_alarm_appop

        for dump in ('', 'SCHEDULE_EXACT_ALARM: weird\n',
                     'SCHEDULE_EXACT_ALARM: allow\nSCHEDULE_EXACT_ALARM: ignore\n'):
            with self.subTest(dump=dump), self.assertRaises(ValueError):
                exact_alarm_appop(dump)


class SettingTransactionTest(unittest.TestCase):
    def test_restores_setting_after_interruption(self):
        from measure import temporary_setting

        state = ['original']
        with self.assertRaisesRegex(RuntimeError, 'interrupted'):
            with temporary_setting(lambda: state[0], lambda value: state.__setitem__(0, value), 'temporary'):
                self.assertEqual(state[0], 'temporary')
                raise RuntimeError('interrupted')
        self.assertEqual(state[0], 'original')

    def test_restores_when_changing_setting_partly_fails(self):
        from measure import temporary_setting

        state = ['original']

        def write(value):
            state[0] = value
            if value == 'temporary':
                raise RuntimeError('write failed')

        with self.assertRaisesRegex(RuntimeError, 'write failed'):
            with temporary_setting(lambda: state[0], write, 'temporary'):
                self.fail('Must not run after the write failed')
        self.assertEqual(state[0], 'original')

    def test_rejects_a_restore_that_does_not_take_effect(self):
        from measure import temporary_setting

        state = ['original']

        def write(value):
            if value != 'original':
                state[0] = value

        with self.assertRaisesRegex(RuntimeError, 'restore'):
            with temporary_setting(lambda: state[0], write, 'temporary'):
                pass


class NotificationRestoreTest(unittest.TestCase):
    def test_restores_user_flags_changed_by_grant(self):
        from measure import Phone

        class FakePhone(Phone):
            granted = False
            flags = {'USER_SET', 'USER_FIXED', 'REVOKED_COMPAT'}

            def __init__(self):
                pass

            def notification_state(self):
                return self.granted, frozenset(self.flags)

            def shell(self, command, data=None):
                if command.startswith('pm grant '):
                    self.granted = True
                    self.flags.clear()
                elif command.startswith('pm set-permission-flags '):
                    self.flags.add(command.split()[-1].upper().replace('-', '_'))
                return b''

        phone = FakePhone()
        flags = frozenset({'USER_SET', 'USER_FIXED', 'REVOKED_COMPAT'})
        phone.set_notification_state((True, flags))
        self.assertEqual(phone.notification_state(), (True, flags))


class ScreenTest(unittest.TestCase):
    def test_reads_screen_and_last_wake(self):
        from measure import last_wake, screen_on

        self.assertTrue(screen_on('  mScreenOn=true\n'))
        self.assertFalse(screen_on('  mScreenOn=false\n'))
        self.assertEqual(last_wake('  mLastWakeTime=123456 (2000 ms ago)\n'), 123456)
        with self.assertRaises(ValueError):
            screen_on('other')
        with self.assertRaises(ValueError):
            last_wake('other')


class RunTest(unittest.TestCase):
    def test_a_complete_run_restores_state_and_reports_post_time(self):
        from unittest.mock import patch
        from measure import run_measurement, target_time

        clock = [1_700_000_000.0]
        target, _ = target_time(int((clock[0] + 120) * 1000), 1, 'UTC')

        class FakePhone:
            serial = 'fake-device'
            exact = 'default'
            permission = (False, frozenset({'USER_SET'}))
            battery = False
            idle = False
            prefs = b'<map />'
            alarm_queries = 0

            def exact_mode(self): return self.exact
            def set_exact_mode(self, value): self.exact = value
            def notification_appop_state(self): return ('allow' if self.permission[0] else 'ignore', None)
            def restore_notification_appop(self, value): pass
            def notification_state(self): return self.permission
            def set_notification_state(self, value): self.permission = value
            def battery_override(self): return self.battery
            def set_battery_override(self, value): self.battery = value
            def forced_idle(self): return self.idle
            def set_forced_idle(self, value): self.idle = value
            def stop(self): pass
            def launch(self): pass
            def read_preferences(self): return self.prefs
            def write_preferences(self, data): self.prefs = data
            def command(self, *args): return b''

            def shell(self, command):
                if command == 'date +%s': return str(int(clock[0] + 120)).encode()
                if command == 'getprop persist.sys.timezone': return b'UTC'
                if command == 'dumpsys alarm':
                    self.alarm_queries += 1
                    if self.alarm_queries < 3:
                        return b''
                    return (f' RTC_WAKEUP #1: Alarm{{abc type 0 origWhen {target} whenElapsed 5 com.astrovm.crosstune.dev}}\n'
                            '  tag=*walarm*:com.astrovm.crosstune.action.REMIND_PRACTICE\n'
                            '  type=RTC_WAKEUP window=0\n').encode()
                if command == 'dumpsys deviceidle': return b'mScreenOn=false\n'
                if command == 'dumpsys power': return b'mLastWakeTime=123\n'
                if command == 'dumpsys notification':
                    return (f'NotificationRecord(0x1: pkg=com.astrovm.crosstune.dev user=0 id=5 tag=null)\n'
                            f' mCreationTimeMs={target + 200}\n').encode()
                if command == 'input keyevent 223': return b''
                raise AssertionError(command)

        def sleep(seconds): clock[0] += seconds
        phone = FakePhone()
        with tempfile.TemporaryDirectory() as directory:
            with patch('time.time', side_effect=lambda: clock[0]), patch('time.sleep', side_effect=sleep):
                result = run_measurement(phone, 1, 1, True, True, Path(directory) / 'backup.xml')
            self.assertEqual(result[:2], (target, target + 200))
            self.assertEqual(clock[0], target / 1000 + 60 - 120)
        self.assertGreaterEqual(phone.alarm_queries, 3)
        self.assertEqual(phone.exact, 'default')
        self.assertEqual(phone.permission, (False, frozenset({'USER_SET'})))
        self.assertFalse(phone.battery)
        self.assertFalse(phone.idle)
        self.assertEqual(phone.prefs, b'<map />')

        class WokenPhone(FakePhone):
            wakes = 0
            def shell(self, command):
                if command == 'dumpsys power':
                    self.wakes += 1
                    return f'mLastWakeTime={self.wakes}\n'.encode()
                return super().shell(command)

        clock[0] = 1_700_000_000.0
        phone = WokenPhone()
        with tempfile.TemporaryDirectory() as directory:
            with patch('time.time', side_effect=lambda: clock[0]), patch('time.sleep', side_effect=sleep):
                with self.assertRaisesRegex(RuntimeError, 'woke'):
                    run_measurement(phone, 1, 1, True, True, Path(directory) / 'backup.xml')
            self.assertFalse((Path(directory) / 'backup.xml').exists())
        self.assertEqual(phone.exact, 'default')
        self.assertEqual(phone.permission, (False, frozenset({'USER_SET'})))
        self.assertFalse(phone.battery)
        self.assertFalse(phone.idle)
        self.assertEqual(phone.prefs, b'<map />')

        class WrongAlarmPhone(FakePhone):
            def shell(self, command):
                if command == 'dumpsys alarm':
                    return b'RTC_WAKEUP #1: origWhen 123 com.astrovm.crosstune.dev\n'
                return super().shell(command)

        clock[0] = 1_700_000_000.0
        phone = WrongAlarmPhone()
        with tempfile.TemporaryDirectory() as directory:
            with patch('time.time', side_effect=lambda: clock[0]), patch('time.sleep', side_effect=sleep):
                with self.assertRaisesRegex(RuntimeError, 'origWhen 123'):
                    run_measurement(phone, 1, 1, False, True, Path(directory) / 'backup.xml')
            self.assertFalse((Path(directory) / 'backup.xml').exists())
        self.assertEqual(phone.prefs, b'<map />')

        class UnknownNotificationPhone(FakePhone):
            def shell(self, command):
                if command == 'dumpsys notification':
                    return (b'NotificationRecord(0x1: pkg=com.astrovm.crosstune.dev user=0 id=5 tag=null)\n'
                            b' when=1700000220200\n')
                return super().shell(command)
            def command(self, *args):
                return b'1700000220.200 1 2 I NotificationService: enqueued com.astrovm.crosstune.dev\n'

        import contextlib
        import io
        clock[0] = 1_700_000_000.0
        phone = UnknownNotificationPhone()
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            with patch('time.time', side_effect=lambda: clock[0]), patch('time.sleep', side_effect=sleep):
                with contextlib.redirect_stdout(output):
                    with self.assertRaisesRegex(ValueError, 'when=1700000220200'):
                        run_measurement(phone, 1, 1, False, True, Path(directory) / 'backup.xml')
        self.assertIn('enqueued com.astrovm.crosstune.dev', output.getvalue())
        self.assertEqual(phone.prefs, b'<map />')


class ScreenRestoreTest(unittest.TestCase):
    def test_restores_screen_after_interrupted_run(self):
        from measure import restore_screen

        class FakePhone:
            on = True

            def shell(self, command):
                if command == 'dumpsys deviceidle':
                    return f'mScreenOn={str(self.on).lower()}\n'.encode()
                if command == 'input keyevent 224':
                    self.on = True
                    return b''
                raise AssertionError(command)

        phone = FakePhone()
        with self.assertRaisesRegex(RuntimeError, 'interrupted'):
            with restore_screen(phone):
                phone.on = False
                raise RuntimeError('interrupted')
        self.assertTrue(phone.on)

    def test_reports_screen_restoration_failure(self):
        from unittest.mock import patch
        from measure import restore_screen

        class StuckPhone:
            on = True
            def shell(self, command):
                if command == 'dumpsys deviceidle':
                    return f'mScreenOn={str(self.on).lower()}\n'.encode()
                return b''

        phone = StuckPhone()
        with patch('time.sleep'):
            with self.assertRaisesRegex(RuntimeError, 'screen'):
                with restore_screen(phone):
                    phone.on = False


class LockTest(unittest.TestCase):
    def test_requires_our_lock_with_enough_time(self):
        import datetime
        from measure import lock_seconds_left

        now = datetime.datetime.fromisoformat('2026-11-11T03:00:00-03:00')
        output = ('samsung: held by issue 773 pid 123 since 2026-11-11T02:59:00-03:00 '
                  'until 2026-11-11T03:45:00-03:00; owning session is gone\n')
        self.assertEqual(lock_seconds_left(output, 'issue 773', now), 2700)
        with self.assertRaises(ValueError):
            lock_seconds_left(output, 'issue 884', now)
        with self.assertRaises(ValueError):
            lock_seconds_left('samsung: free\n', 'issue 773', now)


class AlarmTest(unittest.TestCase):
    def test_matches_target_package_action_and_exactness_in_one_alarm(self):
        from measure import reminder_alarm

        dump = (' RTC_WAKEUP #1: Alarm{a type 0 origWhen 1700000100000 whenElapsed 7 com.astrovm.crosstune.dev}\n'
                '  tag=*walarm*:com.astrovm.crosstune.action.REMIND_PRACTICE\n'
                '  type=RTC_WAKEUP window=0 exactAllowReason=permission\n'
                ' RTC_WAKEUP #2: Alarm{b type 0 origWhen 1700000100000 whenElapsed 7 com.other}\n'
                '  tag=*walarm*:other\n  type=RTC_WAKEUP window=+10m0s\n')
        self.assertIn('window=0', reminder_alarm(dump, 1700000100000, True))
        other_first = dump[dump.index(' RTC_WAKEUP #2:'):] + '\n' + dump[:dump.index(' RTC_WAKEUP #2:')]
        self.assertIn('window=0', reminder_alarm(other_first, 1700000100000, True))
        for target, exact in ((1700000100001, True), (1700000100000, False)):
            with self.subTest(target=target, exact=exact), self.assertRaises(ValueError):
                reminder_alarm(dump, target, exact)


class NotificationAppOpTest(unittest.TestCase):
    def test_reads_uid_mode_and_default(self):
        from measure import notification_appop

        self.assertEqual(notification_appop('Uid mode: POST_NOTIFICATION: ignore\n'), ('ignore', None))
        self.assertEqual(notification_appop('Uid mode: POST_NOTIFICATION: ignore\nPOST_NOTIFICATION: allow; time=+1m\n'), ('ignore', None))
        self.assertEqual(notification_appop('No operations.\nDefault mode: allow\n'), (None, 'allow'))

    def test_rejects_package_and_unknown_modes(self):
        from measure import notification_appop

        for dump in ('POST_NOTIFICATION: ignore\n', 'Uid mode: POST_NOTIFICATION: weird\n', ''):
            with self.subTest(dump=dump), self.assertRaises(ValueError):
                notification_appop(dump)


class NotificationAppOpRestoreTest(unittest.TestCase):
    def test_restores_the_original_uid_mode(self):
        from measure import Phone

        class FakePhone(Phone):
            mode = 'allow'
            def __init__(self): pass
            def notification_appop_state(self): return self.mode, None
            def shell(self, command, data=None):
                self.mode = command.split()[-1]
                return b''

        phone = FakePhone()
        phone.restore_notification_appop(('ignore', None))
        self.assertEqual(phone.mode, 'ignore')


class BatteryStateTest(unittest.TestCase):
    def test_rejects_an_unrecognized_battery_dump(self):
        from measure import Phone

        class FakePhone(Phone):
            def __init__(self): pass
            def shell(self, command, data=None): return b'Unknown service'

        with self.assertRaises(ValueError):
            FakePhone().battery_override()


class RecoveryJournalTest(unittest.TestCase):
    def test_journal_is_private_exclusive_and_has_no_device_address(self):
        import json
        from measure import write_journal

        state = {'exact': 'default', 'notifications': [False, ['USER_FIXED']], 'battery': False}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            write_journal(path, state)
            self.assertEqual(json.loads(path.read_text()), state)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                write_journal(path, {'exact': 'allow'})
            self.assertEqual(json.loads(path.read_text()), state)


class RecoveryTest(unittest.TestCase):
    def test_restores_preferences_and_original_system_states(self):
        from measure import recover_state

        class FakePhone:
            exact = 'allow'
            permission = (True, frozenset({'USER_SET'}))
            appop = ('allow', None)
            battery = True
            idle = True
            screen = True
            prefs = b'<map>temporary</map>'

            def exact_mode(self): return self.exact
            def set_exact_mode(self, value): self.exact = value
            def notification_state(self): return self.permission
            def set_notification_state(self, value): self.permission = value
            def notification_appop_state(self): return self.appop
            def restore_notification_appop(self, value): self.appop = value
            def battery_override(self): return self.battery
            def set_battery_override(self, value): self.battery = value
            def forced_idle(self): return self.idle
            def set_forced_idle(self, value): self.idle = value
            def stop(self): pass
            def launch(self): pass
            def write_preferences(self, data): self.prefs = data
            def shell(self, command):
                if command == 'dumpsys deviceidle':
                    return f'mScreenOn={str(self.screen).lower()}\n'.encode()
                if command == 'input keyevent 223':
                    self.screen = False
                    return b''
                raise AssertionError(command)

        state = {'exact': 'default', 'notifications': [False, ['USER_SET']],
                 'notification_appop': ['ignore', None], 'battery': False,
                 'forced_idle': False, 'screen': False}
        phone = FakePhone()
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / 'original.xml'
            backup.write_bytes(b'<map />')
            recover_state(phone, state, backup)
            self.assertFalse(backup.exists())
        self.assertEqual(phone.prefs, b'<map />')
        self.assertEqual(phone.exact, 'default')
        self.assertEqual(phone.permission, (False, frozenset({'USER_SET'})))
        self.assertEqual(phone.appop, ('ignore', None))
        self.assertFalse(phone.battery or phone.idle or phone.screen)


class VolumeTest(unittest.TestCase):
    def test_reads_the_current_media_volume(self):
        from measure import media_volume

        self.assertEqual(media_volume('[V] will control stream=3 (STREAM_MUSIC)\n[V] volume is 0 in range [0..15]\n'), 0)
        self.assertEqual(media_volume('[V] volume is 7 in range [0..15]\n'), 7)
        with self.assertRaises(ValueError):
            media_volume('unknown output')


class LaunchTest(unittest.TestCase):
    def test_reports_an_android_activity_launch_error(self):
        from measure import Phone

        class FakePhone(Phone):
            def __init__(self): pass
            def shell(self, command, data=None): return b'Error type 3\nError: Activity class does not exist.\n'

        with self.assertRaisesRegex(RuntimeError, 'launch'):
            FakePhone().launch()


if __name__ == '__main__':
    unittest.main()
