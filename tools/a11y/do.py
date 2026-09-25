"""Performs an accessibility action on the first node whose name starts with a prefix, as Orca would.

Usage: a11y_do.py NAME_PREFIX [ACTION]   (ACTION defaults to Tap)
"""
import sys
import pyatspi

prefix = sys.argv[1]
action = sys.argv[2] if len(sys.argv) > 2 else 'Tap'

def find(node):
    try:
        if (node.name or '').startswith(prefix):
            return node
        for i in range(node.childCount):
            hit = find(node.getChildAtIndex(i))
            if hit:
                return hit
    except Exception:
        pass
    return None

desktop = pyatspi.Registry.getDesktop(0)
app = [desktop.getChildAtIndex(i) for i in range(desktop.childCount)]
app = [a for a in app if a and 'soiboi' in (a.name or '').lower()][0]
node = find(app)
if not node:
    print('not found:', prefix); sys.exit(1)
act = node.queryAction()
names = [act.getName(i) for i in range(act.nActions)]
print('node:', node.getRoleName(), repr(node.name), names)
print('done:', act.doAction(names.index(action)))
