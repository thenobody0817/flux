package core

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"flux/internal/config"
	"flux/internal/openchamber"
	"flux/internal/proto"
)

func TestOpenChamberStatus(t *testing.T) {
	st := openchamber.Status{
		Sessions: map[string]openchamber.SessionStatus{
			"busy":  {Status: "busy"},
			"retry": {Status: "retry"},
			"idle":  {Status: "idle"},
		},
		Pending: map[string]openchamber.Pending{
			"form":  {Forms: []openchamber.Form{{ID: "f"}}},
			"perm":  {Permissions: []openchamber.Permission{{ID: "p"}}},
			"mixed": {Forms: []openchamber.Form{{ID: "f"}}, Permissions: []openchamber.Permission{{ID: "p"}}},
		},
	}
	for id, want := range map[string][2]string{
		"busy":  {"working", ""},
		"retry": {"working", ""},
		"idle":  {"idle", ""},
		"form":  {"blocked", "form"},
		"perm":  {"blocked", "permission"},
		"mixed": {"blocked", "form"},
		"gone":  {"idle", ""},
	} {
		got, waiting := openchamberStatus(id, st)
		if got != want[0] || waiting != want[1] {
			t.Errorf("%s: %s %q, want %s %q", id, got, waiting, want[0], want[1])
		}
	}
}

func TestOpenChamberAgents(t *testing.T) {
	sessions := []openchamber.Session{
		{ID: "a", Title: "Idle one", Agent: "build", Model: openchamber.Model{ID: "m1"}},
		{ID: "b", Title: "Working one", Agent: "plan"},
		{ID: "c", Title: "Blocked one", Agent: "build", ProjectID: "p"},
		{ID: "", Title: "no id"},
	}
	sessions[0].Time.Updated = 10
	sessions[1].Time.Updated = 30
	sessions[2].Time.Updated = 20
	status := openchamber.Status{
		Sessions: map[string]openchamber.SessionStatus{"b": {Status: "busy"}},
		Pending:  map[string]openchamber.Pending{"c": {Forms: []openchamber.Form{{ID: "f"}}}},
	}
	projects := map[string]string{"p": "/home/u/Code/flux"}
	got := openchamberAgents(sessions, status, projects)
	if len(got) != 3 {
		t.Fatalf("agents %+v", got)
	}
	if got[0].ID != "c" || got[0].Status != "blocked" || got[0].Project != "flux" || got[0].Waiting != "form" {
		t.Errorf("blocked first: %+v", got[0])
	}
	if got[1].ID != "b" || got[1].Status != "working" {
		t.Errorf("working second: %+v", got[1])
	}
	if got[2].ID != "a" || got[2].Status != "idle" || got[2].Model != "m1" {
		t.Errorf("idle last: %+v", got[2])
	}
}

func TestOpenChamberDirs(t *testing.T) {
	home, _ := os.UserHomeDir()
	if home == "" {
		t.Skip("no home folder")
	}
	got := openchamberDirs([]string{
		"/", home, home + "/Code/app", home + "/Code/app",
		home + "/.local/share/Trash/files/old",
	})
	want := []string{"~", "~/Code/app"}
	if len(got) != len(want) {
		t.Fatalf("dirs %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("dir %d = %q, want %q", i, got[i], want[i])
		}
	}
	if openchamberDirs(nil) != nil {
		t.Error("no dirs must give nil")
	}
}

func TestOpenChamberMessagesCount(t *testing.T) {
	for in, want := range map[int]int{-1: openchamberDefaultMessages, 0: openchamberDefaultMessages, 5: 5, 5000: openchamberMaxMessages} {
		if got := openchamberMessages(in); got != want {
			t.Errorf("openchamberMessages(%d) = %d, want %d", in, got, want)
		}
	}
}

func sampleMessages() []openchamber.Message {
	user := openchamber.Message{Type: "user", Text: "fix the build"}
	a := openchamber.Message{Type: "assistant", Agent: "build", Model: openchamber.Model{ID: "m1"}}
	text := openchamber.Content{Type: "text", Text: "On it."}
	tool := openchamber.Content{Type: "tool", Name: "shell"}
	tool.State.Status = "completed"
	tool.State.Input = json.RawMessage(`{"command":"go build ./..."}`)
	tool.State.Content = []openchamber.Content{{Type: "text", Text: "ok"}}
	a.Content = []openchamber.Content{text, tool}
	return []openchamber.Message{a, user} // newest first, as the API returns them
}

