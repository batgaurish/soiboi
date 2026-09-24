import 'package:material_ui/material_ui.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/keyboard.dart';
import 'package:soiboi/base/widgets/icon_label.dart';

class MySearchField extends StatefulWidget {
  final String hintText;

  final TextEditingController textController;

  final void Function()? onSearchTextChanged;

  final bool useCurrentSong;

  const MySearchField({
    super.key,
    required this.hintText,
    required this.textController,
    this.onSearchTextChanged,
    this.useCurrentSong = true,
  });

  @override
  State<StatefulWidget> createState() => _MySearchFieldState();
}

class _MySearchFieldState extends State<MySearchField> {
  final focusNode = FocusNode();
  final isSearchNotifier = ValueNotifier(false);

  @override
  void initState() {
    focusNode.addListener(() {
      isTyping = focusNode.hasFocus;
    });
    super.initState();
  }

  @override
  void dispose() {
    focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: isSearchNotifier,
      builder: (context, value, child) {
        if (!value) {
          return IconButton(
            tooltip: 'Search',
            onPressed: () {
              isSearchNotifier.value = true;
              WidgetsBinding.instance.addPostFrameCallback((_) {
                focusNode.requestFocus();
              });
            },
            icon: labelIcon('Search', const Icon(Icons.search)),
          );
        }
        return Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(50, 0, 0, 0),
            child: SizedBox(
              height: 30,
              child: ListenableBuilder(
                listenable: Listenable.merge([
                  widget.useCurrentSong ? currentSongNotifier : null,
                ]),
                builder: (context, _) {
                  return TextField(
                    focusNode: focusNode,
                    controller: widget.textController,
                    onTapOutside: (event) {
                      focusNode.unfocus();
                    },
                    decoration: InputDecoration(
                      hint: Text(
                        widget.hintText,
                        style: TextStyle(color: textColor.value),
                      ),
                      prefixIcon: Icon(Icons.search),
                      suffixIcon: IconButton(
                        tooltip: AppLocalizations.of(context).clear,
                        onPressed: () {
                          isSearchNotifier.value = false;
                          widget.textController.clear();
                          FocusScope.of(context).unfocus();
                          widget.onSearchTextChanged?.call();
                        },
                        icon: labelIcon(
                          AppLocalizations.of(context).clear,
                          const Icon(Icons.clear),
                        ),
                        padding: EdgeInsets.zero,
                      ),
                      filled: true,
                      fillColor: colorManager
                          .getSpecificMainPageSearchFieldColorForm(
                            widget.useCurrentSong
                                ? currentSongNotifier.value?.picture
                                : backgroundPicture,
                          ),
                      contentPadding: EdgeInsets.zero,
                      isDense: true,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(15),
                        borderSide: BorderSide.none,
                      ),
                    ),
                    onChanged: (value) {
                      widget.onSearchTextChanged?.call();
                    },
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }
}
