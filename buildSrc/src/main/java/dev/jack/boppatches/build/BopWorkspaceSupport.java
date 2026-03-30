package dev.jack.boppatches.build;

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
import java.util.ArrayList;
import java.util.HashSet;
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
            Path overridesDir = settings.getSourceOverridesDir();
            if (Files.exists(overridesDir)) {
                copyTree(overridesDir, settings.getWorkspaceDir());
            }
            for (String deletion : readDeletions(settings)) {
                deleteRecursively(settings.getWorkspaceDir().resolve(deletion));
            }
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
        Path upstreamDir = settings.getUpstreamDir();
        Path overridesDir = settings.getSourceOverridesDir();
        Path deletionsFile = settings.getDeletionsFile();

        try {
            deleteRecursively(overridesDir);
            Files.createDirectories(overridesDir);
            Files.createDirectories(deletionsFile.getParent());

            Set<String> deletions = new TreeSet<>();
            Set<String> seenFiles = new HashSet<>();

            for (String root : settings.getWorkspaceRoots()) {
                Path workspaceRoot = workspaceDir.resolve(root);
                Path upstreamRoot = upstreamDir.resolve(root);
                Set<Path> paths = new TreeSet<>();
                paths.addAll(collectRelativeFiles(workspaceRoot));
                paths.addAll(collectRelativeFiles(upstreamRoot));

                for (Path relativePath : paths) {
                    Path workspaceFile = workspaceRoot.resolve(relativePath);
                    Path upstreamFile = upstreamRoot.resolve(relativePath);
                    String rel = root + "/" + normalize(relativePath);
                    seenFiles.add(rel);
                    if (Files.exists(workspaceFile) && Files.exists(upstreamFile)) {
                        if (!contentEquals(workspaceFile, upstreamFile)) {
                            copyFile(workspaceFile, overridesDir.resolve(rel));
                        }
                    } else if (Files.exists(workspaceFile)) {
                        copyFile(workspaceFile, overridesDir.resolve(rel));
                    } else if (Files.exists(upstreamFile)) {
                        deletions.add(rel);
                    }
                }
            }

            if (isDirectoryEmpty(overridesDir)) {
                Files.writeString(overridesDir.resolve(".gitkeep"), "", StandardCharsets.UTF_8);
            }

            Files.write(deletionsFile, deletions, StandardCharsets.UTF_8);
            logger.lifecycle(
                    "Captured {} override file(s) and {} deletion(s) from bop-src",
                    countFiles(overridesDir),
                    deletions.size());
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

    private static void runCommand(Path workingDirectory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new GradleException("Command failed (" + String.join(" ", command) + "):\n" + output.trim());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Failed to run command: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new GradleException("Failed to run command: " + String.join(" ", command), exception);
        }
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

    private static List<String> readDeletions(BopPatchSettings settings) {
        Path deletionsFile = settings.getDeletionsFile();
        if (!Files.exists(deletionsFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(deletionsFile, StandardCharsets.UTF_8);
            List<String> result = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        } catch (IOException exception) {
            throw new GradleException("Failed to read BOP deletions manifest", exception);
        }
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

    private static boolean isDirectoryEmpty(Path path) throws IOException {
        if (!Files.exists(path)) {
            return true;
        }
        try (var stream = Files.walk(path)) {
            return stream.noneMatch(candidate -> Files.isRegularFile(candidate));
        }
    }

    private static int countFiles(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        try (var stream = Files.walk(path)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(candidate -> !candidate.getFileName().toString().equals(".gitkeep"))
                    .count();
        }
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
}
