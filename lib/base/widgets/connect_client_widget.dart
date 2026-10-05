import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/config.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/emby_client.dart';
import 'package:soiboi/base/widgets/dialogs.dart';
import 'package:soiboi/base/services/center_toast.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/navidrome_client.dart';
import 'package:soiboi/base/services/stream_client.dart';
import 'package:soiboi/base/services/webdav_client.dart';
import 'package:soiboi/base/utils/source_type.dart';
import 'package:soiboi/base/widgets/custom_text_field.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';

class ConnectClientWidget extends StatefulWidget {
  final SourceType sourceType;

  const ConnectClientWidget({super.key, required this.sourceType});

  @override
  State<StatefulWidget> createState() => _ConnectClientWidgetState();
}

class _ConnectClientWidgetState extends State<ConnectClientWidget> {
  final baseUrlController = TextEditingController();
  final usernameController = TextEditingController();
  final passwordController = TextEditingController();

  @override
  void initState() {
    super.initState();
    if (widget.sourceType == .webdav) {
      baseUrlController.text = webdavClient?.baseUrl ?? '';
      usernameController.text = webdavClient?.username ?? '';
      passwordController.text = webdavClient?.password ?? '';
    } else if (widget.sourceType == .navidrome) {
      baseUrlController.text = config.navidromeBaseUrl ?? '';
      usernameController.text = config.navidromeUsername ?? '';
      passwordController.text = config.navidromePassword ?? '';
    } else {
      baseUrlController.text = config.embyBaseUrl ?? '';
      usernameController.text = config.embyUsername ?? '';
      passwordController.text = config.embyPassword ?? '';
    }
  }

  @override
  void dispose() {
    baseUrlController.dispose();
    usernameController.dispose();
    passwordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return SizedBox(
      width: 300,
      child: Padding(
        padding: .fromLTRB(20, 15, 20, 15),
        child: Column(
          mainAxisAlignment: .center,
          mainAxisSize: .min,
          children: [
            if (!firstLaunch)
              SizedBox(
                child: Text(
                  getSourceTypeDisplayName(l10n, widget.sourceType),
                  style: .new(fontWeight: .bold, fontSize: 18),
                ),
              ),

            SizedBox(height: 10),
            isTV
                ? fakeTextField('Url', baseUrlController)
                : CustomTextField('Url', baseUrlController, compact: false),

            SizedBox(height: 10),
            isTV
                ? fakeTextField(l10n.username, usernameController)
                : CustomTextField(
                    l10n.username,
                    usernameController,
                    compact: false,
                  ),

            SizedBox(height: 10),
            isTV
                ? fakeTextField(l10n.password, passwordController)
                : CustomTextField(
                    l10n.password,
                    passwordController,
                    needObscure: true,
                    compact: false,
                  ),

            SizedBox(height: isMobile ? 10 : 25),

            buttons(),
          ],
        ),
      ),
    );
  }

  Widget fakeTextField(String title, TextEditingController textController) {
    return Column(
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Text('$title:', style: TextStyle(fontWeight: FontWeight.bold)),
        ),
        InkWell(
          onTap: () async {
            textController.text = await getInputTextDialog(
              context,
              title,
              needConfirm: false,
            );
            setState(() {});
          },
          child: Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            alignment: Alignment.centerLeft,
            decoration: BoxDecoration(
              border: Border.all(color: textColor.value),
            ),
            child: Text(textController.text, overflow: TextOverflow.ellipsis),
          ),
        ),
      ],
    );
  }

  Widget buttons() {
    return ValueListenableBuilder(
      valueListenable: buttonColor.valueNotifier,
      builder: (context, value, child) {
        final l10n = AppLocalizations.of(context);

        return Row(
          children: [
            Spacer(),

            if (!firstLaunch)
              ElevatedButton(
                onPressed: () => onDelete(),
                style: ElevatedButton.styleFrom(
                  backgroundColor: firstLaunch ? null : value,
                ),
                child: Text(l10n.delete),
              ),

            if (!firstLaunch) SizedBox(width: 20),

            firstLaunch
                ? Card(
                    clipBehavior: .antiAlias,
                    child: InkWell(
                      mouseCursor: SystemMouseCursors.click,
                      onTap: onSave,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 24,
                          vertical: 8,
                        ),
                        child: Text(l10n.save),
                      ),
                    ),
                  )
                : ElevatedButton(
                    onPressed: () => onSave(),
                    style: ElevatedButton.styleFrom(backgroundColor: value),
                    child: Text(l10n.save),
                  ),
            Spacer(),
          ],
        );
      },
    );
  }

  void onDelete() async {
    if (!await showConfirmDialog(
      context,
      AppLocalizations.of(context).delete,
    )) {
      return;
    }
    if (widget.sourceType == .webdav) {
      await library.updateFolders([]);
      webdavClient = null;
    } else if (widget.sourceType == .navidrome) {
      config.navidromeBaseUrl = null;
      config.navidromeUsername = null;
      config.navidromePassword = null;
      if (sourceType == widget.sourceType) {
        streamClient = null;
      }
    } else {
      config.embyBaseUrl = null;
      config.embyUsername = null;
      config.embyPassword = null;
      if (sourceType == widget.sourceType) {
        streamClient = null;
      }
    }
    if (mounted) {
      Navigator.pop(context);
    }

    await config.save();
    if (widget.sourceType == sourceType) {
      await Loader.sync();
    }
  }

  void onSave() async {
    try {
      if (widget.sourceType == .webdav) {
        final tmp = webdavClient;
        webdavClient = WebDavClient(
          baseUrl: baseUrlController.text,
          username: usernameController.text,
          password: passwordController.text,
        );
        if (!await webdavClient!.ping()) {
          showCenterMessage('Can not connect to WebDAV');
          webdavClient = tmp;
          return;
        }
      } else if (widget.sourceType == .navidrome) {
        final tmp = streamClient;
        final navidromeClient = NavidromeClient(
          baseUrl: baseUrlController.text,
          username: usernameController.text,
          password: passwordController.text,
        );
        if (!await navidromeClient.ping()) {
          showCenterMessage('Can not connect to Navidrome');
          streamClient = tmp;
          return;
        }
        if (widget.sourceType == sourceType) {
          streamClient = navidromeClient;
        }
        config.navidromeBaseUrl = baseUrlController.text;
        config.navidromeUsername = usernameController.text;
        config.navidromePassword = passwordController.text;
      } else {
        final tmp = streamClient;
        final embyClient = EmbyClient(
          baseUrl: baseUrlController.text,
          username: usernameController.text,
          password: passwordController.text,
        );

        if (!await embyClient.ping()) {
          showCenterMessage('Can not connect to Emby');
          streamClient = tmp;
          return;
        }
        if (widget.sourceType == sourceType) {
          streamClient = embyClient;
        }
        config.embyBaseUrl = baseUrlController.text;
        config.embyUsername = usernameController.text;
        config.embyPassword = passwordController.text;
      }
    } catch (e) {
      if (context.mounted) {
        showCenterMessage(e.toString(), duration: 5000);
      }
      logger.output(e.toString());
      return;
    }

    if (!firstLaunch && mounted) {
      Navigator.pop(context);
    }
    showCenterMessage('Save successfully');
    await config.save();

    if (!firstLaunch &&
        widget.sourceType != .webdav &&
        widget.sourceType == sourceType) {
      await Loader.sync();
    }
  }
}
