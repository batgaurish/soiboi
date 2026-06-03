part of '../../layer/license_layer.dart';

extension _LicensePanel on _LicenseLayerState {
  Widget panelView(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return Column(
      children: [
        TitleBar(
          hintText: l10n.searchLicenses,
          textController: textController,
          backToRoot: () {
            layersManager.popDetail('settings');
          },
        ),
        Expanded(child: panelContent()),
      ],
    );
  }

  Widget panelContent() {
    return Column(
      children: [
        ValueListenableBuilder(
          valueListenable: highlightTextColor.valueNotifier,
          builder: (context, value, child) {
            return Text(
              'Sylvakru',
              style: .new(fontWeight: .bold, fontSize: 20, color: value),
            );
          },
        ),
        Text(versionNumber),
        SizedBox(height: 5),
        Text('© 2025-2026 AfalpHy'),

        SizedBox(height: 5),
        Text('Powered by Flutter'),
        SizedBox(height: 10),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Expanded(
                  flex: 2,
                  child: ListView.builder(
                    itemCount: packages.length,
                    itemBuilder: (context, index) {
                      final pkg = packages[index];

                      return Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                        child: Material(
                          color: Colors.transparent,
                          shape: SmoothRectangleBorder(
                            smoothness: 1,
                            borderRadius: .all(.circular(10)),
                          ),
                          clipBehavior: .antiAlias,
                          child: ValueListenableBuilder(
                            valueListenable: selectedItemColor.valueNotifier,
                            builder: (context, value, child) {
                              return ListTile(
                                tileColor: pkg == selectedPackage
                                    ? value
                                    : null,
                                title: Text(pkg),
                                onTap: () {
                                  selectedPackage = pkg;
                                  rebuild();
                                },
                              );
                            },
                          ),
                        ),
                      );
                    },
                  ),
                ),

                MyDivider(
                  width: 1,
                  thickness: 0.5,
                  color: dividerColor,
                  vertical: true,
                ),

                Expanded(
                  flex: 5,
                  child: selectedPackage == null
                      ? SizedBox()
                      : buildLicenseDetail(selectedPackage!),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
