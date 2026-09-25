"""Prints the deepest focused node of the running Soiboi window.

Walks like a11y_dump.py (name and role read on the way down), since querying
a node found by state alone after the walk can time out.
"""
import sys
import pyatspi

found = []

def walk(node, path):
    try:
        label = f'[{node.getRoleName()}] {node.name!r}'
        if node.getState().contains(pyatspi.STATE_FOCUSED):
            found.append(path + [label])
        for i in range(node.childCount):
            walk(node.getChildAtIndex(i), path + [label])
    except Exception as e:
        found.append(path + [f'ERROR {e}'])

desktop = pyatspi.Registry.getDesktop(0)
app = [desktop.getChildAtIndex(i) for i in range(desktop.childCount)]
app = [a for a in app if a and 'soiboi' in (a.name or '').lower()][0]
walk(app, [])
deepest = max(found, key=len) if found else ['(nothing focused)']
print(deepest[-1])
