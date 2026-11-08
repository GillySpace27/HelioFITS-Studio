package org.helioviewer.jhv.activity;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandleProxies;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Platform;

/**
 * This process's own storage traffic, from the operating system: bytes read from and written to
 * disk since the process started. One system call, so the DISK light can be read ten times a second
 * and see everything Studio does, the native decoders and the HTTP cache included, without a hook
 * at every file.
 *
 * <p>macOS: {@code proc_pid_rusage} with {@code RUSAGE_INFO_V2}, fields {@code ri_diskio_bytesread}
 * and {@code ri_diskio_byteswritten}, which also gives {@code ri_phys_footprint}, the "Memory"
 * column of Activity Monitor. Linux: {@code /proc/self/io}, {@code read_bytes} and
 * {@code write_bytes}. Windows: {@code GetProcessIoCounters}, whose transfer counts include
 * network and device I/O as well as files.
 *
 * <p>Reads served from the system's file cache never reach the disk and are not counted, as on a
 * real disk light. A child process (HelioFITS Core) has counters of its own, not counted here.
 */
public final class IoCounters {

    /** Bytes read and written since the process started, and the macOS memory footprint (0 elsewhere). */
    public record Sample(long read, long written, long footprint) {}

    private interface Source {
        @Nullable
        Sample sample();
    }

    // Native calls as plain interfaces, so no caller has to catch the Throwable that invokeExact declares.
    public interface Rusage {
        int call(int pid, int flavor, MemorySegment buffer);
    }

    public interface CurrentProcess {
        MemorySegment call();
    }

    public interface ProcessIoCounters {
        int call(MemorySegment process, MemorySegment counters);
    }

    private static final Source SOURCE = open();

    private IoCounters() {}

    public static boolean supported() {
        return SOURCE != null;
    }

    /** The counters now, or null where the system does not say. */
    @Nullable
    public static Sample sample() {
        return SOURCE == null ? null : SOURCE.sample();
    }

    @Nullable
    private static Source open() {
        try {
            if (Platform.isLinux())
                return linux();
            if (Platform.isMacOS())
                return mac();
            if (Platform.isWindows())
                return windows();
        } catch (RuntimeException | LinkageError e) { // a missing symbol leaves the light to the named events
            Log.warn("Process I/O counters are not available: " + e);
        }
        return null;
    }

    @Nullable
    private static Source linux() {
        Path io = Path.of("/proc/self/io");
        if (!Files.isReadable(io))
            return null;
        return () -> {
            long read = -1, written = -1;
            try {
                for (String line : Files.readAllLines(io)) {
                    if (line.startsWith("read_bytes:"))
                        read = Long.parseLong(line.substring(11).trim());
                    else if (line.startsWith("write_bytes:"))
                        written = Long.parseLong(line.substring(12).trim());
                }
            } catch (IOException | NumberFormatException e) {
                return null;
            }
            return read < 0 || written < 0 ? null : new Sample(read, written, 0);
        };
    }

    // struct rusage_info_v2 (sys/resource.h): uint8_t ri_uuid[16], then 18 uint64_t fields
    private static final int RUSAGE_INFO_V2 = 2;
    private static final long V2_BYTES = 16 + 18 * 8;
    private static final long FOOTPRINT = 16 + 7 * 8; // ri_phys_footprint
    private static final long DISK_READ = 16 + 16 * 8; // ri_diskio_bytesread
    private static final long DISK_WRITTEN = 16 + 17 * 8; // ri_diskio_byteswritten

    private static Source mac() {
        Linker linker = Linker.nativeLinker();
        MemorySegment fn = linker.defaultLookup().find("proc_pid_rusage").orElseThrow(() -> new UnsatisfiedLinkError("proc_pid_rusage"));
        Rusage call = MethodHandleProxies.asInterfaceInstance(Rusage.class,
                linker.downcallHandle(fn, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
        int pid = (int) ProcessHandle.current().pid();
        MemorySegment buf = Arena.global().allocate(V2_BYTES, 8);
        Object lock = new Object();
        return () -> {
            synchronized (lock) {
                if (call.call(pid, RUSAGE_INFO_V2, buf) != 0)
                    return null;
                return new Sample(buf.get(ValueLayout.JAVA_LONG, DISK_READ), buf.get(ValueLayout.JAVA_LONG, DISK_WRITTEN),
                        buf.get(ValueLayout.JAVA_LONG, FOOTPRINT));
            }
        };
    }

    // IO_COUNTERS (winnt.h): six ULONGLONG, ReadOperationCount, WriteOperationCount, OtherOperationCount,
    // ReadTransferCount, WriteTransferCount, OtherTransferCount
    private static Source windows() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        CurrentProcess current = MethodHandleProxies.asInterfaceInstance(CurrentProcess.class,
                linker.downcallHandle(kernel32.find("GetCurrentProcess").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS)));
        ProcessIoCounters counters = MethodHandleProxies.asInterfaceInstance(ProcessIoCounters.class,
                linker.downcallHandle(kernel32.find("GetProcessIoCounters").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)));
        MemorySegment buf = Arena.global().allocate(6 * 8, 8);
        Object lock = new Object();
        return () -> {
            synchronized (lock) {
                if (counters.call(current.call(), buf) == 0)
                    return null;
                return new Sample(buf.get(ValueLayout.JAVA_LONG, 24), buf.get(ValueLayout.JAVA_LONG, 32), 0);
            }
        };
    }

}
