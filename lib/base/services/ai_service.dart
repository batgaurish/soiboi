/// Bring-your-own-key AI: one small client for every supported provider.
///
/// Soiboi ships no key and no account. The person pastes their own key, which
/// stays in the platform's secure storage (Android Keystore, libsecret on
/// Linux) and is only ever sent to the provider they chose.
///
/// Two wire formats cover every provider: Anthropic's Messages API for Claude,
/// and the OpenAI-compatible chat completions API for everyone else. Gemini,
/// OpenRouter, Groq and Ollama all serve that second format, so adding a
/// provider is a base URL, a key page and a default model, not new code.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

enum AiProvider { gemini, openRouter, anthropic, openAi, groq, ollama, custom }

class AiProviderInfo {
  const AiProviderInfo({
    required this.label,
    required this.blurb,
    required this.baseUrl,
    this.keyPage,
    this.defaultModel,
    this.free = false,
    this.needsKey = true,
  });

  final String label;
  final String blurb;

  /// OpenAI-compatible providers: the base the `/chat/completions` and
  /// `/models` paths hang off. Anthropic: the API host.
  final String baseUrl;

  /// Where a key is created, opened by "Get a free key" / "Get a key".
  final String? keyPage;

  /// Used until the person picks one, and when listing models fails.
  final String? defaultModel;

  /// Has a free tier that needs no card.
  final bool free;
  final bool needsKey;
}

const Map<AiProvider, AiProviderInfo> aiProviders = {
  AiProvider.gemini: AiProviderInfo(
    label: 'Google Gemini',
    blurb: 'Free tier, sign in with a Google account, no card',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    keyPage: 'https://aistudio.google.com/apikey',
    defaultModel: 'gemini-2.5-flash',
    free: true,
  ),
  AiProvider.openRouter: AiProviderInfo(
    label: 'OpenRouter',
    blurb: 'Free models, plus hundreds of paid ones on one key',
    baseUrl: 'https://openrouter.ai/api/v1',
    keyPage: 'https://openrouter.ai/keys',
    free: true,
  ),
  AiProvider.anthropic: AiProviderInfo(
    label: 'Anthropic (Claude)',
    blurb: 'Claude models, paid',
    baseUrl: 'https://api.anthropic.com',
    keyPage: 'https://console.anthropic.com/settings/keys',
    defaultModel: 'claude-opus-5',
  ),
  AiProvider.openAi: AiProviderInfo(
    label: 'OpenAI',
    blurb: 'GPT models, paid',
    baseUrl: 'https://api.openai.com/v1',
    keyPage: 'https://platform.openai.com/api-keys',
  ),
  AiProvider.groq: AiProviderInfo(
    label: 'Groq',
    blurb: 'Fast open models, free tier',
    baseUrl: 'https://api.groq.com/openai/v1',
    keyPage: 'https://console.groq.com/keys',
    free: true,
  ),
  AiProvider.ollama: AiProviderInfo(
    label: 'Ollama (local)',
    blurb: 'Models on your own machine, nothing leaves your network',
    baseUrl: 'http://localhost:11434/v1',
    needsKey: false,
  ),
  AiProvider.custom: AiProviderInfo(
    label: 'Other (OpenAI-compatible)',
    blurb: 'Any endpoint that speaks the OpenAI chat API',
    baseUrl: '',
    needsKey: false,
  ),
};

/// What the person configured. [key] is never logged or shown.
class AiConfig {
  const AiConfig({
    required this.provider,
    this.key = '',
    this.model = '',
    this.baseUrl = '',
  });

  final AiProvider provider;
  final String key;
  final String model;

  /// Overrides the provider's base URL (Ollama on another machine, custom).
  final String baseUrl;

  AiProviderInfo get info => aiProviders[provider]!;
  String get effectiveBaseUrl =>
      (baseUrl.trim().isEmpty ? info.baseUrl : baseUrl.trim()).replaceAll(
        RegExp(r'/+$'),
        '',
      );
  String get effectiveModel =>
      model.trim().isNotEmpty ? model.trim() : (info.defaultModel ?? '');

