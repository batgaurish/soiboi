import 'package:drift/drift.dart' hide isNull;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/data/database.dart';

void main() {
  late MetadataDB db;

  setUp(() {
    db = MetadataDB(NativeDatabase.memory());
  });

  tearDown(() async {
    await db.close();
  });

  test('MetadataDB schemaVersion is 3', () {
    expect(db.schemaVersion, 3);
  });

  test('can insert, query, update and delete metadata items', () async {
    // Insert
    await db.into(db.metadataItems).insert(
      MetadataItemsCompanion.insert(
        id: 'track_1',
        title: const Value('Solar Fields'),
        artist: const Value('Mirror'),
        album: const Value('Origin'),
        albumArtist: const Value('Mirror'),
        genre: const Value('Ambient'),
        year: const Value(2021),
        track: const Value(1),
        disc: const Value(1),
        duration: const Value(360),
        bitrate: const Value(320),
        samplerate: const Value(44100),
      ),
    );

    // Query
    final item = await (db.select(db.metadataItems)..where((t) => t.id.equals('track_1'))).getSingle();
    expect(item.id, 'track_1');
    expect(item.title, 'Solar Fields');
    expect(item.artist, 'Mirror');
    expect(item.album, 'Origin');
    expect(item.albumArtist, 'Mirror');
    expect(item.genre, 'Ambient');
    expect(item.year, 2021);
    expect(item.track, 1);
    expect(item.disc, 1);
    expect(item.duration, 360);
    expect(item.playCount, 0); // default value
    expect(item.lastPlayed, isNull);

    // Update
    await (db.update(db.metadataItems)..where((t) => t.id.equals('track_1'))).write(
      const MetadataItemsCompanion(
        playCount: Value(5),
        lastPlayed: Value(1700000000),
      ),
    );

    final updated = await (db.select(db.metadataItems)..where((t) => t.id.equals('track_1'))).getSingle();
    expect(updated.playCount, 5);
    expect(updated.lastPlayed, 1700000000);

    // Delete
    await (db.delete(db.metadataItems)..where((t) => t.id.equals('track_1'))).go();
    final remaining = await (db.select(db.metadataItems)..where((t) => t.id.equals('track_1'))).getSingleOrNull();
    expect(remaining, isNull);
  });

  test('primary key constraint prevents duplicate IDs', () async {
    await db.into(db.metadataItems).insert(
      MetadataItemsCompanion.insert(id: 'unique_id', title: const Value('Track A')),
    );

    expect(
      () => db.into(db.metadataItems).insert(
        MetadataItemsCompanion.insert(id: 'unique_id', title: const Value('Track B')),
      ),
      throwsA(isA<SqliteException>()),
    );
  });
}
