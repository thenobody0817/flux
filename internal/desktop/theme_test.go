package desktop

import (
	"os"
	"path/filepath"
	"testing"
)

func TestThemeGetReadsColorsAndName(t *testing.T) {
	dir := t.TempDir()
	colors := filepath.Join(dir, "colors.toml")
	if err := os.WriteFile(colors, []byte("mode = \"dark\"\nbackground = \"#121212\"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	name := filepath.Join(dir, "theme.name")
	if err := os.WriteFile(name, []byte("matte-black\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	th := &Theme{colorsPath: colors, namePath: name}
	slug, text, ok := th.Get()
	if !ok || slug != "matte-black" || text == "" {
		t.Fatalf("Get = %q %q %v", slug, text, ok)
	}
	if Mode(text) != "dark" {
		t.Fatalf("Mode = %q", Mode(text))
	}
}

func TestThemeGetMissingFile(t *testing.T) {
	th := &Theme{colorsPath: filepath.Join(t.TempDir(), "nope.toml")}
	if _, _, ok := th.Get(); ok {
		t.Fatal("Get returned ok for a missing file")
	}
}

func TestThemeModeDefaultsToDark(t *testing.T) {
	if got := Mode("background = \"#000000\"\n"); got != "dark" {
		t.Fatalf("Mode = %q, want dark", got)
	}
}
