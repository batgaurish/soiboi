/// System notifications, one call for every platform.
///
/// A feature posts under a key of its own choosing ("download-queue",
/// "update"), posts again under the same key to update it, and dismisses it by
/// that key. How that happens is the backend's business: Android notification
/// channels through a platform channel, or the freedesktop notification spec
/// over D-Bus on Linux, which every desktop's notification daemon speaks
/// (Noctalia, KDE, GNOME, mako, dunst, swaync).
///
/// Nothing is posted anywhere else. Windows, macOS and iOS get a service that
/// accepts every call and does nothing, so callers never check the platform.
library;

import 'dart:async';
import 'dart:io';

import 'package:dbus/dbus.dart';
import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:soiboi/base/services/logger.dart';

/// Whether the app posts notifications at all. Persisted by `setting.dart`.
final notificationsEnabledNotifier = ValueNotifier(true);

/// What a notification is about. On Android each kind is its own channel, so
/// the user can silence download progress and still hear about updates.
enum NotificationKind {
  /// Work in progress, such as a download. Quiet, and stays until replaced
  /// or dismissed.
  progress,

  /// How finished work went: downloaded, or failed and why.
  result,

  /// A new version is available.
  update,
}

/// A button on a notification. [id] comes back in [NotificationTap.action].
class NotificationAction {
  const NotificationAction(this.id, this.label);

  final String id;
  final String label;
}

class AppNotification {
  const AppNotification({
    required this.kind,
    required this.title,
    this.body = '',
    this.progress,
    this.actions = const [],
  });

  final NotificationKind kind;
  final String title;
  final String body;

  /// 0 to 100 draws a progress bar, a negative value an indeterminate one,
  /// null none.
  final int? progress;

  final List<NotificationAction> actions;

  /// Progress stays on screen until it is replaced or dismissed; results and
  /// updates can be swiped away.
  bool get ongoing => kind == NotificationKind.progress;
}

/// The user pressed one of a notification's [NotificationAction]s, or the
/// notification itself ([action] is `default`).
class NotificationTap {
  const NotificationTap(this.key, this.action);

  final String key;
  final String action;

  static const defaultAction = 'default';
}

/// One platform's way of showing notifications. Keys are the caller's; each
/// backend maps them to whatever identifier its platform uses.
abstract class NotificationBackend {
  Future<void> show(String key, AppNotification notification);
  Future<void> dismiss(String key);
  Stream<NotificationTap> get taps;

  /// Asks for permission where the platform needs it. True when notifications
  /// can be shown.
  Future<bool> requestPermission();
}

class NotificationService {
  /// A null [backend] is a platform without notifications: every call is
  /// accepted and does nothing.
  NotificationService({
    required NotificationBackend? backend,
    ValueListenable<bool>? enabled,
    void Function(String message)? log,
  }) : _backend = backend,
       _enabled = enabled ?? notificationsEnabledNotifier,
       _log = log ?? logger.output {
    _enabled.addListener(_onEnabledChanged);
  }

  final NotificationBackend? _backend;
  final ValueListenable<bool> _enabled;
  final void Function(String message) _log;

  /// Keys on screen right now, so turning notifications off can clear them.
  final _shown = <String>{};

  static NotificationBackend? platformBackend() {
    if (Platform.isAndroid) return AndroidNotificationBackend();
    if (Platform.isLinux) return LinuxNotificationBackend();
    return null;
  }

  /// Whether this platform shows notifications at all.
  bool get supported => _backend != null;

  /// Taps on notifications and their buttons, for every key.
  Stream<NotificationTap> get taps =>
      _backend?.taps ?? const Stream<NotificationTap>.empty();

  /// Shows [notification] under [key], replacing what that key showed before.
  ///
  /// Never throws. A notification is never the only place something is said,
  /// so one that cannot be shown is logged and otherwise ignored.
  Future<void> show(String key, AppNotification notification) async {
    final backend = _backend;
    if (backend == null || !_enabled.value) return;
    try {
      await backend.show(key, notification);
      _shown.add(key);
    } catch (e) {
      _log('notification $key: $e');
    }
  }

