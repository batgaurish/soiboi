import 'package:flutter/material.dart';
import 'package:sylvakru/base/data/library.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/l10n/generated/app_localizations.dart';

class BigHomePanel extends StatefulWidget {
  const BigHomePanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigHomePanelState();
}

class _BigHomePanelState extends State<BigHomePanel> {
  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 30),
      child: ListView(
        children: [
          Text(l10n.songs),
          SizedBox(
            height: 300,
            child: ListView.separated(
              scrollDirection: .horizontal,
              itemCount: 21,
              separatorBuilder: (context, index) {
                return const SizedBox(width: 12);
              },
              itemBuilder: (context, index) {
                bool focus = false;
                final song = library.songListManager.navidromeSongList[index];
                return StatefulBuilder(
                  builder: (context, setState) {
                    return Transform.scale(
                      scale: focus ? 1.0 : 0.9,
                      child: InkWell(
                        mouseCursor: SystemMouseCursors.click,
                        focusColor: Colors.transparent,
                        hoverColor: Colors.transparent,
                        onFocusChange: (value) {
                          setState(() {
                            focus = value;
                          });
                        },
                        onTap: () {},
                        child: Column(
                          children: [
                            CoverArtWidget(
                              size: 200,
                              borderRadius: 10,
                              song: song,
                            ),
                            SizedBox(
                              width: 200,
                              child: ListTile(
                                mouseCursor: SystemMouseCursors.click,
                                title: Text(
                                  getTitle(song),
                                  style: .new(overflow: .ellipsis),
                                ),
                                subtitle: Text(
                                  '${getArtist(song)} - ${getAlbum(song)}',
                                  style: .new(overflow: .ellipsis),
                                ),
                                visualDensity: .new(vertical: -4),
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
