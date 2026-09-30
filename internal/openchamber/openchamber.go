// Package openchamber is a client for the local HTTP API of the
// OpenChamber desktop app. OpenChamber runs opencode sessions and serves
// their list, their status, and their messages on a loopback port. fluxd
// uses this client to show the OpenChamber sessions of this computer on a
// paired phone.
//
// The API is not public, so the client reads it defensively: unknown
// fields are ignored, and a missing field is never a failure. The health
// call reports the API version that Flux checks against MinAPIVersion.
package openchamber

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// MinAPIVersion is the oldest OpenChamber API version that Flux supports.
// The health call reports it as compatibility.apiVersion.
const MinAPIVersion = 1

// maxBody is the largest reply body that the client reads. A session with
// many messages is much smaller.
const maxBody = 32 << 20

// callTimeout limits one call when the context has no deadline.
const callTimeout = 5 * time.Second

// Error is an error reply from OpenChamber, or an error of the client.
type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string { return "openchamber: " + e.Message }

// Code returns the code of an OpenChamber error, or an empty string for
// another error.
func Code(err error) string {
	var oe *Error
	if errors.As(err, &oe) {
		return oe.Code
	}
	return ""
}

func errf(code, format string, args ...any) error {
	return &Error{Code: code, Message: fmt.Sprintf(format, args...)}
}

// ConfigDir returns ~/.config/openchamber, or $XDG_CONFIG_HOME/openchamber.
func ConfigDir() string {
	dir := os.Getenv("XDG_CONFIG_HOME")
	if dir == "" {
		home, _ := os.UserHomeDir()
		dir = filepath.Join(home, ".config")
	}
	return filepath.Join(dir, "openchamber")
}

// settings is the part of ~/.config/openchamber/settings.json that Flux
// reads. OpenChamber writes the port and a token for local clients there.
type settings struct {
	Port  int    `json:"desktopLocalPort"`
	Token string `json:"desktopLocalClientToken"`
}

// readSettings reads the OpenChamber settings. A missing or broken file
// gives a zero value.
func readSettings() settings {
	var s settings
	data, err := os.ReadFile(filepath.Join(ConfigDir(), "settings.json"))
	if err != nil {
		return s
	}
	_ = json.Unmarshal(data, &s)
	return s
}

// Port returns the loopback port of the OpenChamber desktop API.
// OPENCHAMBER_PORT overrides the port that OpenChamber writes in its
// settings. A zero result means that OpenChamber has never run here.
func Port() int {
	if p := os.Getenv("OPENCHAMBER_PORT"); p != "" {
		if n, err := strconv.Atoi(p); err == nil && n > 0 {
			return n
		}
	}
	return readSettings().Port
}

// LocalToken returns the token that OpenChamber gives to clients on this
// computer. OpenChamber writes it when a UI password is set, and the API
// asks for it from every request. OPENCHAMBER_TOKEN overrides it.
func LocalToken() string {
	if t := os.Getenv("OPENCHAMBER_TOKEN"); t != "" {
		return t
	}
	return readSettings().Token
}

// BaseURL returns the base URL of the OpenChamber API on this computer.
func BaseURL() string { return fmt.Sprintf("http://127.0.0.1:%d", Port()) }

// Client is a client for one OpenChamber API base URL.
type Client struct {
	Base string
	HTTP *http.Client
	// Token overrides the local client token of the OpenChamber settings.
	// An empty token makes each call read the current token, which follows
	// a password change.
	Token string
}

// New returns a client for the OpenChamber API on this computer.
func New() *Client {
	return &Client{Base: BaseURL(), HTTP: &http.Client{}}
}

// token returns the client token for one call.
func (c *Client) token() string {
	if c.Token != "" {
		return c.Token
	}
	return LocalToken()
}

// Health reports the version and API of the OpenChamber server.
type Health struct {
	Status          string `json:"status"`
	Version         string `json:"openchamberVersion"`
	Runtime         string `json:"runtime"`
	OpenCodeRunning bool   `json:"openCodeRunning"`
	Compatibility   struct {
		APIVersion int `json:"apiVersion"`
	} `json:"compatibility"`
}

