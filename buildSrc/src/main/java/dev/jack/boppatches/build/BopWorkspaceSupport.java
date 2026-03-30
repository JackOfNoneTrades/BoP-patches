package dev.jack.boppatches.build;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

public final class BopWorkspaceSupport {

    private BopWorkspaceSupport() {}

    public static void ensureWorkspaceExists(Project project) {
        BopPatchSettings settings = BopPatchSettings.from(project);
        ensureUpstreamCheckout(project.getLogger(), settings);
        if (!Files.exists(settings.getWorkspaceDir())) {
            refreshWorkspace(project);
        }
    }

    public static void refreshWorkspace(Project project) {
        BopPatchSettings settings = BopPatchSettings.from(project);
        Logger logger = project.getLogger();
        ensureUpstreamCheckout(logger, settings);
        logger.lifecycle("Refreshing editable BOP workspace in {}", settings.getWorkspaceDir());
        deleteRecursively(settings.getWorkspaceDir());
        try {
            Files.createDirectories(settings.getWorkspaceDir());
            for (String root : settings.getWorkspaceRoots()) {
                Path upstreamRoot = settings.getUpstreamDir().resolve(root);
                if (Files.exists(upstreamRoot)) {
                    copyTree(upstreamRoot, settings.getWorkspaceDir().resolve(root));
                }
            }
            applyWorkspacePatch(logger, settings);
            verifyWorkspacePatchApplied(project, settings);
        } catch (IOException exception) {
            throw new GradleException("Failed to refresh BOP workspace", exception);
        }
    }

    public static void updateUpstream(Project project) {
        BopPatchSettings settings = BopPatchSettings.from(project);
        Logger logger = project.getLogger();
        Path upstreamDir = settings.getUpstreamDir();
        deleteRecursively(upstreamDir);
        ensureUpstreamCheckout(logger, settings);
    }

    public static void captureWorkspace(Project project) {
        BopPatchSettings settings = BopPatchSettings.from(project);
        Logger logger = project.getLogger();
        ensureWorkspaceExists(project);

        Path workspaceDir = settings.getWorkspaceDir();
        Path workspacePatchFile = settings.getWorkspacePatchFile();
        Path patchDir = settings.getPatchDir();

        try {
            Files.createDirectories(patchDir);
            deleteLegacyPatchArtifacts(settings);

            Path tempRoot = Files.createTempDirectory(settings.getMetadataRoot(), "workspace-diff-");
            try {
                String upstreamSnapshotName = "__upstream_snapshot__";
                String workspaceSnapshotName = "__workspace_snapshot__";
                Path tempUpstream = tempRoot.resolve(upstreamSnapshotName);
                Path tempWorkspace = tempRoot.resolve(workspaceSnapshotName);
                populatePatchSnapshot(settings.getUpstreamDir(), tempUpstream, settings.getWorkspaceRoots());
                populatePatchSnapshot(workspaceDir, tempWorkspace, settings.getWorkspaceRoots());

                CommandResult diffResult = runCommand(
                        tempRoot,
                        List.of(
                                "git",
                                "-c",
                                "core.safecrlf=false",
                                "-c",
                                "core.autocrlf=false",
                                "diff",
                                "--no-index",
                                "--binary",
                                "--full-index",
                                "--no-color",
                                "--src-prefix=upstream/",
                                "--dst-prefix=workspace/",
                                upstreamSnapshotName,
                                workspaceSnapshotName),
                        Set.of(0, 1));

                String workspacePatch = diffResult.exitCode() == 0
                        ? ""
                        : sanitizeWorkspacePatch(diffResult.output(), upstreamSnapshotName, workspaceSnapshotName);
                Files.writeString(workspacePatchFile, workspacePatch, StandardCharsets.UTF_8);
                logger.lifecycle(
                        "Captured {} changed file(s) from bop-src into {}",
                        countPatchEntries(workspacePatch),
                        workspacePatchFile);
            } finally {
                deleteRecursively(tempRoot);
            }
        } catch (IOException exception) {
            throw new GradleException("Failed to capture BOP workspace changes", exception);
        }
    }

