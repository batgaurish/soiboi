# Scope expansion plan

Branch: `scope-expansion`. Goal: make Soiboi accessible, easy to set up and
pleasant to live with, then widen where playlists can come from.

Every idea from the brainstorm is in here. They are ordered so that each phase
builds on the one before. Each phase is a tested milestone on this branch;
nothing reaches `main` until the final release is debugged.

## How we work through it

- **Plan work stays on `scope-expansion`.** No commit from this plan goes to
  `main` until the owner has debugged the final release and says to merge.
  `main` only gets bug fixes for the current release line, which are then
  merged into this branch.
- **Phases are milestones, not public releases.** Each phase ends with test
  builds from this branch (and, only if the owner asks, a GitHub pre-release
  from `scope-expansion`). The version numbers below are targets for when the
  work reaches `main`.
- **One item per commit.** Each commit is small enough to review and to undo.
- **Every item has a "done when".** It is not done until that holds on both
  Linux and Android.
- **Accessibility is a rule from Phase 1 on.** Every widget added in a later
  phase gets labels, focus order and contrast right the first time, instead of
  being fixed afterwards.
- **Tick the boxes here** as items land, so this file stays the status page.

Sizes: **S** is under a day, **M** is one to three days, **L** is more.

---

## Phase 0: Shared building blocks (v1.2.0, with Phase 1)

Pieces several later items need. Built once, first.

- [ ] **0.1 Notification service** (M). One Dart service with two backends:
  Android notification channels (download progress, download results,
  updates), and desktop notifications on Linux over D-Bus
  (`org.freedesktop.Notifications`). Needs Android 13's notification
  permission prompt.
  *Done when:* any feature can post, update and dismiss a notification with a
  single call on both platforms.
  *Status:* built (`notification_service.dart`, `NotificationBridge.kt`).
  Linux checked end to end with a real notification daemon (dunst): shown,
  updated in place, closed, result posted. Android type-checked, not yet run
  on a phone. Settings > Notifications > Send a test exercises it.
