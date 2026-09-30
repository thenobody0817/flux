package openchamber

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPortFromSettings(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("OPENCHAMBER_PORT", "")
	t.Setenv("OPENCHAMBER_TOKEN", "")
	os.MkdirAll(filepath.Join(dir, "openchamber"), 0o755)
	os.WriteFile(filepath.Join(dir, "openchamber", "settings.json"),
		[]byte(`{"desktopLocalPort":57123,"desktopLocalClientToken":"oc_client_abc"}`), 0o644)
	if got := Port(); got != 57123 {
		t.Errorf("Port() = %d", got)
	}
	if got := BaseURL(); got != "http://127.0.0.1:57123" {
		t.Errorf("BaseURL() = %q", got)
	}
	if got := LocalToken(); got != "oc_client_abc" {
		t.Errorf("LocalToken() = %q", got)
	}
	t.Setenv("OPENCHAMBER_PORT", "1234")
	if got := Port(); got != 1234 {
		t.Errorf("Port() with env = %d", got)
	}
	t.Setenv("OPENCHAMBER_TOKEN", "env-token")
	if got := LocalToken(); got != "env-token" {
		t.Errorf("LocalToken() with env = %q", got)
	}
}

func TestBearerToken(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("Authorization")
		w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()
	// A fixed token goes out as a bearer header.
	if _, err := (&Client{Base: srv.URL, Token: "abc"}).Sessions(context.Background(), nil); err != nil {
		t.Fatal(err)
	}
	if got != "Bearer abc" {
		t.Errorf("authorization %q", got)
	}
	// Without a token, no header goes out.
	got = "unset"
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("OPENCHAMBER_TOKEN", "")
	if _, err := (&Client{Base: srv.URL}).Sessions(context.Background(), nil); err != nil {
		t.Fatal(err)
	}
	if got != "" {
		t.Errorf("authorization without a token %q", got)
	}
}

func TestHealth(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/health" {
			t.Errorf("path %s", r.URL.Path)
		}
		w.Write([]byte(`{"status":"ok","openchamberVersion":"2.0.3","compatibility":{"apiVersion":1}}`))
	}))
	defer srv.Close()
	h, err := (&Client{Base: srv.URL}).Health(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if h.Version != "2.0.3" || h.Compatibility.APIVersion != 1 {
		t.Errorf("health %+v", h)
	}
}

func TestSessionsAndProjects(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/session":
			if got := r.URL.Query().Get("limit"); got != "" {
				t.Errorf("limit %q", got)
			}
			w.Write([]byte(`{"data":[{"id":"ses_1","title":"Fix build","agent":"build",
				"projectID":"p1","model":{"id":"m"},"location":{"directory":"/src/app"},
				"time":{"created":1,"updated":2}}]}`))
		case "/api/project":
			w.Write([]byte(`[{"id":"p1","canonical":"/src/app"}]`))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	}))
	defer srv.Close()
	c := &Client{Base: srv.URL}
	ctx := context.Background()
	ss, err := c.Sessions(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(ss) != 1 || ss[0].ID != "ses_1" || ss[0].ProjectID != "p1" || ss[0].Location.Directory != "/src/app" {
		t.Errorf("sessions %+v", ss)
	}
	ps, err := c.Projects(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(ps) != 1 || ps[0].Canonical != "/src/app" {
		t.Errorf("projects %+v", ps)
	}
}

func TestStatusAndMessages(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/sessions/status":
			w.Write([]byte(`{"sessions":{"ses_1":{"status":"busy","lastUpdateAt":9}},
				"pending":{"ses_1":{"permissions":[{"id":"perm_1","sessionID":"ses_1","action":"shell","resources":["ls"]}],
				"forms":[{"id":"frm_1","sessionID":"ses_1","title":"Questions",
				"fields":[{"key":"choice","type":"string","label":"Pick","options":[{"value":"1","label":"One"}],"required":true}]}]}}}`))
		case strings.HasSuffix(r.URL.Path, "/message"):
			if got := r.URL.Query().Get("order"); got != "desc" {
				t.Errorf("order %q", got)
			}
			w.Write([]byte(`{"data":[{"id":"m1","type":"user","text":"hi","time":{"created":3}}]}`))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	}))
	defer srv.Close()
	c := &Client{Base: srv.URL}
	ctx := context.Background()
	st, err := c.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.Sessions["ses_1"].Status != "busy" {
		t.Errorf("status %+v", st.Sessions)
	}
	if len(st.Pending["ses_1"].Forms) != 1 || st.Pending["ses_1"].Forms[0].Fields[0].Options[0].Value != "1" {
		t.Errorf("pending %+v", st.Pending)
	}
	msgs, err := c.Messages(ctx, "ses_1", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 || msgs[0].Text != "hi" {
		t.Errorf("messages %+v", msgs)
	}
}