    public static BopWorkspaceDiff diffWorkspace(Project project) {
        BopPatchSettings settings = BopPatchSettings.from(project);
        ensureWorkspaceExists(project);

        Set<String> overlayClassPrefixes = new TreeSet<>();
        Set<String> deletedClassPrefixes = new TreeSet<>();
        Set<String> overlayResources = new TreeSet<>();
        Set<String> deletedResources = new TreeSet<>();

        try {
            for (String root : settings.getJavaWorkspaceRoots()) {
                Path workspaceRoot = settings.getWorkspaceDir().resolve(root);
                Path upstreamRoot = settings.getUpstreamDir().resolve(root);
                Set<Path> paths = new TreeSet<>();
                paths.addAll(collectRelativeFiles(workspaceRoot));
                paths.addAll(collectRelativeFiles(upstreamRoot));

                for (Path relativePath : paths) {
                    Path workspaceFile = workspaceRoot.resolve(relativePath);
                    Path upstreamFile = upstreamRoot.resolve(relativePath);
                    String classPrefix = toClassPrefix(relativePath);

                    if (Files.exists(workspaceFile) && Files.exists(upstreamFile)) {
                        if (!contentEquals(workspaceFile, upstreamFile)) {
                            overlayClassPrefixes.add(classPrefix);
                        }
                    } else if (Files.exists(workspaceFile)) {
                        overlayClassPrefixes.add(classPrefix);
                    } else if (Files.exists(upstreamFile)) {
                        deletedClassPrefixes.add(classPrefix);
                    }
                }
            }

            for (String root : settings.getResourceWorkspaceRoots()) {
                Path workspaceRoot = settings.getWorkspaceDir().resolve(root);
                Path upstreamRoot = settings.getUpstreamDir().resolve(root);
                Set<Path> paths = new TreeSet<>();
                paths.addAll(collectRelativeFiles(workspaceRoot));
                paths.addAll(collectRelativeFiles(upstreamRoot));

                for (Path relativePath : paths) {
                    Path workspaceFile = workspaceRoot.resolve(relativePath);
                    Path upstreamFile = upstreamRoot.resolve(relativePath);
                    String resourcePath = normalize(relativePath);

                    if (Files.exists(workspaceFile) && Files.exists(upstreamFile)) {
                        if (!contentEquals(workspaceFile, upstreamFile)) {
                            overlayResources.add(resourcePath);
                        }
                    } else if (Files.exists(workspaceFile)) {
                        overlayResources.add(resourcePath);
                    } else if (Files.exists(upstreamFile)) {
                        deletedResources.add(resourcePath);
                    }
                }
            }
        } catch (IOException exception) {
            throw new GradleException("Failed to diff the BOP workspace", exception);
        }

        return new BopWorkspaceDiff(overlayClassPrefixes, deletedClassPrefixes, overlayResources, deletedResources);
    }

    public static void downloadOfficialJar(Project project) {
        BopPatchSettings settings = BopPatchSettings.from(project);
        Path officialJar = settings.getOfficialJarPath();
        if (Files.exists(officialJar)) {
            return;
        }
        Logger logger = project.getLogger();
        logger.lifecycle("Downloading official BOP jar from {}", settings.getOfficialJarUrl());
        try {
            Files.createDirectories(officialJar.getParent());
            HttpURLConnection connection = (HttpURLConnection) URI.create(settings.getOfficialJarUrl()).toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                    OutputStream output = new BufferedOutputStream(Files.newOutputStream(officialJar))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException exception) {
            throw new GradleException("Failed to download official BOP jar", exception);
        }
    }

    public static void prepareRunDirectory(
            Project project,
            Path runDirectory,
            Path sourceJar,
            String jarName,
            String managedProjectJarPrefix) {
        ensureWorkspaceExists(project);
        try {
            replaceBopJar(runDirectory, sourceJar, jarName, managedProjectJarPrefix, true);
        } catch (IOException exception) {
            throw new GradleException("Failed to prepare run directory for BOP", exception);
        }
    }