- [ ] **0.2 Error catalog** (S). One table that maps what the pipeline and
  wrapper report (error codes and known messages) to a plain explanation and a
  fix action, such as "sign in again", "skip" or "retry". The download logs
  from v1.1.7 are how new entries get found.
  *Done when:* the Downloads screen and notifications show the catalog's text
  instead of raw exception text for every known failure.
  *Status:* built (`error_catalog.dart`, 25 entries from gamdl 3.9's
  exceptions and the pipeline's codes). Queue rows, the failure banner, the
  playlist and album sheets and the Apple playlists card use it; the raw text
  and the matched entry go to the job's log. No download notification exists
  yet: 3.1 posts them through `describeFailure`.
- [ ] **0.3 Motion and contrast settings** (S). A `reduceMotion` flag that
  follows the system's "remove animations" setting with a manual override, and
  a helper that enforces minimum contrast, generalised from the sidebar's
  `_wordmarkColor`.
  *Done when:* both are available from `setting.dart` and `color_manager.dart`.
  *Status:* built. `motion.dart` has `reduceMotionNotifier` (system setting,
  or the saved "motion" override); `color_manager.dart` has `contrastRatio`,
  `ensureContrast` (moves lightness only) and `readableOr`, which the
  sidebar wordmark now uses. The Settings switch comes with 1.4.

## Phase 1: Accessibility (v1.2.0)

Today screen-reader labels exist in one file, there are about 11 keyboard
shortcuts, and every palette comes from album art with no contrast check.

- [ ] **1.1 Screen reader labels** (L). Go through every icon-only button and
  cover image: player controls, mini player, queue rows and their actions,
  playlist menus, sidebar, title bar and the Downloads cards. Add `tooltip` or
  `Semantics` labels, merge row semantics so a song reads as one item
  ("title, artist, 3:21"), and have progress bars report their value.
  *Done when:* TalkBack (Android) and Orca (Linux) can play a song, queue a
  download and open a playlist without sighted help.
  *Status:* built. Checked on the Linux build through AT-SPI (what Orca
  reads): the main, Songs, Albums and Downloads screens have no unnamed
  controls (18 of 18 were unnamed before), and activating a song row plays
  it. Flutter's Linux bridge ignores tooltips and values, so `labelIcon` and
  `nameWithValue` put them in the label there. Queueing a real download
  needs a signed-in Apple account, and TalkBack needs a phone: both not yet
  checked.
- [ ] **1.2 Text scaling** (M). Test at 200% system font on Android and at
  desktop zoom. Fix clipping in queue rows, the mini player, sidebar items and
  cards by letting fixed-height rows grow and removing hard `itemExtent`s
  where text lives.
  *Done when:* nothing overflows at 200% on a 360-dp-wide phone.
  *Status:* built. Checked with a debug build of the phone layout at 360 by
  780 and 200% text (Home, drawer, Songs, Downloads, Settings, player, queue,
  song menu) and the desktop layout at 200% (sidebar, Songs, Settings,
  player): no overflow reported. `scaledExtent` grows fixed rows by the part
  that holds text, so lists keep their fixed row height (and scroll-to-song)
  at every size; nothing changes at 100%. Not yet checked on a phone.
- [ ] **1.3 Readable palettes** (M). Apply the 0.3 contrast helper to every
  generated palette: body text at least 4.5:1, icons and large text at least
  3:1. Nudge the lightness instead of throwing the palette away.
  *Done when:* a test runs 50 real covers through the palette code and every
  pair passes.
- [ ] **1.4 Reduce motion** (S). Use 0.3 to turn off the animated
  backgrounds, hero transitions and lyric scrolling effects, and replace
  slides with plain fades.
  *Done when:* the toggle removes all non-essential motion.
- [ ] **1.5 Keyboard navigation and shortcuts** (M). Visible focus outlines
  and a sensible Tab order in Downloads and Settings. Shortcuts: Space
  (play/pause), ←/→ (seek 5 s), Shift+←/→ (previous/next), ↑/↓ (volume),
  Ctrl+F or `/` (search), Ctrl+L (lyrics), Ctrl+D (Downloads). `?` opens a
  list of all shortcuts. Build on `keyboard.dart`.
  *Done when:* the whole app can be used without a mouse.

## Phase 2: First run and setup (v1.3.0)

- [ ] **2.1 Setup wizard** (L). Shown on first launch, and later from
  Settings. Steps: pick music folders → sign in to Apple Music (lossless
  wrapper or cookies, chosen from a short comparison) → download quality and
  folder → optional ListenBrainz → done. Every step can be skipped. It reuses
  the existing screens (`lossless_setup.dart`, the Apple sign-in layer and
  folder selection) rather than copying them.
  *Done when:* a fresh install reaches its first finished download without
  opening Settings.
- [ ] **2.2 Status panel** (M). The "Before you can archive" card grows into
  one panel covering the Apple account (which sign-in, and when it expires),
  the wrapper, the download engine, free space in the download folder and
  network. Each line has one fix button. It is always reachable from
  Downloads, not only when something is missing.
  *Done when:* every "why can't I download" case the app can detect shows up
  there with its fix.
- [ ] **2.3 Plain-language errors** (M). Wire the 0.2 catalog into queue
  rows, the failure banner and toasts, and let the fix buttons act directly
  (sign in, retry, open the log).
  *Done when:* the ten most common failures in the logs have a catalog entry.

## Phase 3: Downloads (v1.4.0)

- [ ] **3.1 Notifications and background downloads** (L). On Android, run
  the download queue inside a foreground service (the special-use permission
  is already declared, but no service uses it yet), so downloads survive the
  screen turning off and the app being swiped away. Show one progress
  notification for the queue ("12 of 50, now: <title>") with Pause and Stop,
  then a summary when done ("<playlist>: 48 downloaded, 2 failed"). On Linux,
  post the summary through 0.1.
  *Done when:* a 50-song playlist finishes with the phone locked the whole
  time.
- [ ] **3.2 Retry all, and automatic retry** (S). A "Retry failed" button in
  the queue. Failures the 0.2 catalog marks as temporary (network, timeouts,
  Apple rate limits) retry by themselves three times with increasing waits;
  permanent ones (not in the catalog) do not.
  *Done when:* a Wi-Fi drop mid-playlist recovers without any taps.
- [ ] **3.3 Download conditions** (S, Android). Settings for Wi-Fi only and
  while charging only. The queue pauses with a clear reason ("Waiting for
  Wi-Fi") and resumes by itself.
  *Done when:* switching to mobile data pauses the queue within seconds.
- [ ] **3.4 Keep linked playlists in sync with their source** (M). For
  playlists created by import, remember where they came from (source and ID,
  added to `linked_playlists.json`). "Check for new songs" re-reads the source,
  downloads only new songs and updates the local playlist, per playlist or
  all at once. An optional daily check on Android.
  *Done when:* adding a song to the playlist in Apple Music and pressing sync
  downloads just that song.
- [ ] **3.5 Share to Soiboi** (M, Android). A share target for text and URLs.
  Apple Music links go straight to the queue; Spotify, Deezer, Tidal, YouTube,
  SoundCloud and JioSaavn links open the import sheet. On desktop, pasting a
  link anywhere does the same.
  *Done when:* sharing a playlist from the Apple Music app queues it.

## Phase 4: Library and playback (v1.5.0)

- [ ] **4.1 Missing lyrics and artwork fixer** (M). A library check that
  lists tracks without synced lyrics or embedded artwork and fills them in
  bulk from the existing sources (Apple, then LRCLIB), with a progress bar and
  a skip list.
  *Done when:* a run on the real library fixes everything the sources have.
- [ ] **4.2 Sleep timer** (S). 15/30/45/60 minutes, a custom time, "end of
  this song" and "end of this album or playlist", with a short fade-out.
  Reachable from the player and the media notification.
  *Done when:* playback stops as chosen and the timer survives a screen lock.
- [ ] **4.3 Resume position** (S). Remember where long tracks were stopped
  (anything over 10 minutes, or mixes and podcasts by tag) and resume from
  there, with a "start over" option.
  *Done when:* a 2-hour mix resumes where it stopped after an app restart.
- [ ] **4.4 Android widget, Linux media polish** (M). A home-screen widget
  with artwork, title, play/pause, next and previous. On Linux, MPRIS already
  exists through `audio_service_mpris`: check that artwork, seek and the
  shuffle and repeat state reach the desktop's media controls, and fix gaps.
  *Done when:* the widget controls playback, and Noctalia's media widget
  shows full, correct state.
- [ ] **4.5 Playlist folders** (M). Group playlists into folders on the
  Playlists tab (for example "Imported", "Moods"), collapsible, with drag and
  drop. Pinning (v1.1.6) still decides what appears in the sidebar.
  *Done when:* imports can go into an "Imported" folder automatically.

## Phase 5: Trust and safety (v1.6.0)

- [ ] **5.1 Automatic backups** (M). Scheduled backups (daily or weekly) of
  playlists, pins, covers, linked-playlist sources, smart playlists and
  settings to a folder you choose, keeping the last N. Built on the existing
  manual backup and restore, which stay.
  *Done when:* restoring the latest automatic backup on a fresh install
  brings back every playlist, with its songs and cover.
- [ ] **5.2 What's new** (S). After an update, show that release's notes once
  (from the GitHub release body, already fetched by `update_service.dart`).
  The update check can also run on launch, once a day.
  *Done when:* the first launch after an update shows its notes, and never
  again.

## Phase 6: Playlists from other services

From the earlier investigation. Spotify stays link-only: its API now requires
Premium, allows 5 users per app, and no longer lists playlists by username.

- [ ] **6.1 Deezer by profile link** (S). A user's public playlists with
  ISRCs, from the public API. The `arl` login cookie comes later, for private
  playlists.
- [ ] **6.2 SoundCloud by profile link** (S). Public playlists and likes.
- [ ] **6.3 Tidal login** (M). Device-code sign-in (approve at
  link.tidal.com), then playlists and favorites with ISRCs.
- [ ] **6.4 YouTube Music via cookies** (M). Library playlists and liked
  songs through the bundled yt-dlp.
- [ ] **6.5 JioSaavn** (research). Find out whether a signed-in session can
  list playlists at all.

Every source here reuses the same parts: the "your playlists" card, import,
linked playlists, cover import (Deezer, Tidal and SoundCloud all provide
playlist artwork) and, from 3.4, sync.

---

## Order and why

1. **Phase 0 and 1 first.** Accessibility is cheapest before more UI exists,
   and later phases then follow the rules as they go.
2. **Phase 2 next.** It depends on 0.2, and it is what a new user sees first.
3. **Phase 3.** The biggest everyday improvement on the phone, and 3.4 builds
   on the playlist sync that just shipped.
4. **Phase 4, then 5.** Independent of each other, and lower risk.
5. **Phase 6 can run alongside** any phase from 3 on, one source at a time,
   because it touches different code.
