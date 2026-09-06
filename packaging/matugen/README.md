# matugen integration

Soiboi can follow your wallpaper's colours by reading a matugen-generated
scheme, so it matches the rest of your desktop rather than inventing its own
palette.

## Setup

matugen's `--json` output already has exactly the shape Soiboi reads, so no
template is needed — just write it somewhere and point the app at it.

Add to `~/.config/matugen/config.toml`:

```toml
[config]
# run after every scheme generation
[config.custom_colors]

[templates.soiboi]
input_path = "~/.config/matugen/templates/soiboi-colors.json"
output_path = "~/.config/matugen/soiboi-colors.json"
```

with `~/.config/matugen/templates/soiboi-colors.json` containing:

```json
{
  "colors": {
    "surface":                  {"light": {"color": "{{colors.surface.light.default.hex}}"},                  "dark": {"color": "{{colors.surface.dark.default.hex}}"}},
    "surface_container_lowest": {"light": {"color": "{{colors.surface_container_lowest.light.default.hex}}"}, "dark": {"color": "{{colors.surface_container_lowest.dark.default.hex}}"}},
    "surface_container_low":    {"light": {"color": "{{colors.surface_container_low.light.default.hex}}"},    "dark": {"color": "{{colors.surface_container_low.dark.default.hex}}"}},
    "surface_container":        {"light": {"color": "{{colors.surface_container.light.default.hex}}"},        "dark": {"color": "{{colors.surface_container.dark.default.hex}}"}},
    "surface_container_high":   {"light": {"color": "{{colors.surface_container_high.light.default.hex}}"},   "dark": {"color": "{{colors.surface_container_high.dark.default.hex}}"}},
    "secondary_container":      {"light": {"color": "{{colors.secondary_container.light.default.hex}}"},      "dark": {"color": "{{colors.secondary_container.dark.default.hex}}"}},
    "outline_variant":          {"light": {"color": "{{colors.outline_variant.light.default.hex}}"},          "dark": {"color": "{{colors.outline_variant.dark.default.hex}}"}},
    "on_surface":               {"light": {"color": "{{colors.on_surface.light.default.hex}}"},               "dark": {"color": "{{colors.on_surface.dark.default.hex}}"}},
    "on_surface_variant":       {"light": {"color": "{{colors.on_surface_variant.light.default.hex}}"},       "dark": {"color": "{{colors.on_surface_variant.dark.default.hex}}"}},
    "primary":                  {"light": {"color": "{{colors.primary.light.default.hex}}"},                  "dark": {"color": "{{colors.primary.dark.default.hex}}"}}
  }
}
```

## Simpler alternative

If you'd rather not maintain a template, dump matugen's full output and point
Soiboi at that instead — the app reads the same `colors.<role>.<mode>.color`
structure:

```bash
matugen image ~/Pictures/wallpaper.png --json hex > ~/.config/matugen/soiboi-colors.json
```

Then in Soiboi: Settings -> Follow system colours -> confirm the path.

## Notes

Soiboi re-reads the file at startup and whenever you confirm the path in
Settings. Roles it doesn't find fall through to the active flavour, so a
partial file degrades rather than breaking.

Dynamic colour overrides the flavour's *palette* only. Corner radius, density
and motion still come from the flavour, so Console stays dense and Expressive
stays springy whatever colours the wallpaper produces.
