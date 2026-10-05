import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'generated/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('zh'),
  ];

  /// No description provided for @soiboi.
  ///
  /// In en, this message translates to:
  /// **'Soiboi'**
  String get soiboi;

  /// No description provided for @title.
  ///
  /// In en, this message translates to:
  /// **'Title'**
  String get title;

  /// No description provided for @artist.
  ///
  /// In en, this message translates to:
  /// **'Artist'**
  String get artist;

  /// No description provided for @album.
  ///
  /// In en, this message translates to:
  /// **'Album'**
  String get album;

  /// No description provided for @albumArtist.
  ///
  /// In en, this message translates to:
  /// **'Album Artist'**
  String get albumArtist;

  /// No description provided for @genre.
  ///
  /// In en, this message translates to:
  /// **'Genre'**
  String get genre;

  /// No description provided for @year.
  ///
  /// In en, this message translates to:
  /// **'Year'**
  String get year;

  /// No description provided for @track.
  ///
  /// In en, this message translates to:
  /// **'Track'**
  String get track;

  /// No description provided for @disc.
  ///
  /// In en, this message translates to:
  /// **'Disc'**
  String get disc;

  /// No description provided for @lyrics.
  ///
  /// In en, this message translates to:
  /// **'Lyrics'**
  String get lyrics;

  /// No description provided for @folder.
  ///
  /// In en, this message translates to:
  /// **'Folder'**
  String get folder;

  /// No description provided for @ranking.
  ///
  /// In en, this message translates to:
  /// **'Most Played'**
  String get ranking;

  /// No description provided for @recently.
  ///
  /// In en, this message translates to:
  /// **'Recently Played'**
  String get recently;

  /// No description provided for @artists.
  ///
  /// In en, this message translates to:
  /// **'Artists'**
  String get artists;

  /// No description provided for @albums.
  ///
  /// In en, this message translates to:
  /// **'Albums'**
  String get albums;

  /// No description provided for @folders.
  ///
  /// In en, this message translates to:
  /// **'Folders'**
  String get folders;

  /// No description provided for @songs.
  ///
  /// In en, this message translates to:
  /// **'Songs'**
  String get songs;

  /// No description provided for @downloads.
  ///
  /// In en, this message translates to:
  /// **'Downloads'**
  String get downloads;

  /// No description provided for @home.
  ///
  /// In en, this message translates to:
  /// **'Home'**
  String get home;

  /// No description provided for @playlists.
  ///
  /// In en, this message translates to:
  /// **'Playlists'**
  String get playlists;

  /// No description provided for @language.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get language;

  /// No description provided for @playQueue.
  ///
  /// In en, this message translates to:
  /// **'Play Queue'**
  String get playQueue;

  /// No description provided for @followSystem.
  ///
  /// In en, this message translates to:
  /// **'Follow System'**
  String get followSystem;

  /// No description provided for @settings.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settings;

  /// No description provided for @reload.
  ///
  /// In en, this message translates to:
  /// **'Reload'**
  String get reload;

  /// No description provided for @manageMusicFolder.
  ///
  /// In en, this message translates to:
  /// **'Manage Music Folders'**
  String get manageMusicFolder;

  /// No description provided for @about.
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get about;

  /// No description provided for @openSourceLicense.
  ///
  /// In en, this message translates to:
  /// **'Open Source License'**
  String get openSourceLicense;

  /// No description provided for @privacyPolicy.
  ///
  /// In en, this message translates to:
  /// **'Privacy Policy'**
  String get privacyPolicy;

  /// No description provided for @sleepTimer.
  ///
  /// In en, this message translates to:
  /// **'Sleep Timer'**
  String get sleepTimer;

  /// No description provided for @pauseAfterCurrentTrack.
  ///
  /// In en, this message translates to:
  /// **'Pause After Current Track'**
  String get pauseAfterCurrentTrack;

  /// No description provided for @vibration.
  ///
  /// In en, this message translates to:
  /// **'Vibration'**
  String get vibration;

  /// No description provided for @library.
  ///
  /// In en, this message translates to:
  /// **'Library'**
  String get library;

  /// No description provided for @select.
  ///
  /// In en, this message translates to:
  /// **'Select'**
  String get select;

  /// No description provided for @sortSongs.
  ///
  /// In en, this message translates to:
  /// **'Sort Songs'**
  String get sortSongs;

  /// No description provided for @delete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get delete;

  /// No description provided for @remove.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get remove;

  /// No description provided for @createPlaylist.
  ///
  /// In en, this message translates to:
  /// **'Create Playlist'**
  String get createPlaylist;

  /// No description provided for @order.
  ///
  /// In en, this message translates to:
  /// **'Order'**
  String get order;

  /// No description provided for @reorder.
  ///
  /// In en, this message translates to:
  /// **'Reorder'**
  String get reorder;

  /// No description provided for @songCount.
  ///
  /// In en, this message translates to:
  /// **'{count} songs'**
  String songCount(int count);

  /// No description provided for @artistCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in total'**
  String artistCount(int count);

  /// No description provided for @albumCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in total'**
  String albumCount(int count);

  /// No description provided for @playlistCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in total'**
  String playlistCount(int count);

  /// No description provided for @folderCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in total'**
  String folderCount(int count);

  /// No description provided for @settingCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in total'**
  String settingCount(int count);

  /// No description provided for @fontCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in total'**
  String fontCount(int count);

  /// No description provided for @searchSongs.
  ///
  /// In en, this message translates to:
  /// **'Search Songs'**
  String get searchSongs;

  /// No description provided for @searchArtists.
  ///
  /// In en, this message translates to:
  /// **'Search Artists'**
  String get searchArtists;

  /// No description provided for @searchAlbums.
  ///
  /// In en, this message translates to:
  /// **'Search Albums'**
  String get searchAlbums;

  /// No description provided for @searchPlaylists.
  ///
  /// In en, this message translates to:
  /// **'Search Playlists'**
  String get searchPlaylists;

  /// No description provided for @searchLicenses.
  ///
  /// In en, this message translates to:
  /// **'Search Licenses'**
  String get searchLicenses;

  /// No description provided for @ascending.
  ///
  /// In en, this message translates to:
  /// **'Ascending'**
  String get ascending;

  /// No description provided for @descending.
  ///
  /// In en, this message translates to:
  /// **'Descending'**
  String get descending;

  /// No description provided for @pictureSize.
  ///
  /// In en, this message translates to:
  /// **'Picture Size'**
  String get pictureSize;

  /// No description provided for @large.
  ///
  /// In en, this message translates to:
  /// **'Large'**
  String get large;

  /// No description provided for @small.
  ///
  /// In en, this message translates to:
  /// **'Small'**
  String get small;

  /// No description provided for @view.
  ///
  /// In en, this message translates to:
  /// **'View'**
  String get view;

  /// No description provided for @list.
  ///
  /// In en, this message translates to:
  /// **'List'**
  String get list;

  /// No description provided for @grid.
  ///
  /// In en, this message translates to:
  /// **'Grid'**
  String get grid;

  /// No description provided for @favorited.
  ///
  /// In en, this message translates to:
  /// **'Favorited'**
  String get favorited;

  /// No description provided for @favorites.
  ///
  /// In en, this message translates to:
  /// **'Favorites'**
  String get favorites;

  /// No description provided for @duration.
  ///
  /// In en, this message translates to:
  /// **'Duration'**
  String get duration;

  /// No description provided for @quality.
  ///
  /// In en, this message translates to:
  /// **'Quality'**
  String get quality;

  /// No description provided for @times.
  ///
  /// In en, this message translates to:
  /// **'Play Count'**
  String get times;

  /// No description provided for @loop.
  ///
  /// In en, this message translates to:
  /// **'Loop'**
  String get loop;

  /// No description provided for @shuffle.
  ///
  /// In en, this message translates to:
  /// **'Shuffle'**
  String get shuffle;

  /// No description provided for @repeat.
  ///
  /// In en, this message translates to:
  /// **'Repeat'**
  String get repeat;

  /// No description provided for @playAll.
  ///
  /// In en, this message translates to:
  /// **'Play All'**
  String get playAll;

  /// No description provided for @move2Top.
  ///
  /// In en, this message translates to:
  /// **'Move to Top'**
  String get move2Top;

  /// No description provided for @playNow.
  ///
  /// In en, this message translates to:
  /// **'Play Now'**
  String get playNow;

  /// No description provided for @playNext.
  ///
  /// In en, this message translates to:
  /// **'Play Next'**
  String get playNext;

  /// No description provided for @add2Queue.
  ///
  /// In en, this message translates to:
  /// **'Add to Queue'**
  String get add2Queue;

  /// No description provided for @editMetadata.
  ///
  /// In en, this message translates to:
  /// **'Edit Metadata'**
  String get editMetadata;

  /// No description provided for @add2Playlist.
  ///
  /// In en, this message translates to:
  /// **'Add to Playlist'**
  String get add2Playlist;

  /// No description provided for @added2Playlist.
  ///
  /// In en, this message translates to:
  /// **'Added to playlist'**
  String get added2Playlist;

  /// No description provided for @selectAll.
  ///
  /// In en, this message translates to:
  /// **'Select All'**
  String get selectAll;

  /// No description provided for @complete.
  ///
  /// In en, this message translates to:
  /// **'Complete'**
  String get complete;

  /// No description provided for @continueMsg.
  ///
  /// In en, this message translates to:
  /// **'Are you sure you want to continue?'**
  String get continueMsg;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @confirm.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get confirm;

  /// No description provided for @addFolder.
  ///
  /// In en, this message translates to:
  /// **'Add Folder'**
  String get addFolder;

  /// No description provided for @addRecursiveFolder.
  ///
  /// In en, this message translates to:
  /// **'Add Folder and All Subfolders'**
  String get addRecursiveFolder;

  /// No description provided for @replacePicture.
  ///
  /// In en, this message translates to:
  /// **'Replace Picture'**
  String get replacePicture;

  /// No description provided for @unknown.
  ///
  /// In en, this message translates to:
  /// **'Unknown'**
  String get unknown;

  /// No description provided for @updateMetadata.
  ///
  /// In en, this message translates to:
  /// **'Update Metadata'**
  String get updateMetadata;

  /// No description provided for @defaultText.
  ///
  /// In en, this message translates to:
  /// **'Default'**
  String get defaultText;

  /// No description provided for @titleAscending.
  ///
  /// In en, this message translates to:
  /// **'Title Ascending'**
  String get titleAscending;

  /// No description provided for @titleDescending.
  ///
  /// In en, this message translates to:
  /// **'Title Descending'**
  String get titleDescending;

  /// No description provided for @artistAscending.
  ///
  /// In en, this message translates to:
  /// **'Artist Ascending'**
  String get artistAscending;

  /// No description provided for @artistDescending.
  ///
  /// In en, this message translates to:
  /// **'Artist Descending'**
  String get artistDescending;

  /// No description provided for @albumAscending.
  ///
  /// In en, this message translates to:
  /// **'Album Ascending'**
  String get albumAscending;

  /// No description provided for @albumDescending.
  ///
  /// In en, this message translates to:
  /// **'Album Descending'**
  String get albumDescending;

  /// No description provided for @durationAscending.
  ///
  /// In en, this message translates to:
  /// **'Duration Ascending'**
  String get durationAscending;

  /// No description provided for @durationDescending.
  ///
  /// In en, this message translates to:
  /// **'Duration Descending'**
  String get durationDescending;

  /// No description provided for @selectSortingType.
  ///
  /// In en, this message translates to:
  /// **'Sort by'**
  String get selectSortingType;

  /// No description provided for @loadingFolder.
  ///
  /// In en, this message translates to:
  /// **'Loading Folder'**
  String get loadingFolder;

  /// No description provided for @loadedSongs.
  ///
  /// In en, this message translates to:
  /// **'Loaded Songs'**
  String get loadedSongs;

  /// No description provided for @loadingNavidrome.
  ///
  /// In en, this message translates to:
  /// **'Loading Navidrome'**
  String get loadingNavidrome;

  /// No description provided for @canNotUpdate.
  ///
  /// In en, this message translates to:
  /// **'Can\'t edit a track while it\'s playing'**
  String get canNotUpdate;

  /// No description provided for @updateSuccessfully.
  ///
  /// In en, this message translates to:
  /// **'Updated successfully'**
  String get updateSuccessfully;

  /// No description provided for @updateFailed.
  ///
  /// In en, this message translates to:
  /// **'Update failed'**
  String get updateFailed;

  /// No description provided for @clear.
  ///
  /// In en, this message translates to:
  /// **'Clear'**
  String get clear;

  /// No description provided for @reset.
  ///
  /// In en, this message translates to:
  /// **'Reset'**
  String get reset;

  /// No description provided for @desktopLyrics.
  ///
  /// In en, this message translates to:
  /// **'Desktop Lyrics'**
  String get desktopLyrics;

  /// No description provided for @horizontal.
  ///
  /// In en, this message translates to:
  /// **'Horizontal'**
  String get horizontal;

  /// No description provided for @vertical.
  ///
  /// In en, this message translates to:
  /// **'Vertical'**
  String get vertical;

  /// No description provided for @lock.
  ///
  /// In en, this message translates to:
  /// **'Lock'**
  String get lock;

  /// No description provided for @unlock.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get unlock;

  /// No description provided for @closeAction.
  ///
  /// In en, this message translates to:
  /// **'Close Action'**
  String get closeAction;

  /// No description provided for @exit.
  ///
  /// In en, this message translates to:
  /// **'Exit'**
  String get exit;

  /// No description provided for @hide.
  ///
  /// In en, this message translates to:
  /// **'Hide'**
  String get hide;

  /// No description provided for @checkUpdate.
  ///
  /// In en, this message translates to:
  /// **'Check for Updates'**
  String get checkUpdate;

  /// No description provided for @go2Download.
  ///
  /// In en, this message translates to:
  /// **'Open Downloads'**
  String get go2Download;

  /// No description provided for @alreadyLatest.
  ///
  /// In en, this message translates to:
  /// **'You\'re on the latest version'**
  String get alreadyLatest;

  /// No description provided for @theme.
  ///
  /// In en, this message translates to:
  /// **'Theme'**
  String get theme;

  /// No description provided for @mainPageTheme.
  ///
  /// In en, this message translates to:
  /// **'Main Page Theme'**
  String get mainPageTheme;

  /// No description provided for @lyricsPageTheme.
  ///
  /// In en, this message translates to:
  /// **'Lyrics Page Theme'**
  String get lyricsPageTheme;

  /// No description provided for @vividMode.
  ///
  /// In en, this message translates to:
  /// **'Vivid Mode'**
  String get vividMode;

  /// No description provided for @lightMode.
  ///
  /// In en, this message translates to:
  /// **'Light Mode'**
  String get lightMode;

  /// No description provided for @darkMode.
  ///
  /// In en, this message translates to:
  /// **'Dark Mode'**
  String get darkMode;

  /// No description provided for @customMode.
  ///
  /// In en, this message translates to:
  /// **'Custom Mode'**
  String get customMode;

  /// No description provided for @local.
  ///
  /// In en, this message translates to:
  /// **'Local'**
  String get local;

  /// No description provided for @switchSource.
  ///
  /// In en, this message translates to:
  /// **'Switch Source'**
  String get switchSource;

  /// No description provided for @manageServers.
  ///
  /// In en, this message translates to:
  /// **'Manage Servers'**
  String get manageServers;

  /// No description provided for @username.
  ///
  /// In en, this message translates to:
  /// **'Username'**
  String get username;

  /// No description provided for @password.
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get password;

  /// No description provided for @exportLog.
  ///
  /// In en, this message translates to:
  /// **'Export Log'**
  String get exportLog;

  /// No description provided for @showApp.
  ///
  /// In en, this message translates to:
  /// **'Show App'**
  String get showApp;

  /// No description provided for @skip2Previous.
  ///
  /// In en, this message translates to:
  /// **'Skip to Previous'**
  String get skip2Previous;

  /// No description provided for @skip2Next.
  ///
  /// In en, this message translates to:
  /// **'Skip to Next'**
  String get skip2Next;

  /// No description provided for @playOrPause.
  ///
  /// In en, this message translates to:
  /// **'Play/Pause'**
  String get playOrPause;

  /// No description provided for @unlockDeskLrc.
  ///
  /// In en, this message translates to:
  /// **'Unlock Desktop Lyrics'**
  String get unlockDeskLrc;

  /// No description provided for @autoPlayOnStartup.
  ///
  /// In en, this message translates to:
  /// **'Auto-Play on Startup'**
  String get autoPlayOnStartup;

  /// No description provided for @return2Previous.
  ///
  /// In en, this message translates to:
  /// **'Return to Previous'**
  String get return2Previous;

  /// No description provided for @addedFolders.
  ///
  /// In en, this message translates to:
  /// **'Added Folders'**
  String get addedFolders;

  /// No description provided for @recursiveScan.
  ///
  /// In en, this message translates to:
  /// **'Scan Subfolders'**
  String get recursiveScan;

  /// No description provided for @songInfo.
  ///
  /// In en, this message translates to:
  /// **'Song Info'**
  String get songInfo;

  /// No description provided for @format.
  ///
  /// In en, this message translates to:
  /// **'Format'**
  String get format;

  /// No description provided for @bitrate.
  ///
  /// In en, this message translates to:
  /// **'Bitrate'**
  String get bitrate;

  /// No description provided for @samplerate.
  ///
  /// In en, this message translates to:
  /// **'Sample Rate'**
  String get samplerate;

  /// No description provided for @filePath.
  ///
  /// In en, this message translates to:
  /// **'File Path'**
  String get filePath;

  /// No description provided for @path.
  ///
  /// In en, this message translates to:
  /// **'Path'**
  String get path;

  /// No description provided for @go2Artist.
  ///
  /// In en, this message translates to:
  /// **'Go to Artist'**
  String get go2Artist;

  /// No description provided for @go2Album.
  ///
  /// In en, this message translates to:
  /// **'Go to Album'**
  String get go2Album;

  /// No description provided for @equalizer.
  ///
  /// In en, this message translates to:
  /// **'Equalizer'**
  String get equalizer;

  /// No description provided for @more.
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get more;

  /// No description provided for @randomize.
  ///
  /// In en, this message translates to:
  /// **'Randomize'**
  String get randomize;

  /// No description provided for @normal.
  ///
  /// In en, this message translates to:
  /// **'Normal'**
  String get normal;

  /// No description provided for @randomizeTemp.
  ///
  /// In en, this message translates to:
  /// **'Shuffle once'**
  String get randomizeTemp;

  /// No description provided for @randomizePermanent.
  ///
  /// In en, this message translates to:
  /// **'Shuffle and save order'**
  String get randomizePermanent;

  /// No description provided for @modifiedTimeAscending.
  ///
  /// In en, this message translates to:
  /// **'Modified Time Ascending'**
  String get modifiedTimeAscending;

  /// No description provided for @modifiedTimeDescending.
  ///
  /// In en, this message translates to:
  /// **'Modified Time Descending'**
  String get modifiedTimeDescending;

  /// No description provided for @cannotBeUndone.
  ///
  /// In en, this message translates to:
  /// **'This can\'t be undone'**
  String get cannotBeUndone;

  /// No description provided for @clearCache.
  ///
  /// In en, this message translates to:
  /// **'Clear Cache'**
  String get clearCache;

  /// No description provided for @backupLibrary.
  ///
  /// In en, this message translates to:
  /// **'Back Up Library'**
  String get backupLibrary;

  /// No description provided for @restoreLibrary.
  ///
  /// In en, this message translates to:
  /// **'Restore Library'**
  String get restoreLibrary;

  /// No description provided for @backupSaved.
  ///
  /// In en, this message translates to:
  /// **'Backup saved to {path}'**
  String backupSaved(String path);

  /// No description provided for @backupFailed.
  ///
  /// In en, this message translates to:
  /// **'Backup failed'**
  String get backupFailed;

  /// No description provided for @restoreDone.
  ///
  /// In en, this message translates to:
  /// **'Restored. Re-add your music folders, then restart the app.'**
  String get restoreDone;

  /// No description provided for @tapAgain.
  ///
  /// In en, this message translates to:
  /// **'Tap Again to Exit'**
  String get tapAgain;

  /// No description provided for @close.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get close;

  /// No description provided for @fonts.
  ///
  /// In en, this message translates to:
  /// **'Fonts'**
  String get fonts;

  /// No description provided for @searchFonts.
  ///
  /// In en, this message translates to:
  /// **'Search Fonts'**
  String get searchFonts;

  /// No description provided for @setFontName.
  ///
  /// In en, this message translates to:
  /// **'Set Font Name'**
  String get setFontName;

  /// No description provided for @setFont.
  ///
  /// In en, this message translates to:
  /// **'Set Font'**
  String get setFont;

  /// No description provided for @restoreDefault.
  ///
  /// In en, this message translates to:
  /// **'Restore Default'**
  String get restoreDefault;

  /// No description provided for @addFont.
  ///
  /// In en, this message translates to:
  /// **'Add Font'**
  String get addFont;

  /// No description provided for @deleteFont.
  ///
  /// In en, this message translates to:
  /// **'Delete Font'**
  String get deleteFont;

  /// No description provided for @currentFont.
  ///
  /// In en, this message translates to:
  /// **'Current Font'**
  String get currentFont;

  /// No description provided for @refresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get refresh;

  /// No description provided for @syncLibrary.
  ///
  /// In en, this message translates to:
  /// **'Synchronize Library'**
  String get syncLibrary;

  /// No description provided for @syncingTryLater.
  ///
  /// In en, this message translates to:
  /// **'Library is syncing — try again in a moment'**
  String get syncingTryLater;

  /// No description provided for @all.
  ///
  /// In en, this message translates to:
  /// **'All'**
  String get all;

  /// No description provided for @folderExist.
  ///
  /// In en, this message translates to:
  /// **'This folder is already added'**
  String get folderExist;

  /// No description provided for @folderNotSupportedYet.
  ///
  /// In en, this message translates to:
  /// **'This folder type isn\'t supported yet'**
  String get folderNotSupportedYet;

  /// No description provided for @getPermissionFailed.
  ///
  /// In en, this message translates to:
  /// **'Permission denied'**
  String get getPermissionFailed;

  /// No description provided for @premiumFeatures.
  ///
  /// In en, this message translates to:
  /// **'Premium Features'**
  String get premiumFeatures;

  /// No description provided for @premiumDescription.
  ///
  /// In en, this message translates to:
  /// **'Enjoy the full experience and support ongoing development'**
  String get premiumDescription;

  /// No description provided for @unlockPremium.
  ///
  /// In en, this message translates to:
  /// **'Unlock Premium Features'**
  String get unlockPremium;

  /// No description provided for @restorePurchase.
  ///
  /// In en, this message translates to:
  /// **'Restore Purchases'**
  String get restorePurchase;

  /// No description provided for @whatPremiumContains.
  ///
  /// In en, this message translates to:
  /// **'What\'s Included'**
  String get whatPremiumContains;

  /// No description provided for @themeDescription.
  ///
  /// In en, this message translates to:
  /// **'Unlock Vivid Mode for the Main Page'**
  String get themeDescription;

  /// No description provided for @fontDescription.
  ///
  /// In en, this message translates to:
  /// **'Use custom fonts'**
  String get fontDescription;

  /// No description provided for @equalizerDescription.
  ///
  /// In en, this message translates to:
  /// **'Adjust audio levels across different frequencies'**
  String get equalizerDescription;

  /// No description provided for @futurePremium.
  ///
  /// In en, this message translates to:
  /// **'Future Premium Features'**
  String get futurePremium;

  /// No description provided for @futurePremiumDescription.
  ///
  /// In en, this message translates to:
  /// **'All future premium features will be unlocked automatically'**
  String get futurePremiumDescription;

  /// No description provided for @premiumRequiredMessage.
  ///
  /// In en, this message translates to:
  /// **'Unlock Premium to use this'**
  String get premiumRequiredMessage;

  /// No description provided for @premiumUnlockHint.
  ///
  /// In en, this message translates to:
  /// **'Go to Settings > Premium Features to unlock it'**
  String get premiumUnlockHint;

  /// No description provided for @alreadyPremium.
  ///
  /// In en, this message translates to:
  /// **'Premium features are already unlocked'**
  String get alreadyPremium;

  /// No description provided for @pendingPurchase.
  ///
  /// In en, this message translates to:
  /// **'Processing your purchase...'**
  String get pendingPurchase;

  /// No description provided for @purchaseNotFound.
  ///
  /// In en, this message translates to:
  /// **'No purchase records found'**
  String get purchaseNotFound;

  /// No description provided for @productNotAvailable.
  ///
  /// In en, this message translates to:
  /// **'Unable to load product information. Please check your internet connection and try again'**
  String get productNotAvailable;

  /// No description provided for @iapNotAvailable.
  ///
  /// In en, this message translates to:
  /// **'In-App Purchases are not available. Please try again later'**
  String get iapNotAvailable;

  /// No description provided for @connectingToAppStore.
  ///
  /// In en, this message translates to:
  /// **'Connecting to the App Store...'**
  String get connectingToAppStore;

  /// No description provided for @checkingPurchase.
  ///
  /// In en, this message translates to:
  /// **'Checking purchase history...'**
  String get checkingPurchase;

  /// No description provided for @noLyrics.
  ///
  /// In en, this message translates to:
  /// **'No lyrics found'**
  String get noLyrics;

  /// No description provided for @lyricsParseFailed.
  ///
  /// In en, this message translates to:
  /// **'Lyrics parsing failed'**
  String get lyricsParseFailed;

  /// No description provided for @switchMode.
  ///
  /// In en, this message translates to:
  /// **'Switch Mode'**
  String get switchMode;

  /// No description provided for @viewLog.
  ///
  /// In en, this message translates to:
  /// **'View Log'**
  String get viewLog;

  /// No description provided for @premiumTrialActive.
  ///
  /// In en, this message translates to:
  /// **'Premium Trial Active'**
  String get premiumTrialActive;

  /// No description provided for @trialRemainingStatus.
  ///
  /// In en, this message translates to:
  /// **'You have {count} min of Premium access remaining\nPurchase Premium to continue after your trial ends'**
  String trialRemainingStatus(int count);

  /// No description provided for @gotIt.
  ///
  /// In en, this message translates to:
  /// **'Got it'**
  String get gotIt;

  /// No description provided for @trialRemaining.
  ///
  /// In en, this message translates to:
  /// **'Trial Remaining'**
  String get trialRemaining;

  /// No description provided for @bigPictureMode.
  ///
  /// In en, this message translates to:
  /// **'Big Picture Mode'**
  String get bigPictureMode;

  /// No description provided for @bigPictureModeDescription.
  ///
  /// In en, this message translates to:
  /// **'Unlock Big Picture Mode'**
  String get bigPictureModeDescription;

  /// No description provided for @adjustLyrics.
  ///
  /// In en, this message translates to:
  /// **'Adjust Lyrics'**
  String get adjustLyrics;

  /// No description provided for @fontSize.
  ///
  /// In en, this message translates to:
  /// **'Font Size'**
  String get fontSize;

  /// No description provided for @fontWeight.
  ///
  /// In en, this message translates to:
  /// **'Font Weight'**
  String get fontWeight;

  /// No description provided for @offset.
  ///
  /// In en, this message translates to:
  /// **'Offset'**
  String get offset;

  /// No description provided for @getStart.
  ///
  /// In en, this message translates to:
  /// **'Get Started'**
  String get getStart;

  /// No description provided for @immersiveWideLayout.
  ///
  /// In en, this message translates to:
  /// **'Immersive for Wide Layout'**
  String get immersiveWideLayout;

  /// No description provided for @menuOnRight.
  ///
  /// In en, this message translates to:
  /// **'Open Menu from Right'**
  String get menuOnRight;

  /// No description provided for @save.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get save;

  /// No description provided for @chooseMusicSource.
  ///
  /// In en, this message translates to:
  /// **'Choose a Music Source'**
  String get chooseMusicSource;

  /// No description provided for @search.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get search;

  /// No description provided for @smartPlaylists.
  ///
  /// In en, this message translates to:
  /// **'Smart playlists'**
  String get smartPlaylists;

  /// No description provided for @newSmartPlaylist.
  ///
  /// In en, this message translates to:
  /// **'New smart playlist'**
  String get newSmartPlaylist;

  /// No description provided for @nothingHereYet.
  ///
  /// In en, this message translates to:
  /// **'Nothing here yet'**
  String get nothingHereYet;

  /// No description provided for @smartPlaylistExplainer.
  ///
  /// In en, this message translates to:
  /// **'A smart playlist stores a question rather than a list of songs, and answers it against your library every time you open it — so \"lossless tracks I have not played this year\" stays true as the library grows.'**
  String get smartPlaylistExplainer;

  /// No description provided for @editRules.
  ///
  /// In en, this message translates to:
  /// **'Edit rules'**
  String get editRules;

  /// No description provided for @trackCount.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 track} other{{count} tracks}}'**
  String trackCount(int count);

  /// No description provided for @rulesJoinAll.
  ///
  /// In en, this message translates to:
  /// **' and '**
  String get rulesJoinAll;

  /// No description provided for @rulesJoinAny.
  ///
  /// In en, this message translates to:
  /// **' or '**
  String get rulesJoinAny;

  /// No description provided for @startFromTemplate.
  ///
  /// In en, this message translates to:
  /// **'Start from a template'**
  String get startFromTemplate;

  /// No description provided for @startFromTemplateHint.
  ///
  /// In en, this message translates to:
  /// **'Pick a vibe and tweak it, or start from a blank ruleset.'**
  String get startFromTemplateHint;

  /// No description provided for @startFromScratch.
  ///
  /// In en, this message translates to:
  /// **'Start from scratch'**
  String get startFromScratch;

  /// No description provided for @name.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get name;

  /// No description provided for @match.
  ///
  /// In en, this message translates to:
  /// **'Match'**
  String get match;

  /// No description provided for @allRules.
  ///
  /// In en, this message translates to:
  /// **'All rules'**
  String get allRules;

  /// No description provided for @anyRule.
  ///
  /// In en, this message translates to:
  /// **'Any rule'**
  String get anyRule;

  /// No description provided for @noRulesYet.
  ///
  /// In en, this message translates to:
  /// **'No rules yet — this would select the whole library.'**
  String get noRulesYet;

  /// No description provided for @addRule.
  ///
  /// In en, this message translates to:
  /// **'Add rule'**
  String get addRule;

  /// No description provided for @limit.
  ///
  /// In en, this message translates to:
  /// **'Limit'**
  String get limit;

  /// No description provided for @tracksMatch.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 track matches} other{{count} tracks match}}'**
  String tracksMatch(int count);

  /// No description provided for @removeRule.
  ///
  /// In en, this message translates to:
  /// **'Remove rule'**
  String get removeRule;

  /// No description provided for @yes.
  ///
  /// In en, this message translates to:
  /// **'Yes'**
  String get yes;

  /// No description provided for @no.
  ///
  /// In en, this message translates to:
  /// **'No'**
  String get no;

  /// No description provided for @signedIn.
  ///
  /// In en, this message translates to:
  /// **'Signed in'**
  String get signedIn;

  /// No description provided for @notSignedInYet.
  ///
  /// In en, this message translates to:
  /// **'Not signed in yet — complete the login above.'**
  String get notSignedInYet;

  /// No description provided for @signInAppleMusic.
  ///
  /// In en, this message translates to:
  /// **'Sign in to Apple Music'**
  String get signInAppleMusic;

  /// No description provided for @checking.
  ///
  /// In en, this message translates to:
  /// **'Checking…'**
  String get checking;

  /// No description provided for @done.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get done;

  /// No description provided for @selectCookiesFile.
  ///
  /// In en, this message translates to:
  /// **'Select cookies.txt'**
  String get selectCookiesFile;

  /// No description provided for @importAppleCookies.
  ///
  /// In en, this message translates to:
  /// **'Import your Apple Music cookies'**
  String get importAppleCookies;

  /// No description provided for @importAppleCookiesHint.
  ///
  /// In en, this message translates to:
  /// **'Sign in to music.apple.com in your browser, export the cookies with a \"cookies.txt\" extension, then select the file here.\n\nOnly Apple cookies are kept — anything else in the export is discarded.'**
  String get importAppleCookiesHint;

  /// No description provided for @reading.
  ///
  /// In en, this message translates to:
  /// **'Reading…'**
  String get reading;

  /// No description provided for @chooseCookiesFile.
  ///
  /// In en, this message translates to:
  /// **'Choose cookies.txt'**
  String get chooseCookiesFile;

  /// No description provided for @searchHint.
  ///
  /// In en, this message translates to:
  /// **'Songs, albums, artists, playlists'**
  String get searchHint;

  /// No description provided for @clearSearch.
  ///
  /// In en, this message translates to:
  /// **'Clear search'**
  String get clearSearch;

  /// No description provided for @searchIntro.
  ///
  /// In en, this message translates to:
  /// **'Search your whole library at once — songs, albums, artists and playlists, wherever they live.'**
  String get searchIntro;

  /// No description provided for @nothingMatches.
  ///
  /// In en, this message translates to:
  /// **'Nothing in your library matches \"{query}\".'**
  String nothingMatches(String query);

  /// No description provided for @lookOnAppleMusic.
  ///
  /// In en, this message translates to:
  /// **'Look for it on Apple Music'**
  String get lookOnAppleMusic;

  /// No description provided for @playlist.
  ///
  /// In en, this message translates to:
  /// **'Playlist'**
  String get playlist;

  /// No description provided for @smartPlaylist.
  ///
  /// In en, this message translates to:
  /// **'Smart playlist'**
  String get smartPlaylist;

  /// No description provided for @mood.
  ///
  /// In en, this message translates to:
  /// **'Mood'**
  String get mood;

  /// No description provided for @updateRestarting.
  ///
  /// In en, this message translates to:
  /// **'Restarting into the new version…'**
  String get updateRestarting;

  /// No description provided for @updateFinishAndroid.
  ///
  /// In en, this message translates to:
  /// **'Finish the install in the Android prompt.'**
  String get updateFinishAndroid;

  /// No description provided for @updateYouHave.
  ///
  /// In en, this message translates to:
  /// **'{tag} · you have {version}'**
  String updateYouHave(String tag, String version);

  /// No description provided for @prereleaseSuffix.
  ///
  /// In en, this message translates to:
  /// **' · prerelease'**
  String get prereleaseSuffix;

  /// No description provided for @noReleaseNotes.
  ///
  /// In en, this message translates to:
  /// **'No release notes.'**
  String get noReleaseNotes;

  /// No description provided for @updateDownload.
  ///
  /// In en, this message translates to:
  /// **'Update download'**
  String get updateDownload;

  /// No description provided for @noDownloadForPlatform.
  ///
  /// In en, this message translates to:
  /// **'This release has no download for {platform}.'**
  String noDownloadForPlatform(String platform);

  /// No description provided for @openReleases.
  ///
  /// In en, this message translates to:
  /// **'Open releases'**
  String get openReleases;

  /// No description provided for @bytesOf.
  ///
  /// In en, this message translates to:
  /// **'{received} of {total}'**
  String bytesOf(String received, String total);

  /// No description provided for @startingDownload.
  ///
  /// In en, this message translates to:
  /// **'Starting download…'**
  String get startingDownload;

  /// No description provided for @later.
  ///
  /// In en, this message translates to:
  /// **'Later'**
  String get later;

  /// No description provided for @downloading.
  ///
  /// In en, this message translates to:
  /// **'Downloading…'**
  String get downloading;

  /// No description provided for @downloadAndInstall.
  ///
  /// In en, this message translates to:
  /// **'Download & install'**
  String get downloadAndInstall;

  /// No description provided for @deleteTracksConfirm.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Delete 1 track ({size})} other{Delete {count} tracks ({size})}}'**
  String deleteTracksConfirm(int count, String size);

  /// No description provided for @freedBytes.
  ///
  /// In en, this message translates to:
  /// **'Freed {size}'**
  String freedBytes(String size);

  /// No description provided for @storage.
  ///
  /// In en, this message translates to:
  /// **'Storage'**
  String get storage;

  /// No description provided for @nothingStored.
  ///
  /// In en, this message translates to:
  /// **'Nothing stored on this device.'**
  String get nothingStored;

  /// No description provided for @storedSummary.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 track} other{{count} tracks}} · {size} on this device'**
  String storedSummary(int count, String size);

  /// No description provided for @selectTracksToDelete.
  ///
  /// In en, this message translates to:
  /// **'Select tracks to delete'**
  String get selectTracksToDelete;

  /// No description provided for @selectedSize.
  ///
  /// In en, this message translates to:
  /// **'{count} selected · {size}'**
  String selectedSize(int count, String size);

  /// No description provided for @deleting.
  ///
  /// In en, this message translates to:
  /// **'Deleting…'**
  String get deleting;

  /// No description provided for @neverPlayed.
  ///
  /// In en, this message translates to:
  /// **'Never played'**
  String get neverPlayed;

  /// No description provided for @playCountLabel.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 play} other{{count} plays}}'**
  String playCountLabel(int count);

  /// No description provided for @lastPlayed.
  ///
  /// In en, this message translates to:
  /// **'Last played {when}'**
  String lastPlayed(String when);

  /// No description provided for @today.
  ///
  /// In en, this message translates to:
  /// **'today'**
  String get today;

  /// No description provided for @daysAgo.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 day ago} other{{count} days ago}}'**
  String daysAgo(int count);

  /// No description provided for @monthsAgo.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 month ago} other{{count} months ago}}'**
  String monthsAgo(int count);

  /// No description provided for @yearsAgo.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{a year ago} other{{count} years ago}}'**
  String yearsAgo(int count);

  /// No description provided for @queueEmpty.
  ///
  /// In en, this message translates to:
  /// **'Nothing in the queue. Anything you archive shows up here, and keeps going if you leave the screen.'**
  String get queueEmpty;

  /// No description provided for @downloadQueue.
  ///
  /// In en, this message translates to:
  /// **'Download queue'**
  String get downloadQueue;

  /// No description provided for @finishedCount.
  ///
  /// In en, this message translates to:
  /// **'{count} finished'**
  String finishedCount(int count);

  /// No description provided for @pausedCount.
  ///
  /// In en, this message translates to:
  /// **'{count} paused'**
  String pausedCount(int count);

  /// No description provided for @inQueueCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in the queue'**
  String inQueueCount(int count);

  /// No description provided for @resume.
  ///
  /// In en, this message translates to:
  /// **'Resume'**
  String get resume;

  /// No description provided for @pause.
  ///
  /// In en, this message translates to:
  /// **'Pause'**
  String get pause;

  /// No description provided for @stopAll.
  ///
  /// In en, this message translates to:
  /// **'Stop all'**
  String get stopAll;

  /// No description provided for @log.
  ///
  /// In en, this message translates to:
  /// **'Log'**
  String get log;

  /// No description provided for @retry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// No description provided for @stopThisDownload.
  ///
  /// In en, this message translates to:
  /// **'Stop this download'**
  String get stopThisDownload;

  /// No description provided for @removeFromQueue.
  ///
  /// In en, this message translates to:
  /// **'Remove from queue'**
  String get removeFromQueue;

  /// No description provided for @stateDone.
  ///
  /// In en, this message translates to:
  /// **'done'**
  String get stateDone;

  /// No description provided for @stateFailed.
  ///
  /// In en, this message translates to:
  /// **'failed'**
  String get stateFailed;

  /// No description provided for @stateAlreadyInLibrary.
  ///
  /// In en, this message translates to:
  /// **'already in your library'**
  String get stateAlreadyInLibrary;

  /// No description provided for @stateSkipped.
  ///
  /// In en, this message translates to:
  /// **'skipped'**
  String get stateSkipped;

  /// No description provided for @stateDownloading.
  ///
  /// In en, this message translates to:
  /// **'downloading'**
  String get stateDownloading;

  /// No description provided for @alreadyInLibrary.
  ///
  /// In en, this message translates to:
  /// **'Already in your library'**
  String get alreadyInLibrary;

  /// No description provided for @trackNumber.
  ///
  /// In en, this message translates to:
  /// **'Track {index}'**
  String trackNumber(int index);

  /// No description provided for @stateWaiting.
  ///
  /// In en, this message translates to:
  /// **'waiting'**
  String get stateWaiting;

  /// No description provided for @stateDownloaded.
  ///
  /// In en, this message translates to:
  /// **'downloaded'**
  String get stateDownloaded;

  /// No description provided for @stateFailedWith.
  ///
  /// In en, this message translates to:
  /// **'failed. {reason}'**
  String stateFailedWith(String reason);

  /// No description provided for @stateCancelled.
  ///
  /// In en, this message translates to:
  /// **'cancelled'**
  String get stateCancelled;

  /// No description provided for @doneCount.
  ///
  /// In en, this message translates to:
  /// **'{count} done'**
  String doneCount(int count);

  /// No description provided for @doneOfTotal.
  ///
  /// In en, this message translates to:
  /// **'{done} of {total} done'**
  String doneOfTotal(int done, int total);

  /// No description provided for @alreadyThereCount.
  ///
  /// In en, this message translates to:
  /// **'{count} already there'**
  String alreadyThereCount(int count);

  /// No description provided for @skippedCount.
  ///
  /// In en, this message translates to:
  /// **'{count} skipped'**
  String skippedCount(int count);

  /// No description provided for @failedCount.
  ///
  /// In en, this message translates to:
  /// **'{count} failed'**
  String failedCount(int count);

  /// No description provided for @cancelledCount.
  ///
  /// In en, this message translates to:
  /// **'{count} cancelled'**
  String cancelledCount(int count);

  /// No description provided for @nowLabel.
  ///
  /// In en, this message translates to:
  /// **'now {label}'**
  String nowLabel(String label);

  /// No description provided for @showTracksFor.
  ///
  /// In en, this message translates to:
  /// **'Show tracks: {name}'**
  String showTracksFor(String name);

  /// No description provided for @showTracks.
  ///
  /// In en, this message translates to:
  /// **'Show tracks'**
  String get showTracks;

  /// No description provided for @hideTracks.
  ///
  /// In en, this message translates to:
  /// **'Hide tracks'**
  String get hideTracks;

  /// No description provided for @logFor.
  ///
  /// In en, this message translates to:
  /// **'Log: {label}'**
  String logFor(String label);

  /// No description provided for @copy.
  ///
  /// In en, this message translates to:
  /// **'Copy'**
  String get copy;

  /// No description provided for @logCopied.
  ///
  /// In en, this message translates to:
  /// **'Log copied'**
  String get logCopied;

  /// No description provided for @notInAppleCatalog.
  ///
  /// In en, this message translates to:
  /// **'Not found in the Apple Music catalog'**
  String get notInAppleCatalog;

  /// No description provided for @couldNotLoadTrackList.
  ///
  /// In en, this message translates to:
  /// **'Could not load the track list'**
  String get couldNotLoadTrackList;

  /// No description provided for @lookingUpAlbum.
  ///
  /// In en, this message translates to:
  /// **'Looking up the album…'**
  String get lookingUpAlbum;

  /// No description provided for @selectedCount.
  ///
  /// In en, this message translates to:
  /// **'{count} selected'**
  String selectedCount(int count);

  /// No description provided for @longPressToPick.
  ///
  /// In en, this message translates to:
  /// **'Long press to pick tracks'**
  String get longPressToPick;

  /// No description provided for @playOwnedTracks.
  ///
  /// In en, this message translates to:
  /// **'Play the {count} tracks you have'**
  String playOwnedTracks(int count);

  /// No description provided for @archiving.
  ///
  /// In en, this message translates to:
  /// **'Archiving…'**
  String get archiving;

  /// No description provided for @archiveCount.
  ///
  /// In en, this message translates to:
  /// **'Archive {count}'**
  String archiveCount(int count);

  /// No description provided for @loadingTracks.
  ///
  /// In en, this message translates to:
  /// **'Loading tracks…'**
  String get loadingTracks;

  /// No description provided for @noTracksForAlbum.
  ///
  /// In en, this message translates to:
  /// **'No tracks listed for this album'**
  String get noTracksForAlbum;

  /// No description provided for @inYourLibrary.
  ///
  /// In en, this message translates to:
  /// **'In your library'**
  String get inYourLibrary;

  /// No description provided for @stop.
  ///
  /// In en, this message translates to:
  /// **'Stop'**
  String get stop;

  /// No description provided for @preview.
  ///
  /// In en, this message translates to:
  /// **'Preview'**
  String get preview;

  /// No description provided for @archiveThisTrack.
  ///
  /// In en, this message translates to:
  /// **'Archive this track'**
  String get archiveThisTrack;

  /// No description provided for @releasesInCatalog.
  ///
  /// In en, this message translates to:
  /// **'{count} releases in the catalog'**
  String releasesInCatalog(int count);

  /// No description provided for @lookingUpReleases.
  ///
  /// In en, this message translates to:
  /// **'Looking up releases…'**
  String get lookingUpReleases;

  /// No description provided for @noReleasesListed.
  ///
  /// In en, this message translates to:
  /// **'No releases listed'**
  String get noReleasesListed;

  /// No description provided for @ownedOfTotal.
  ///
  /// In en, this message translates to:
  /// **'{owned} of {total} in your library'**
  String ownedOfTotal(int owned, int total);

  /// No description provided for @archivingProgress.
  ///
  /// In en, this message translates to:
  /// **'Archiving {current} of {total}{detail}'**
  String archivingProgress(int current, int total, String detail);

  /// No description provided for @archiveProgress.
  ///
  /// In en, this message translates to:
  /// **'Archive progress'**
  String get archiveProgress;

  /// No description provided for @countOfTotal.
  ///
  /// In en, this message translates to:
  /// **'{done} of {total}'**
  String countOfTotal(int done, int total);

  /// No description provided for @downloadFolder.
  ///
  /// In en, this message translates to:
  /// **'Download folder'**
  String get downloadFolder;

  /// No description provided for @analyseLibrary.
  ///
  /// In en, this message translates to:
  /// **'Analyse Library'**
  String get analyseLibrary;

  /// No description provided for @analyseLibraryHint.
  ///
  /// In en, this message translates to:
  /// **'Needed for smart playlists and mood shelves'**
  String get analyseLibraryHint;

  /// No description provided for @alreadyAnalysing.
  ///
  /// In en, this message translates to:
  /// **'Already analysing'**
  String get alreadyAnalysing;

  /// No description provided for @starting.
  ///
  /// In en, this message translates to:
  /// **'Starting…'**
  String get starting;

  /// No description provided for @analyseFolderProgress.
  ///
  /// In en, this message translates to:
  /// **'{status} (folder {index}/{count})'**
  String analyseFolderProgress(String status, int index, int count);

  /// No description provided for @notAvailableOnDevice.
  ///
  /// In en, this message translates to:
  /// **'Not available on this device'**
  String get notAvailableOnDevice;

  /// No description provided for @failedWithError.
  ///
  /// In en, this message translates to:
  /// **'Failed: {error}'**
  String failedWithError(String error);

  /// No description provided for @analyseSummary.
  ///
  /// In en, this message translates to:
  /// **'{analysed} analysed · {skipped} already done'**
  String analyseSummary(int analysed, int skipped);

  /// No description provided for @analyseUnreadable.
  ///
  /// In en, this message translates to:
  /// **' · {count} unreadable'**
  String analyseUnreadable(int count);

  /// No description provided for @aiProviderAndKey.
  ///
  /// In en, this message translates to:
  /// **'AI provider and key'**
  String get aiProviderAndKey;

  /// No description provided for @aiNotSetUp.
  ///
  /// In en, this message translates to:
  /// **'Not set up. Free keys from Gemini or OpenRouter'**
  String get aiNotSetUp;

  /// No description provided for @downloadLogs.
  ///
  /// In en, this message translates to:
  /// **'Download logs'**
  String get downloadLogs;

  /// No description provided for @downloadLogsHint.
  ///
  /// In en, this message translates to:
  /// **'Tap to read the last week of logs. The switch adds a log button to each download in the queue.'**
  String get downloadLogsHint;

  /// No description provided for @motionFollowingSystemReduced.
  ///
  /// In en, this message translates to:
  /// **'Following the system (reduced)'**
  String get motionFollowingSystemReduced;

  /// No description provided for @motionFollowingSystemFull.
  ///
  /// In en, this message translates to:
  /// **'Following the system (full)'**
  String get motionFollowingSystemFull;

  /// No description provided for @motionReducedDescription.
  ///
  /// In en, this message translates to:
  /// **'On: no sliding, zooming or scrolling'**
  String get motionReducedDescription;

  /// No description provided for @motionFullDescription.
  ///
  /// In en, this message translates to:
  /// **'Off: all animations'**
  String get motionFullDescription;

  /// No description provided for @reduceMotion.
  ///
  /// In en, this message translates to:
  /// **'Reduce motion'**
  String get reduceMotion;

  /// No description provided for @followTheSystem.
  ///
  /// In en, this message translates to:
  /// **'Follow the system'**
  String get followTheSystem;

  /// No description provided for @fullMotion.
  ///
  /// In en, this message translates to:
  /// **'Full motion'**
  String get fullMotion;

  /// No description provided for @notifications.
  ///
  /// In en, this message translates to:
  /// **'Notifications'**
  String get notifications;

  /// No description provided for @notificationsHint.
  ///
  /// In en, this message translates to:
  /// **'Download progress and results, and app updates'**
  String get notificationsHint;

  /// No description provided for @sendATest.
  ///
  /// In en, this message translates to:
  /// **'Send a test'**
  String get sendATest;

  /// No description provided for @notificationsOff.
  ///
  /// In en, this message translates to:
  /// **'Notifications are turned off'**
  String get notificationsOff;

  /// No description provided for @notificationsNotAllowed.
  ///
  /// In en, this message translates to:
  /// **'Notifications are not allowed for Soiboi'**
  String get notificationsNotAllowed;

  /// No description provided for @testDownload.
  ///
  /// In en, this message translates to:
  /// **'Test download'**
  String get testDownload;

  /// No description provided for @stepOf.
  ///
  /// In en, this message translates to:
  /// **'Step {step} of {total}'**
  String stepOf(int step, int total);

  /// No description provided for @testFinished.
  ///
  /// In en, this message translates to:
  /// **'Test finished'**
  String get testFinished;

  /// No description provided for @testFinishedBody.
  ///
  /// In en, this message translates to:
  /// **'A progress notification was shown, updated and dismissed.'**
  String get testFinishedBody;

  /// No description provided for @flavour.
  ///
  /// In en, this message translates to:
  /// **'Flavour'**
  String get flavour;

  /// No description provided for @colourSource.
  ///
  /// In en, this message translates to:
  /// **'Colour source'**
  String get colourSource;

  /// No description provided for @appColoursDefault.
  ///
  /// In en, this message translates to:
  /// **'App colours (default)'**
  String get appColoursDefault;

  /// No description provided for @noColoursTapToSetUp.
  ///
  /// In en, this message translates to:
  /// **'No colours found — tap to set up'**
  String get noColoursTapToSetUp;

  /// No description provided for @matchedViaMatugen.
  ///
  /// In en, this message translates to:
  /// **'Matched via matugen'**
  String get matchedViaMatugen;

  /// No description provided for @prebuiltWithName.
  ///
  /// In en, this message translates to:
  /// **'Prebuilt · {name}'**
  String prebuiltWithName(String name);

  /// No description provided for @appColours.
  ///
  /// In en, this message translates to:
  /// **'App colours'**
  String get appColours;

  /// No description provided for @appColoursHint.
  ///
  /// In en, this message translates to:
  /// **'Default — no system or prebuilt colours'**
  String get appColoursHint;

  /// No description provided for @matugenHint.
  ///
  /// In en, this message translates to:
  /// **'Reads or generates a matugen scheme'**
  String get matugenHint;

  /// No description provided for @prebuiltPalette.
  ///
  /// In en, this message translates to:
  /// **'Prebuilt palette'**
  String get prebuiltPalette;

  /// No description provided for @prebuiltPaletteHint.
  ///
  /// In en, this message translates to:
  /// **'Dracula, Nord, Catppuccin, and more'**
  String get prebuiltPaletteHint;

  /// No description provided for @materialYouExplainer.
  ///
  /// In en, this message translates to:
  /// **'Uses the Material You palette Android builds from your wallpaper, so Soiboi matches the rest of your system. Needs Android 12 or newer.'**
  String get materialYouExplainer;

  /// No description provided for @matugenExplainer.
  ///
  /// In en, this message translates to:
  /// **'Matches the rest of your desktop. Reads an existing matugen scheme if you have one, otherwise generates one from your wallpaper.'**
  String get matugenExplainer;

  /// No description provided for @scheme.
  ///
  /// In en, this message translates to:
  /// **'Scheme'**
  String get scheme;

  /// No description provided for @usingSchemeFromWallpaper.
  ///
  /// In en, this message translates to:
  /// **'Using a {scheme} scheme from your wallpaper'**
  String usingSchemeFromWallpaper(String scheme);

  /// No description provided for @androidNoPalette.
  ///
  /// In en, this message translates to:
  /// **'Android did not provide a palette'**
  String get androidNoPalette;

  /// No description provided for @matugenCouldNotGenerate.
  ///
  /// In en, this message translates to:
  /// **'Could not generate — is matugen installed?'**
  String get matugenCouldNotGenerate;

  /// No description provided for @schemeFromWallpaper.
  ///
  /// In en, this message translates to:
  /// **'{scheme} from your wallpaper'**
  String schemeFromWallpaper(String scheme);

  /// No description provided for @matugenJsonOptional.
  ///
  /// In en, this message translates to:
  /// **'matugen JSON (optional)'**
  String get matugenJsonOptional;

  /// No description provided for @turnOff.
  ///
  /// In en, this message translates to:
  /// **'Turn off'**
  String get turnOff;

  /// No description provided for @androidNoPaletteLong.
  ///
  /// In en, this message translates to:
  /// **'Android did not provide a palette. Material You needs Android 12 or newer.'**
  String get androidNoPaletteLong;

  /// No description provided for @noColoursMatugenFailed.
  ///
  /// In en, this message translates to:
  /// **'No colours found, and matugen could not generate any from your wallpaper'**
  String get noColoursMatugenFailed;

  /// No description provided for @useTheseColours.
  ///
  /// In en, this message translates to:
  /// **'Use these colours'**
  String get useTheseColours;

  /// No description provided for @coloursFromAlbumArt.
  ///
  /// In en, this message translates to:
  /// **'Colours taken from the album art'**
  String get coloursFromAlbumArt;

  /// No description provided for @listenBrainzNotConnected.
  ///
  /// In en, this message translates to:
  /// **'Not connected — using local play counts'**
  String get listenBrainzNotConnected;

  /// No description provided for @listenBrainzExplainer.
  ///
  /// In en, this message translates to:
  /// **'Your username ranks Home by everything you listen to, not just this device. Read-only, no token needed.'**
  String get listenBrainzExplainer;

  /// No description provided for @appleMusicAccount.
  ///
  /// In en, this message translates to:
  /// **'Apple Music account'**
  String get appleMusicAccount;

  /// No description provided for @signedInViaWrapper.
  ///
  /// In en, this message translates to:
  /// **'Signed in through the lossless wrapper · cookies not needed'**
  String get signedInViaWrapper;

  /// No description provided for @signedInExpires.
  ///
  /// In en, this message translates to:
  /// **'Signed in · expires {date}'**
  String signedInExpires(String date);

  /// No description provided for @notSignedInNeeded.
  ///
  /// In en, this message translates to:
  /// **'Not signed in — needed to download'**
  String get notSignedInNeeded;

  /// No description provided for @signOut.
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get signOut;

  /// No description provided for @downloadQuality.
  ///
  /// In en, this message translates to:
  /// **'Download quality'**
  String get downloadQuality;

  /// No description provided for @setupWizard.
  ///
  /// In en, this message translates to:
  /// **'Setup wizard'**
  String get setupWizard;

  /// No description provided for @setupWizardHint.
  ///
  /// In en, this message translates to:
  /// **'Folders, Apple Music sign-in, downloads, ListenBrainz'**
  String get setupWizardHint;

  /// No description provided for @losslessAlac.
  ///
  /// In en, this message translates to:
  /// **'Lossless (ALAC)'**
  String get losslessAlac;

  /// No description provided for @ready.
  ///
  /// In en, this message translates to:
  /// **'Ready'**
  String get ready;

  /// No description provided for @needsSetup.
  ///
  /// In en, this message translates to:
  /// **'Needs setup'**
  String get needsSetup;

  /// No description provided for @needsSignIn.
  ///
  /// In en, this message translates to:
  /// **'Needs sign-in'**
  String get needsSignIn;

  /// No description provided for @notAvailableInBuild.
  ///
  /// In en, this message translates to:
  /// **'Not available in this build'**
  String get notAvailableInBuild;

  /// No description provided for @signedInStartsOnDemand.
  ///
  /// In en, this message translates to:
  /// **'Signed in · starts when a download needs it'**
  String get signedInStartsOnDemand;

  /// No description provided for @setUp.
  ///
  /// In en, this message translates to:
  /// **'Set up'**
  String get setUp;

  /// No description provided for @widevineAdvanced.
  ///
  /// In en, this message translates to:
  /// **'Widevine device (advanced)'**
  String get widevineAdvanced;

  /// No description provided for @wrapperEnabledSetUrl.
  ///
  /// In en, this message translates to:
  /// **'Wrapper enabled — set URL'**
  String get wrapperEnabledSetUrl;

  /// No description provided for @wrapperWithUrl.
  ///
  /// In en, this message translates to:
  /// **'Wrapper: {url}'**
  String wrapperWithUrl(String url);

  /// No description provided for @wvdFileSet.
  ///
  /// In en, this message translates to:
  /// **'WVD file set'**
  String get wvdFileSet;

  /// No description provided for @usingBuiltInDevice.
  ///
  /// In en, this message translates to:
  /// **'Using the built-in device'**
  String get usingBuiltInDevice;

  /// No description provided for @widevineConfiguration.
  ///
  /// In en, this message translates to:
  /// **'Widevine configuration'**
  String get widevineConfiguration;

  /// No description provided for @widevineExplainer.
  ///
  /// In en, this message translates to:
  /// **'AAC downloads use the Widevine device built into the downloader, so they need nothing here. ALAC is protected by FairPlay instead and needs a wrapper-v2 service: turn on the wrapper and enter its address. A .wvd file only replaces the AAC device.'**
  String get widevineExplainer;

  /// No description provided for @useWrapperService.
  ///
  /// In en, this message translates to:
  /// **'Use wrapper service'**
  String get useWrapperService;

  /// No description provided for @insteadOfWvd.
  ///
  /// In en, this message translates to:
  /// **'Instead of a local .wvd file'**
  String get insteadOfWvd;

  /// No description provided for @wrapperUrl.
  ///
  /// In en, this message translates to:
  /// **'Wrapper URL'**
  String get wrapperUrl;

  /// No description provided for @wvdFilePath.
  ///
  /// In en, this message translates to:
  /// **'WVD file path'**
  String get wvdFilePath;

  /// No description provided for @chooseWvdFile.
  ///
  /// In en, this message translates to:
  /// **'Choose a .wvd file'**
  String get chooseWvdFile;

  /// No description provided for @fetchLyricsLrclib.
  ///
  /// In en, this message translates to:
  /// **'Fetch lyrics from LRCLIB'**
  String get fetchLyricsLrclib;

  /// No description provided for @fetchLyricsLrclibHint.
  ///
  /// In en, this message translates to:
  /// **'Only for tracks with no local lyrics'**
  String get fetchLyricsLrclibHint;

  /// No description provided for @youHaveVersion.
  ///
  /// In en, this message translates to:
  /// **'You have {version}'**
  String youHaveVersion(String version);

  /// No description provided for @checkingForUpdates.
  ///
  /// In en, this message translates to:
  /// **'Checking for updates…'**
  String get checkingForUpdates;

  /// No description provided for @couldNotCheckUpdates.
  ///
  /// In en, this message translates to:
  /// **'Could not check for updates: {error}'**
  String couldNotCheckUpdates(String error);

  /// No description provided for @inProgressCount.
  ///
  /// In en, this message translates to:
  /// **'{count} in progress'**
  String inProgressCount(int count);

  /// No description provided for @failedTapToRetry.
  ///
  /// In en, this message translates to:
  /// **'{count} failed — tap to retry'**
  String failedTapToRetry(int count);

  /// No description provided for @nothingDownloading.
  ///
  /// In en, this message translates to:
  /// **'Nothing downloading'**
  String get nothingDownloading;

  /// No description provided for @storageHint.
  ///
  /// In en, this message translates to:
  /// **'Review the largest, least played and oldest tracks'**
  String get storageHint;

  /// No description provided for @removeDuplicates.
  ///
  /// In en, this message translates to:
  /// **'Remove duplicates'**
  String get removeDuplicates;

  /// No description provided for @removeDuplicatesHint.
  ///
  /// In en, this message translates to:
  /// **'Keep one copy of each song. You pick the codec when copies differ.'**
  String get removeDuplicatesHint;

  /// No description provided for @exportedTo.
  ///
  /// In en, this message translates to:
  /// **'Export to {path}'**
  String exportedTo(String path);

  /// No description provided for @downloadStatus.
  ///
  /// In en, this message translates to:
  /// **'Download status'**
  String get downloadStatus;

  /// No description provided for @unresolvedTracksNeedChoice.
  ///
  /// In en, this message translates to:
  /// **'Unresolved tracks, need your choice'**
  String get unresolvedTracksNeedChoice;

  /// No description provided for @archiveFromAppleMusic.
  ///
  /// In en, this message translates to:
  /// **'Archive from Apple Music'**
  String get archiveFromAppleMusic;

  /// No description provided for @archive.
  ///
  /// In en, this message translates to:
  /// **'Archive'**
  String get archive;

  /// No description provided for @archiveExplainer.
  ///
  /// In en, this message translates to:
  /// **'Songs, albums or playlists. Files are downloaded, tagged and added to your library on this device. Downloads are queued, so they keep going if you leave this screen.'**
  String get archiveExplainer;

  /// No description provided for @resumeDownloads.
  ///
  /// In en, this message translates to:
  /// **'Resume downloads'**
  String get resumeDownloads;

  /// No description provided for @pauseDownloads.
  ///
  /// In en, this message translates to:
  /// **'Pause downloads'**
  String get pauseDownloads;

  /// No description provided for @stopAllDownloads.
  ///
  /// In en, this message translates to:
  /// **'Stop all downloads'**
  String get stopAllDownloads;

  /// No description provided for @openQueue.
  ///
  /// In en, this message translates to:
  /// **'Open queue'**
  String get openQueue;

  /// No description provided for @downloadFailed.
  ///
  /// In en, this message translates to:
  /// **'Download failed'**
  String get downloadFailed;

  /// No description provided for @yourAppleMusicPlaylists.
  ///
  /// In en, this message translates to:
  /// **'Your Apple Music playlists'**
  String get yourAppleMusicPlaylists;

  /// No description provided for @applePlaylistsExplainer.
  ///
  /// In en, this message translates to:
  /// **'Archive any playlist from your library directly — no link to copy.'**
  String get applePlaylistsExplainer;

  /// No description provided for @showMyPlaylists.
  ///
  /// In en, this message translates to:
  /// **'Show my playlists'**
  String get showMyPlaylists;

  /// No description provided for @readingYourLibrary.
  ///
  /// In en, this message translates to:
  /// **'Reading your library…'**
  String get readingYourLibrary;

  /// No description provided for @signIn.
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get signIn;

  /// No description provided for @tryAgain.
  ///
  /// In en, this message translates to:
  /// **'Try again'**
  String get tryAgain;

  /// No description provided for @noPlaylistsInAccount.
  ///
  /// In en, this message translates to:
  /// **'No playlists in this account yet.'**
  String get noPlaylistsInAccount;

  /// No description provided for @saveAsLocalPlaylist.
  ///
  /// In en, this message translates to:
  /// **'Save as local playlist'**
  String get saveAsLocalPlaylist;

  /// No description provided for @queueingName.
  ///
  /// In en, this message translates to:
  /// **'Queueing {name}…'**
  String queueingName(String name);

  /// No description provided for @queuedSkipped.
  ///
  /// In en, this message translates to:
  /// **'Queued {queued} · skipped {skipped} not in the catalog'**
  String queuedSkipped(int queued, int skipped);

  /// No description provided for @queuedName.
  ///
  /// In en, this message translates to:
  /// **'Queued {name}'**
  String queuedName(String name);

  /// No description provided for @queuedTracks.
  ///
  /// In en, this message translates to:
  /// **'Queued {count} tracks'**
  String queuedTracks(int count);

  /// No description provided for @importAPlaylist.
  ///
  /// In en, this message translates to:
  /// **'Import a playlist'**
  String get importAPlaylist;

  /// No description provided for @importHint.
  ///
  /// In en, this message translates to:
  /// **'Paste a playlist link, or Artist - Title lines'**
  String get importHint;

  /// No description provided for @import.
  ///
  /// In en, this message translates to:
  /// **'Import'**
  String get import;

  /// No description provided for @importExplainer.
  ///
  /// In en, this message translates to:
  /// **'Paste a public {sources} link, or a tracklist with one Artist - Title per line for anything else. Its tracks are matched against the Apple Music catalog so you can archive the ones you want — nothing is downloaded from the other platform.'**
  String importExplainer(String sources);

  /// No description provided for @playlistWord.
  ///
  /// In en, this message translates to:
  /// **'playlist'**
  String get playlistWord;

  /// No description provided for @listOr.
  ///
  /// In en, this message translates to:
  /// **'{first} or {last}'**
  String listOr(String first, String last);

  /// No description provided for @notAPlaylistLink.
  ///
  /// In en, this message translates to:
  /// **'That is not a playlist link this app can read.'**
  String get notAPlaylistLink;

  /// No description provided for @couldNotReadPlaylist.
  ///
  /// In en, this message translates to:
  /// **'Could not read that playlist.'**
  String get couldNotReadPlaylist;

  /// No description provided for @weeklyDiscoveries.
  ///
  /// In en, this message translates to:
  /// **'Weekly discoveries'**
  String get weeklyDiscoveries;

  /// No description provided for @listenBrainzWeeklyExplainer.
  ///
  /// In en, this message translates to:
  /// **'ListenBrainz builds a weekly exploration and jams playlist from your listening. Add your username to browse and archive them here — no account link or token needed, the lists are public.'**
  String get listenBrainzWeeklyExplainer;

  /// No description provided for @listenBrainzUsername.
  ///
  /// In en, this message translates to:
  /// **'ListenBrainz username'**
  String get listenBrainzUsername;

  /// No description provided for @connect.
  ///
  /// In en, this message translates to:
  /// **'Connect'**
  String get connect;

  /// No description provided for @couldNotLoadPlaylist.
  ///
  /// In en, this message translates to:
  /// **'Could not load this playlist'**
  String get couldNotLoadPlaylist;

  /// No description provided for @archivingProgressSimple.
  ///
  /// In en, this message translates to:
  /// **'Archiving {current} of {total}…'**
  String archivingProgressSimple(int current, int total);

  /// No description provided for @matchingProgress.
  ///
  /// In en, this message translates to:
  /// **'Matching {current} of {total}…'**
  String matchingProgress(int current, int total);

  /// No description provided for @matchedOfTotal.
  ///
  /// In en, this message translates to:
  /// **'{matched} of {total} matched'**
  String matchedOfTotal(int matched, int total);

  /// No description provided for @matchingToCatalog.
  ///
  /// In en, this message translates to:
  /// **'Matching tracks to the Apple Music catalog…'**
  String get matchingToCatalog;

  /// No description provided for @savedPlaylistAll.
  ///
  /// In en, this message translates to:
  /// **'Saved \"{name}\" with all {total} songs'**
  String savedPlaylistAll(String name, int total);

  /// No description provided for @savedPlaylistPartial.
  ///
  /// In en, this message translates to:
  /// **'Saved \"{name}\": {matched} of {total} songs are in your library. The rest join it when downloaded.'**
  String savedPlaylistPartial(String name, int matched, int total);
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