func TestKindsFilters(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"data":[
			{"id":"build","name":"Build","mode":"primary"},
			{"id":"plan","name":"Plan","mode":"primary","hidden":true},
			{"id":"general","name":"General","mode":"subagent"}]}`))
	}))
	defer srv.Close()
	ks, err := (&Client{Base: srv.URL}).Kinds(context.Background(), "/src")
	if err != nil {
		t.Fatal(err)
	}
	if len(ks) != 1 || ks[0].ID != "build" {
		t.Errorf("kinds %+v", ks)
	}
}

// bodyRecorder records the method, path, query, and body of each call and
// answers with the reply of the test.
type bodyRecorder struct {
	method string
	path   string
	query  string
	body   map[string]any
}

func recordServer(t *testing.T, rec *bodyRecorder, reply string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec.method, rec.path, rec.query = r.Method, r.URL.Path, r.URL.RawQuery
		rec.body = nil
		if r.Body != nil {
			var m map[string]any
			if json.NewDecoder(r.Body).Decode(&m) == nil {
				rec.body = m
			}
		}
		w.Write([]byte(reply))
	}))
}

func TestReplyCalls(t *testing.T) {
	ctx := context.Background()

	var rec bodyRecorder
	srv := recordServer(t, &rec, `{}`)
	defer srv.Close()
	c := &Client{Base: srv.URL}

	if err := c.Prompt(ctx, "ses_1", "hello"); err != nil {
		t.Fatal(err)
	}
	if rec.method != "POST" || rec.path != "/api/session/ses_1/prompt" || rec.body["text"] != "hello" {
		t.Errorf("prompt %s %s %+v", rec.method, rec.path, rec.body)
	}

	if err := c.Interrupt(ctx, "ses_1"); err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/session/ses_1/interrupt" {
		t.Errorf("interrupt %s", rec.path)
	}

	if err := c.AnswerForm(ctx, "ses_1", "frm_1", json.RawMessage(`{"choice":"1"}`)); err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/session/ses_1/form/frm_1/reply" {
		t.Errorf("form %s", rec.path)
	}
	if ans, ok := rec.body["answer"].(map[string]any); !ok || ans["choice"] != "1" {
		t.Errorf("form answer %+v", rec.body)
	}

	if err := c.AnswerPermission(ctx, "ses_1", "perm_1", "allow"); err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/session/ses_1/permission/perm_1/reply" || rec.body["decision"] != "allow" {
		t.Errorf("permission %s %+v", rec.path, rec.body)
	}

	if err := c.Archive(ctx, "/src/app", []string{"ses_1"}); err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/openchamber/sessions/archive" || rec.body["directory"] != "/src/app" {
		t.Errorf("archive %s %+v", rec.path, rec.body)
	}

	if err := c.Unarchive(ctx, "/src/app", []string{"ses_1"}); err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/openchamber/sessions/unarchive" || !strings.Contains(rec.query, "directory=%2Fsrc%2Fapp") {
		t.Errorf("unarchive %s %s", rec.path, rec.query)
	}
}

func TestCreate(t *testing.T) {
	var rec bodyRecorder
	srv := recordServer(t, &rec, `{"data":{"id":"ses_9","title":"New"}}`)
	defer srv.Close()
	s, err := (&Client{Base: srv.URL}).Create(context.Background(), "plan", "/src/app", "")
	if err != nil {
		t.Fatal(err)
	}
	if s.ID != "ses_9" {
		t.Errorf("create %+v", s)
	}
	if rec.path != "/api/session" || rec.body["agent"] != "plan" {
		t.Errorf("create body %s %+v", rec.path, rec.body)
	}
	loc, ok := rec.body["location"].(map[string]any)
	if !ok || loc["directory"] != "/src/app" {
		t.Errorf("create location %+v", rec.body)
	}
}

func TestHTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte(`{"error":"no such session"}`))
	}))
	defer srv.Close()
	err := (&Client{Base: srv.URL}).Prompt(context.Background(), "ses_1", "hi")
	if Code(err) != "not_found" {
		t.Errorf("code %q err %v", Code(err), err)
	}
	if !strings.Contains(err.Error(), "no such session") {
		t.Errorf("message %v", err)
	}
}

func TestSubscribe(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Accept"); got != "text/event-stream" {
			t.Errorf("accept %q", got)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		fl := w.(http.Flusher)
		w.Write([]byte(": heartbeat\n\n"))
		fl.Flush()
		w.Write([]byte("data: {\"type\":\"session.updated\"}\n\n"))
		fl.Flush()
	}))
	defer srv.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s, err := (&Client{Base: srv.URL}).Subscribe(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ev, err := s.Next()
	if err != nil {
		t.Fatal(err)
	}
	if ev.Type != "session.updated" {
		t.Errorf("event %+v", ev)
	}
}