  bool get isUsable =>
      (!info.needsKey || key.trim().isNotEmpty) &&
      effectiveBaseUrl.isNotEmpty &&
      effectiveModel.isNotEmpty;
}

class AiException implements Exception {
  AiException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// The saved configuration, or null before setup.
final aiConfigNotifier = ValueNotifier<AiConfig?>(null);

const _storage = FlutterSecureStorage();
const _storageKey = 'soiboi.ai.config';

Future<void> loadAiConfig() async {
  try {
    final raw = await _storage.read(key: _storageKey);
    if (raw == null) return;
    final json = jsonDecode(raw) as Map<String, dynamic>;
    final provider = AiProvider.values
        .where((p) => p.name == json['provider'])
        .firstOrNull;
    if (provider == null) return;
    aiConfigNotifier.value = AiConfig(
      provider: provider,
      key: json['key'] as String? ?? '',
      model: json['model'] as String? ?? '',
      baseUrl: json['baseUrl'] as String? ?? '',
    );
  } catch (_) {
    // No keyring (a bare Linux session): AI simply stays unconfigured.
  }
}

Future<void> saveAiConfig(AiConfig? config) async {
  aiConfigNotifier.value = config;
  if (config == null) {
    await _storage.delete(key: _storageKey);
    return;
  }
  await _storage.write(
    key: _storageKey,
    value: jsonEncode({
      'provider': config.provider.name,
      'key': config.key,
      'model': config.model,
      'baseUrl': config.baseUrl,
    }),
  );
}

const _timeout = Duration(minutes: 4);

/// One request, one text answer.
Future<String> aiComplete(
  AiConfig config, {
  required String system,
  required String prompt,
}) async {
  if (!config.isUsable) throw AiException('AI is not set up yet');
  return config.provider == AiProvider.anthropic
      ? _anthropic(config, system, prompt)
      : _openAiCompatible(config, system, prompt);
}

Future<String> _anthropic(AiConfig c, String system, String prompt) async {
  final resp = await http
      .post(
        Uri.parse('${c.effectiveBaseUrl}/v1/messages'),
        headers: {
          'content-type': 'application/json',
          'x-api-key': c.key.trim(),
          'anthropic-version': '2023-06-01',
          // A declined request is retried on Anthropic's recommended model
          // instead of coming back as a refusal.
          'anthropic-beta': 'server-side-fallback-2026-07-01',
        },
        body: jsonEncode({
          'model': c.effectiveModel,
          'max_tokens': 16000,
          'fallbacks': 'default',
          'system': system,
          'messages': [
            {'role': 'user', 'content': prompt},
          ],
        }),
      )
      .timeout(_timeout);
  final body = _decode(resp);
  if (body['stop_reason'] == 'refusal') {
    throw AiException('Claude declined this request');
  }
  final text = (body['content'] as List? ?? const [])
      .whereType<Map>()
      .where((b) => b['type'] == 'text')
      .map((b) => b['text'] as String? ?? '')
      .join();
  if (text.trim().isEmpty) throw AiException('The model sent back nothing');
  return text;
}

Future<String> _openAiCompatible(AiConfig c, String system, String prompt) async {
  final resp = await http
      .post(
        Uri.parse('${c.effectiveBaseUrl}/chat/completions'),
        headers: {
          'content-type': 'application/json',
          if (c.key.trim().isNotEmpty) 'authorization': 'Bearer ${c.key.trim()}',
          // OpenRouter shows these on its activity page; harmless elsewhere.
          'x-title': 'Soiboi',
          'http-referer': 'https://github.com/batgaurish/soiboi',
        },
        body: jsonEncode({
          'model': c.effectiveModel,
          'messages': [
            {'role': 'system', 'content': system},
            {'role': 'user', 'content': prompt},
          ],
        }),
      )
      .timeout(_timeout);
  final body = _decode(resp);
  final choices = body['choices'] as List? ?? const [];
  final message = choices.isEmpty ? null : (choices.first as Map)['message'];
  final text = message is Map ? message['content'] as String? : null;
  if (text == null || text.trim().isEmpty) {
    throw AiException('The model sent back nothing');
  }
  return text;
}

Map<String, dynamic> _decode(http.Response resp) {
  Map<String, dynamic>? body;
  try {
    body = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  } catch (_) {}
  if (resp.statusCode >= 200 && resp.statusCode < 300 && body != null) {
    return body;
  }
  throw AiException(_explain(resp.statusCode, body));
}

/// Provider errors in words a person can act on.
String _explain(int status, Map<String, dynamic>? body) {
  final err = body?['error'];
  final detail = err is Map
      ? err['message'] as String?
      : (err is String ? err : null);
  final hint = switch (status) {
    401 || 403 => 'The key was refused. Check it was copied whole.',
    404 => 'That model or address was not found.',
    429 => 'Rate limited or out of free quota. Wait a minute and try again.',
    >= 500 => 'The provider is having trouble. Try again shortly.',
    _ => 'The request failed ($status).',
  };
  return detail == null || detail.isEmpty ? hint : '$hint\n$detail';
}

/// The provider's model ids, for the model picker. Empty when the provider
/// has no listing endpoint or the request fails.
Future<List<String>> aiListModels(AiConfig c) async {
  try {
    final isAnthropic = c.provider == AiProvider.anthropic;
    final resp = await http
        .get(
          Uri.parse(
            isAnthropic
                ? '${c.effectiveBaseUrl}/v1/models?limit=100'
                : '${c.effectiveBaseUrl}/models',
          ),
          headers: {
            if (isAnthropic) ...{
              'x-api-key': c.key.trim(),
              'anthropic-version': '2023-06-01',
            } else if (c.key.trim().isNotEmpty)
              'authorization': 'Bearer ${c.key.trim()}',
          },
        )
        .timeout(const Duration(seconds: 20));
    final body = _decode(resp);
    final ids = (body['data'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => (m['id'] as String? ?? '').replaceFirst('models/', ''))
        .where((id) => id.isNotEmpty)
        .toList();
    return ids;
  } catch (_) {
    return const [];
  }
}

/// A sensible model from [ids] when the provider has no fixed default: a free
/// one on OpenRouter, a chat model elsewhere.
String? pickDefaultModel(AiProvider provider, List<String> ids) {
  if (ids.isEmpty) return null;
  bool chatty(String id) => !RegExp(
    r'embed|whisper|tts|audio|image|vision-only|guard|moderation|dall-e|transcribe|realtime',
    caseSensitive: false,
  ).hasMatch(id);
  final usable = ids.where(chatty).toList();
  if (usable.isEmpty) return ids.first;
  switch (provider) {
    case AiProvider.openRouter:
      final free = usable.where((id) => id.endsWith(':free')).toList();
      for (final pref in ['deepseek', 'llama', 'gemini', 'qwen', 'mistral']) {
        final hit = free.where((id) => id.contains(pref)).firstOrNull;
        if (hit != null) return hit;
      }
      return free.firstOrNull ?? usable.first;
    case AiProvider.gemini:
      return usable
              .where((id) => id.contains('flash') && !id.contains('lite'))
              .firstOrNull ??
          usable.first;
    case AiProvider.groq:
      return usable.where((id) => id.contains('versatile')).firstOrNull ??
          usable.where((id) => id.contains('llama')).firstOrNull ??
          usable.first;
    case AiProvider.openAi:
      return usable.where((id) => id.startsWith('gpt-')).firstOrNull ??
          usable.first;
    default:
      return usable.first;
  }
}

/// The first JSON object in [text], tolerating code fences and chatter
/// around it, since not every model honours "reply with JSON only".
Map<String, dynamic> extractJsonObject(String text) {
  final start = text.indexOf('{');
  final end = text.lastIndexOf('}');
  if (start < 0 || end <= start) {
    throw AiException('The model did not answer in the expected format');
  }
  try {
    return jsonDecode(text.substring(start, end + 1)) as Map<String, dynamic>;
  } catch (_) {
    throw AiException('The model did not answer in the expected format');
  }
}
