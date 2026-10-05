import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/widgets/dialogs.dart';
import 'package:soiboi/base/services/center_toast.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';

Widget _buildTestApp({required Widget child}) {
  return MaterialApp(
    navigatorKey: globalNavigatorKey,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(body: child),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('interaction services', () {
    testWidgets(
      'showCenterMessage displays overlay text and throttles duplicate messages',
      (tester) async {
        await tester.pumpWidget(_buildTestApp(child: const Text('Home')));

        showCenterMessage('First Notification', duration: 1000);
        await tester.pump();

        expect(find.text('First Notification'), findsOneWidget);

        // Throttling: duplicate message within 2s should be ignored
        showCenterMessage('First Notification', duration: 1000);
        await tester.pump();
        expect(find.text('First Notification'), findsOneWidget);

        // Wait for duration to remove first entry
        await tester.pump(const Duration(milliseconds: 1100));
        expect(find.text('First Notification'), findsNothing);
      },
    );

    testWidgets(
      'showCenterLoading and removeCenterLoading toggle loading overlay',
      (tester) async {
        await tester.pumpWidget(_buildTestApp(child: const Text('Home')));

        showCenterLoading();
        await tester.pump();

        expect(find.byType(CircularProgressIndicator), findsOneWidget);

        removeCenterLoading();
        await tester.pump();

        expect(find.byType(CircularProgressIndicator), findsNothing);
      },
    );

    testWidgets(
      'showConfirmDialog returns true on confirm and false on cancel',
      (tester) async {
        bool? result;

        await tester.pumpWidget(
          _buildTestApp(
            child: Builder(
              builder: (context) {
                return ElevatedButton(
                  onPressed: () async {
                    result = await showConfirmDialog(
                      context,
                      'Delete item?',
                      message: 'Sure?',
                      confirmText: 'OK',
                    );
                  },
                  child: const Text('Open Dialog'),
                );
              },
            ),
          ),
        );

        // 1. Confirm flow
        await tester.tap(find.text('Open Dialog'));
        await tester.pumpAndSettle();

        expect(find.text('Delete item?'), findsOneWidget);
        expect(find.text('Sure?'), findsOneWidget);

        // Tap confirm button (second ElevatedButton in dialog)
        final buttons = find.byType(ElevatedButton);
        // The last button in the dialog row is the confirm button
        await tester.tap(buttons.last);
        await tester.pumpAndSettle();

        expect(result, isTrue);

        // 2. Cancel flow
        await tester.tap(find.text('Open Dialog'));
        await tester.pumpAndSettle();

        // Tap cancel button
        final cancelButtons = find.byType(ElevatedButton);
        // First button in the dialog row is Cancel
        await tester.tap(cancelButtons.at(1));
        await tester.pumpAndSettle();

        expect(result, isFalse);
      },
    );

    testWidgets('getInputTextDialog inputs text and returns value', (
      tester,
    ) async {
      String? entered;

      await tester.pumpWidget(
        _buildTestApp(
          child: Builder(
            builder: (context) {
              return ElevatedButton(
                onPressed: () async {
                  entered = await getInputTextDialog(context, 'Enter Name');
                },
                child: const Text('Show Input'),
              );
            },
          ),
        ),
      );

      await tester.tap(find.text('Show Input'));
      await tester.pumpAndSettle();

      expect(find.text('Enter Name'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'Test Playlist');
      await tester.pump();

      final confirmBtn = find.widgetWithText(ElevatedButton, 'Confirm');
      if (confirmBtn.evaluate().isNotEmpty) {
        await tester.tap(confirmBtn);
      } else {
        // Look for buttons
        final buttons = find.byType(ElevatedButton);
        await tester.tap(buttons.last);
      }
      await tester.pumpAndSettle();

      expect(entered, 'Test Playlist');
    });
  });
}
