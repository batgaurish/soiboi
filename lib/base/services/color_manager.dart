import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/utils/contrast_color_generator.dart';
import 'package:soiboi/layer/lyrics_page_layer.dart';

final colorManager = ColorManager();

MyPicture? backgroundPicture;
Color backgroundCoverArtColor = Colors.grey;
Color currentCoverArtColor = Colors.grey;

bool useCurrentSongForBg = true;

ContrastColorTextTheme contrastColorTheme = ContrastColorGenerator.generate(
  currentCoverArtColor,
);

final lightHoverFocusColorNotifier = ValueNotifier(false);

void updateHoverFocusColor() {
  if ((displayLyricsPage && lyricsPageThemeNotifier.value == .vivid) ||
      viewModeNotifier.value == .mini) {
    double r = currentCoverArtColor.r;
    double g = currentCoverArtColor.g;
    double b = currentCoverArtColor.b;
    final luminance = 0.299 * r + 0.587 * g + 0.114 * b;

    lightHoverFocusColorNotifier.value = luminance < 0.5;
  } else {
    lightHoverFocusColorNotifier.value = mainPageThemeNotifier.value == .dark;
  }
}

final MyColor pageBackgroundColor = MyColor(
  vividModeValue: Color.fromARGB(100, 245, 245, 245),
  lightModeValue: Colors.grey.shade100,
  darkModeValue: Color.fromARGB(255, 50, 50, 50),
  token: ColorToken.pageBackground,
);

final MyColor iconColor = MyColor(
  vividModeValue: Colors.black,
  lightModeValue: Colors.black,
  darkModeValue: Colors.grey.shade400,
  token: ColorToken.icon,
);

final MyColor textColor = MyColor(
  vividModeValue: Colors.grey.shade900,
  lightModeValue: Colors.grey.shade900,
  darkModeValue: Colors.grey.shade400,
  token: ColorToken.text,
);

final MyColor highlightTextColor = MyColor(
  vividModeValue: Colors.black,
  lightModeValue: Colors.black,
  darkModeValue: Color.fromARGB(255, 230, 230, 230),
  token: ColorToken.highlightText,
);

final MyColor switchColor = MyColor(
  vividModeValue: Colors.black87,
  lightModeValue: Colors.black87,
  darkModeValue: Color.fromARGB(221, 0, 0, 0),
  token: ColorToken.switchTrack,
);

final MyColor glassColor = MyColor(
  vividModeValue: Color.fromARGB(75, 255, 255, 255),
  lightModeValue: Color.fromARGB(128, 255, 255, 255),
  darkModeValue: Color.fromARGB(128, 30, 30, 30),
  token: ColorToken.glass,
);

final MyColor panelColor = MyColor(
  vividModeValue: Color.fromARGB(100, 245, 245, 245),
  lightModeValue: Colors.white,
  darkModeValue: Color.fromARGB(255, 50, 50, 50),
  token: ColorToken.panel,
);

final MyColor sidebarColor = MyColor(
  vividModeValue: Color.fromARGB(100, 238, 238, 238),
  lightModeValue: Colors.grey.shade50,
  darkModeValue: Color.fromARGB(255, 55, 55, 55),
  token: ColorToken.sidebar,
);

final MyColor bottomColor = MyColor(
  vividModeValue: Color.fromARGB(100, 250, 250, 250),
  lightModeValue: Colors.grey.shade100,
  darkModeValue: Color.fromARGB(255, 60, 60, 60),
  token: ColorToken.bottom,
);

final MyColor searchFieldColor = MyColor(
  getVividValue: () {
    final tmpColor =
        backgroundPicture?.lowerLuminance ?? backgroundCoverArtColor;
    return tmpColor.withAlpha(75);
  },
  lightModeValue: Colors.grey.shade200,
  darkModeValue: Colors.grey.shade700,
  token: ColorToken.searchField,
);

final MyColor buttonColor = MyColor(
  getVividValue: () {
    final tmpColor =
        backgroundPicture?.lowerLuminance ?? backgroundCoverArtColor;
    return tmpColor.withAlpha(75);
  },
  lightModeValue: Colors.grey.shade200,
  darkModeValue: Colors.grey.shade700,
  token: ColorToken.button,
);

final MyColor dividerColor = MyColor(
  getVividValue: () {
    return backgroundPicture?.lowerLuminance ?? backgroundCoverArtColor;
  },
  lightModeValue: Colors.grey,
  darkModeValue: Colors.grey.shade700,
  token: ColorToken.divider,
);

final MyColor selectedItemColor = MyColor(
  getVividValue: () {
    final tmpColor =
        backgroundPicture?.lowerLuminance ?? backgroundCoverArtColor;
    return tmpColor.withAlpha(75);
  },
  lightModeValue: Colors.grey.shade200,
  darkModeValue: Colors.grey.shade700,
  token: ColorToken.selectedItem,
);

