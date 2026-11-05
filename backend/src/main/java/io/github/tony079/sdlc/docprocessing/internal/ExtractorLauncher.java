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
 * <p>This is process-level isolation inside the worker container. Network, file-system and system-call isolation of
 * a dedicated extractor container (non-root, read-only root, no network, seccomp) is the O-15 design and is <b>not
 * verified</b>: O-15 stays OPEN — BLOCKING for real client documents.
 */
@Component
class ExtractorLauncher {

    enum Status { COMPLETED, TIMED_OUT, CRASHED }

    private final Duration timeout;
    private final String maxHeap;

    ExtractorLauncher(@Value("${sdlc.documents.extraction.timeout:120s}") Duration timeout,
                      @Value("${sdlc.documents.extraction.max-heap:256m}") String maxHeap) {
        this.timeout = timeout;
        if (!maxHeap.matches("^[0-9]{2,5}m$")) {
            throw new IllegalStateException("sdlc.documents.extraction.max-heap must look like 256m");
        }
        this.maxHeap = maxHeap;
    }

    Status run(Path input, String type, Path outDir, Path workDir) throws IOException, InterruptedException {
        Path tmp = Files.createDirectories(workDir.resolve("tmp"));
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse(System.getProperty("java.home") + "/bin/java"));
        command.add("-Xmx" + maxHeap);
        command.add("-Xss1m");
        command.add("-XX:+UseSerialGC");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Djava.awt.headless=true");
        command.add("-Djava.io.tmpdir=" + tmp);
        command.addAll(classpathArguments());
        command.add(input.toString());
        command.add(type);
        command.add(outDir.toString());

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
    private static List<String> classpathArguments() {
        // Absolute entries: the extractor runs in its own working directory (java -jar may give a relative path).
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        String main = ExtractorMain.class.getName();
        boolean bootJar = !classpath.contains(File.pathSeparator) && classpath.endsWith(".jar");
        if (bootJar) {
            return List.of("-cp", classpath, "-Dloader.main=" + main,
                    "org.springframework.boot.loader.launch.PropertiesLauncher");
        }
        return List.of("-cp", classpath, main);
    }
}