    public static void prepareMirroredObfuscatedRunDirectory(
            Project project,
            Path stagedObfuscatedRunDirectory,
            Path targetRunDirectory,
            Path officialBopJar,
            String officialJarName,
            String managedProjectJarPrefix) {
        ensureWorkspaceExists(project);
        try {
            mirrorModsDirectory(stagedObfuscatedRunDirectory.resolve("mods"), targetRunDirectory.resolve("mods"));
            replaceBopJar(targetRunDirectory, officialBopJar, officialJarName, managedProjectJarPrefix, false);
        } catch (IOException exception) {
            throw new GradleException("Failed to prepare the mirrored obfuscated run directory for BOP", exception);
        }
    }

    private static void ensureUpstreamCheckout(Logger logger, BopPatchSettings settings) {
        Path upstreamDir = settings.getUpstreamDir();
        if (Files.exists(upstreamDir.resolve(".git"))) {
            return;
        }

        deleteRecursively(upstreamDir);
        try {
            Files.createDirectories(settings.getMetadataRoot());
        } catch (IOException exception) {
            throw new GradleException("Failed to create .bop metadata directory", exception);
        }

        logger.lifecycle("Cloning BOP upstream branch {} from {}", settings.getUpstreamBranch(), settings.getUpstreamUrl());
        runCommand(
                settings.getProjectDir(),
                List.of(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        "--branch",
                        settings.getUpstreamBranch(),
                        settings.getUpstreamUrl(),
                        upstreamDir.toString()));
    }

    private static void applyWorkspacePatch(Logger logger, BopPatchSettings settings) throws IOException {
        Path workspacePatchFile = settings.getWorkspacePatchFile();
        if (!Files.exists(workspacePatchFile) || Files.size(workspacePatchFile) == 0L) {
            return;
        }

        logger.lifecycle("Applying committed BOP workspace patch {}", workspacePatchFile);
        runCommand(
                settings.getProjectDir(),
                List.of(
                        "git",
                        "apply",
                        "--whitespace=nowarn",
                        "--directory=" + settings.getProjectDir().relativize(settings.getWorkspaceDir()),
                        "-p1",
                        workspacePatchFile.toAbsolutePath().toString()));
    }

    private static void verifyWorkspacePatchApplied(Project project, BopPatchSettings settings) throws IOException {
        Path workspacePatchFile = settings.getWorkspacePatchFile();
        if (!Files.exists(workspacePatchFile) || Files.size(workspacePatchFile) == 0L) {
            return;
        }

        BopWorkspaceDiff diff = diffWorkspace(project);
        if (!hasWorkspaceChanges(diff)) {
            throw new GradleException(
                    "The committed workspace patch was applied, but no BOP workspace changes were detected afterward. "
                            + "This usually means git applied the patch relative to the wrong directory.");
        }
    }

    private static void populatePatchSnapshot(Path sourceRoot, Path targetRoot, List<String> workspaceRoots)
            throws IOException {
        Files.createDirectories(targetRoot);
        for (String root : workspaceRoots) {
            Path sourcePath = sourceRoot.resolve(root);
            if (Files.exists(sourcePath)) {
                copyTree(sourcePath, targetRoot.resolve(root));
            }
        }
    }

    private static void deleteLegacyPatchArtifacts(BopPatchSettings settings) throws IOException {
        deleteRecursively(settings.getPatchDir().resolve("source-overrides"));
        Files.deleteIfExists(settings.getPatchDir().resolve("deletions.txt"));
    }

    private static CommandResult runCommand(Path workingDirectory, List<String> command) {
        return runCommand(workingDirectory, command, Set.of(0));
    }

