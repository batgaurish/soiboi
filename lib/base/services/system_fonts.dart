/// System font discovery for Linux and Android.
///
/// The picker gets its list from just_font_scan, which binds DirectWrite and
/// CoreText and declares support for Windows and macOS only. On the two
/// platforms this app actually ships to it returns nothing, so the picker shows
/// an empty, unscrollable list — which is what "the font changer doesn't work"
/// looks like from outside.
///
/// On Linux the family name is all that is needed: Flutter passes `fontFamily`
/// to Skia, which resolves it through fontconfig.
///
/// Android is different, and the difference is easy to miss. Skia's Android
/// font manager only answers to the nine names declared in
/// /system/etc/fonts.xml ("sans-serif", "casual", "monospace"...), not to the
/// real family names in the font files. Asking for "Coming Soon" there silently
/// renders the default font instead — the picker lists it, previews it wrongly,
/// and selecting it appears to do nothing. So on Android each font is
/// registered from its file with a FontLoader before it can be used, which
/// [ensureSystemFontLoaded] does on demand.
///
/// Names come from each font's own `name` table rather than its filename.
/// Filenames lie: `NotoSansCJK-Regular.ttc` holds "Noto Sans CJK JP", and a
/// name Skia cannot resolve renders as the default font while the picker
/// claims otherwise.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:soiboi/base/services/logger.dart';

/// Where each platform keeps fonts.
///
/// Linux is only consulted when fontconfig is unavailable; Android has no
/// fontconfig, so these are the whole story there.
const _linuxFontDirs = [
  '/usr/share/fonts',
  '/usr/local/share/fonts',
  '/run/host/fonts', // Flatpak's view of the host's fonts
];

const _androidFontDirs = ['/system/fonts', '/product/fonts', '/system/font'];

const _fontExtensions = {'.ttf', '.otf', '.ttc'};

/// Fonts larger than this are skipped.
///
/// Android ships a 24 MB CJK serif collection and several megabytes of colour
/// emoji. Registering those to preview them would cost more memory than the
/// rest of the picker combined, and none of them is a sensible interface font.
const _maxFontBytes = 8 * 1024 * 1024;

/// Family name to the file it came from, for platforms that need the file.
final Map<String, String> _fontFiles = {};
final Set<String> _loadedFamilies = {};

/// The file a scanned [family] came from, or null if it was never scanned.
String? systemFontFile(String family) => _fontFiles[family];

/// Registers a font from a known file, without scanning first.
///
/// Startup needs this: the chosen family cannot be resolved on Android until
/// its file is registered, and scanning two hundred font files to find one
/// path already recorded in settings would delay every launch.
Future<void> loadSystemFontFromFile(String family, String path) async {
  _fontFiles[family] = path;
  await ensureSystemFontLoaded(family);
}

/// Registers [family] with Flutter if the platform cannot resolve it alone.
///
/// A no-op everywhere except Android, and idempotent: the picker calls it for
/// every list item it builds, which for a long list means many times per
/// family. Returns true when the family became newly usable, so a caller can
/// rebuild exactly once rather than on every call.
Future<bool> ensureSystemFontLoaded(String family) async {
  if (!Platform.isAndroid) return false;
  if (_loadedFamilies.contains(family)) return false;
  final path = _fontFiles[family];
  if (path == null) return false;

  // Marked before loading, not after: list items are built faster than a font
  // file is read, and without this the same font is loaded several times over.
  _loadedFamilies.add(family);
  try {
    final bytes = await File(path).readAsBytes();
    final loader = FontLoader(family)
      ..addFont(Future.value(ByteData.view(bytes.buffer)));
    await loader.load();
    return true;
  } catch (e) {
    logger.output('fonts: could not load $family from $path: $e');
    return false;
  }
}

/// Every family the platform can render, sorted and deduplicated.
///
/// Returns an empty list rather than throwing: an unreadable font directory
/// should cost the user some choices, never the settings screen.
Future<List<String>> scanSystemFonts() async {
  try {
    if (Platform.isLinux) {
      final viaFontconfig = await _scanWithFontconfig();
      if (viaFontconfig.isNotEmpty) return viaFontconfig;
      // Awaited, not returned: a bare return would escape the catch below and
      // let an unreadable font directory take down the settings screen.
      return await scanFontDirectories([
        ..._linuxFontDirs,
        ..._userLinuxFontDirs(),
      ]);
    }
    if (Platform.isAndroid) {
      return await scanFontDirectories(_androidFontDirs);
    }
  } catch (e) {
    logger.output('fonts: system scan failed: $e');
  }
  return const [];
}

List<String> _userLinuxFontDirs() {
  final home = Platform.environment['HOME'];
  if (home == null) return const [];
  return ['$home/.local/share/fonts', '$home/.fonts'];
}

/// Asks fontconfig, which is what actually resolves the name later.
///
/// Preferred over reading files directly because it already knows about user
/// config, aliases and any directory this list does not think to look in — so
/// its answer matches what Skia will accept.
Future<List<String>> _scanWithFontconfig() async {
  try {
    final result = await Process.run('fc-list', [':', 'family']);
    if (result.exitCode != 0) return const [];
    final families = <String>{};
    for (final line in const LineSplitter().convert(result.stdout as String)) {
      // A line lists a family and its aliases: "DejaVu Sans,DejaVu Sans Book".
      // The first is the one to offer.
      final name = line.split(',').first.trim();
      if (name.isNotEmpty) families.add(name);
    }
    return families.toList()..sort();
  } on ProcessException {
    // No fontconfig on this system; the caller falls back to reading files.
    return const [];
  }
}

