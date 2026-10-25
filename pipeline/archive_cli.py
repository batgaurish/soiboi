#!/usr/bin/env python3
"""CLI entry point for ArchivePipeline child process adapter."""
import sys
import os

here = os.path.dirname(os.path.abspath(__file__))
if here not in sys.path:
    sys.path.insert(0, here)

from soiboi_pipeline import archive

def main() -> int:
    if len(sys.argv) < 3:
        sys.stderr.write("Usage: archive_cli.py <action> <job_id> [args...]\n")
        return 2
    action = sys.argv[1]
    job_id = sys.argv[2]
    args = sys.argv[3:]
    result = archive.handle_archive(action, job_id, *args)
    if result:
        sys.stdout.write(result + "\n")
        sys.stdout.flush()
    return 0

if __name__ == "__main__":
    sys.exit(main())
