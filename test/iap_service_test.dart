import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:in_app_purchase/in_app_purchase.dart';
// ignore: depend_on_referenced_packages
import 'package:in_app_purchase_platform_interface/in_app_purchase_platform_interface.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/config.dart';
import 'package:soiboi/base/services/iap_service.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/premium_layer.dart';

class _FakeInAppPurchasePlatform extends InAppPurchasePlatform {
  final purchaseController = StreamController<List<PurchaseDetails>>.broadcast();
  bool available = true;
  List<ProductDetails> productsToReturn = [];
  final completedPurchases = <PurchaseDetails>[];
  final nonConsumablePurchases = <PurchaseParam>[];
  bool restorePurchasesCalled = false;

  @override
  Stream<List<PurchaseDetails>> get purchaseStream => purchaseController.stream;

  @override
  Future<bool> isAvailable() async => available;

  @override
  Future<ProductDetailsResponse> queryProductDetails(Set<String> identifiers) async {
    return ProductDetailsResponse(
      productDetails: productsToReturn,
      notFoundIDs: const [],
    );
  }

  @override
  Future<bool> buyNonConsumable({required PurchaseParam purchaseParam}) async {
    nonConsumablePurchases.add(purchaseParam);
    return true;
  }

  @override
  Future<void> completePurchase(PurchaseDetails purchase) async {
    completedPurchases.add(purchase);
  }

  @override
  Future<void> restorePurchases({String? applicationUserName}) async {
    restorePurchasesCalled = true;
  }
}

class _TestPurchaseDetails extends PurchaseDetails {
  _TestPurchaseDetails({
    required super.productID,
    required super.status,
  }) : super(
          verificationData: PurchaseVerificationData(
            localVerificationData: 'test_local',
            serverVerificationData: 'test_server',
            source: 'test',
          ),
          transactionDate: '2026-10-05',
        ) {
    pendingCompletePurchase = true;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  late _FakeInAppPurchasePlatform fakePlatform;
  late IAPService iapService;
  late AppLocalizations l10n;
  final messages = <String>[];

  setUpAll(() async {
    debugDefaultTargetPlatformOverride = TargetPlatform.linux;
    tempDir = await Directory.systemTemp.createTemp('iap_service_test_');
    appSupportDir = tempDir;
    await logger.init();
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
    config.file = File('${tempDir.path}/config.json');

    // Trigger InAppPurchase.instance once on Linux so no platform registers
    InAppPurchase.instance;
  });

  tearDownAll(() async {
    debugDefaultTargetPlatformOverride = null;
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  setUp(() {
    fakePlatform = _FakeInAppPurchasePlatform();
    InAppPurchasePlatform.instance = fakePlatform;
    iapService = IAPService()..l10n = l10n;
    messages.clear();
    iapService.onMessage = (msg, {duration}) => messages.add(msg);
    isPremiumNotifier.value = false;
    trialRemainingMinNotifier.value = 60;
  });

  tearDown(() {
    // Only dispose if initialized
    try {
      iapService.dispose();
    } catch (_) {}
  });

  group('IAPService', () {
    test('checkAvailability returns true when available', () async {
      fakePlatform.available = true;
      final result = await iapService.checkAvailability();
      expect(result, isTrue);
      expect(messages, isEmpty);
    });

    test('checkAvailability reports message when unavailable', () async {
      fakePlatform.available = false;
      final result = await iapService.checkAvailability();
      expect(result, isFalse);
      expect(messages, contains(l10n.iapNotAvailable));
    });

    test('buyProduct queries products and triggers buyNonConsumable', () async {
      fakePlatform.productsToReturn = [
        ProductDetails(
          id: 'com.batgaurish.soiboi.premium.lifetime',
          title: 'Premium',
          description: 'Lifetime unlock',
          price: r'$4.99',
          rawPrice: 4.99,
          currencyCode: 'USD',
        ),
      ];

      await iapService.buyProduct();
      expect(messages, contains(l10n.connectingToAppStore));
      expect(fakePlatform.nonConsumablePurchases, isNotEmpty);
      expect(
        fakePlatform.nonConsumablePurchases.first.productDetails.id,
        'com.batgaurish.soiboi.premium.lifetime',
      );
    });

    test('buyProduct reports productNotAvailable when no products found', () async {
      fakePlatform.productsToReturn = [];
      await iapService.buyProduct();
      expect(messages, contains(l10n.productNotAvailable));
      expect(fakePlatform.nonConsumablePurchases, isEmpty);
    });

    test('handles purchase stream: pending, purchased, restored, and completePurchase', () async {
      iapService.initialize();

      // 1. Pending status
      fakePlatform.purchaseController.add([
        _TestPurchaseDetails(
          productID: 'com.batgaurish.soiboi.premium.lifetime',
          status: PurchaseStatus.pending,
        ),
      ]);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(messages, contains(l10n.pendingPurchase));

      // 2. Purchased status -> unlocks premium and completes purchase
      fakePlatform.purchaseController.add([
        _TestPurchaseDetails(
          productID: 'com.batgaurish.soiboi.premium.lifetime',
          status: PurchaseStatus.purchased,
        ),
      ]);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(isPremiumNotifier.value, isTrue);
      expect(trialRemainingMinNotifier.value, -1);
      expect(fakePlatform.completedPurchases, isNotEmpty);

      // 3. Restored status -> sets foundAnyRestored and unlocks
      fakePlatform.purchaseController.add([
        _TestPurchaseDetails(
          productID: 'com.batgaurish.soiboi.premium.lifetime',
          status: PurchaseStatus.restored,
        ),
      ]);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(iapService.foundAnyRestored, isTrue);

      // 4. Canceled status -> completes pending purchase
      fakePlatform.purchaseController.add([
        _TestPurchaseDetails(
          productID: 'com.batgaurish.soiboi.premium.lifetime',
          status: PurchaseStatus.canceled,
        ),
      ]);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(fakePlatform.completedPurchases.length, 3);
    });
  });
}