  /// Removes the notification shown under [key], if there is one.
  Future<void> dismiss(String key) async {
    final backend = _backend;
    if (backend == null) return;
    // Passed on even when this run never showed [key]: on Android a
    // notification can outlive the process that posted it.
    _shown.remove(key);
    try {
      await backend.dismiss(key);
    } catch (e) {
      _log('notification $key: $e');
    }
  }

  Future<void> dismissAll() async {
    for (final key in _shown.toList()) {
      await dismiss(key);
    }
  }

  /// Asks for the notification permission where one exists (Android 13 and
  /// later). Call it when the user has just started something that will
  /// notify, so the prompt has an obvious reason; asking from the background
  /// has no window to show it in.
  Future<bool> requestPermission() async {
    final backend = _backend;
    if (backend == null) return false;
    try {
      return await backend.requestPermission();
    } catch (e) {
      _log('notification permission: $e');
      return false;
    }
  }

  void _onEnabledChanged() {
    if (!_enabled.value) unawaited(dismissAll());
  }
}

/// The app's notifications.
final notifications = NotificationService(
  backend: NotificationService.platformBackend(),
);

// ---------------------------------------------------------------------------
// Android
// ---------------------------------------------------------------------------

/// Android notifications, posted by `NotificationBridge.kt`.
class AndroidNotificationBackend implements NotificationBackend {
  AndroidNotificationBackend({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.batgaurish.soiboi/notifications') {
    _channel.setMethodCallHandler(_onCall);
  }

  final MethodChannel _channel;
  final _taps = StreamController<NotificationTap>.broadcast();

  /// Android identifies a notification by an int. Keys hash to one that stays
  /// the same across restarts, so a notification left behind by a previous
  /// run can still be replaced or dismissed.
  final _keys = <int, String>{};

  static int idFor(String key) {
    // FNV-1a, kept clear of audio_service's playback notification (1124) by
    // staying above 0x10000.
    var hash = 0x811c9dc5;
    for (final unit in key.codeUnits) {
      hash = ((hash ^ unit) * 0x01000193) & 0x7fffffff;
    }
    return 0x10000 + hash % 0x7fff0000;
  }

  static String _channelFor(NotificationKind kind) => switch (kind) {
    NotificationKind.progress => 'download_progress',
    NotificationKind.result => 'download_results',
    NotificationKind.update => 'updates',
  };

  @override
  Stream<NotificationTap> get taps => _taps.stream;

  @override
  Future<void> show(String key, AppNotification notification) async {
    final id = idFor(key);
    _keys[id] = key;
    await _channel.invokeMethod<void>('show', {
      'id': id,
      'channel': _channelFor(notification.kind),
      'title': notification.title,
      'body': notification.body,
      'progress': notification.progress,
      'ongoing': notification.ongoing,
      'actions': [
        for (final action in notification.actions) [action.id, action.label],
      ],
    });
  }

  @override
  Future<void> dismiss(String key) async {
    await _channel.invokeMethod<void>('dismiss', {'id': idFor(key)});
  }

  @override
  Future<bool> requestPermission() async {
    final status = await Permission.notification.request();
    return status.isGranted;
  }

  Future<void> _onCall(MethodCall call) async {
    if (call.method != 'tap') return;
    final args = Map<String, dynamic>.from(call.arguments as Map);
    final key = _keys[args['id'] as int?];
    if (key == null) return;
    _taps.add(
      NotificationTap(
        key,
        args['action'] as String? ?? NotificationTap.defaultAction,
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Linux
// ---------------------------------------------------------------------------

const _notificationsName = 'org.freedesktop.Notifications';
const _notificationsInterface = 'org.freedesktop.Notifications';

/// Desktop notifications over D-Bus, per the freedesktop.org notification
/// spec.
///
/// The notification daemon assigns each notification an id. Passing it back
/// as `replaces_id` updates that notification in place, which is how a
/// progress notification moves without stacking up copies.
class LinuxNotificationBackend implements NotificationBackend {
  LinuxNotificationBackend({DBusClient? client}) : _clientOverride = client;

  final DBusClient? _clientOverride;
  DBusRemoteObject? _object;
  final _taps = StreamController<NotificationTap>.broadcast();
  final _subscriptions = <StreamSubscription<DBusSignal>>[];

  final _ids = <String, int>{};
  final _keys = <int, String>{};

  /// Connects on first use, so an app that never notifies never touches the
  /// session bus.
  DBusRemoteObject _connect() {
    final existing = _object;
    if (existing != null) return existing;
    final client = _clientOverride ?? DBusClient.session();
    final object = DBusRemoteObject(
      client,
      name: _notificationsName,
      path: DBusObjectPath('/org/freedesktop/Notifications'),
    );
    _subscriptions.add(
      DBusRemoteObjectSignalStream(
        object: object,
        interface: _notificationsInterface,
        name: 'ActionInvoked',
        signature: DBusSignature('us'),
      ).listen((signal) {
        final key = _keys[signal.values[0].asUint32()];
        if (key != null) {
          _taps.add(NotificationTap(key, signal.values[1].asString()));
        }
      }),
    );
    _subscriptions.add(
      DBusRemoteObjectSignalStream(
        object: object,
        interface: _notificationsInterface,
        name: 'NotificationClosed',
        signature: DBusSignature('uu'),
      ).listen((signal) {
        // Closed by the user or expired: the next show for that key is a new
        // notification, not a replacement of one that is gone.
        final key = _keys.remove(signal.values[0].asUint32());
        if (key != null) _ids.remove(key);
      }),
    );
    return _object = object;
  }

  static String _category(NotificationKind kind) => switch (kind) {
    NotificationKind.progress => 'transfer',
    NotificationKind.result => 'transfer.complete',
    NotificationKind.update => 'x-soiboi.update',
  };

  /// Notification bodies may be read as markup, where a stray `&` or `<` in
  /// a track title would swallow the rest of the text.
  static String _escape(String text) => text
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;');

  @override
  Stream<NotificationTap> get taps => _taps.stream;

  @override
  Future<void> show(String key, AppNotification notification) async {
    final object = _connect();
    final progress = notification.progress;
    final hints = <String, DBusValue>{
      // Lets the daemon find the app's name and icon from soiboi.desktop.
      'desktop-entry': const DBusString('soiboi'),
      'category': DBusString(_category(notification.kind)),
      'urgency': DBusByte(notification.ongoing ? 0 : 1),
      // Progress is not worth keeping in the notification history.
      if (notification.ongoing) 'transient': const DBusBoolean(true),
      if (progress != null && progress >= 0)
        'value': DBusInt32(progress.clamp(0, 100)),
    };
    final result = await object.callMethod(
      _notificationsInterface,
      'Notify',
      [
        const DBusString('Soiboi'),
        DBusUint32(_ids[key] ?? 0),
        const DBusString('soiboi'),
        DBusString(notification.title),
        DBusString(_escape(notification.body)),
        DBusArray.string([
          // The daemon invokes "default" when the notification itself is
          // clicked; its label is not shown.
          NotificationTap.defaultAction,
          'Open',
          for (final action in notification.actions) ...[
            action.id,
            action.label,
          ],
        ]),
        DBusDict.stringVariant(hints),
        // Progress stays until replaced; everything else expires however the
        // desktop normally expires notifications.
        DBusInt32(notification.ongoing ? 0 : -1),
      ],
      replySignature: DBusSignature('u'),
    );
    final id = result.returnValues[0].asUint32();
    final previous = _ids[key];
    if (previous != null && previous != id) _keys.remove(previous);
    _ids[key] = id;
    _keys[id] = key;
  }

  @override
  Future<void> dismiss(String key) async {
    final id = _ids.remove(key);
    if (id == null) return;
    _keys.remove(id);
    await _connect().callMethod(
      _notificationsInterface,
      'CloseNotification',
      [DBusUint32(id)],
      replySignature: DBusSignature(''),
    );
  }

  /// Desktop notifications need no permission.
  @override
  Future<bool> requestPermission() async => true;

  Future<void> close() async {
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    _subscriptions.clear();
    if (_clientOverride == null) await _object?.client.close();
    _object = null;
  }
}
