part of 'settings_list.dart';

/// Language, motion, fonts, colours and theme.
mixin _AppearanceSettings on _SettingsBase {
  Widget languageListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(languageImage, size: iconSize),
      title: Text(l10n.language),
      onTap: () {
        showAnimationDialog(
          context: context,

          child: SizedBox(
            width: 300,
            height: isMobile ? 200 : 180,
            child: Padding(
              padding: const EdgeInsets.all(15.0),
              child: ValueListenableBuilder(
                valueListenable: localeNotifier,
                builder: (context, value, child) {
                  final l10n = AppLocalizations.of(context);

                  return ListView(
                    children: [
                      ListTile(
                        title: Text(l10n.followSystem),
                        onTap: () {
                          localeNotifier.value = null;
                          setting.save();
                        },
                        trailing: value == null ? Icon(Icons.check) : null,
                      ),
                      ListTile(
                        title: Text('English'),
                        onTap: () {
                          localeNotifier.value = Locale('en');
                          setting.save();
                        },
                        trailing: value == Locale('en')
                            ? Icon(Icons.check)
                            : null,
                      ),
                      ListTile(
                        title: Text('中文'),
                        onTap: () {
                          localeNotifier.value = Locale('zh');
                          setting.save();
                        },
                        trailing: value == Locale('zh')
                            ? Icon(Icons.check)
                            : null,
                      ),
                    ],
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }

  Widget drawerListTile(AppLocalizations l10n) {
    return ListTile(
      leading: Transform.scale(
        scale: 0.95,
        child: Icon(Icons.menu_rounded, size: iconSize),
      ),
      title: Text(l10n.menuOnRight),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          semanticLabel: l10n.menuOnRight,
          valueNotifier: endDrawerNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  Widget reduceMotionListTile() {
    final l10n = AppLocalizations.of(context);
    String describe(MotionPreference preference, bool systemReduces) =>
        switch (preference) {
          MotionPreference.system =>
            systemReduces
                ? l10n.motionFollowingSystemReduced
                : l10n.motionFollowingSystemFull,
          MotionPreference.reduced => l10n.motionReducedDescription,
          MotionPreference.full => l10n.motionFullDescription,
        };
    return ListenableBuilder(
      listenable: Listenable.merge([
        motionPreferenceNotifier,
        systemReducesMotionNotifier,
      ]),
      builder: (context, _) => ListTile(
        leading: Icon(Icons.motion_photos_off_outlined, size: iconSize),
        title: Text(l10n.reduceMotion),
        subtitle: Text(
          describe(
            motionPreferenceNotifier.value,
            systemReducesMotionNotifier.value,
          ),
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
        onTap: () => showAnimationDialog(
          context: context,
          child: SizedBox(
            width: 340,
            child: Padding(
              padding: const EdgeInsets.all(10),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final (preference, title) in [
                    (MotionPreference.system, l10n.followTheSystem),
                    (MotionPreference.reduced, l10n.reduceMotion),
                    (MotionPreference.full, l10n.fullMotion),
                  ])
                    ListTile(
                      title: Text(title),
                      selected: motionPreferenceNotifier.value == preference,
                      trailing: motionPreferenceNotifier.value == preference
                          ? const Icon(Icons.check)
                          : null,
                      onTap: () {
                        motionPreferenceNotifier.value = preference;
                        setting.save();
                        Navigator.pop(context);
                      },
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget vibrationListTile(AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(vibrationImage, size: iconSize),
      title: Text(l10n.vibration),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          semanticLabel: l10n.vibration,
          valueNotifier: vibrationOnNoitifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  void _updateMainPageTheme() {
    setting.save();
    colorManager.updateMainPageColors();
  }

  void _updateLyricsPageTheme() {
    setting.save();
    colorManager.updateLyricsPageColors();
  }

  Widget fontListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(fontImage, size: iconSize),

      title: Text(l10n.fonts),
      onTap: () {
        if (!isPremiumNotifier.value) {
          showPremiumDialog(context);
          return;
        }
        layersManager.pushDetail('settings', 'font_picker');
      },
      trailing: ValueListenableBuilder(
        valueListenable: isPremiumNotifier,
        builder: (context, value, child) {
          if (value) {
            return SizedBox.shrink();
          }
          return Icon(Icons.lock);
        },
      ),
    );
  }

  void _updateFlavour() {
    setting.save();
    colorManager.updateColors();
  }

  /// Flavour picker. Distinct from the Theme tile, which controls
  /// light/dark/vivid — flavour is *which* visual identity, brightness is how
  /// light it is, and the two compose.
  Widget flavourListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(themeImage, size: iconSize),
      title: Text(l10n.flavour),
      subtitle: ValueListenableBuilder(
        valueListenable: flavourNotifier,
        builder: (context, value, child) => Text(
          value.label,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      onTap: () async {
        flavourNotifier.addListener(_updateFlavour);
        await showAnimationDialog(
          context: context,
          child: SizedBox(
            width: 300,
            height: 290,
            child: Padding(
              padding: const EdgeInsets.all(15.0),
              child: ValueListenableBuilder(
                valueListenable: flavourNotifier,
                builder: (context, value, child) {
                  return Column(
                    children: [
                      Text(
                        l10n.flavour,
                        style: TextStyle(fontSize: 18, fontWeight: .bold),
                      ),
                      for (final flavour in Flavour.values)
                        ListTile(
                          title: Text(flavour.label),
                          subtitle: Text(
                            flavour.blurb,
                            style: const TextStyle(fontSize: 11),
                          ),
                          onTap: () {
                            flavourNotifier.value = flavour;
                            updateHoverFocusColor();
                          },
                          trailing: value == flavour
                              ? const Icon(Icons.check)
                              : null,
                        ),
                    ],
                  );
                },
              ),
            ),
          ),
        );
        flavourNotifier.removeListener(_updateFlavour);
      },
    );
  }

  /// System colours override the flavour's palette but keep its shape,
  /// density and motion -- dynamic colour restyles a flavour, it doesn't
  /// replace one.
  /// Colour source: where the app's colours come from, independent of
  /// [Flavour] (shape/motion only, see flavour.dart). Three choices — app's
  /// own colours, matugen/Material You, or a prebuilt named palette — plus
  /// "Album art", which is not a fourth branch here at all: it is
  /// `ThemeType.vivid`, already offered per-page from the Theme tile.
  Widget colorSourceListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.palette_outlined, size: 30),
      title: Text(l10n.colourSource),
      subtitle: ValueListenableBuilder(
        valueListenable: colorSourceNotifier,
        builder: (context, source, child) {
          final loaded = dynamicDarkNotifier.value != null;
          final label = switch (source) {
            ColorSource.off => l10n.appColoursDefault,
            ColorSource.matugen =>
              !loaded
                  ? l10n.noColoursTapToSetUp
                  // Says where the colours actually came from: with two routes
                  // (an existing matugen setup's file, or generating from the
                  // wallpaper) "it worked" is not enough to debug from.
                  : dynamicColorSourceDescription ??
                        (Platform.isAndroid
                            ? 'Material You'
                            : l10n.matchedViaMatugen),
            ColorSource.prebuilt => l10n.prebuiltWithName(
              prebuiltPaletteNotifier.value.label,
            ),
          };
          return Text(
            label,
            style: TextStyle(fontSize: 12, color: textColor.value),
          );
        },
      ),
      onTap: () => _openColorSourcePicker(context),
    );
  }

  Future<void> _openColorSourcePicker(BuildContext context) async {
    final l10n = AppLocalizations.of(context);
    await showAnimationDialog(
      context: context,
      child: ValueListenableBuilder(
        valueListenable: colorSourceNotifier,
        builder: (context, source, child) => SizedBox(
          width: 320,
          child: Padding(
            padding: const EdgeInsets.all(15.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  l10n.colourSource,
                  style: TextStyle(fontSize: 18, fontWeight: .bold),
                ),
                ListTile(
                  title: Text(l10n.appColours),
                  subtitle: Text(
                    l10n.appColoursHint,
                    style: TextStyle(fontSize: 11),
                  ),
                  onTap: () {
                    colorSourceNotifier.value = ColorSource.off;
                    clearDynamicPalette();
                    setting.save();
                    colorManager.updateColors();
                    Navigator.of(context).pop();
                  },
                  trailing: source == ColorSource.off
                      ? const Icon(Icons.check)
                      : null,
                ),
                ListTile(
                  title: Text(Platform.isAndroid ? 'Material You' : 'Matugen'),
                  subtitle: Text(
                    Platform.isAndroid
                        ? "Your wallpaper's system palette"
                        : l10n.matugenHint,
                    style: const TextStyle(fontSize: 11),
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _openMatugenDialog(context);
                  },
                  trailing: source == ColorSource.matugen
                      ? const Icon(Icons.check)
                      : null,
                ),
                ListTile(
                  title: Text(l10n.prebuiltPalette),
                  subtitle: Text(
                    l10n.prebuiltPaletteHint,
                    style: TextStyle(fontSize: 11),
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _openPrebuiltPaletteDialog(context);
                  },
                  trailing: source == ColorSource.prebuilt
                      ? const Icon(Icons.check)
                      : null,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _openMatugenDialog(BuildContext context) async {
    final l10n = AppLocalizations.of(context);
    final controller = TextEditingController(
      text: matugenPathNotifier.value.isEmpty
          ? defaultMatugenPath
          : matugenPathNotifier.value,
    );
    String? status;
    await showAnimationDialog(
      context: context,
      child: StatefulBuilder(
        builder: (context, setDialogState) => SizedBox(
          width: 360,
          // Taller than it looks it needs: the scheme row and the
          // optional-path field both wrap on narrow displays, and a
          // Column in a fixed box overflows rather than scrolling.
          height: 340,
          child: Padding(
            padding: const EdgeInsets.all(18.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  Platform.isAndroid ? 'Material You' : 'Matugen',
                  style: const TextStyle(fontSize: 18, fontWeight: .bold),
                ),
                const SizedBox(height: 6),
                Text(
                  Platform.isAndroid
                      // Android derives the palette itself, from the
                      // wallpaper, and hands it over whole — there is
                      // nothing to configure and nothing to run.
                      ? l10n.materialYouExplainer
                      : l10n.matugenExplainer,
                  style: TextStyle(fontSize: 12, color: textColor.value),
                ),
                const SizedBox(height: 12),
                // The scheme applies on both platforms, by different
                // routes: matugen builds it from the wallpaper on Linux,
                // and on Android the wallpaper's seed is rebuilt through
                // the matching Material variant. Only the JSON path below
                // is matugen's alone.
                Row(
                  children: [
                    Text(l10n.scheme, style: const TextStyle(fontSize: 12)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButton<String>(
                        isExpanded: true,
                        value: matugenSchemeNotifier.value,
                        // Both are needed. The default menu paints on the
                        // ambient Material canvas, which this app never
                        // sets, so it comes out white — and the app's own
                        // near-white text on it is unreadable.
                        dropdownColor: menuColor.value,
                        style: TextStyle(fontSize: 12, color: textColor.value),
                        items: [
                          for (final scheme in matugenSchemes)
                            DropdownMenuItem(
                              value: scheme,
                              child: Text(schemeLabel(scheme)),
                            ),
                        ],
                        onChanged: (scheme) async {
                          if (scheme == null) return;
                          matugenSchemeNotifier.value = scheme;
                          // Android has no matugen: the palette comes from
                          // the system, so the scheme is applied by
                          // rebuilding from the same seed.
                          final ok = Platform.isAndroid
                              ? await loadSystemPalette()
                              : await generateMatugenPalette();
                          setDialogState(() {
                            status = ok
                                ? l10n.usingSchemeFromWallpaper(
                                    schemeLabel(scheme),
                                  )
                                : Platform.isAndroid
                                ? l10n.androidNoPalette
                                : l10n.matugenCouldNotGenerate;
                          });
                          if (ok) {
                            dynamicColorSourceDescription = l10n
                                .schemeFromWallpaper(schemeLabel(scheme));
                            colorSourceNotifier.value = ColorSource.matugen;
                            leaveVividMainTheme();
                            setting.save();
                            colorManager.updateColors();
                          }
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                if (!Platform.isAndroid)
                  TextField(
                    controller: controller,
                    style: const TextStyle(fontSize: 12),
                    decoration: InputDecoration(
                      isDense: true,
                      border: OutlineInputBorder(),
                      labelText: l10n.matugenJsonOptional,
                    ),
                  ),
                if (status != null) ...[
                  const SizedBox(height: 8),
                  Text(status!, style: const TextStyle(fontSize: 12)),
                ],
                const Spacer(),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () {
                        colorSourceNotifier.value = ColorSource.off;
                        clearDynamicPalette();
                        setting.save();
                        colorManager.updateColors();
                        Navigator.of(context).pop();
                      },
                      child: Text(l10n.turnOff),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: () async {
                        matugenPathNotifier.value = controller.text.trim();
                        // Falls back to generating, so an empty or wrong
                        // path is not a dead end.
                        final ok = await autoLoadDynamicPalette();
                        if (!ok) {
                          setDialogState(
                            () => status = Platform.isAndroid
                                ? l10n.androidNoPaletteLong
                                : l10n.noColoursMatugenFailed,
                          );
                          return;
                        }
                        colorSourceNotifier.value = ColorSource.matugen;
                        leaveVividMainTheme();
                        setting.save();
                        colorManager.updateColors();
                        if (context.mounted) Navigator.of(context).pop();
                      },
                      child: Text(l10n.useTheseColours),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
    controller.dispose();
  }

  Future<void> _openPrebuiltPaletteDialog(BuildContext context) async {
    final l10n = AppLocalizations.of(context);
    final isDark = mainPageThemeNotifier.value == .dark;
    await showAnimationDialog(
      context: context,
      child: SizedBox(
        width: 320,
        height: 420,
        child: Padding(
          padding: const EdgeInsets.all(15.0),
          child: Column(
            children: [
              Text(
                l10n.prebuiltPalette,
                style: TextStyle(fontSize: 18, fontWeight: .bold),
              ),
              const SizedBox(height: 6),
              Expanded(
                child: ValueListenableBuilder(
                  valueListenable: prebuiltPaletteNotifier,
                  builder: (context, selected, child) => ListView(
                    children: [
                      for (final palette in PrebuiltPalette.values)
                        ListTile(
                          leading: CircleAvatar(
                            radius: 10,
                            backgroundColor: palette.accent(isDark: isDark),
                          ),
                          title: Text(palette.label),
                          onTap: () {
                            prebuiltPaletteNotifier.value = palette;
                            colorSourceNotifier.value = ColorSource.prebuilt;
                            leaveVividMainTheme();
                            setting.save();
                            colorManager.updateColors();
                            Navigator.of(context).pop();
                          },
                          trailing:
                              selected == palette &&
                                  colorSourceNotifier.value ==
                                      ColorSource.prebuilt
                              ? const Icon(Icons.check)
                              : null,
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget themeListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(themeImage, size: iconSize),
      title: Text(l10n.theme),
      onTap: () async {
        mainPageThemeNotifier.addListener(_updateMainPageTheme);
        lyricsPageThemeNotifier.addListener(_updateLyricsPageTheme);
        await showAnimationDialog(
          context: context,

          child: OrientationBuilder(
            builder: (context, orientation) {
              final size = MediaQuery.of(context).size;
              final shortSide = size.shortestSide;

              bool isPhone = shortSide < 600;

              return SizedBox(
                width: 300,
                height: isPhone && orientation == .landscape
                    ? 350
                    : isMobile
                    ? 420
                    : 370,
                child: Padding(
                  padding: const EdgeInsets.all(15.0),
                  child: CustomScrollView(
                    scrollBehavior: ScrollBehavior().copyWith(
                      scrollbars: false,
                    ),
                    slivers: [
                      sliverBox(
                        ValueListenableBuilder(
                          valueListenable: mainPageThemeNotifier,
                          builder: (context, value, child) {
                            final l10n = AppLocalizations.of(context);
                            return Column(
                              children: [
                                Text(
                                  l10n.mainPageTheme,
                                  style: .new(fontSize: 18, fontWeight: .bold),
                                ),
                                ListTile(
                                  title: Text(l10n.vividMode),
                                  // "Vivid" says nothing about what it does.
                                  // This is the album-art colouring people
                                  // go looking for and do not find, so the
                                  // tile now says so.
                                  subtitle: Text(
                                    l10n.coloursFromAlbumArt,
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: textColor.value,
                                    ),
                                  ),
                                  onTap: () {
                                    if (!isPremiumNotifier.value) {
                                      showPremiumDialog(context);
                                      return;
                                    }
                                    mainPageThemeNotifier.value = .vivid;
                                    updateHoverFocusColor();
                                  },
                                  trailing: ValueListenableBuilder(
                                    valueListenable: isPremiumNotifier,
                                    builder: (context, isPremium, child) {
                                      if (!isPremium) {
                                        return Icon(Icons.lock);
                                      }
                                      return value == .vivid
                                          ? Icon(Icons.check)
                                          : SizedBox.shrink();
                                    },
                                  ),
                                ),
                                ListTile(
                                  title: Text(l10n.lightMode),
                                  onTap: () {
                                    mainPageThemeNotifier.value = .light;
                                    updateHoverFocusColor();
                                  },
                                  trailing: value == .light
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                                ListTile(
                                  title: Text(l10n.darkMode),
                                  onTap: () {
                                    mainPageThemeNotifier.value = .dark;
                                    updateHoverFocusColor();
                                  },
                                  trailing: value == .dark
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                              ],
                            );
                          },
                        ),
                      ),
                      sliverBox(
                        ValueListenableBuilder(
                          valueListenable: lyricsPageThemeNotifier,
                          builder: (context, value, child) {
                            final l10n = AppLocalizations.of(context);
                            return Column(
                              children: [
                                Text(
                                  l10n.lyricsPageTheme,
                                  style: .new(fontSize: 18, fontWeight: .bold),
                                ),
                                ListTile(
                                  title: Text(l10n.vividMode),
                                  // "Vivid" says nothing about what it does.
                                  // This is the album-art colouring people
                                  // go looking for and do not find, so the
                                  // tile now says so.
                                  subtitle: Text(
                                    l10n.coloursFromAlbumArt,
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: textColor.value,
                                    ),
                                  ),
                                  onTap: () {
                                    lyricsPageThemeNotifier.value = .vivid;
                                  },
                                  trailing: value == .vivid
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                                ListTile(
                                  title: Text(l10n.lightMode),
                                  onTap: () {
                                    lyricsPageThemeNotifier.value = .light;
                                  },
                                  trailing: value == .light
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                                ListTile(
                                  title: Text(l10n.darkMode),
                                  onTap: () {
                                    lyricsPageThemeNotifier.value = .dark;
                                  },
                                  trailing: value == .dark
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                              ],
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        );
        mainPageThemeNotifier.removeListener(_updateMainPageTheme);
        lyricsPageThemeNotifier.removeListener(_updateLyricsPageTheme);
      },
    );
  }

  Widget equalizerListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(equalizerImage, size: iconSize),
      title: Text(l10n.equalizer),
      onTap: () {
        if (!isPremiumNotifier.value) {
          showPremiumDialog(context);
          return;
        }
        showAnimationDialog(
          context: context,
          child: OrientationBuilder(
            builder: (context, orientation) {
              final size = MediaQuery.of(context).size;
              final shortSide = size.shortestSide;

              bool isPhone = shortSide < 600;
              if (isMobile && orientation == .portrait) {
                return SizedBox(
                  height: 500,
                  width: isPhone ? 300 : 400,
                  child: EqualizerWidget(),
                );
              } else {
                return SizedBox(
                  height: isPhone ? 350 : 400,
                  width: 540,
                  child: EqualizerWidget(),
                );
              }
            },
          ),
        );
      },
      trailing: ValueListenableBuilder(
        valueListenable: isPremiumNotifier,
        builder: (context, value, child) {
          if (value) {
            return SizedBox.shrink();
          }
          return Icon(Icons.lock);
        },
      ),
    );
  }

  Widget immersiveWideLayoutListTile(AppLocalizations l10n) {
    return ListTile(
      leading: Transform.scale(
        scale: 0.9,
        child: AppIcon(fullscreenImage, size: iconSize),
      ),

      title: Text(l10n.immersiveWideLayout),
      trailing: SizedBox(
        width: 50,
        child: Builder(
          builder: (context) {
            return MySwitch(
              semanticLabel: l10n.immersiveWideLayout,
              valueNotifier: immersiveWideLayoutNotifier,
              onToggleCallBack: () {
                if (!isTooNarrow(context)) {
                  applySystemUiMode(
                    mode: immersiveWideLayoutNotifier.value
                        ? .immersiveSticky
                        : .edgeToEdge,
                  );
                }
                setting.save();
              },
            );
          },
        ),
      ),
    );
  }

  /// Network lyric lookup. Off means the app never reaches out for lyrics --
  /// worth having as a switch since everything else here works offline.
}