func TestOpenChamberPlain(t *testing.T) {
	text, cut := openchamberPlain(sampleMessages(), openchamberMaxText)
	if cut {
		t.Error("plain text must fit")
	}
	order := strings.Index(text, "fix the build")
	answer := strings.Index(text, "On it.")
	if order < 0 || answer < 0 || order > answer {
		t.Errorf("the oldest message must come first:\n%s", text)
	}
	if !strings.Contains(text, "▌ You") || !strings.Contains(text, "build (m1)") {
		t.Errorf("plain header:\n%s", text)
	}
	if !strings.Contains(text, "⚙ shell") || !strings.Contains(text, "go build ./...") || !strings.Contains(text, "ok") {
		t.Errorf("plain tool:\n%s", text)
	}
}

func TestOpenChamberRich(t *testing.T) {
	text, _ := openchamberRich(sampleMessages(), openchamberMaxText)
	var entries []map[string]any
	if err := json.Unmarshal([]byte(text), &entries); err != nil {
		t.Fatalf("rich text must be JSON: %v\n%s", err, text)
	}
	if len(entries) != 4 {
		t.Fatalf("entries %+v", entries)
	}
	if entries[0]["r"] != "u" || entries[0]["t"] != "fix the build" {
		t.Errorf("user entry %+v", entries[0])
	}
	if entries[3]["r"] != "t" || entries[3]["n"] != "shell" || entries[3]["s"] != "completed" || entries[3]["o"] != "ok" {
		t.Errorf("tool entry %+v", entries[3])
	}
}

func TestOpenChamberView(t *testing.T) {
	d := &Daemon{cfg: &config.Config{OpenChamber: true}, devices: map[string]*Device{}}
	v := d.openchamberViewLocked()
	if !v.Enabled || v.Control {
		t.Errorf("view %+v", v)
	}
	if v.Agents == nil || v.Kinds == nil || len(v.Kinds) != 0 {
		t.Errorf("the lists must be empty, not nil: %+v", v)
	}
	d.cfg.OpenChamberControl = true
	d.ocAgents = []OpenChamberAgent{{ID: "a"}}
	d.ocKinds = []OpenChamberKind{{ID: "build", Name: "Build"}}
	v = d.openchamberViewLocked()
	if !v.Control || len(v.Agents) != 1 || len(v.Kinds) != 1 {
		t.Errorf("control view %+v", v)
	}
	d.cfg.OpenChamber = false
	v = d.openchamberViewLocked()
	if v.Running || v.Control || len(v.Agents) != 0 || len(v.Kinds) != 0 {
		t.Errorf("off view %+v", v)
	}
}

// fakeOC is an OpenChamber API server for tests. sessions, status,
// messages, projects, and kinds are the reply bodies, and calls records
// each request that is not a get of those. push sends an event to each
// event stream.
type fakeOC struct {
	srv *httptest.Server

	mu       sync.Mutex
	sessions string
	status   string
	messages string
	projects string
	kinds    string
	calls    []string
	bodies   map[string]string
	subs     []chan struct{}
}

