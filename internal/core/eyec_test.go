package core

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"flux/internal/config"
	"flux/internal/lan"
	"flux/internal/proto"
)

func eyecDaemon() (*Daemon, *Device) {
	d := &Daemon{cfg: &config.Config{}, devices: map[string]*Device{}, logger: log.New(io.Discard, "", 0)}
	dev := &Device{ID: "phone1", Name: "Pixel 8", Paired: true}
	dev.Incoming = []string{proto.TypeFluxEyec}
	d.devices[dev.ID] = dev
	return d, dev
}

func TestEyecPermitChecks(t *testing.T) {
	d, dev := eyecDaemon()
	raw := func(fields map[string]any) json.RawMessage {
		base := map[string]any{"device": "phone1", "title": "curl https://x", "pattern": "curl *", "service": "bash"}
		for k, v := range fields {
			base[k] = v
		}
		b, _ := json.Marshal(base)
		return b
	}
	if _, err := d.EyecPermit(raw(map[string]any{"device": "other"})); errCode(err) != "not_found" {
		t.Errorf("an unknown phone: %v", err)
	}
	if _, err := d.EyecPermit(raw(nil)); errCode(err) != "offline" {
		t.Errorf("an offline phone: %v", err)
	}
	dev.Paired = false
	if _, err := d.EyecPermit(raw(nil)); errCode(err) != "not_paired" {
		t.Errorf("a phone that is not paired: %v", err)
	}
	// A phone with a link that does not accept flux.eyec.
	dev.Paired, dev.link, dev.Incoming = true, &lan.Link{}, nil
	if _, err := d.EyecPermit(raw(nil)); errCode(err) != "unsupported" {
		t.Errorf("a phone without the plugin: %v", err)
	}
}

func TestEyecBook(t *testing.T) {
	var b eyecBook
	a := &eyecRequest{id: "e1", device: "phone1", deadline: time.Now().Add(time.Minute)}
	if err := b.add(a); err != nil {
		t.Fatal(err)
	}
	if err := b.add(&eyecRequest{id: "e2", device: "phone1", deadline: time.Now().Add(time.Minute)}); errCode(err) != "busy" {
		t.Fatalf("a second request for the same phone: %v", err)
	}

	// No answer yet: the wait returns pending after its slice.
	res, _, expired, err := b.wait(context.Background(), "e1", 20*time.Millisecond)
	if err != nil || expired || res.State != "pending" {
		t.Fatalf("pending: %+v %v %v", res, expired, err)
	}

	// Another phone and an unknown decision cannot answer.
	if b.deliver("phone2", "e1", "allow") {
		t.Fatal("another phone answered the request")
	}
	if b.deliver("phone1", "e1", "maybe") {
		t.Fatal("an unknown decision answered the request")
	}
	if !b.deliver("phone1", "e1", "yolo") {
		t.Fatal("the phone could not answer")
	}
	if b.deliver("phone1", "e1", "deny") {
		t.Fatal("the phone answered twice")
	}
	res, _, _, err = b.wait(context.Background(), "e1", time.Second)
	if err != nil || res.State != "yolo" {
		t.Fatalf("the answer: %+v %v", res, err)
	}
	if _, _, _, err := b.wait(context.Background(), "e1", time.Second); errCode(err) != "not_found" {
		t.Fatalf("a request after its answer: %v", err)
	}
}

func TestEyecExpires(t *testing.T) {
	var b eyecBook
	if err := b.add(&eyecRequest{id: "e1", device: "phone1", deadline: time.Now().Add(30 * time.Millisecond)}); err != nil {
		t.Fatal(err)
	}
	_, device, expired, err := b.wait(context.Background(), "e1", time.Second)
	if !expired || device != "phone1" || errCode(err) != "timeout" {
		t.Fatalf("expired %v device %q err %v", expired, device, err)
	}
	// The phone is free for a new request.
	if err := b.add(&eyecRequest{id: "e2", device: "phone1", deadline: time.Now().Add(time.Minute)}); err != nil {
		t.Fatal(err)
	}
}

