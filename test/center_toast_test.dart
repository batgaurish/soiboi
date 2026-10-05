import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/center_toast.dart';

Widget _buildTestApp({required Widget child}) {
  return MaterialApp(
    navigatorKey: globalNavigatorKey,
    home: Scaffold(body: child),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('center_toast leaf service', () {
    testWidgets('showCenterMessage displays message and throttles within 2s', (
      tester,
    ) async {
      await tester.pumpWidget(_buildTestApp(child: const Text('Root')));

      showCenterMessage('Hello Toast', duration: 500);
      await tester.pump();

      expect(find.text('Hello Toast'), findsOneWidget);

      // Throttling: subsequent call inside 2s is dropped
      showCenterMessage('Ignored Toast', duration: 500);
      await tester.pump();
      expect(find.text('Ignored Toast'), findsNothing);

      // Advance clock past toast display duration
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('Hello Toast'), findsNothing);
    });

    testWidgets('showCenterLoading and removeCenterLoading toggle overlay', (
      tester,
    ) async {
      await tester.pumpWidget(_buildTestApp(child: const Text('Root')));

      await showCenterLoading();
      await tester.pump();

      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      removeCenterLoading();
      await tester.pump();

      expect(find.byType(CircularProgressIndicator), findsNothing);
    });

    test('tryVibrate executes safely without throwing', () {
      vibrationOnNoitifier.value = false;
      expect(() => tryVibrate(), returnsNormally);

      vibrationOnNoitifier.value = true;
      expect(() => tryVibrate(), returnsNormally);
    });
  });
}
