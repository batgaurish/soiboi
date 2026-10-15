package app.soiboi.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.smart.SmartField
import app.soiboi.engine.smart.SmartFieldKind
import app.soiboi.engine.smart.SmartOperator
import app.soiboi.engine.smart.SmartPlaylist
import app.soiboi.engine.smart.SmartRule
import app.soiboi.engine.smart.SmartSort
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.SongMenu
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.Chip
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.components.Listbox
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.QualitySticker
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.formatTime
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/** What can be done to one song. One menu serves every list; with a playlist open it also offers removal from that playlist. */
@Composable
fun SongMenuDialog(app: AppController, menu: SongMenu) {
    val c = Soiboi.colors
    val engine = app.engine
    val song = menu.song
    val playlists by engine.playlists.playlists.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var details by remember { mutableStateOf(false) }
    val isFav = song.id in engine.playlists.favoriteIds
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close = { app.songMenu = null }

    @Composable fun Action(glyph: Glyph, label: String, tint: androidx.compose.ui.graphics.Color = c.ink, run: () -> Unit) {
        Row(Modifier.fillMaxWidth().press({ run() }, label, shape = Soiboi.look.shape(10)).padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = 20.dp, tint = tint); Txt(label, style = Soiboi.type.body, color = tint)
        }
    }

    SDialog(song.displayTitle, close) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Cover(song, 56.dp)
            Column(Modifier.weight(1f)) { Txt(song.displayArtist, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(song.displayAlbum, style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
            QualitySticker(QualityInfo.of(song), large = true)
        }
        Hairline()
        if (!picking && !details) {
            Action(Glyph.Play, "Play now") { app.play(listOf(song)); close() }
            Action(Glyph.Next, "Play next") { app.playNext(song); close() }
            Action(Glyph.Queue, "Add to queue") { app.addToQueue(song); close() }
            Action(if (isFav) Glyph.HeartFilled else Glyph.Heart, if (isFav) "Remove from favorites" else "Add to favorites", if (isFav) c.accent else c.ink) { app.toggleFavorite(song); close() }
            Action(Glyph.Playlist, "Add to playlist") { picking = true }
            Action(Glyph.Disc, "Go to album") { app.go(Route.Album(song.displayAlbum)); close() }
            Action(Glyph.Person, "Go to artist") { app.go(Route.Artist(song.artists.first())); close() }
            if (menu.inPlaylist != null) Action(Glyph.Close, "Remove from ${menu.inPlaylist}") { scope.launch { engine.playlists.remove(menu.inPlaylist, setOf(song.id)) }; close() }
            Action(Glyph.Info, "Song details") { details = true }
        } else if (picking) {
            Row(verticalAlignment = Alignment.CenterVertically) { SIconButton(Glyph.Back, "Back", { picking = false; creating = false }, size = 36.dp, iconSize = 18.dp); Txt("Add to playlist", style = Soiboi.type.title) }
            if (creating) {
                SField(newName, { newName = it }, "New playlist name", requestFocus = true, onSubmit = { if (newName.isNotBlank()) { app.newPlaylistWith(song, newName); close() } })
                SButton("Create playlist and add song", { app.newPlaylistWith(song, newName); close() }, Modifier.fillMaxWidth(), tone = Tone.Primary, enabled = newName.isNotBlank())
            } else {
                Action(Glyph.Plus, "New playlist") { creating = true }
                LazyColumn(Modifier.heightIn(max = 280.dp)) { items(playlists.filter { !it.isFavorite }) { p -> Action(Glyph.Playlist, p.name) { app.addToPlaylist(song, p.name); close() } } }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) { SIconButton(Glyph.Back, "Back", { details = false }, size = 36.dp, iconSize = 18.dp); Txt("Details", style = Soiboi.type.title) }
            val info = QualityInfo.of(song)
            listOf(
                "Format" to (song.format ?: "Unknown"), "Quality" to (info.label + (info.detail?.let { ", $it" } ?: "")), "Length" to (song.durationMs?.let(::formatTime) ?: "Unknown"),
                "Year" to (song.year?.toString() ?: "Unknown"), "Genre" to song.displayGenre, "Plays" to "${song.playCount}", "Source" to song.origin.name,
                "Location" to (song.path ?: song.id),
            ).forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Txt(k, Modifier.weight(0.35f), style = Soiboi.type.caption, color = c.muted); Txt(v, Modifier.weight(0.65f), style = Soiboi.type.caption, maxLines = 3) } }
        }
    }
}

