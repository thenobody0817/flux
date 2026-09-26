package main

import (
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// TestCopyPluginLayout copies the plugin from the checkout and checks the
// layout that `omarchy plugin validate` accepts: real files, the shared
// views in Flux/, and no tools folder.
func TestCopyPluginLayout(t *testing.T) {
	dest := filepath.Join(t.TempDir(), "flux")
	if err := copyPlugin("../../gui/omarchy", "../../gui/qml", dest); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"manifest.json", "Panel.qml", "BarWidget.qml", "Service.qml", "Backend.qml", "Flux/qmldir", "Flux/FluxView.qml", "Flux/pages/qmldir", "Flux/components/qmldir"} {
		if _, err := os.Stat(filepath.Join(dest, want)); err != nil {
			t.Errorf("missing %s", want)
		}
	}
	_ = filepath.WalkDir(dest, func(path string, d fs.DirEntry, err error) error {
		rel, _ := filepath.Rel(dest, path)
		if d.Type()&fs.ModeSymlink != 0 {
			t.Errorf("symlink %s", rel)
		}
		if strings.Contains(rel, "tools") {
			t.Errorf("tools file %s", rel)
		}
		return nil
	})
	// The real validator, when this machine has Omarchy.
	if _, err := exec.LookPath("omarchy"); err == nil {
		if out, err := exec.Command("omarchy", "plugin", "validate", dest).CombinedOutput(); err != nil {
			t.Fatalf("omarchy plugin validate: %v\n%s", err, out)
		}
	}
}

// TestCopyPluginInstalledLayout copies the plugin the way an installed
// package lays it out: the plugin root already contains Flux/ and no
// separate views dir exists. The Flux/ tree must survive.
func TestCopyPluginInstalledLayout(t *testing.T) {
	src := filepath.Join(t.TempDir(), "omarchy-plugin")
	for _, f := range []string{"manifest.json", "Panel.qml", "Flux/FluxView.qml", "Flux/components/qmldir", "tools/helper"} {
		p := filepath.Join(src, f)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	dest := filepath.Join(t.TempDir(), "flux")
	if err := copyPlugin(src, "", dest); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"manifest.json", "Panel.qml", "Flux/FluxView.qml", "Flux/components/qmldir"} {
		if _, err := os.Stat(filepath.Join(dest, want)); err != nil {
			t.Errorf("missing %s", want)
		}
	}
	if _, err := os.Stat(filepath.Join(dest, "tools")); !os.IsNotExist(err) {
		t.Errorf("tools should be skipped, stat err = %v", err)
	}
}
