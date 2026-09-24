import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/utils/media_query.dart';

/// Measures [textGrowth] and [scaledExtent] under a given text scaler.
Future<(double, double)> _measure(
  WidgetTester tester,
  TextScaler scaler, {
  double base = 60,
  double share = 0.6,
}) async {
  late double growth;
  late double extent;
  await tester.pumpWidget(
    MediaQuery(
      data: MediaQueryData(textScaler: scaler),
      child: Builder(
        builder: (context) {
          growth = textGrowth(context);
          extent = scaledExtent(context, base, textShare: share);
          return const SizedBox();
        },
      ),
    ),
  );
  return (growth, extent);
}

void main() {
  testWidgets('sizes are untouched at the default text size', (tester) async {
    final (growth, extent) = await _measure(tester, TextScaler.noScaling);
    expect(growth, 1);
    expect(extent, 60);
  });

  testWidgets('only the text share of a size grows', (tester) async {
    final (growth, extent) = await _measure(tester, const TextScaler.linear(2));
    expect(growth, 2);
    // 60 with 60% of it text: the 36 of text doubles, the 24 stays.
    expect(extent, closeTo(96, 0.001));
  });

  testWidgets('smaller text never shrinks a row', (tester) async {
    final (_, extent) = await _measure(tester, const TextScaler.linear(0.8));
    expect(extent, 60);
  });

  testWidgets('a row grown for 200% text holds two lines of it', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(
            size: Size(360, 800),
            textScaler: TextScaler.linear(2),
          ),
          child: Builder(
            builder: (context) => Material(
              child: ListView(
                itemExtent: scaledExtent(context, 60),
                children: const [
                  ListTile(
                    dense: false,
                    visualDensity: VisualDensity(vertical: -4),
                    title: Text('Digital Love', maxLines: 1),
                    subtitle: Text('Daft Punk - Discovery', maxLines: 1),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    // A ListTile that did not fit would report an overflow here.
    expect(tester.takeException(), isNull);
    final tile = tester.getSize(find.byType(ListTile));
    expect(tile.height, greaterThanOrEqualTo(90));
  });
}