// Model names the model of a session.
type Model struct {
	ID         string `json:"id"`
	ProviderID string `json:"providerID"`
	Variant    string `json:"variant"`
}

// Session is one OpenChamber session, as the phone sees it.
type Session struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Agent     string `json:"agent"`
	Model     Model  `json:"model"`
	ParentID  string `json:"parentID"`
	ProjectID string `json:"projectID"`
	Location  struct {
		Directory string `json:"directory"`
	} `json:"location"`
	Time struct {
		Created int64 `json:"created"`
		Updated int64 `json:"updated"`
	} `json:"time"`
}

// Status is the reply of the session status call. Pending holds the
// questions and the permission prompts that wait for an answer.
type Status struct {
	Sessions map[string]SessionStatus `json:"sessions"`
	Pending  map[string]Pending       `json:"pending"`
}

// SessionStatus is the run status of one session.
type SessionStatus struct {
	Status       string `json:"status"`
	LastUpdateAt int64  `json:"lastUpdateAt"`
}

// Pending is what one session waits for.
type Pending struct {
	Permissions []Permission `json:"permissions"`
	Forms       []Form       `json:"forms"`
}

// Permission is one permission prompt that waits for an answer. Action is
// the kind of the request, such as "shell" or "edit", and Resources lists
// what it touches.
type Permission struct {
	ID        string   `json:"id"`
	SessionID string   `json:"sessionID"`
	Action    string   `json:"action"`
	Resources []string `json:"resources"`
}

// FieldOption is one choice of a form field.
type FieldOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// Field is one input of a question form. Type is "string", "number",
// "integer", "boolean", "multiselect", or "external". A string field with
// options is a choice, and a field without options is free text. An
// external field is an acknowledgement.
type Field struct {
	Key      string        `json:"key"`
	Type     string        `json:"type"`
	Label    string        `json:"label"`
	Default  any           `json:"default"`
	Options  []FieldOption `json:"options"`
	Required bool          `json:"required"`
}

// Form is one question that waits for an answer.
type Form struct {
	ID        string  `json:"id"`
	SessionID string  `json:"sessionID"`
	Title     string  `json:"title"`
	Fields    []Field `json:"fields"`
}

// Kind is one agent that OpenChamber can start a session with.
type Kind struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Mode        string `json:"mode"`
	Hidden      bool   `json:"hidden"`
}

// Content is one part of an assistant message. Type is "text",
// "reasoning", or "tool".
type Content struct {
	Type  string `json:"type"`
	Text  string `json:"text"`
	ID    string `json:"id"`
	Name  string `json:"name"`
	State struct {
		Status  string          `json:"status"`
		Input   json.RawMessage `json:"input"`
		Content []Content       `json:"content"`
		Error   string          `json:"error"`
	} `json:"state"`
}

// Message is one message of a session. Type is "user", "assistant", or
// "synthetic". A user message carries Text, and an assistant message
// carries Content.
type Message struct {
	ID      string    `json:"id"`
	Type    string    `json:"type"`
	Text    string    `json:"text"`
	Agent   string    `json:"agent"`
	Model   Model     `json:"model"`
	Content []Content `json:"content"`
	Time    struct {
		Created   int64 `json:"created"`
		Completed int64 `json:"completed"`
	} `json:"time"`
}

// Project is one folder that OpenChamber knows. Canonical is its path.
type Project struct {
	ID        string `json:"id"`
	Canonical string `json:"canonical"`
}

// Health calls the health endpoint. A caller checks the API version
// against MinAPIVersion before it uses the rest of the client.
func (c *Client) Health(ctx context.Context) (Health, error) {
	var h Health
	err := c.get(ctx, "/health", nil, &h)
	return h, err
}