/** Ask for a playlist in plain words. The model only sees title, artist and any moods it saved earlier, never the audio or paths. */
@Composable
fun AskAiDialog(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val config by engine.aiConfig.collectAsState()
    val progress by engine.ai.progress.collectAsState()
    var request by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close = { app.askAiOpen = false }
    SDialog("Ask for a playlist", close, actions = {
        SButton("Cancel", close, tone = Tone.Ghost)
        if (config != null) SButton("Make the playlist", {
            busy = true; error = null
            scope.launch {
                try { val made = engine.ai.makePlaylist(request); app.toast("Saved ${made.name}, ${made.songs.size} songs"); close(); app.go(Route.Playlist(made.name)) }
                catch (e: Exception) { error = e.message ?: "The request failed" }
                busy = false
            }
        }, tone = Tone.Primary, enabled = request.isNotBlank() && !busy, loading = busy)
    }) {
        if (config == null) {
            Banner("AI is not set up", "Add a key from Gemini, OpenRouter, Groq, Anthropic, OpenAI, or point it at Ollama on your own machine.", Glyph.Key, action = { SButton("Open settings", { close(); app.go(Route.Settings) }, compact = true) })
        } else {
            Txt("Describe it however you like. Mention a number of songs and it keeps to it.", style = Soiboi.type.caption, color = c.soft)
            SField(request, { request = it }, "What should it sound like?", hint = "Calm songs for a long train ride, nothing sad, 25 songs", minLines = 3, singleLine = false, requestFocus = true)
            progress?.let { Loading(it) }
            error?.let { Banner("It did not work", it, Glyph.Warning, tint = c.danger) }
            Txt("Sent to ${config!!.info.label}: song titles and artists from your library, plus moods the AI wrote earlier.", style = Soiboi.type.caption, color = c.muted)
        }
    }
}

/** The rule editor for a smart playlist. Every field, operator and sort is a real option; a half-typed rule simply matches nothing. */
@Composable
fun SmartEditorDialog(app: AppController, start: SmartPlaylist) {
    val c = Soiboi.colors
    val engine = app.engine
    var name by remember { mutableStateOf(start.name) }
    val rules = remember { mutableStateListOf<SmartRule>().apply { addAll(start.rules) } }
    var matchAll by remember { mutableStateOf(start.matchAll) }
    var sort by remember { mutableStateOf(start.sort) }
    var descending by remember { mutableStateOf(start.descending) }
    var limit by remember { mutableStateOf(start.limit?.toString() ?: "") }
    val songs by engine.library.songs.collectAsState()
    val preview = remember(rules.toList(), matchAll, sort, descending, limit, songs) { SmartPlaylist(name, rules.toList(), matchAll, sort, descending, limit.toIntOrNull()).let { engine.songsOf(it).size } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close = { app.smartEditor = null }
    SDialog(if (app.smartEditorOriginal == null) "New smart playlist" else "Edit rules", close, width = 620.dp, actions = {
        Txt("$preview songs match", Modifier.weight(1f), style = Soiboi.type.caption, color = c.soft)
        SButton("Cancel", close, tone = Tone.Ghost)
        SButton("Save smart playlist", {
            val p = SmartPlaylist(name.trim(), rules.toList(), matchAll, sort, descending, limit.toIntOrNull())
            scope.launch { engine.saveSmart(p, app.smartEditorOriginal); close(); app.go(Route.Smart(p.name)) }
        }, tone = Tone.Primary, enabled = name.isNotBlank())
    }) {
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            item { SField(name, { name = it }, "Name") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { Chip("Match all rules", matchAll, { matchAll = true }); Chip("Match any rule", !matchAll, { matchAll = false }) } }
            items(rules.size) { i ->
                val r = rules[i]
                Column(Modifier.fillMaxWidth().border(1.5.dp, c.rule, Soiboi.look.shape(12)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
                        Listbox("Field", SmartField.entries.map { it to it.label }, r.field, { f -> rules[i] = SmartRule(f, SmartOperator.forKind(f.kind).first(), if (f.kind == SmartFieldKind.Flag) "yes" else r.value) }, Modifier.weight(1f))
                        SIconButton(Glyph.Trash, "Remove this rule", { rules.removeAt(i) }, size = 40.dp, iconSize = 18.dp)
                    }
                    Listbox("Condition", SmartOperator.forKind(r.field.kind).map { it to it.label }, r.operator, { o -> rules[i] = r.copy(operator = o) })
                    if (r.field.kind == SmartFieldKind.Flag) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { Chip("Is a favorite", r.value != "no", { rules[i] = r.copy(value = "yes") }); Chip("Is not a favorite", r.value == "no", { rules[i] = r.copy(value = "no") }) }
                    else SField(r.value, { rules[i] = r.copy(value = it) }, "Value", hint = when (r.field.kind) { SmartFieldKind.Number -> "A number, such as 0.6"; SmartFieldKind.Date -> "Days, such as 90"; else -> "Text to look for" })
                }
            }
            item { SButton("Add a rule", { rules.add(SmartRule(SmartField.Genre, SmartOperator.Contains, "")) }, glyph = Glyph.Plus) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
                    Listbox("Order by", SmartSort.entries.map { it to it.label }, sort, { sort = it }, Modifier.weight(1f))
                    SField(limit, { limit = it.filter(Char::isDigit) }, "Limit", Modifier.weight(0.6f), hint = "No limit")
                }
            }
            item { SwitchRow("Highest first", descending, { descending = it }) }
        }
    }
}