final MyColor menuColor = MyColor(
  vividModeValue: Colors.white54,
  lightModeValue: Colors.grey.shade50,
  darkModeValue: Colors.grey.shade800,
  token: ColorToken.menu,
);

final MyColor seekBarColor = MyColor(
  vividModeValue: Colors.black,
  lightModeValue: Colors.black,
  darkModeValue: Colors.grey.shade400,
  token: ColorToken.seekBar,
);

final MyColor volumeBarColor = MyColor(
  vividModeValue: Colors.black,
  lightModeValue: Colors.black,
  darkModeValue: Colors.grey.shade400,
  token: ColorToken.volumeBar,
);

final MyColor lyricsPageBackgroundColor = MyColor(
  vividModeValue: Colors.transparent,
  lightModeValue: Colors.grey.shade200,
  darkModeValue: Color.fromARGB(255, 50, 50, 50),
  pageType: 1,
  token: ColorToken.lyricsBackground,
);

final MyColor lyricsPageForegroundColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular;
  },
  lightModeValue: Colors.grey.shade900,
  darkModeValue: Colors.grey.shade300,
  pageType: 1,
  token: ColorToken.lyricsForeground,
);

final MyColor lyricsPageHighlightTextColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.accent;
  },
  lightModeValue: Colors.black,
  darkModeValue: Colors.grey.shade200,
  pageType: 1,
  token: ColorToken.lyricsHighlightText,
);

final MyColor lyricsPageButtonColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular.withAlpha(50);
  },
  lightModeValue: Colors.white70,
  darkModeValue: Colors.grey.shade700,
  pageType: 1,
  token: ColorToken.lyricsButton,
);

final MyColor lyricsPageDividerColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular;
  },
  lightModeValue: Colors.grey,
  darkModeValue: Colors.grey.shade700,
  pageType: 1,
  token: ColorToken.lyricsDivider,
);

final MyColor lyricsPageSelectedItemColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular.withAlpha(50);
  },
  lightModeValue: Colors.white,
  darkModeValue: Colors.grey.shade700,
  pageType: 1,
  token: ColorToken.lyricsSelectedItem,
);

final MyColor lyricsPageMenuColor = MyColor(
  vividModeValue: Colors.white10,
  lightModeValue: Colors.grey.shade50,
  darkModeValue: Colors.grey.shade800,
  pageType: 1,
  token: ColorToken.lyricsMenu,
);

final MyColor miniViewForegroundColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular;
  },
  pageType: 2,
  token: ColorToken.miniForeground,
);

final MyColor miniViewHighlightTextColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.accent;
  },

  pageType: 2,
  token: ColorToken.miniHighlightText,
);

final MyColor miniViewButtonColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular.withAlpha(50);
  },
  pageType: 2,
  token: ColorToken.miniButton,
);

final MyColor miniViewDividerColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular;
  },
  pageType: 2,
  token: ColorToken.miniDivider,
);

final MyColor miniViewSelectedItemColor = MyColor(
  getVividValue: () {
    return contrastColorTheme.regular.withAlpha(50);
  },

  pageType: 2,
  token: ColorToken.miniSelectedItem,
);

final MyColor miniViewMenuColor = MyColor(
  vividModeValue: Colors.white10,
  pageType: 2,
  token: ColorToken.miniMenu,
);

class ColorManager {
  late final List<MyColor> myMainPageColors;
  late final List<MyColor> myLyricsPageColors;
  late final List<MyColor> myMiniViewColors;

  ColorManager() {
    myMainPageColors = [
      pageBackgroundColor,
      iconColor,
      textColor,
      highlightTextColor,
      switchColor,
      glassColor,
      panelColor,
      sidebarColor,
      bottomColor,
      searchFieldColor,
      buttonColor,
      dividerColor,
      selectedItemColor,
      menuColor,
      seekBarColor,
      volumeBarColor,
    ];

    myLyricsPageColors = [
      lyricsPageBackgroundColor,
      lyricsPageForegroundColor,
      lyricsPageHighlightTextColor,
      lyricsPageDividerColor,
      lyricsPageButtonColor,
      lyricsPageSelectedItemColor,
      lyricsPageMenuColor,
    ];

    if (!isMobile) {
      myMiniViewColors = [
        miniViewForegroundColor,
        miniViewHighlightTextColor,
        miniViewDividerColor,
        miniViewButtonColor,
        miniViewSelectedItemColor,
        miniViewMenuColor,
      ];
    }
  }

  void updateMainPageColors() {
    for (final color in myMainPageColors) {
      color.updateColor();
    }
  }

  void updateLyricsPageColors() {
    for (final color in myLyricsPageColors) {
      color.updateColor();
    }
  }

  void updateMiniViewColors() {
    for (final color in myMiniViewColors) {
      color.updateColor();
    }
  }

  void updateBigPictureRelatedColors(MyPicture? picture) {
    backgroundPicture = picture;
    backgroundCoverArtColor = backgroundPicture?.color ?? Colors.grey;
    searchFieldColor.updateColor();
    buttonColor.updateColor();
    dividerColor.updateColor();
    selectedItemColor.updateColor();
  }