// Projects returns the projects that OpenChamber knows.
func (c *Client) Projects(ctx context.Context) ([]Project, error) {
	var p []Project
	if err := c.get(ctx, "/api/project", nil, &p); err != nil {
		return nil, err
	}
	return p, nil
}

// Sessions returns the sessions, newest first. The query is passed to the
// API, so a nil query returns the default list.
func (c *Client) Sessions(ctx context.Context, query url.Values) ([]Session, error) {
	var r struct {
		Data []Session `json:"data"`
	}
	if err := c.get(ctx, "/api/session", query, &r); err != nil {
		return nil, err
	}
	return r.Data, nil
}

// Status returns the run status of the sessions and what they wait for.
func (c *Client) Status(ctx context.Context) (Status, error) {
	var s Status
	err := c.get(ctx, "/api/sessions/status", nil, &s)
	return s, err
}

// Messages returns up to limit messages of a session, newest first.
func (c *Client) Messages(ctx context.Context, session string, limit int) ([]Message, error) {
	q := url.Values{}
	if limit > 0 {
		q.Set("limit", strconv.Itoa(limit))
	}
	q.Set("order", "desc")
	var r struct {
		Data []Message `json:"data"`
	}
	if err := c.get(ctx, "/api/session/"+url.PathEscape(session)+"/message", q, &r); err != nil {
		return nil, err
	}
	return r.Data, nil
}

// Kinds returns the agents that OpenChamber can start a session with.
func (c *Client) Kinds(ctx context.Context, dir string) ([]Kind, error) {
	q := url.Values{}
	if dir != "" {
		q.Set("location", dir)
	}
	var r struct {
		Data []Kind `json:"data"`
	}
	if err := c.get(ctx, "/api/agent", q, &r); err != nil {
		return nil, err
	}
	var out []Kind
	for _, k := range r.Data {
		if k.ID == "" || k.Hidden || k.Mode != "primary" {
			continue
		}
		out = append(out, k)
	}
	return out, nil
}

// Prompt sends a prompt to a session.
func (c *Client) Prompt(ctx context.Context, session, text string) error {
	body := map[string]any{"text": text}
	return c.post(ctx, "/api/session/"+url.PathEscape(session)+"/prompt", nil, body, nil)
}

// Interrupt stops the run of a session.
func (c *Client) Interrupt(ctx context.Context, session string) error {
	return c.post(ctx, "/api/session/"+url.PathEscape(session)+"/interrupt", nil, map[string]any{}, nil)
}

// AnswerForm answers a question that waits for a session. answer maps each
// field key to its value, as the API expects it.
func (c *Client) AnswerForm(ctx context.Context, session, form string, answer json.RawMessage) error {
	path := "/api/session/" + url.PathEscape(session) + "/form/" + url.PathEscape(form) + "/reply"
	return c.post(ctx, path, nil, map[string]any{"answer": answer}, nil)
}

// CancelForm drops a question that waits for a session.
func (c *Client) CancelForm(ctx context.Context, session, form string) error {
	path := "/api/session/" + url.PathEscape(session) + "/form/" + url.PathEscape(form)
	return c.do(ctx, http.MethodDelete, path, nil, nil, nil)
}

// AnswerPermission answers a permission prompt. decision is "allow" or
// "deny".
func (c *Client) AnswerPermission(ctx context.Context, session, request, decision string) error {
	path := "/api/session/" + url.PathEscape(session) + "/permission/" + url.PathEscape(request) + "/reply"
	body := map[string]any{"decision": decision}
	return c.post(ctx, path, nil, body, nil)
}

// Create starts a session in a folder with an agent kind. An empty kind
// uses the default agent of OpenChamber. It returns the new session.
func (c *Client) Create(ctx context.Context, kind, dir, title string) (Session, error) {
	body := map[string]any{}
	if kind != "" {
		body["agent"] = kind
	}
	if title != "" {
		body["title"] = title
	}
	if dir != "" {
		body["location"] = map[string]any{"directory": dir}
	}
	var r struct {
		Data Session `json:"data"`
	}
	err := c.post(ctx, "/api/session", nil, body, &r)
	return r.Data, err
}

