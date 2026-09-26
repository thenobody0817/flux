package desktop

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Theme reads the active Omarchy theme. Get returns the colors.toml text
// that the Fluxe window also uses, and the theme slug from theme.name. List
// and Current call the omarchy CLI. Set applies a theme.
//
// Get caches by the file time and size, like DND, because the phone asks
// for the theme on every connect and the poll runs every 2 seconds.
type Theme struct {
	colorsPath string
	namePath   string

	mu      sync.Mutex
	valid   bool
	modTime time.Time
	size    int64
	slug    string
	text    string
}

// NewTheme returns the Omarchy theme of this desktop, or nil when Omarchy
// is not installed. FLUX_THEME_FILE overrides the colors.toml path, as it
// does for the Flux window.
func NewTheme() *Theme {
	if _, err := exec.LookPath("omarchy-theme-set"); err != nil {
		return nil
	}
	home, _ := os.UserHomeDir()
	state := os.Getenv("XDG_STATE_HOME")
	if state == "" {
		state = filepath.Join(home, ".local", "state")
	}
	t := &Theme{
		colorsPath: filepath.Join(state, "omarchy", "current", "theme", "colors.toml"),
		namePath:   filepath.Join(state, "omarchy", "current", "theme.name"),
	}
	if override := os.Getenv("FLUX_THEME_FILE"); override != "" {
		t.colorsPath = override
	}
	return t
}

// Get returns the theme slug and the colors.toml text. ok is false when
// the theme file does not exist.
func (t *Theme) Get() (slug, text string, ok bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	st, err := os.Stat(t.colorsPath)
	if err != nil {
		t.valid = false
		return "", "", false
	}
	if t.valid && st.ModTime().Equal(t.modTime) && st.Size() == t.size {
		return t.slug, t.text, true
	}
	b, err := os.ReadFile(t.colorsPath)
	if err != nil {
		return "", "", false
	}
	t.slug = readSlug(t.namePath)
	t.text = string(b)
	t.modTime, t.size, t.valid = st.ModTime(), st.Size(), true
	return t.slug, t.text, true
}

func readSlug(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// Current returns the display name of the active theme, for example
// "Matte Black". ok is false when the omarchy CLI does not answer.
func (t *Theme) Current() (string, bool) {
	out, err := exec.Command("omarchy-theme-current").Output()
	if err != nil {
		return "", false
	}
	name := strings.TrimSpace(string(out))
	return name, name != ""
}

// List returns the display names of the installed themes.
func (t *Theme) List() ([]string, bool) {
	out, err := exec.Command("omarchy-theme-list").Output()
	if err != nil {
		return nil, false
	}
	var themes []string
	for _, line := range strings.Split(string(out), "\n") {
		if line = strings.TrimSpace(line); line != "" {
			themes = append(themes, line)
		}
	}
	return themes, true
}

// Set applies the theme with the display name. It rejects a name that is
// not in the installed list, so a phone cannot pass another argument.
func (t *Theme) Set(name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return errors.New("empty theme name")
	}
	themes, ok := t.List()
	if !ok {
		return errors.New("cannot list the themes")
	}
	found := false
	for _, x := range themes {
		if x == name {
			found = true
			break
		}
	}
	if !found {
		return errors.New("unknown theme: " + name)
	}
	if err := exec.Command("omarchy-theme-set", name).Run(); err != nil {
		return err
	}
	// A new theme replaces the files, so the next Get must read them again.
	t.mu.Lock()
	t.valid = false
	t.mu.Unlock()
	return nil
}

// Mode returns the "mode" line of a colors.toml, or "dark" when it has
// none.
func Mode(text string) string {
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "mode") {
			if i := strings.IndexByte(line, '"'); i >= 0 {
				if j := strings.IndexByte(line[i+1:], '"'); j >= 0 {
					return line[i+1 : i+1+j]
				}
			}
		}
	}
	return "dark"
}