func newFakeOC(t *testing.T) *fakeOC {
	t.Helper()
	f := &fakeOC{
		sessions: `{"data":[]}`,
		status:   `{"sessions":{},"pending":{}}`,
		messages: `{"data":[]}`,
		projects: `[]`,
		kinds:    `{"data":[]}`,
		bodies:   map[string]string{},
	}
	mux := http.NewServeMux()
	write := func(w http.ResponseWriter, get func() string) {
		f.mu.Lock()
		body := get()
		f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, body)
	}
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"status":"ok","openchamberVersion":"2.0.3","compatibility":{"apiVersion":1}}`)
	})
	mux.HandleFunc("/api/session", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			f.record(r)
			io.WriteString(w, `{"data":{"id":"ses_new","title":"New"}}`)
			return
		}
		write(w, func() string { return f.sessions })
	})
	mux.HandleFunc("/api/sessions/status", func(w http.ResponseWriter, r *http.Request) {
		write(w, func() string { return f.status })
	})
	mux.HandleFunc("/api/project", func(w http.ResponseWriter, r *http.Request) {
		write(w, func() string { return f.projects })
	})
	mux.HandleFunc("/api/agent", func(w http.ResponseWriter, r *http.Request) {
		write(w, func() string { return f.kinds })
	})
	mux.HandleFunc("/api/session/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/message") {
			write(w, func() string { return f.messages })
			return
		}
		f.record(r)
		io.WriteString(w, `{}`)
	})
	mux.HandleFunc("/api/openchamber/sessions/", func(w http.ResponseWriter, r *http.Request) {
		f.record(r)
		io.WriteString(w, `{}`)
	})
	mux.HandleFunc("/api/event", func(w http.ResponseWriter, r *http.Request) {
		fl, _ := w.(http.Flusher)
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		if fl != nil {
			fl.Flush()
		}
		ch := make(chan struct{}, 1)
		f.mu.Lock()
		f.subs = append(f.subs, ch)
		f.mu.Unlock()
		for {
			select {
			case <-r.Context().Done():
				return
			case <-ch:
				io.WriteString(w, "data: {\"type\":\"session.updated\"}\n\n")
				if fl != nil {
					fl.Flush()
				}
			}
		}
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeOC) record(r *http.Request) {
	var b map[string]any
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&b)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, r.Method+" "+r.URL.Path)
	if b != nil {
		data, _ := json.Marshal(b)
		f.bodies[r.URL.Path] = string(data)
	}
}

func (f *fakeOC) set(sessions, status string) {
	f.mu.Lock()
	f.sessions, f.status = sessions, status
	f.mu.Unlock()
}

func (f *fakeOC) push() {
	f.mu.Lock()
	subs := append([]chan struct{}(nil), f.subs...)
	f.mu.Unlock()
	for _, ch := range subs {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

func (f *fakeOC) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.calls)
}

func (f *fakeOC) body(path string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.bodies[path]
}

// ocDaemon returns a Daemon that talks to the fake OpenChamber server.
func ocDaemon(ctx context.Context, base string) *Daemon {
	return &Daemon{
		cfg: &config.Config{OpenChamber: true}, devices: map[string]*Device{},
		logger: log.New(io.Discard, "", 0), dirty: make(chan struct{}, 1), ctx: ctx,
		oc: &openchamber.Client{Base: base}, ocWake: make(chan struct{}, 1),
	}
}

func (d *Daemon) ocStatus() (bool, []OpenChamberAgent) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.ocRunning, d.ocAgents
}

const oneSession = `{"data":[{"id":"ses_1","title":"Fix build","agent":"build","model":{"id":"m1"},` +
	`"location":{"directory":"/src/app"},"time":{"created":1,"updated":2}}]}`
const busyStatus = `{"sessions":{"ses_1":{"status":"busy","lastUpdateAt":9}},"pending":{}}`
const blockedStatus = `{"sessions":{"ses_1":{"status":"idle","lastUpdateAt":9}},` +
	`"pending":{"ses_1":{"forms":[{"id":"frm_1","sessionID":"ses_1","title":"Questions",` +
	`"fields":[{"key":"choice","type":"string","options":[{"value":"1","label":"One"}]}]}]}}}`

func TestOpenChamberLoopFollowsEvents(t *testing.T) {
	f := newFakeOC(t)
	f.set(oneSession, busyStatus)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := ocDaemon(ctx, f.srv.URL)
	done := make(chan struct{})
	go func() {
		d.openchamberLoop(ctx)
		close(done)
	}()

	waitFor(t, "the first session list", func() bool {
		running, agents := d.ocStatus()
		return running && len(agents) == 1 && agents[0].Status == "working"
	})

	// A blocked session makes fluxd read again after an event.
	f.set(oneSession, blockedStatus)
	f.push()
	waitFor(t, "the blocked status", func() bool {
		_, agents := d.ocStatus()
		return len(agents) == 1 && agents[0].Status == "blocked" && agents[0].Waiting == "form"
	})

	// Turning the feature off clears the state.
	d.mu.Lock()
	d.cfg.OpenChamber = false
	d.mu.Unlock()
	d.openchamberChanged()
	waitFor(t, "the cleared state", func() bool {
		running, agents := d.ocStatus()
		return !running && agents == nil
	})

	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("the loop must end with the context")
	}
}

func TestOpenChamberLoopWithoutServer(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := ocDaemon(ctx, "http://127.0.0.1:1")
	done := make(chan struct{})
	go func() {
		d.openchamberLoop(ctx)
		close(done)
	}()
	time.Sleep(50 * time.Millisecond)
	if running, _ := d.ocStatus(); running {
		t.Fatal("OpenChamber cannot run without its server")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("the loop must end with the context")
	}
}

func ocOutputBody(t *testing.T, p *proto.Packet) map[string]any {
	t.Helper()
	if p.Type != proto.TypeFluxOpenChamber {
		t.Fatalf("packet type %s", p.Type)
	}
	var body map[string]any
	if err := p.Decode(&body); err != nil {
		t.Fatal(err)
	}
	return body
}

func TestOpenChamberRead(t *testing.T) {
	f := newFakeOC(t)
	f.set(oneSession, blockedStatus)
	f.mu.Lock()
	f.messages = `{"data":[{"type":"assistant","agent":"build","model":{"id":"m1"},` +
		`"content":[{"type":"text","text":"On it."}],"time":{"created":3}},` +
		`{"type":"user","text":"fix the build","time":{"created":2}}]}`
	f.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := ocDaemon(ctx, f.srv.URL)
	live, err := d.openchamberRead(ctx, new([]OpenChamberKind), new(time.Time))
	if err != nil {
		t.Fatal(err)
	}
	d.setOpenChamber(true, live)

	body := ocOutputBody(t, d.readOpenChamber("ses_1", 0, false))
	if body["error"] != nil {
		t.Fatalf("read error: %v", body["error"])
	}
	if text, _ := body["text"].(string); !strings.Contains(text, "fix the build") || !strings.Contains(text, "On it.") {
		t.Errorf("plain text %q", text)
	}
	pending, _ := body["pending"].([]any)
	if len(pending) != 1 {
		t.Fatalf("pending %+v", body["pending"])
	}
	item, _ := pending[0].(map[string]any)
	if item["kind"] != "form" || item["id"] != "frm_1" {
		t.Errorf("pending item %+v", item)
	}
	fields, _ := item["fields"].([]any)
	if len(fields) != 1 {
		t.Fatalf("fields %+v", item["fields"])
	}

	// A session that is not in the last state cannot be read.
	body = ocOutputBody(t, d.readOpenChamber("ses_9", 0, false))
	if body["error"] == nil {
		t.Error("an unknown session must not be readable")
	}

	// With rich, the text is JSON.
	body = ocOutputBody(t, d.readOpenChamber("ses_1", 0, true))
	if body["format"] != "rich" {
		t.Errorf("format %v", body["format"])
	}
	if text, _ := body["text"].(string); !strings.HasPrefix(text, "[") {
		t.Errorf("rich text %q", text)
	}
}

func TestOpenChamberReplyGating(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		io.WriteString(w, `{}`)
	}))
	defer srv.Close()
	d := ocDaemon(context.Background(), srv.URL)
	d.ocAgents = []OpenChamberAgent{{ID: "ses_1"}}

	// Control is off.
	body := ocOutputBody(t, d.openchamberPrompt(&Device{}, "ses_1", "hi"))
	if body["error"] != errOpenChamberControlOff {
		t.Errorf("control off: %v", body["error"])
	}

	// Control is on: the prompt goes to OpenChamber.
	d.cfg.OpenChamberControl = true
	body = ocOutputBody(t, d.openchamberPrompt(&Device{}, "ses_1", "  hi there  "))
	if body["error"] != nil {
		t.Fatalf("prompt: %v", body["error"])
	}
	if calls != 1 {
		t.Errorf("calls %d", calls)
	}

	// An empty prompt is refused before the call.
	body = ocOutputBody(t, d.openchamberPrompt(&Device{}, "ses_1", "   "))
	if body["error"] == nil || calls != 1 {
		t.Errorf("empty prompt: %v calls %d", body["error"], calls)
	}

	// A decision that Flux does not allow is refused.
	body = ocOutputBody(t, d.openchamberPermission(&Device{}, "ses_1", "perm_1", "always"))
	if body["error"] == nil || calls != 1 {
		t.Errorf("bad decision: %v calls %d", body["error"], calls)
	}
}

func TestOpenChamberCreateGating(t *testing.T) {
	f := newFakeOC(t)
	d := ocDaemon(context.Background(), f.srv.URL)
	d.cfg.OpenChamberControl = true
	d.ocKinds = []OpenChamberKind{{ID: "build", Name: "Build"}}
	// The loop reports the new session at once, so the wait for it in the
	// state returns without the loop.
	d.ocAgents = []OpenChamberAgent{{ID: "ses_new"}}

	body := ocOutputBody(t, d.openchamberCreate(&Device{}, "nope", "", ""))
	if body["error"] == nil {
		t.Error("an unknown agent must be refused")
	}

	body = ocOutputBody(t, d.openchamberCreate(&Device{}, "build", t.TempDir(), ""))
	if body["error"] != nil {
		t.Fatalf("create: %v", body["error"])
	}
	if body["session"] != "ses_new" {
		t.Errorf("new session %v", body["session"])
	}
}

func TestOpenChamberClose(t *testing.T) {
	f := newFakeOC(t)
	d := ocDaemon(context.Background(), f.srv.URL)
	d.cfg.OpenChamberControl = true
	d.ocAgents = []OpenChamberAgent{{ID: "ses_1", Dir: "/src/app"}}

	body := ocOutputBody(t, d.openchamberClose(&Device{}, "ses_1"))
	if body["error"] != nil {
		t.Fatalf("close: %v", body["error"])
	}
	f.mu.Lock()
	calls := append([]string(nil), f.calls...)
	f.mu.Unlock()
	if len(calls) != 2 || !strings.HasSuffix(calls[0], "/interrupt") || !strings.HasSuffix(calls[1], "/archive") {
		t.Errorf("close calls %v", calls)
	}
	if b := f.body("/api/openchamber/sessions/archive"); !strings.Contains(b, "/src/app") || !strings.Contains(b, "ses_1") {
		t.Errorf("archive body %s", b)
	}
}
