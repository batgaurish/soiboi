import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:soiboi/base/widgets/my_navigator.dart';
import 'package:soiboi/landscape_view/panels/collection_list_panel.dart';
import 'package:soiboi/portrait_view/pages/collection_list_page.dart';

class CollectionItem {
  final MyPicture? picture;
  final String text;
  final int? subCount;
  final VoidCallback onTap;
  final void Function(BuildContext context, Offset position)? onMenu;

  const CollectionItem({
    required this.picture,
    required this.text,
    this.subCount,
    required this.onTap,
    this.onMenu,
  });

  CollectionItem copyWith({
    MyPicture? picture,
    String? text,
    int? subCount,
    VoidCallback? onTap,
    void Function(BuildContext context, Offset position)? onMenu,
  }) {
    return CollectionItem(
      picture: picture ?? this.picture,
      text: text ?? this.text,
      subCount: subCount ?? this.subCount,
      onTap: onTap ?? this.onTap,
      onMenu: onMenu ?? this.onMenu,
    );
  }
}

abstract class CollectionList extends StatefulWidget {
  const CollectionList({super.key});
}

abstract class CollectionListState extends State<CollectionList> {
  final GlobalKey<NavigatorState> globalKey = GlobalKey();
  final visibleNotifier = ValueNotifier(true);

  List<CollectionItem> currentItems = [];

  /// Opens item [index]'s menu at [position], or at the item's centre.
  void openItemMenu(BuildContext context, int index, [Offset? position]) {
    if (index >= currentItems.length) return;
    final menu = currentItems[index].onMenu;
    if (menu == null) return;
    final box = context.findRenderObject() as RenderBox?;
    menu(
      context,
      position ??
          (box == null
              ? Offset.zero
              : box.localToGlobal(box.size.center(Offset.zero))),
    );
  }

  final textController = TextEditingController();

  final ScrollController scrollController = ScrollController();

  ValueNotifier<bool>? randomizeNotifier;

  /// A floating button over the list itself, not the pages it opens.
  Widget? floatingAction(BuildContext context) => null;
  ValueNotifier<bool>? isAscendingNotifier;
  ValueNotifier<bool> useLargePictureNotifier = ValueNotifier(false);

  final changeNotifier = ValueNotifier(0);

  ValueNotifier<bool>? isListViewNotifier;

  String title = '';
  String searchHint = '';

  // for hero tag
  String label = '';

  late final AssetImage image;

  late final String Function(int) countFunction;

  bool preparing = true;

  void updateCurrentList();

  Future<void> fetchCollectionList() async {}

  bool reachEnd = false;
  void _onScroll() async {
    if (preparing | reachEnd) {
      return;
    }

    if (scrollController.position.pixels >=
        scrollController.position.maxScrollExtent) {
      await fetchCollectionList();
    }
  }

  void onSearch() {
    if (preparing) {
      return;
    }
    updateCurrentList();
  }

  @override
  void initState() {
    super.initState();

    isAscendingNotifier?.addListener(updateCurrentList);
    textController.addListener(onSearch);
    scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    isAscendingNotifier?.removeListener(updateCurrentList);
    textController.dispose();
    scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return myNavigator(
      key: globalKey,
      visibleNotifier: visibleNotifier,
      pageViewBuilder: () => pageView(context),
      panelViewBuilder: () => panelView(context),
    );
  }
}