func TestHandleEyec(t *testing.T) {
	cases := []struct {
		decision string
		state    string
	}{
		{"allow", "allow"},
		{"deny", "deny"},
		{"yolo", "yolo"},
		{"maybe", ""},
	}
	for i, c := range cases {
		d, dev := eyecDaemon()
		if err := d.eyec.add(&eyecRequest{id: "e1", device: dev.ID, deadline: time.Now().Add(time.Minute)}); err != nil {
			t.Fatal(err)
		}
		d.handleEyec(dev, proto.New(proto.TypeFluxEyec, map[string]any{"kind": "permit", "id": "e1", "decision": c.decision}))
		res, _, _, err := d.eyec.wait(context.Background(), "e1", 10*time.Millisecond)
		if err != nil {
			t.Fatalf("case %d: %v", i, err)
		}
		want := c.state
		if want == "" {
			want = "pending"
		}
		if res.State != want {
			t.Errorf("case %d: state %q, want %q", i, res.State, want)
		}
	}
}

// fakeEyec answers one ask with two text events and a done event.
func fakeEyec(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "eyec.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = bufio.NewReader(c).ReadString('\n')
				_, _ = c.Write([]byte(`{"event":"text","data":"Hel"}` + "\n"))
				_, _ = c.Write([]byte(`{"event":"text","data":"lo"}` + "\n"))
				_, _ = c.Write([]byte(`{"event":"done","answer":"Hello","choices":["a","b"]}` + "\n"))
			}(conn)
		}
	}()
	return path
}

func TestEyecAsk(t *testing.T) {
	t.Setenv("EYEC_SOCKET", fakeEyec(t))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	answer, choices, err := eyecAsk(ctx, "hello", "")
	if err != nil {
		t.Fatal(err)
	}
	if answer != "Hello" {
		t.Fatalf("answer %q, want %q", answer, "Hello")
	}
	if len(choices) != 2 || choices[0] != "a" || choices[1] != "b" {
		t.Fatalf("choices %v", choices)
	}
}

func TestEyecAskUnavailable(t *testing.T) {
	t.Setenv("EYEC_SOCKET", filepath.Join(t.TempDir(), "missing.sock"))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, _, err := eyecAsk(ctx, "hello", ""); errCode(err) != "eyec_unavailable" {
		t.Fatalf("a missing daemon: %v", err)
	}
}

func TestEyecActions(t *testing.T) {
	if _, ok := eyecActionByID("dock.toggle"); !ok {
		t.Fatal("dock.toggle is missing")
	}
	if _, ok := eyecActionByID("rm -rf /"); ok {
		t.Fatal("an arbitrary action was accepted")
	}
	if _, ok := eyecActionByID(""); ok {
		t.Fatal("an empty action was accepted")
	}
	ok, detail := runEyecAction(context.Background(), "nope")
	if ok || detail != "unknown action" {
		t.Fatalf("an unknown action ran: %v %q", ok, detail)
	}
}

// fakeEyecPeek answers one peek with a done event that names a JPEG file.
func fakeEyecPeek(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	img := filepath.Join(dir, "peek.jpg")
	if err := os.WriteFile(img, []byte("jpegdata"), 0o600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "eyec.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = bufio.NewReader(c).ReadString('\n')
				body, _ := json.Marshal(map[string]any{"event": "done", "answer": "A screen", "ocr": "txt", "image": img})
				_, _ = c.Write(append(body, '\n'))
			}(conn)
		}
	}()
	return path
}

func TestEyecPeek(t *testing.T) {
	t.Setenv("EYEC_SOCKET", fakeEyecPeek(t))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	answer, ocr, image, err := eyecPeek(ctx, "look")
	if err != nil {
		t.Fatal(err)
	}
	if answer != "A screen" || ocr != "txt" {
		t.Fatalf("answer %q ocr %q", answer, ocr)
	}
	if data, err := os.ReadFile(image); err != nil || string(data) != "jpegdata" {
		t.Fatalf("image %q: %v", image, err)
	}
}
