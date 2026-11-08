package io.github.tony079.sdlc.docprocessing.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.tony079.sdlc.docprocessing.internal.extractor.ExtractorMain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Starts the extractor as a separate, restricted OS process (TDD 15.7): its own small heap, one CPU, a wall-clock
 * limit, an empty environment (no secrets, no credentials, no proxy settings) and a private working directory per
 * job and lease epoch. It is killed when the time limit is reached.
 *
 * <p>Owner decision O-15 (2026-11-08): with {@code sdlc.documents.extraction.sandbox=unshare-net} the process also
 * runs in new user and network namespaces ({@code unshare -rn}: no network interface but a down loopback, so no
 * database, scanner, object store or Internet) under kernel resource limits ({@code prlimit}: CPU seconds, maximum
 * file size, open files). At start-up the launcher verifies the sandbox works; when it is required and does not work,
 * extraction refuses to run (fail closed) instead of running unisolated.
 *
 * <p>Not covered here (infrastructure dependency): a separate mount namespace or container with a read-only root and
 * seccomp. The process still runs as the worker's OS user and could read files that user can read; stored documents
 * are encrypted and the key is not in its environment.
 */
@Component
class ExtractorLauncher {

    enum Status { COMPLETED, TIMED_OUT, CRASHED }

    private final Duration timeout;
    private final String maxHeap;
    private final String sandbox;
    private final boolean sandboxRequired;
    private final long maxOutputBytes;
    private volatile Boolean sandboxWorks;

    @org.springframework.beans.factory.annotation.Autowired
    ExtractorLauncher(@Value("${sdlc.documents.extraction.timeout:120s}") Duration timeout,
                      @Value("${sdlc.documents.extraction.max-heap:256m}") String maxHeap,
                      @Value("${sdlc.documents.extraction.sandbox:none}") String sandbox,
                      @Value("${sdlc.documents.extraction.sandbox-required:false}") boolean sandboxRequired,
                      @Value("${sdlc.documents.extraction.max-output-bytes:67108864}") long maxOutputBytes) {
        this.timeout = timeout;
        if (!maxHeap.matches("^[0-9]{2,5}m$")) {
            throw new IllegalStateException("sdlc.documents.extraction.max-heap must look like 256m");
        }
        this.maxHeap = maxHeap;
        if (!"none".equals(sandbox) && !"unshare-net".equals(sandbox)) {
            throw new IllegalStateException("sdlc.documents.extraction.sandbox must be none or unshare-net");
        }
        if (sandboxRequired && "none".equals(sandbox)) {
            throw new IllegalStateException("sdlc.documents.extraction.sandbox-required needs sandbox=unshare-net");
        }
        this.sandbox = sandbox;
        this.sandboxRequired = sandboxRequired;
        this.maxOutputBytes = maxOutputBytes;
    }

    ExtractorLauncher(Duration timeout, String maxHeap) {
        this(timeout, maxHeap, "none", false, 67_108_864L);
    }

    /** The OS-level wrapper put in front of the JVM command (empty without a sandbox). */
    List<String> sandboxPrefix() {
        if ("none".equals(sandbox)) {
            return List.of();
        }
        long cpuSeconds = Math.max(5, timeout.toSeconds() + 5);
        return List.of("prlimit", "--cpu=" + cpuSeconds, "--fsize=" + maxOutputBytes, "--nofile=256", "--core=0",
                "unshare", "--user", "--map-root-user", "--net");
    }

    /**
     * Verifies once that the sandbox can be created on this host. Returns true when no sandbox is configured, or when
     * it works. When it is configured but cannot be created, extraction is refused if the sandbox is required.
     */
    boolean sandboxAvailable() {
        if ("none".equals(sandbox)) {
            return true;
        }
        if (sandboxWorks == null) {
            List<String> probe = new ArrayList<>(sandboxPrefix());
            probe.add("true");
            try {
                Process process = new ProcessBuilder(probe).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                sandboxWorks = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
            } catch (IOException e) {
                sandboxWorks = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sandboxWorks = false;
            }
        }
        return sandboxWorks;
    }

    String sandbox() {
        return sandbox;
    }

    boolean sandboxRequired() {
        return sandboxRequired;
    }

    Status run(Path input, String type, Path outDir, Path workDir) throws IOException, InterruptedException {
        return run(ExtractorMain.class.getName(), List.of(input.toString(), type, outDir.toString()), workDir);
    }

    /** Runs {@code mainClass} with the extractor's restrictions (package-private for the isolation tests). */
    Status run(String mainClass, List<String> arguments, Path workDir) throws IOException, InterruptedException {
        if (!sandboxAvailable()) {
            if (sandboxRequired) {
                throw new IOException("extraction sandbox unavailable");
            }
        }
        Path tmp = Files.createDirectories(workDir.resolve("tmp"));
        List<String> command = new ArrayList<>(sandboxAvailable() ? sandboxPrefix() : List.of());
        command.add(ProcessHandle.current().info().command().orElse(System.getProperty("java.home") + "/bin/java"));
        command.add("-Xmx" + maxHeap);
        command.add("-Xss1m");
        command.add("-XX:+UseSerialGC");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Djava.awt.headless=true");
        command.add("-Djava.io.tmpdir=" + tmp);
        command.addAll(classpathArguments(mainClass));
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        builder.environment().clear();
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDir.resolve("extractor.log").toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = builder.start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return Status.TIMED_OUT;
        }
        return process.exitValue() == 0 ? Status.COMPLETED : Status.CRASHED;
    }

    /**
     * Inside the packaged Spring Boot jar the extractor class is in BOOT-INF/classes, so it is started through Boot's
     * PropertiesLauncher; from a plain class path (tests, IDE) it is started directly.
     */
    private static List<String> classpathArguments(String main) {
        // Absolute entries: the extractor runs in its own working directory (java -jar may give a relative path).
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        boolean bootJar = !classpath.contains(File.pathSeparator) && classpath.endsWith(".jar");
        if (bootJar) {
            return List.of("-cp", classpath, "-Dloader.main=" + main,
                    "org.springframework.boot.loader.launch.PropertiesLauncher");
        }
        return List.of("-cp", classpath, main);
    }
}
