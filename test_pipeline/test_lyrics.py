"""Tests for turning Apple's syllable TTML into enhanced LRC."""
from soiboi_pipeline import lyrics

SYLLABLE_TTML = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
<body><div>
<p begin="12.34" end="14.0"><span begin="12.34" end="12.8">Hello</span> <span begin="12.80" end="13.5">world</span></p>
<p begin="1:02.500" end="1:04.0"><span begin="1:02.500" end="1:03">Syl</span><span begin="1:03.000" end="1:03.5">la</span><span begin="1:03.500" end="1:04">ble</span></p>
</div></body></tt>"""

LINE_TTML = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
<p begin="00:00:05.000" end="00:00:07.000">Just a   line</p>
</div></body></tt>"""


def test_word_timing_becomes_enhanced_lrc():
    lrc, word_timed = lyrics.ttml_to_enhanced_lrc(SYLLABLE_TTML)
    assert word_timed
    assert lrc.splitlines() == [
        "[00:12.34]<00:12.34>Hello <00:12.80>world",
        # Syllables of one word stay joined; only real gaps get a space.
        "[01:02.50]<01:02.50>Syl<01:03.00>la<01:03.50>ble",
    ]


def test_line_timing_only_is_reported_as_such():
    lrc, word_timed = lyrics.ttml_to_enhanced_lrc(LINE_TTML)
    assert not word_timed
    assert lrc == "[00:05.00]Just a line\n"


def test_only_new_m4a_files_without_lrc_are_candidates(tmp_path):
    track = tmp_path / "a.m4a"
    track.write_bytes(b"")
    assert lyrics._needs_lyrics(str(track))
    # gamdl's own line-timed .lrc does not count as done.
    (tmp_path / "a.lrc").write_text("[00:00.00]x")
    assert lyrics._needs_lyrics(str(track))
    (tmp_path / "a.lrc").write_text("[00:00.00]<00:00.00>x")
    assert not lyrics._needs_lyrics(str(track))
    assert not lyrics._needs_lyrics(str(tmp_path / "b.flac"))


def test_missing_session_is_a_quiet_no_op(tmp_path):
    assert lyrics.fetch_for_directory(str(tmp_path / "none.txt"), str(tmp_path), lambda e: None) == 0
