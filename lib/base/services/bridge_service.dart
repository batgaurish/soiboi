/// Owns the download-bridge connection: its address, its liveness, and the
/// polling loop behind the Downloads screen.
///
/// Polling only runs while something is watching. The bridge is a remote
/// service reached over LAN or Tailscale, and a background poll on a phone is
/// pure battery and radio cost for a screen nobody is looking at — so
/// [startPolling] is refcounted and the timer stops as soon as the last viewer
/// leaves.
library;

import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/bridge_client.dart';

/// Configured server address, empty when the user has not set one.
final bridgeUrlNotifier = ValueNotifier<String>('');

/// Latest stats, or null before the first successful poll.
final bridgeStatsNotifier = ValueNotifier<BridgeStats?>(null);

/// Last error, cleared on the next success. Drives the page's error banner.
final bridgeErrorNotifier = ValueNotifier<String?>(null);

/// Whether a request is in flight, for spinners on first load.
final bridgeLoadingNotifier = ValueNotifier<bool>(false);

bool get bridgeConfigured => bridgeUrlNotifier.value.trim().isNotEmpty;

BridgeClient? get bridgeClient =>
    bridgeConfigured ? BridgeClient(bridgeUrlNotifier.value) : null;

/// While a download is running the UI should feel live; while idle, there is
/// nothing to watch, so back off and stop hammering the server.
const _activeInterval = Duration(seconds: 2);
const _idleInterval = Duration(seconds: 10);

Timer? _timer;
int _viewers = 0;

/// Call when a screen starts showing bridge state. Must be paired with
/// [stopPolling].
void startPolling() {
  _viewers++;
  if (_viewers == 1) {
    unawaited(refreshStats());
    _schedule(_activeInterval);
  }
}

void stopPolling() {
  _viewers = _viewers > 0 ? _viewers - 1 : 0;
  if (_viewers == 0) {
    _timer?.cancel();
    _timer = null;
  }
}

void _schedule(Duration interval) {
  _timer?.cancel();
  _timer = Timer(interval, () async {
    if (_viewers == 0) return;
    await refreshStats();
    final stats = bridgeStatsNotifier.value;
    _schedule(stats == null || stats.isIdle ? _idleInterval : _activeInterval);
  });
}

Future<void> refreshStats() async {
  final client = bridgeClient;
  if (client == null) {
    bridgeStatsNotifier.value = null;
    return;
  }
  try {
    final stats = await client.stats();
    bridgeStatsNotifier.value = stats;
    bridgeErrorNotifier.value = null;
  } on BridgeException catch (e) {
    // Keep the last good stats on screen rather than blanking the page; a
    // transient network blip shouldn't wipe what the user was reading.
    bridgeErrorNotifier.value = e.message;
  }
}

/// Runs [action] with the loading flag set, surfacing failures through
/// [bridgeErrorNotifier]. Returns true when the action succeeded.
Future<bool> runBridgeAction(Future<void> Function(BridgeClient) action) async {
  final client = bridgeClient;
  if (client == null) {
    bridgeErrorNotifier.value = 'No server address configured';
    return false;
  }
  bridgeLoadingNotifier.value = true;
  try {
    await action(client);
    bridgeErrorNotifier.value = null;
    await refreshStats();
    return true;
  } on BridgeException catch (e) {
    bridgeErrorNotifier.value = e.message;
    return false;
  } finally {
    bridgeLoadingNotifier.value = false;
  }
}
