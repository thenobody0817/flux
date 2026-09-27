package core

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"flux/internal/config"
)

func TestDestDir(t *testing.T) {
	cfg := &config.Config{DownloadDir: "/tmp/dl", ScanDir: "/tmp/scan", PhotoDir: "/tmp/pics"}
	cases := map[fileDest]string{
		destDownload:   "/tmp/dl",
		destScan:       "/tmp/scan",
		destPhoto:      "/tmp/pics",
		destScreenshot: "/tmp/pics/screenshots",
		destSignature:  "/tmp/pics/signatures",
	}
	for kind, want := range cases {
		if got := destDir(cfg, kind); got != want {
			t.Errorf("kind %d: got %s, want %s", kind, got, want)
		}
	}
}

func TestCopyImage(t *testing.T) {
	dir := t.TempDir()
	clip := &memClipboard{}
	d := &Daemon{clip: clip}

	png := filepath.Join(dir, "signature.png")
	data := append([]byte("\x89PNG\r\n\x1a\n"), 1, 2, 3)
	if err := os.WriteFile(png, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := d.copyImage(png); err != nil {
		t.Fatal(err)
	}
	if clip.mime != "image/png" || string(clip.image) != string(data) {
		t.Errorf("clipboard has %q as %s", clip.image, clip.mime)
	}

	// A file that is not a PNG stays off the clipboard.
	other := filepath.Join(dir, "signature.txt")
	if err := os.WriteFile(other, []byte("not an image"), 0o644); err != nil {
		t.Fatal(err)
	}
	clip.image, clip.mime = nil, ""
	if err := d.copyImage(other); err == nil {
		t.Error("copied a file that is not a PNG")
	}
	if clip.image != nil {
		t.Errorf("clipboard has %q", clip.image)
	}
}

func TestWriteScan(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "flux", "scanned")
	now := time.Date(2026, 9, 25, 11, 15, 30, 0, time.Local)
	first, err := writeScan(dir, "Gate B14\nBoarding 15:40", now)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(first) != "scan-2026-09-25-111530.txt" {
		t.Errorf("name %s", filepath.Base(first))
	}
	b, _ := os.ReadFile(first)
	if string(b) != "Gate B14\nBoarding 15:40\n" {
		t.Errorf("content %q", b)
	}
	second, err := writeScan(dir, "more", now)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(second) != "scan-2026-09-25-111530 (2).txt" {
		t.Errorf("second scan in the same second: %s", filepath.Base(second))
	}
}