/// Reads family names out of every font file under [dirs].
///
/// This is the Android path, and the fallback when a Linux system has no
/// fontconfig. Public so it can be tested directly: `flutter test` replaces
/// fontconfig with a minimal config for deterministic golden rendering, so a
/// test going through [scanSystemFonts] on Linux exercises fc-list against
/// nine fonts and never touches this at all.
Future<List<String>> scanFontDirectories(List<String> dirs) async {
  final families = <String>{};
  for (final path in dirs) {
    final dir = Directory(path);
    if (!dir.existsSync()) continue;
    await for (final entry in dir.list(recursive: true, followLinks: false)) {
      if (entry is! File) continue;
      final ext = entry.path.toLowerCase();
      if (!_fontExtensions.any(ext.endsWith)) continue;
      if (await entry.length() > _maxFontBytes) continue;
      final family = await _readFamilyName(entry);
      if (family == null) continue;
      families.add(family);
      // Kept whether or not this platform needs it; the map is what
      // ensureSystemFontLoaded reads, and building it here avoids a second
      // pass over every font file.
      _fontFiles.putIfAbsent(family, () => entry.path);
    }
  }
  return families.toList()..sort();
}

/// Reads the family name out of a font's `name` table.
///
/// Reads only the bytes it needs. Font files run to tens of megabytes for CJK
/// collections, and there can be hundreds of them.
Future<String?> _readFamilyName(File file) async {
  RandomAccessFile? handle;
  try {
    handle = await file.open();
    var offset = 0;

    final tag = await _read(handle, 0, 4);
    if (_ascii(tag) == 'ttcf') {
      // A collection: several fonts in one file. The first is representative
      // enough for a picker, and reading them all would multiply the work for
      // names that differ only by script.
      final header = await _read(handle, 8, 8);
      if (header.getUint32(0) == 0) return null; // no fonts
      offset = header.getUint32(4);
    }

    final numTables = (await _read(handle, offset + 4, 2)).getUint16(0);
    for (var i = 0; i < numTables; i++) {
      final record = await _read(handle, offset + 12 + i * 16, 16);
      if (_ascii(record) != 'name') continue;
      return _parseNameTable(
        await _read(handle, record.getUint32(8), record.getUint32(12)),
      );
    }
  } catch (_) {
    // A malformed or truncated font is not worth reporting: the only sensible
    // response is to leave it out of the list.
  } finally {
    await handle?.close();
  }
  return null;
}

Future<ByteData> _read(RandomAccessFile handle, int offset, int length) async {
  await handle.setPosition(offset);
  final bytes = await handle.read(length);
  if (bytes.length < length) throw const FormatException('truncated font');
  return ByteData.sublistView(bytes);
}

String _ascii(ByteData data) =>
    String.fromCharCodes(Uint8List.sublistView(data, 0, 4));

/// Picks the best family name from a parsed `name` table.
///
/// Name 16 is the typographic family and is what a user recognises: for a font
/// shipping several weights, name 1 splits them into "Roboto Light",
/// "Roboto Medium" and so on, while name 16 keeps them as "Roboto". Name 1 is
/// the fallback because name 16 is optional.
String? _parseNameTable(ByteData table) {
  final count = table.getUint16(2);
  final storage = table.getUint16(4);

  String? typographic;
  String? family;

  for (var i = 0; i < count; i++) {
    final record = 6 + i * 12;
    final nameId = table.getUint16(record + 6);
    if (nameId != 1 && nameId != 16) continue;

    final platformId = table.getUint16(record);
    final length = table.getUint16(record + 8);
    final offset = storage + table.getUint16(record + 10);
    if (offset + length > table.lengthInBytes) continue;

    final value = _decodeName(
      platformId,
      Uint8List.sublistView(table, offset, offset + length),
    );
    if (value.isEmpty || value.contains('\u0000')) continue;

    if (nameId == 16) {
      typographic ??= value;
    } else {
      family ??= value;
    }
  }
  return typographic ?? family;
}

/// Decodes one name record.
///
/// Platform 0 (Unicode) and 3 (Windows) always store UTF-16BE. Platform 1
/// (Macintosh) is supposed to store single bytes, but the declaration cannot be
/// trusted: fonts from some generators (icomoon among them) claim Macintosh and
/// store UTF-16BE anyway. Decoding those as single bytes yields a name
/// interleaved with NULs — "\u0000i\u0000c\u0000o..." — which Skia will never
/// match while the picker shows it as an option. So for platform 1 the bytes
/// decide, not the declaration.
String _decodeName(int platformId, Uint8List bytes) {
  if (platformId != 1) return _decodeUtf16Be(bytes);
  final looksUtf16 = bytes.length.isEven && bytes.isNotEmpty && bytes[0] == 0;
  return looksUtf16 ? _decodeUtf16Be(bytes) : String.fromCharCodes(bytes);
}

String _decodeUtf16Be(Uint8List bytes) {
  final units = <int>[];
  for (var i = 0; i + 1 < bytes.length; i += 2) {
    units.add((bytes[i] << 8) | bytes[i + 1]);
  }
  return String.fromCharCodes(units);
}
