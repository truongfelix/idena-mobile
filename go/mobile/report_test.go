package mobile

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime/debug"
	"strings"
	"testing"
)

func TestWriteGoroutinesHasEveryGoroutinesStack(t *testing.T) {
	path := filepath.Join(t.TempDir(), "goroutines.txt")
	blocked := make(chan struct{})
	defer close(blocked)
	go waitingGoroutine(blocked)

	if res := WriteGoroutines(path); res != "" {
		t.Fatal(res)
	}
	text, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"TestWriteGoroutinesHasEveryGoroutinesStack", "waitingGoroutine", "[chan receive"} {
		if !strings.Contains(string(text), want) {
			t.Errorf("the dump lacks %q:\n%s", want, text)
		}
	}
}

func waitingGoroutine(blocked chan struct{}) { <-blocked }

func TestWriteGoroutinesReportsAFolderItCannotWrite(t *testing.T) {
	if res := WriteGoroutines(filepath.Join(t.TempDir(), "missing", "goroutines.txt")); res == "" {
		t.Fatal("a dump into a missing folder was reported written")
	}
}

// The crash itself runs in a child process: the test binary again, with crashChildEnv set.
const crashChildEnv = "IDENA_MOBILE_CRASH_CHILD_DIR"

func TestCrashOutputGoesToTheCrashFile(t *testing.T) {
	if dir := os.Getenv(crashChildEnv); dir != "" {
		keepCrashOutput(dir)
		panic("the node crashed on purpose")
	}
	dir := t.TempDir()
	cmd := exec.Command(os.Args[0], "-test.run=^TestCrashOutputGoesToTheCrashFile$")
	cmd.Env = append(os.Environ(), crashChildEnv+"="+dir)
	if err := cmd.Run(); err == nil {
		t.Fatal("the child did not crash")
	}
	text, err := os.ReadFile(filepath.Join(dir, "crash.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(text), "panic: the node crashed on purpose") || !strings.Contains(string(text), "goroutine ") {
		t.Fatalf("crash.txt lacks the panic and its stack:\n%s", text)
	}
}

func TestCrashFileStartsOverAboveItsLimit(t *testing.T) {
	defer debug.SetCrashOutput(nil, debug.CrashOptions{})
	dir := t.TempDir()
	path := filepath.Join(dir, "crash.txt")

	small := []byte("an earlier crash\n")
	if err := os.WriteFile(path, small, 0600); err != nil {
		t.Fatal(err)
	}
	keepCrashOutput(dir)
	if info, _ := os.Stat(path); info == nil || info.Size() != int64(len(small)) {
		t.Fatalf("a small crash file was not kept: %v", info)
	}

	if err := os.WriteFile(path, make([]byte, crashFileLimit+1), 0600); err != nil {
		t.Fatal(err)
	}
	keepCrashOutput(dir)
	if info, _ := os.Stat(path); info == nil || info.Size() != 0 {
		t.Fatalf("a crash file above the limit was kept: %v", info)
	}
}