// Archive files sessions away. directory is the folder of the sessions.
func (c *Client) Archive(ctx context.Context, dir string, ids []string) error {
	body := map[string]any{"ids": ids, "directory": dir, "archivedAt": time.Now().UnixMilli()}
	return c.post(ctx, "/api/openchamber/sessions/archive", nil, body, nil)
}

// Unarchive restores archived sessions to their folder.
func (c *Client) Unarchive(ctx context.Context, dir string, ids []string) error {
	q := url.Values{}
	if dir != "" {
		q.Set("directory", dir)
	}
	return c.post(ctx, "/api/openchamber/sessions/unarchive", q, map[string]any{"ids": ids}, nil)
}

// Event is one server-sent event. fluxd reads the session state again
// after any event, so the content of an event does not matter.
type Event struct {
	Type string `json:"type"`
}

// Stream is an open event stream.
type Stream struct {
	body io.Closer
	r    *bufio.Reader
}

// Subscribe opens the event stream of OpenChamber. The caller closes the
// stream to end it.
func (c *Client) Subscribe(ctx context.Context) (*Stream, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Base+"/api/event", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "text/event-stream")
	if t := c.token(); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}
	resp, err := c.doer().Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, errf("http", "GET /api/event: %s", resp.Status)
	}
	return &Stream{body: resp.Body, r: bufio.NewReader(resp.Body)}, nil
}

// Next blocks until the next event arrives or the stream closes. It skips
// comments and empty lines.
func (s *Stream) Next() (Event, error) {
	var ev Event
	for {
		line, err := s.r.ReadBytes('\n')
		if len(line) > 0 {
			if data, ok := bytes.CutPrefix(bytes.TrimRight(line, "\r\n"), []byte("data:")); ok {
				if json.Unmarshal(bytes.TrimSpace(data), &ev) == nil {
					return ev, nil
				}
				// An event that Flux cannot read ends the wait, because
				// any event makes fluxd read the state again.
				return Event{}, nil
			}
		}
		if err != nil {
			return Event{}, err
		}
	}
}

// Close ends the stream.
func (s *Stream) Close() error { return s.body.Close() }

func (c *Client) doer() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return http.DefaultClient
}

func (c *Client) get(ctx context.Context, path string, q url.Values, out any) error {
	return c.do(ctx, http.MethodGet, path, q, nil, out)
}

func (c *Client) post(ctx context.Context, path string, q url.Values, body, out any) error {
	return c.do(ctx, http.MethodPost, path, q, body, out)
}

// do sends one request and decodes the reply into out. out can be nil. A
// non-2xx reply becomes an Error with the code that OpenChamber sent.
func (c *Client) do(ctx context.Context, method, path string, q url.Values, body, out any) error {
	if _, ok := ctx.Deadline(); !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, callTimeout)
		defer cancel()
	}
	u := c.Base + path
	if len(q) > 0 {
		u += "?" + q.Encode()
	}
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, reader)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if t := c.token(); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}
	resp, err := c.doer().Do(req)
	if err != nil {
		return &Error{Code: "unreachable", Message: "OpenChamber does not answer"}
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return httpError(resp.StatusCode, data)
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		return errf("bad_reply", "cannot read the reply of %s: %v", path, err)
	}
	return nil
}

// httpError turns an error reply into an Error. OpenChamber sends
// {"error": "..."} or {"message": "..."} for a failure.
func httpError(status int, data []byte) error {
	var e struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	_ = json.Unmarshal(data, &e)
	msg := e.Error
	if msg == "" {
		msg = e.Message
	}
	if msg == "" {
		msg = http.StatusText(status)
	}
	code := "http"
	switch status {
	case http.StatusUnauthorized:
		code = "unauthorized"
	case http.StatusNotFound:
		code = "not_found"
	case http.StatusConflict:
		code = "conflict"
	}
	return &Error{Code: code, Message: strings.TrimSpace(msg)}
}
