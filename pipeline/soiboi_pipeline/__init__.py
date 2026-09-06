"""Soiboi's on-device archival pipeline.

This package replaces the Flask service the app used to talk to over HTTP. The
same code now runs inside the app on both desktop and Android, so there is no
server, no Docker and no LAN dependency.

Two things shaped the design:

  * **Android cannot spawn binaries.** Everything is invoked in-process --
    gamdl's CLI is a click command, so it is called directly rather than as a
    subprocess. The desktop build could shell out, but using one code path on
    both platforms means a bug found on desktop is a bug fixed on Android.

  * **Progress must stream.** A download takes minutes, so callers get
    newline-delimited JSON events rather than one result at the end.

The Dart side speaks to this through a single `PipelineRunner` abstraction with
two transports: a subprocess on desktop, Chaquopy on Android. Both run this
identical module.
"""

__version__ = "0.1.0"
