package mobile

import (
	"os"
	"path/filepath"
	"runtime/debug"
	"runtime/pprof"
)

// crashFileLimit is the size above which the crash file starts over at the next start: crashes are rare, and one
// is enough to read.
const crashFileLimit = 1 << 20

// WriteGoroutines writes the stack of every goroutine of the node's process to path, for the app's node reports
// (NodeReports.kt), which it writes when the node stops answering. A runtime that is stopped as a whole never
// returns from it: the app waits for it on a thread of its own, for a limited time.
func WriteGoroutines(path string) string {
	f, err := os.Create(path)
	if err != nil {
		return err.Error()
	}
	defer f.Close()
	if err := pprof.Lookup("goroutine").WriteTo(f, 2); err != nil {
		return err.Error()
	}
	return ""
}

// keepCrashOutput has the Go runtime copy what it prints when it crashes (a fatal error or an unhandled panic, with
// the goroutines' stacks) to crash.txt in dir: Android keeps only minutes of the log, and the app adds the file to
// its node reports.
func keepCrashOutput(dir string) {
	path := filepath.Join(dir, "crash.txt")
	flags := os.O_CREATE | os.O_WRONLY | os.O_APPEND
	if info, err := os.Stat(path); err == nil && info.Size() > crashFileLimit {
		flags |= os.O_TRUNC
	}
	f, err := os.OpenFile(path, flags, 0600)
	if err != nil {
		return
	}
	if err := debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
		f.Close()
		return
	}
	// The runtime keeps its own copy of the file: this one can go.
	f.Close()
}