    private static CommandResult runCommand(Path workingDirectory, List<String> command, Set<Integer> allowedExitCodes) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = readFully(input);
            }
            int exitCode = process.waitFor();
            if (!allowedExitCodes.contains(exitCode)) {
                throw new GradleException("Command failed (" + String.join(" ", command) + "):\n" + output.trim());
            }
            return new CommandResult(exitCode, output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Failed to run command: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new GradleException("Failed to run command: " + String.join(" ", command), exception);
        }
    }

    private static String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int countPatchEntries(String patchText) {
        if (patchText.trim().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : patchText.split("\\R")) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasWorkspaceChanges(BopWorkspaceDiff diff) {
        return !diff.getOverlayClassPrefixes().isEmpty()
                || !diff.getDeletedClassPrefixes().isEmpty()
                || !diff.getOverlayResources().isEmpty()
                || !diff.getDeletedResources().isEmpty();
    }

    private static String sanitizeWorkspacePatch(
            String rawPatch,
            String upstreamSnapshotName,
            String workspaceSnapshotName) {
        if (rawPatch.trim().isEmpty()) {
            return "";
        }

        String upstreamPrefix = "upstream/" + upstreamSnapshotName + "/";
        String workspacePrefix = "workspace/" + workspaceSnapshotName + "/";
        StringBuilder builder = new StringBuilder(rawPatch.length());
        boolean firstLine = true;
        for (String line : rawPatch.split("\\R")) {
            if (line.startsWith("warning: ")) {
                continue;
            }
            String sanitizedLine = line;
            if (line.startsWith("diff --git ")
                    || line.startsWith("--- ")
                    || line.startsWith("+++ ")
                    || line.startsWith("Binary files ")
                    || line.startsWith("rename from ")
                    || line.startsWith("rename to ")
                    || line.startsWith("copy from ")
                    || line.startsWith("copy to ")) {
                sanitizedLine = sanitizedLine.replace(upstreamPrefix, "upstream/")
                        .replace(workspacePrefix, "workspace/");
            }
            if (!firstLine) {
                builder.append('\n');
            }
            builder.append(sanitizedLine);
            firstLine = false;
        }
        if (builder.length() == 0) {
            return "";
        }
        builder.append('\n');
        return builder.toString();
    }

    static Set<Path> collectRelativeFiles(Path root) throws IOException {
        Set<Path> files = new TreeSet<>();
        if (!Files.exists(root)) {
            return files;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> files.add(root.relativize(path)));
        }
        return files;
    }

    static boolean contentEquals(Path left, Path right) throws IOException {
        if (Files.size(left) != Files.size(right)) {
            return false;
        }
        return sha256(left).equals(sha256(right));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 should always be available", exception);
        }
    }

    static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String toClassPrefix(Path relativeJavaPath) {
        String normalized = normalize(relativeJavaPath);
        if (!normalized.endsWith(".java")) {
            throw new IllegalArgumentException("Expected a Java source file but got " + normalized);
        }
        return normalized.substring(0, normalized.length() - ".java".length());
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copyFile(file, to.resolve(from.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copyFile(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void mirrorModsDirectory(Path sourceModsDir, Path targetModsDir) throws IOException {
        deleteRecursively(targetModsDir);
        Files.createDirectories(targetModsDir);
        copyTree(sourceModsDir, targetModsDir);
    }

    private static void replaceBopJar(
            Path runDirectory,
            Path sourceJar,
            String jarName,
            String managedProjectJarPrefix,
            boolean removeManagedProjectJars) throws IOException {
        Path modsDir = runDirectory.resolve("mods");
        Files.createDirectories(modsDir);
        try (var stream = Files.list(modsDir)) {
            stream.filter(path -> {
                        String fileName = path.getFileName().toString();
                        boolean matchesBopJar = fileName.startsWith("BiomesOPlenty") && fileName.endsWith(".jar");
                        boolean matchesManagedProjectJar = removeManagedProjectJars
                                && fileName.startsWith(managedProjectJarPrefix)
                                && fileName.endsWith(".jar");
                        return matchesBopJar || matchesManagedProjectJar;
                    })
                    .forEach(BopWorkspaceSupport::deleteRecursively);
        }
        copyFile(sourceJar, modsDir.resolve(jarName));
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new GradleException("Failed to delete " + path, exception);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static final class CommandResult {

        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        private int exitCode() {
            return exitCode;
        }

        private String output() {
            return output;
        }
    }
}
