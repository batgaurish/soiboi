"""Dumps the accessibility tree of the running Soiboi window, as Orca sees it."""
import sys
import pyatspi

def walk(node, depth, out):
    try:
        role = node.getRoleName()
        name = node.name or ''
        desc = node.description or ''
        states = node.getState()
        focusable = states.contains(pyatspi.STATE_FOCUSABLE)
        actions = []
        try:
            act = node.queryAction()
            actions = [act.getName(i) for i in range(act.nActions)]
        except Exception:
            pass
        value = ''
        try:
            v = node.queryValue()
            value = f' value={v.currentValue}'
        except Exception:
            pass
        out.append((depth, role, name, desc, focusable, actions, value))
        for i in range(node.childCount):
            walk(node.getChildAtIndex(i), depth + 1, out)
    except Exception as e:
        out.append((depth, 'ERROR', str(e), '', False, [], ''))

desktop = pyatspi.Registry.getDesktop(0)
apps = [desktop.getChildAtIndex(i) for i in range(desktop.childCount)]
target = [a for a in apps if a and 'soiboi' in (a.name or '').lower()]
if not target:
    print('apps:', [a.name for a in apps if a]); sys.exit(1)
out = []
walk(target[0], 0, out)
mode = sys.argv[1] if len(sys.argv) > 1 else 'tree'
if mode == 'tree':
    for depth, role, name, desc, focusable, actions, value in out:
        print('  ' * depth + f'[{role}] {name!r}' + (f' desc={desc!r}' if desc else '') + (' F' if focusable else '') + (f' {actions}' if actions else '') + value)
else:
    unlabeled = [(r, a) for d, r, n, de, f, a, v in out if r in ('push button', 'toggle button', 'check box', 'slider', 'link', 'menu item') and not n.strip()]
    buttons = [(r, n) for d, r, n, de, f, a, v in out if r in ('push button', 'toggle button', 'check box', 'slider', 'link', 'menu item')]
    print(f'controls={len(buttons)} unlabeled={len(unlabeled)}')
    for r, n in buttons: print(f'  {r}: {n!r}')
