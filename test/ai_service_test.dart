import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/ai_service.dart';

/// A local stand-in for both wire formats, recording what it was sent.
class _FakeProvider {
  late HttpServer server;
  final requests = <Map<String, dynamic>>[];
  int status = 200;

  String get base => 'http://127.0.0.1:${server.port}';

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      final body = await utf8.decodeStream(req);
      requests.add({
        'path': req.uri.path,
        'auth': req.headers.value('authorization'),
        'x-api-key': req.headers.value('x-api-key'),
        'version': req.headers.value('anthropic-version'),
        'body': body.isEmpty ? null : jsonDecode(body),
      });
      req.response.statusCode = status;
      req.response.headers.contentType = ContentType.json;
      if (status != 200) {
        req.response.write(jsonEncode({
          'error': {'message': 'bad key'},
        }));
      } else if (req.uri.path.endsWith('/models')) {
        req.response.write(jsonEncode({
          'data': [
            {'id': 'text-embedding-3'},
            {'id': 'models/some-model:free'},
            {'id': 'llama-3.3-70b:free'},
          ],
        }));
      } else if (req.uri.path == '/v1/messages') {
        req.response.write(jsonEncode({
          'stop_reason': 'end_turn',
          'content': [
            {'type': 'text', 'text': 'claude says hi'},
          ],
        }));
      } else {
        req.response.write(jsonEncode({
          'choices': [
            {
              'message': {'content': 'compat says hi'},
            },
          ],
        }));
      }
      await req.response.close();
    });
  }
}

void main() {
  late _FakeProvider fake;
  setUp(() async {
    fake = _FakeProvider();
    await fake.start();
  });
  tearDown(() => fake.server.close(force: true));

  test('OpenAI-compatible request shape and parsing', () async {
    final c = AiConfig(
      provider: AiProvider.openRouter,
      key: 'sk-test',
      model: 'm1',
      baseUrl: fake.base,
    );
    final text = await aiComplete(c, system: 'sys', prompt: 'hello');
    expect(text, 'compat says hi');
    final r = fake.requests.single;
    expect(r['path'], '/chat/completions');
    expect(r['auth'], 'Bearer sk-test');
    final body = r['body'] as Map;
    expect(body['model'], 'm1');
    expect((body['messages'] as List).first, {
      'role': 'system',
      'content': 'sys',
    });
  });

  test('Anthropic request shape and parsing', () async {
    final c = AiConfig(
      provider: AiProvider.anthropic,
      key: 'sk-ant',
      baseUrl: fake.base,
    );
    expect(c.effectiveModel, 'claude-opus-5');
    final text = await aiComplete(c, system: 'sys', prompt: 'hello');
    expect(text, 'claude says hi');
    final r = fake.requests.single;
    expect(r['path'], '/v1/messages');
    expect(r['x-api-key'], 'sk-ant');
    expect(r['version'], '2023-06-01');
    final body = r['body'] as Map;
    expect(body['system'], 'sys');
    expect(body['fallbacks'], 'default');
  });

  test('a refused key becomes a readable error', () async {
    fake.status = 401;
    final c = AiConfig(
      provider: AiProvider.groq,
      key: 'nope',
      model: 'm',
      baseUrl: fake.base,
    );
    await expectLater(
      aiComplete(c, system: 's', prompt: 'p'),
      throwsA(
        isA<AiException>().having(
          (e) => e.message,
          'message',
          contains('The key was refused'),
        ),
      ),
    );
  });

  test('model listing strips prefixes; free OpenRouter model preferred', () async {
    final c = AiConfig(
      provider: AiProvider.openRouter,
      key: 'k',
      baseUrl: fake.base,
    );
    final ids = await aiListModels(c);
    expect(ids, ['text-embedding-3', 'some-model:free', 'llama-3.3-70b:free']);
    expect(pickDefaultModel(AiProvider.openRouter, ids), 'llama-3.3-70b:free');
  });

  test('usable only with the pieces its provider needs', () {
    expect(const AiConfig(provider: AiProvider.gemini).isUsable, isFalse);
    expect(
      const AiConfig(provider: AiProvider.gemini, key: 'k').isUsable,
      isTrue,
    );
    expect(const AiConfig(provider: AiProvider.ollama).isUsable, isFalse);
    expect(
      const AiConfig(provider: AiProvider.ollama, model: 'llama3').isUsable,
      isTrue,
    );
  });

  test('JSON is found inside fences and chatter', () {
    expect(
      extractJsonObject('Sure!\n```json\n{"name": "x", "tracks": [1]}\n```'),
      {
        'name': 'x',
        'tracks': [1],
      },
    );
    expect(() => extractJsonObject('no json here'), throwsA(isA<AiException>()));
  });

  group('streaming', () {
    test('openai-compatible deltas are read, noise is ignored', () {
      String? t(String l) => sseText(l, anthropic: false);
      expect(t('data: {"choices":[{"delta":{"content":"he"}}]}'), 'he');
      expect(t('data: {"choices":[{"delta":{"role":"assistant"}}]}'), isNull);
      expect(t('data: [DONE]'), isNull);
      expect(t(': keep-alive'), isNull);
      expect(t('data: {not json'), isNull);
    });

    test('anthropic text deltas are read, refusals are spotted', () {
      expect(
        sseText(
          'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}',
          anthropic: true,
        ),
        'hi',
      );
      expect(
        sseRefused('data: {"type":"message_delta","delta":{"stop_reason":"refusal"}}'),
        isTrue,
      );
      expect(sseRefused('data: {"delta":{"stop_reason":"end_turn"}}'), isFalse);
    });

    test('a streamed answer is assembled and reported as it grows', () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      server.listen((req) async {
        await utf8.decodeStream(req);
        req.response.headers.contentType = ContentType('text', 'event-stream');
        for (final piece in ['{"name": "x", ', '"tracks": [1, 2]}']) {
          req.response.write(
            'data: ${jsonEncode({
              'choices': [
                {
                  'delta': {'content': piece},
                },
              ],
            })}\n\n',
          );
          await req.response.flush();
        }
        req.response.write('data: [DONE]\n\n');
        await req.response.close();
      });
      final c = AiConfig(
        provider: AiProvider.custom,
        key: 'k',
        model: 'm',
        baseUrl: 'http://127.0.0.1:${server.port}',
      );
      final seen = <String>[];
      final text = await aiCompleteStreaming(
        c,
        system: 's',
        prompt: 'p',
        onText: seen.add,
      );
      expect(text, '{"name": "x", "tracks": [1, 2]}');
      expect(seen.length, 2);
      expect(seen.first, '{"name": "x", ');
    });

    test('a provider that ignores stream still works', () async {
      final c = AiConfig(
        provider: AiProvider.openRouter,
        key: 'sk-test',
        model: 'm1',
        baseUrl: fake.base,
      );
      final text = await aiCompleteStreaming(c, system: 's', prompt: 'p');
      expect(text, 'compat says hi');
      expect((fake.requests.single['body'] as Map)['stream'], isTrue);
    });

    test('a refused key on a stream is a readable error', () async {
      fake.status = 401;
      final c = AiConfig(
        provider: AiProvider.groq,
        key: 'nope',
        model: 'm',
        baseUrl: fake.base,
      );
      await expectLater(
        aiCompleteStreaming(c, system: 's', prompt: 'p'),
        throwsA(
          isA<AiException>().having(
            (e) => e.message,
            'message',
            contains('The key was refused'),
          ),
        ),
      );
    });
  });
}
