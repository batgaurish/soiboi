part of "../../base/widgets/collection_list.dart";

extension _CollectionListPage on CollectionListState {
  Widget pageView(BuildContext context) {
    final fab = floatingAction(context);
    return Scaffold(
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      floatingActionButton: fab == null
          ? null
          : Padding(padding: kFabAboveMiniPlayer, child: fab),
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: customAppBarLeading(context),
        backgroundColor: Colors.transparent,
        systemOverlayStyle: mainPageThemeNotifier.value == .dark
            ? .light
            : .dark,
        scrolledUnderElevation: 0,
        title: Text(title),
        centerTitle: true,
        actions: [searchField(searchHint), moreButton(context)],
      ),
      body: ListenableBuilder(
        listenable: Listenable.merge([isListViewNotifier, changeNotifier]),
        builder: (context, child) {
          if (preparing) {
            return Center(
              child: CircularProgressIndicator(color: iconColor.value),
            );
          }
          return (isListViewNotifier?.value ?? false)
              ? listView()
              : pageGridView();
        },
      ),
    );
  }

  Widget searchField(String hintText) {
    return MySearchField(
      hintText: hintText,
      textController: textController,
      onSearchTextChanged: updateCurrentList,
    );
  }

  Widget moreButton(BuildContext context) {
    return IconButton(
      tooltip: AppLocalizations.of(context).more,
      icon: labelIcon(AppLocalizations.of(context).more, Icon(Icons.more_vert)),
      onPressed: () {
        tryVibrate();

        showModalBottomSheet(
          context: context,
          isScrollControlled: true,
          useRootNavigator: true,
          builder: (context) {
            return moreSheet(context);
          },
        );
      },
    );
  }

  Widget moreSheet(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return MySheet(
      height: 300,
      Column(
        children: [
          ListTile(title: Text(l10n.settings)),
          MyDivider(thickness: 0.5, height: 1, color: dividerColor),

          Expanded(
            child: ListView(
              children: [
                if (isListViewNotifier != null)
                  ListTile(
                    leading: ValueListenableBuilder(
                      valueListenable: isListViewNotifier!,
                      builder: (context, value, child) {
                        return AppIcon(value ? listImage : gridImage);
                      },
                    ),
                    title: Text(
                      l10n.view,
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    visualDensity: const VisualDensity(
                      horizontal: 0,
                      vertical: -4,
                    ),
                    trailing: MySwitch(
                      semanticLabel: l10n.view,
                      trueText: l10n.list,
                      falseText: l10n.grid,
                      valueNotifier: isListViewNotifier!,
                      onToggleCallBack: () {
                        setting.save();
                      },
                    ),
                  ),

                ListenableBuilder(
                  listenable: Listenable.merge([isListViewNotifier]),
                  builder: (context, child) {
                    if (isListViewNotifier?.value ?? false) {
                      return SizedBox.shrink();
                    }
                    return ListTile(
                      leading: AppIcon(pictureImage),
                      title: Text(
                        l10n.pictureSize,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      trailing: MySwitch(
                        semanticLabel: l10n.pictureSize,
                        trueText: l10n.large,
                        falseText: l10n.small,
                        valueNotifier: useLargePictureNotifier,
                        onToggleCallBack: () {
                          setting.save();
                        },
                      ),
                    );
                  },
                ),

                if (randomizeNotifier != null)
                  ListTile(
                    leading: AppIcon(sequenceImage),
                    title: Text(
                      l10n.order,
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    visualDensity: const VisualDensity(
                      horizontal: 0,
                      vertical: -4,
                    ),
                    trailing: MySwitch(
                      semanticLabel: l10n.order,
                      trueText: l10n.randomize,
                      falseText: l10n.normal,
                      valueNotifier: randomizeNotifier!,
                      onToggleCallBack: () {
                        updateCurrentList();
                      },
                    ),
                  ),

                ListenableBuilder(
                  listenable: Listenable.merge([
                    isAscendingNotifier,
                    randomizeNotifier,
                  ]),
                  builder: (_, _) {
                    if (isAscendingNotifier == null) {
                      return SizedBox();
                    }
                    if (randomizeNotifier?.value ?? false) {
                      return SizedBox();
                    }
                    return ListTile(
                      visualDensity: const VisualDensity(
                        horizontal: 0,
                        vertical: -4,
                      ),
                      trailing: MySwitch(
                        semanticLabel: l10n.order,
                        trueText: l10n.ascending,
                        falseText: l10n.descending,
                        valueNotifier: isAscendingNotifier!,
                        onToggleCallBack: () {
                          setting.save();
                        },
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget listView() {
    return ListView.builder(
      itemExtent: scaledExtent(context, 64),
      itemCount: currentPictureList.length,
      itemBuilder: (context, index) {
        final picture = currentPictureList[index];
        final text = currentTextList[index];
        return Center(
          child: ListTile(
            contentPadding: EdgeInsets.symmetric(horizontal: 20),

            leading: Hero(
              tag: (picture?.id ?? '') + label + text,
              transitionOnUserGestures: true,
              child: CoverArtWidget(
                size: 50,
                borderRadius: 5,
                picture: picture,
              ),
            ),
            title: Text(text, style: .new(overflow: .ellipsis)),
            subtitle: currentSubCountList == null
                ? null
                : Text(
                    AppLocalizations.of(
                      context,
                    ).songCount(currentSubCountList![index]),
                  ),
            onTap: () {
              currentOnTapList[index].call();
            },
            onLongPress: () => openItemMenu(context, index),
          ),
        );
      },
    );
  }

  Widget pageGridView() {
    return ValueListenableBuilder(
      valueListenable: useLargePictureNotifier,
      builder: (context, useLargePicture, child) {
        return GridView.builder(
          padding: EdgeInsets.symmetric(horizontal: 20),
          gridDelegate: MyGirdDelegate(
            maxCrossAxisExtent: useLargePicture ? 180 : 120,
            crossAxisSpacing: 10,
            mainAxisSpacing: 5,
            textExtent: 25,
          ),
          itemCount: currentPictureList.length,
          itemBuilder: (context, index) {
            final picture = currentPictureList[index];
            final text = currentTextList[index];

            return LayoutBuilder(
              builder: (context, constraints) {
                // One item for a screen reader, as on the desktop grid.
                return Semantics(
                  container: true,
                  button: true,
                  label: text,
                  onTap: () => currentOnTapList[index].call(),
                  onLongPress: () => openItemMenu(context, index),
                  child: Column(
                    children: [
                      GestureDetector(
                        excludeFromSemantics: true,
                        child: Hero(
                          tag: (picture?.id ?? '') + label + text,
                          transitionOnUserGestures: true,
                          child: CoverArtWidget(
                            size: constraints.maxWidth,
                            borderRadius: constraints.maxWidth / 10,
                            picture: picture,
                          ),
                        ),
                        onTap: () {
                          currentOnTapList[index].call();
                        },
                        onLongPressStart: (details) => openItemMenu(
                          context,
                          index,
                          details.globalPosition,
                        ),
                        onSecondaryTapUp: (details) => openItemMenu(
                          context,
                          index,
                          details.globalPosition,
                        ),
                      ),
                      SizedBox(
                        width: constraints.maxWidth - 10,
                        child: ExcludeSemantics(
                          child: Text(
                            text,
                            textAlign: .center,
                            style: TextStyle(overflow: TextOverflow.ellipsis),
                          ),
                        ),
                      ),
                    ],
                  ),
                );
              },
            );
          },
        );
      },
    );
  }
}
