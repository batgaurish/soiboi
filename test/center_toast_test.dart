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
    testWidgets('showCenterMessage displays message and debounces duplicates within 2s', (
      tester,
    ) async {
      await tester.pumpWidget(_buildTestApp(child: const Text('Root')));

      showCenterMessage('Hello Toast', duration: 500);
      await tester.pump();

      expect(find.text('Hello Toast'), findsOneWidget);

      // Throttling: duplicate call inside 2s is dropped
      showCenterMessage('Hello Toast', duration: 500);
      await tester.pump();
      expect(find.text('Hello Toast'), findsOneWidget);

      // Distinct message is displayed
      showCenterMessage('Second Toast', duration: 500);
      await tester.pump();
      expect(find.text('Second Toast'), findsOneWidget);

      // Advance clock past toast display duration
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('Second Toast'), findsNothing);
    });

    testWidgets('showCenterLoading and removeCenterLoading toggle overlay safely', (
      tester,
    ) async {
      await tester.pumpWidget(_buildTestApp(child: const Text('Root')));

      showCenterLoading();
      await tester.pump();

      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      // Repeated calls replace entry safely without orphaning
      showCenterLoading();
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
