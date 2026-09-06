/// Apple Music sign-in.
///
/// On Android this opens Apple's real login page in a WebView and harvests the
/// resulting session cookies. The app never sees a password: Apple's page takes
/// the credentials, and 2FA, passkeys and SSO all work because Apple handles
/// them. All we keep is the session, which is a much smaller thing to ask
/// someone to trust us with than their Apple ID.
///
/// Desktop imports a browser-exported cookies.txt instead, because no
/// maintained Flutter WebView supports Linux. Exporting from a browser is easy
/// on a desktop anyway, which is where the awkwardness belongs.
library;

import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/cookie_store.dart' as store;
import 'package:soiboi/base/theme/flavour.dart';

const _appleMusicUrl = 'https://music.apple.com/us/browse';

/// True once the session cookie we actually need is present, which is the only
/// reliable signal that a sign-in finished -- Apple's flow has no single
/// "done" URL to watch for, between 2FA, passkeys and regional redirects.
Future<bool> _harvest() async {
  final manager = CookieManager.instance();

  // Deduplicated by name: the URLs below overlap, and the same cookie coming
  // back three times would be written three times.
  final byName = <String, store.Cookie>{};

  for (final url in const [
    'https://music.apple.com',
    'https://apple.com',
    'https://idmsa.apple.com',
    'https://buy.itunes.apple.com',
  ]) {
    final cookies = await manager.getCookies(url: WebUri(url));
    for (final cookie in cookies) {
      final value = cookie.value.toString();
      if (cookie.name.isEmpty || value.isEmpty) continue;
      byName[cookie.name] = store.Cookie(
        // Android's CookieManager.getCookie() returns only "name=value" pairs
        // — the plugin explicitly reports null for domain, expiry, secure and
        // httpOnly. So none of those can be read back, and guessing the domain
        // from the URL we happened to query is wrong: myacinfo is set on
        // .apple.com, and labelling it music.apple.com means gamdl never sends
        // it to buy.itunes.apple.com. Downloads would then fail with an opaque
        // account error while the app claimed to be signed in.
        //
        // Writing everything to .apple.com with subdomains covers every host
        // involved (music, itunes, amp-api, idmsa). Broadening scope is safe
        // here because the file is local, is filtered to Apple domains, and is
        // only ever read by our own downloader.
        //
        // The exception is media-user-token: gamdl looks it up by exact
        // domain, so .apple.com makes it invisible. See _hostScopedCookies.
        domain: store.domainForCookie(cookie.name),
        includeSubdomains: true,
        name: cookie.name,
        value: value,
        path: '/',
        secure: true,
        // Written as a session cookie, since the real expiry is unknowable
        // here. The store treats a null expiry as "not expired" rather than
        // 1970, so this does not read as instantly stale.
        expires: cookie.expiresDate == null
            ? null
            : DateTime.fromMillisecondsSinceEpoch(cookie.expiresDate!),
      );
    }
  }

  return store.saveCookies(byName.values.toList());
}

class AppleSignInLayer extends StatefulWidget {
  const AppleSignInLayer({super.key});

  @override
  State<AppleSignInLayer> createState() => _AppleSignInLayerState();
}

class _AppleSignInLayerState extends State<AppleSignInLayer> {
  bool _checking = false;
  String? _status;

  Future<void> _check({bool silent = false}) async {
    if (_checking) return;
    setState(() => _checking = true);
    final ok = await _harvest();
    if (!mounted) return;
    setState(() {
      _checking = false;
      _status = ok
          ? 'Signed in'
          : silent
          ? null
          : 'Not signed in yet — complete the login above.';
    });
    if (ok && mounted) Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid && !Platform.isIOS) {
      return const _DesktopImport();
    }

    return Scaffold(
      backgroundColor: pageBackgroundColor.value,
      appBar: AppBar(
        backgroundColor: panelColor.value,
        title: const Text('Sign in to Apple Music'),
        actions: [
          TextButton(
            onPressed: _checking ? null : () => _check(),
            child: Text(_checking ? 'Checking…' : 'Done'),
          ),
        ],
      ),
      body: Column(
        children: [
          if (_status != null)
            Container(
              width: double.infinity,
              color: buttonColor.value,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              child: Text(
                _status!,
                style: TextStyle(fontSize: 12.5, color: textColor.value),
              ),
            ),
          Expanded(
            child: InAppWebView(
              initialUrlRequest: URLRequest(url: WebUri(_appleMusicUrl)),
              initialSettings: InAppWebViewSettings(
                // Apple serves a cut-down page to unrecognised clients, and the
                // sign-in flow misbehaves there.
                userAgent:
                    'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 '
                    '(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
                javaScriptEnabled: true,
                thirdPartyCookiesEnabled: true,
                sharedCookiesEnabled: true,
              ),
              onLoadStop: (controller, url) {
                // Once back on a music.apple.com page the login has probably
                // completed, so check quietly rather than making the user
                // guess when to press Done.
                final host = url?.host ?? '';
                if (host.contains('music.apple.com')) _check(silent: true);
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// Desktop: import a cookies.txt exported from a browser.
class _DesktopImport extends StatefulWidget {
  const _DesktopImport();

  @override
  State<_DesktopImport> createState() => _DesktopImportState();
}

class _DesktopImportState extends State<_DesktopImport> {
  String? _status;
  bool _busy = false;

  Future<void> _pick() async {
    setState(() => _busy = true);
    // This fork exposes static methods rather than the upstream
    // FilePicker.platform instance.
    final result = await FilePicker.pickFiles(
      dialogTitle: 'Select cookies.txt',
      type: FileType.any,
    );
    final path = result?.files.single.path;
    if (path == null) {
      if (mounted) setState(() => _busy = false);
      return;
    }
    final ok = await store.importCookieFile(path);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _status = ok
          ? 'Signed in'
          : "That file didn't contain an Apple Music session. Make sure you "
                'were signed in when you exported it.';
    });
    if (ok) Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: pageBackgroundColor.value,
      appBar: AppBar(
        backgroundColor: panelColor.value,
        title: const Text('Sign in to Apple Music'),
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Import your Apple Music cookies',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: highlightTextColor.value,
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  'Sign in to music.apple.com in your browser, export the '
                  'cookies with a "cookies.txt" extension, then select the '
                  'file here.\n\nOnly Apple cookies are kept — anything else '
                  'in the export is discarded.',
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.5,
                    color: textColor.value,
                  ),
                ),
                const SizedBox(height: 20),
                FilledButton.icon(
                  onPressed: _busy ? null : _pick,
                  icon: const Icon(Icons.folder_open, size: 18),
                  label: Text(_busy ? 'Reading…' : 'Choose cookies.txt'),
                ),
                if (_status != null) ...[
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: buttonColor.value,
                      borderRadius: BorderRadius.circular(
                        8 * activeFlavour.cornerScale,
                      ),
                    ),
                    child: Text(
                      _status!,
                      style: TextStyle(fontSize: 12.5, color: textColor.value),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
