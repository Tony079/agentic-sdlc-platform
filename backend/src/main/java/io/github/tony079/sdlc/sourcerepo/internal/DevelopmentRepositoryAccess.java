package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubException.Kind;

/**
 * DEVELOPMENT ADAPTER, not an integration: serves one bundled sample repository (classpath
 * {@code dev-repository/}) instead of GitHub, so the local prototype can run the repository, snapshot and index stages
 * without a GitHub App. It makes no network call and holds no credential.
 *
 * <p>Explicit limits, so that nothing here can be mistaken for real repository access:
 * <ul>
 *   <li>only repositories whose owner is {@value #OWNER} are served; any other owner is ACCESS_DENIED, exactly as an
 *       uninstalled GitHub App would be;</li>
 *   <li>every such repository has the same bundled content, whatever its name;</li>
 *   <li>selected only by {@code sdlc.repository.source=development}; production mode refuses to start with it.</li>
 * </ul>
 * The snapshot, limits, SHA check and indexing downstream are the real code paths.
 */
final class DevelopmentRepositoryAccess implements RepositoryAccess {

    static final String OWNER = "sdlc-dev";
    static final String DEFAULT_BRANCH = "main";
    private static final String ROOT = "dev-repository/";

    private final Map<String, byte[]> files;
    private final String headSha;

    DevelopmentRepositoryAccess() {
        this.files = load();
        this.headSha = sha1(files);
    }

    @Override
    public String source() {
        return "development";
    }

    String headSha() {
        return headSha;
    }

    @Override
    public Session open(String owner, String name) throws GitHubException {
        if (!OWNER.equalsIgnoreCase(owner)) {
            throw new GitHubException(Kind.ACCESS_DENIED, "REPO_ACCESS");
        }
        String fullName = OWNER + "/" + name;
        return new Session() {
            @Override
            public RepositoryInfo repository() {
                return new RepositoryInfo(stableId(fullName), fullName, DEFAULT_BRANCH);
            }

            @Override
            public String branchHead(String branch) throws GitHubException {
                if (!DEFAULT_BRANCH.equals(branch)) {
                    throw new GitHubException(Kind.UNAVAILABLE, "REPOSITORY_UNAVAILABLE");
                }
                return headSha;
            }

            @Override
            public InputStream tarball(String sha) throws GitHubException {
                if (!headSha.equals(sha)) {
                    throw new GitHubException(Kind.UNAVAILABLE, "REPOSITORY_UNAVAILABLE");
                }
                return new ByteArrayInputStream(archive(OWNER + "-" + name + "-" + sha.substring(0, 7)));
            }
        };
    }

    /** A stable positive id per repository name, standing in for GitHub's numeric repository id. */
    static long stableId(String fullName) {
        byte[] hash = digest("SHA-256").digest(fullName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        long id = 0;
        for (int i = 0; i < 7; i++) {
            id = (id << 8) | (hash[i] & 0xff);
        }
        return id + 1;
    }

    /** GitHub's tarball shape: a pax global header whose comment is the commit SHA, then files under one directory. */
    private byte[] archive(String prefix) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry global = new TarArchiveEntry("pax_global_header", TarConstants.LF_PAX_GLOBAL_EXTENDED_HEADER);
            global.addPaxHeader("comment", headSha);
            tar.putArchiveEntry(global);
            tar.putArchiveEntry(new TarArchiveEntry(prefix + "/"));
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(prefix + "/" + file.getKey(), true);
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("The development sample repository cannot be archived", e);
        }
        return bytes.toByteArray();
    }

    /** The files named in {@code dev-repository/MANIFEST}, in that order. */
    private static Map<String, byte[]> load() {
        Map<String, byte[]> loaded = new LinkedHashMap<>();
        for (String line : new String(resource("MANIFEST"), StandardCharsets.UTF_8).split("\n")) {
            String path = line.strip();
            if (path.isEmpty() || path.startsWith("#")) {
                continue;
            }
            if (path.startsWith("/") || path.contains("..")) {
                throw new IllegalStateException("Invalid path in the development sample repository manifest");
            }
            loaded.put(path, resource("files/" + path));
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("The development sample repository is empty");
        }
        return loaded;
    }

    private static byte[] resource(String name) {
        try (InputStream in = DevelopmentRepositoryAccess.class.getClassLoader().getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing development sample repository resource: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable development sample repository resource: " + name, e);
        }
    }

    /** A content hash in the shape of a commit SHA (40 hex characters): it changes whenever the sample changes. */
    private static String sha1(Map<String, byte[]> files) {
        MessageDigest digest = digest("SHA-1");
        files.forEach((path, content) -> {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(content);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