  void updateColors() {
    updateMainPageColors();
    updateLyricsPageColors();
    if (!isMobile) {
      updateMiniViewColors();
    }
  }

  Color? getSpecificMainPageCoverArtBaseColorForm(MyPicture? picture) {
    return mainPageThemeNotifier.value == .vivid
        ? picture == null
              ? Colors.grey
              : picture.color
        : isMobile
        ? pageBackgroundColor.value
        : panelColor.value;
  }

  Color? getSpecificMainPageSearchFieldColorForm(MyPicture? picture) {
    return mainPageThemeNotifier.value == .vivid
        ? picture == null
              ? Colors.grey.withAlpha(75)
              : picture.color?.withAlpha(75)
        : searchFieldColor.value;
  }

  Color getSpecificMainPageCoverArtBaseColor() {
    return mainPageThemeNotifier.value == .vivid
        ? backgroundCoverArtColor
        : isMobile
        ? pageBackgroundColor.value
        : panelColor.value;
  }

  Color getSpecificLyricsPageCoverArtBaseColor() {
    return lyricsPageThemeNotifier.value == .vivid
        ? currentCoverArtColor
        : lyricsPageBackgroundColor.value;
  }

  Color getSpecificBgBaseColor() {
    return viewModeNotifier.value == .mini || displayLyricsPage
        ? currentCoverArtColor
        : backgroundCoverArtColor;
  }

  Color getSpecificBgColor() {
    return viewModeNotifier.value == .mini
        ? Colors.transparent
        : displayLyricsPage
        ? lyricsPageBackgroundColor.value
        : isMobile
        ? pageBackgroundColor.value
        : panelColor.value;
  }

  Color getSpecificTextColor() {
    return viewModeNotifier.value == .mini
        ? miniViewForegroundColor.value
        : displayLyricsPage
        ? lyricsPageForegroundColor.value
        : textColor.value;
  }

  Color getSpecificHighlightTextColor() {
    return viewModeNotifier.value == .mini
        ? miniViewHighlightTextColor.value
        : displayLyricsPage
        ? lyricsPageHighlightTextColor.value
        : highlightTextColor.value;
  }

  Color getSpecificIconColor() {
    return viewModeNotifier.value == .mini
        ? miniViewForegroundColor.value
        : displayLyricsPage
        ? lyricsPageForegroundColor.value
        : iconColor.value;
  }

  Color getSpecificButtonColor() {
    return viewModeNotifier.value == .mini
        ? miniViewButtonColor.value
        : displayLyricsPage
        ? lyricsPageButtonColor.value
        : buttonColor.value;
  }

  Color getSpecificDividerColor() {
    return viewModeNotifier.value == .mini
        ? miniViewDividerColor.value
        : displayLyricsPage
        ? lyricsPageDividerColor.value
        : dividerColor.value;
  }

  Color getSpecificSelectedItemColor() {
    return viewModeNotifier.value == .mini
        ? miniViewSelectedItemColor.value
        : displayLyricsPage
        ? lyricsPageSelectedItemColor.value
        : selectedItemColor.value;
  }

  Color getSpecificMenuColor() {
    if (viewModeNotifier.value == .mini) {
      return miniViewMenuColor.value;
    }
    return displayLyricsPage ? lyricsPageMenuColor.value : menuColor.value;
  }
}

class MyColor {
  // fixed
  final Color? vividModeValue;
  // dynamic
  final Color Function()? getVividValue;
  final Color lightModeValue;
  final Color darkModeValue;

  // main: 0, lyrics: 1, mini mode: 2
  final int pageType;

  /// Semantic name used to look this colour up in the active flavour's palette.
  /// Null means "no flavour ever overrides me" — the upstream values below are
  /// then the only source.
  final ColorToken? token;

  ValueNotifier<Color> valueNotifier = ValueNotifier(Colors.transparent);

  MyColor({
    this.vividModeValue,
    this.getVividValue,
    this.lightModeValue = Colors.transparent,
    this.darkModeValue = Colors.transparent,
    this.pageType = 0,
    this.token,
  });

  void updateColor() {
    if (pageType == 2) {
      valueNotifier.value = vividModeValue ?? getVividValue!.call();
      return;
    }

    final themeType = pageType == 0
        ? mainPageThemeNotifier.value
        : lyricsPageThemeNotifier.value;
    switch (themeType) {
      case .vivid:
        // Vivid derives from artwork, so a flavour palette has nothing to say
        // about it. Left exactly as upstream wrote it.
        valueNotifier.value = vividModeValue ?? getVividValue!.call();
        break;
      case .light:
        valueNotifier.value =
            flavourColor(token, isDark: false) ?? lightModeValue;
        break;
      default:
        valueNotifier.value =
            flavourColor(token, isDark: true) ?? darkModeValue;
    }
  }

  Color get value => valueNotifier.value;
}
