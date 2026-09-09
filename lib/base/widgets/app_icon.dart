/// [ImageIcon], minus the faint rectangle it draws around every icon.
///
/// The app's icons are PNGs of 256–512px drawn at 24–50 logical pixels, so
/// every one of them is minified by roughly 10–20x. Flutter's default
/// [FilterQuality.medium] takes the mipmap path for a reduction that large,
/// and generating those levels averages the glyph into the fully transparent
/// border around it. The outermost texel therefore stops being alpha 0, the
/// sampler clamps it along all four edges, and `BlendMode.srcIn` paints that
/// residue in the icon's own colour: a one-pixel hollow rectangle, about 8%
/// opacity, exactly on the icon's bounds.
///
/// It was reported as "the Console flavour introduced weird box artifacts",
/// but flavour has nothing to do with it — Console's near-square corners just
/// make a stray rectangle easier to notice. It affects every flavour, and
/// always did.
///
/// [FilterQuality.low] is plain bilinear sampling of the full-resolution
/// image, with no mipmaps to generate, so the transparent border stays
/// transparent. Verified on device by rendering the same row three ways: a
/// vector [Icon] and this widget draw no rectangle, [ImageIcon] does.
///
/// Everything else matches [ImageIcon] exactly — the size and colour still
/// fall back to the ambient [IconTheme], including its opacity — so this is a
/// drop-in replacement and callers read the same.
library;

import 'package:material_ui/material_ui.dart';

class AppIcon extends StatelessWidget {
  const AppIcon(this.image, {super.key, this.size, this.color, this.semanticLabel});

  final ImageProvider? image;
  final double? size;
  final Color? color;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final iconTheme = IconTheme.of(context);
    final iconSize = size ?? iconTheme.size;

    if (image == null) {
      return Semantics(
        label: semanticLabel,
        child: SizedBox(width: iconSize, height: iconSize),
      );
    }

    var iconColor = color ?? iconTheme.color ?? const Color(0xFFFFFFFF);
    final opacity = iconTheme.opacity;
    if (opacity != null && opacity != 1.0) {
      iconColor = iconColor.withValues(alpha: iconColor.a * opacity);
    }

    return Semantics(
      label: semanticLabel,
      child: Image(
        image: image!,
        width: iconSize,
        height: iconSize,
        color: iconColor,
        // The whole point of this widget: never the mipmap path.
        filterQuality: FilterQuality.low,
        fit: BoxFit.scaleDown,
        excludeFromSemantics: true,
      ),
    );
  }
}
