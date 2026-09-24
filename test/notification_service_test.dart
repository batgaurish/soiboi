import 'dart:async';
import 'dart:io';

import 'package:dbus/dbus.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/notification_service.dart';

/// Records what the service asked of it, so the tests are about the service
/// rather than a platform.
class _FakeBackend implements NotificationBackend {
  final shown = <String, AppNotification>{};
  final dismissed = <String>[];
  final taps$ = StreamController<NotificationTap>.broadcast();
  bool fail = false;

  @override
  Future<void> show(String key, AppNotification notification) async {
    if (fail) throw StateError('no daemon');
    shown[key] = notification;
  }

  @override
  Future<void> dismiss(String key) async {
    dismissed.add(key);
    shown.remove(key);
  }

  @override
  Stream<NotificationTap> get taps => taps$.stream;

  @override
  Future<bool> requestPermission() async => true;
}

/// A notification daemon, as far as the freedesktop spec's two methods go.
class _FakeDaemon extends DBusObject {
  _FakeDaemon() : super(DBusObjectPath('/org/freedesktop/Notifications'));

  final notifies = <List<DBusValue>>[];
  final closed = <int>[];
  var _nextId = 41;

  @override
  Future<DBusMethodResponse> handleMethodCall(DBusMethodCall call) async {
    if (call.interface != 'org.freedesktop.Notifications') {
      return DBusMethodErrorResponse.unknownInterface();
    }
    switch (call.name) {
      case 'Notify':
        notifies.add(call.values);
        final replaces = call.values[1].asUint32();
        return DBusMethodSuccessResponse([
          DBusUint32(replaces == 0 ? ++_nextId : replaces),
        ]);
      case 'CloseNotification':
        closed.add(call.values[0].asUint32());
        return DBusMethodSuccessResponse();
      default:
        return DBusMethodErrorResponse.unknownMethod();
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('NotificationService', () {
    test('shows, updates and dismisses by key', () async {
      final backend = _FakeBackend();
      final service = NotificationService(
        backend: backend,
        enabled: ValueNotifier(true),
      );

      await service.show(
        'queue',
        const AppNotification(
          kind: NotificationKind.progress,
          title: 'Downloading',
          progress: 10,
        ),
      );
      await service.show(
        'queue',
        const AppNotification(
          kind: NotificationKind.progress,
          title: 'Downloading',
          progress: 60,
        ),
      );
      expect(backend.shown['queue']!.progress, 60);

      await service.dismiss('queue');
      expect(backend.shown, isEmpty);
      expect(backend.dismissed, ['queue']);
    });

    test('posts nothing while turned off, and clears what was shown', () async {
      final backend = _FakeBackend();
      final enabled = ValueNotifier(true);
      final service = NotificationService(backend: backend, enabled: enabled);

      await service.show(
        'a',
        const AppNotification(kind: NotificationKind.result, title: 'A'),
      );
      enabled.value = false;
      await Future<void>.delayed(Duration.zero);
      expect(backend.dismissed, ['a']);

      await service.show(
        'b',
        const AppNotification(kind: NotificationKind.result, title: 'B'),
      );
      expect(backend.shown, isEmpty);
    });

    test('a platform without notifications accepts every call', () async {
      final service = NotificationService(
        backend: null,
        enabled: ValueNotifier(true),
      );
      expect(service.supported, isFalse);
      await service.show(
        'a',
        const AppNotification(kind: NotificationKind.result, title: 'A'),
      );
      await service.dismiss('a');
      expect(await service.requestPermission(), isFalse);
    });

    test('a failing backend is logged, never thrown', () async {
      final logged = <String>[];
      final service = NotificationService(
        backend: _FakeBackend()..fail = true,
        enabled: ValueNotifier(true),
        log: logged.add,
      );
      await service.show(
        'a',
        const AppNotification(kind: NotificationKind.result, title: 'A'),
      );
      expect(logged.single, contains('no daemon'));
    });
  });

  group('Android backend', () {
    const channel = MethodChannel('test/notifications');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    tearDown(() => messenger.setMockMethodCallHandler(channel, null));

    test('ids are stable, positive and clear of the playback one', () {
      final id = AndroidNotificationBackend.idFor('download-queue');
      expect(id, AndroidNotificationBackend.idFor('download-queue'));
      expect(id, isNot(AndroidNotificationBackend.idFor('update')));
      for (final key in ['', 'a', 'download-queue', 'x' * 500]) {
        final value = AndroidNotificationBackend.idFor(key);
        expect(value, greaterThanOrEqualTo(0x10000));
        expect(value, lessThanOrEqualTo(0x7fffffff));
      }
    });

    test('sends one call per show and dismiss, on the right channel', () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return null;
      });
      final backend = AndroidNotificationBackend(channel: channel);

      await backend.show(
        'queue',
        const AppNotification(
          kind: NotificationKind.progress,
          title: '12 of 50',
          body: 'Now: One More Time',
          progress: 24,
          actions: [NotificationAction('pause', 'Pause')],
        ),
      );
      await backend.dismiss('queue');

      final args = calls[0].arguments as Map;
      expect(calls[0].method, 'show');
      expect(args['id'], AndroidNotificationBackend.idFor('queue'));
      expect(args['channel'], 'download_progress');
      expect(args['progress'], 24);
      expect(args['ongoing'], isTrue);
      expect(args['actions'], [
        ['pause', 'Pause'],
      ]);
      expect(calls[1].method, 'dismiss');
      expect((calls[1].arguments as Map)['id'], args['id']);
    });

    test('a button press comes back as a tap on its key', () async {
      messenger.setMockMethodCallHandler(channel, (_) async => null);
      final backend = AndroidNotificationBackend(channel: channel);
      await backend.show(
        'queue',
        const AppNotification(kind: NotificationKind.progress, title: 'x'),
      );

      final tap = backend.taps.first;
      await messenger.handlePlatformMessage(
        channel.name,
        const StandardMethodCodec().encodeMethodCall(
          MethodCall('tap', {
            'id': AndroidNotificationBackend.idFor('queue'),
            'action': 'pause',
          }),
        ),
        (_) {},
      );
      final received = await tap;
      expect(received.key, 'queue');
      expect(received.action, 'pause');
    });
  });

  group('Linux backend over D-Bus', () {
    late Directory dir;
    late DBusServer server;
    late DBusClient daemonClient;
    late DBusClient appClient;
    late _FakeDaemon daemon;
    late LinuxNotificationBackend backend;

    setUp(() async {
      dir = await Directory.systemTemp.createTemp('soiboi-dbus');
      server = DBusServer();
      final address = await server.listenAddress(DBusAddress.unix(dir: dir));
      daemonClient = DBusClient(address);
      daemon = _FakeDaemon();
      await daemonClient.requestName('org.freedesktop.Notifications');
      await daemonClient.registerObject(daemon);
      appClient = DBusClient(address);
      backend = LinuxNotificationBackend(client: appClient);
    });

    tearDown(() async {
      await backend.close();
      await appClient.close();
      await daemonClient.close();
      await server.close();
      await dir.delete(recursive: true);
    });

    test('updates in place, then closes', () async {
      await backend.show(
        'queue',
        const AppNotification(
          kind: NotificationKind.progress,
          title: 'Downloading',
          body: 'Simon & Garfunkel <live>',
          progress: 30,
        ),
      );
      await backend.show(
        'queue',
        const AppNotification(
          kind: NotificationKind.progress,
          title: 'Downloading',
          progress: 130,
        ),
      );
      await backend.dismiss('queue');

      final first = daemon.notifies[0];
      expect(first[0].asString(), 'Soiboi');
      expect(first[1].asUint32(), 0, reason: 'a new notification');
      expect(first[3].asString(), 'Downloading');
      expect(first[4].asString(), 'Simon &amp; Garfunkel &lt;live&gt;');
      final hints = first[6].asStringVariantDict();
      expect(hints['value']!.asInt32(), 30);
      expect(hints['desktop-entry']!.asString(), 'soiboi');
      expect(hints['transient']!.asBoolean(), isTrue);
      expect(first[7].asInt32(), 0, reason: 'progress never expires');

      final second = daemon.notifies[1];
      expect(second[1].asUint32(), 42, reason: 'replaces the first');
      expect(second[6].asStringVariantDict()['value']!.asInt32(), 100);
      expect(daemon.closed, [42]);
    });

    test('a result expires normally and carries its buttons', () async {
      await backend.show(
        'done',
        const AppNotification(
          kind: NotificationKind.result,
          title: 'Discovery: 14 downloaded',
          actions: [NotificationAction('open-log', 'Open log')],
        ),
      );
      final call = daemon.notifies.single;
      expect(call[5].asStringArray().toList(), [
        'default',
        'Open',
        'open-log',
        'Open log',
      ]);
      expect(call[7].asInt32(), -1);
      expect(call[6].asStringVariantDict().containsKey('value'), isFalse);
    });

    test('action and close signals reach the right key', () async {
      await backend.show(
        'done',
        const AppNotification(kind: NotificationKind.result, title: 'Done'),
      );
      final tap = backend.taps.first;
      await daemon.emitSignal(
        'org.freedesktop.Notifications',
        'ActionInvoked',
        [const DBusUint32(42), const DBusString('default')],
      );
      final received = await tap.timeout(const Duration(seconds: 5));
      expect(received.key, 'done');
      expect(received.action, NotificationTap.defaultAction);

      // Closed by the user: the next show is a fresh notification.
      await daemon.emitSignal(
        'org.freedesktop.Notifications',
        'NotificationClosed',
        [const DBusUint32(42), const DBusUint32(2)],
      );
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await backend.show(
        'done',
        const AppNotification(kind: NotificationKind.result, title: 'Again'),
      );
      expect(daemon.notifies.last[1].asUint32(), 0);
    });
  });
}
