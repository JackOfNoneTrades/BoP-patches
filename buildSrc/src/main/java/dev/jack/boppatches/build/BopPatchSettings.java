package dev.jack.boppatches.build;

import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;

public final class BopPatchSettings {

    public static final String DEFAULT_UPSTREAM_URL = "https://github.com/Glitchfiend/BiomesOPlenty.git";
    public static final String DEFAULT_UPSTREAM_BRANCH = "BOP-1.7.10-2.1.x";
    public static final String DEFAULT_OFFICIAL_JAR_URL =
            "https://cdn.modrinth.com/data/HXF82T3G/versions/YoWpRk0h/BiomesOPlenty-1.7.10-2.1.0.2308-universal.jar";
    public static final String DEFAULT_OFFICIAL_JAR_NAME = "BiomesOPlenty-1.7.10-2.1.0.2308-universal.jar";

    private static final List<String> JAVA_WORKSPACE_ROOTS = List.of("src/main/java", "src/api/java");
    private static final List<String> RESOURCE_WORKSPACE_ROOTS = List.of("src/main/resources");
    private static final List<String> WORKSPACE_ROOTS = List.of("src/main/java", "src/api/java", "src/main/resources");

    private final Path projectDir;
    private final String upstreamUrl;
    private final String upstreamBranch;
    private final String officialJarUrl;
    private final String officialJarName;

    private BopPatchSettings(
            Path projectDir,
            String upstreamUrl,
            String upstreamBranch,
            String officialJarUrl,
            String officialJarName) {
        this.projectDir = projectDir;
        this.upstreamUrl = upstreamUrl;
        this.upstreamBranch = upstreamBranch;
        this.officialJarUrl = officialJarUrl;
        this.officialJarName = officialJarName;
    }

    public static BopPatchSettings from(Project project) {
        Path projectDir = project.getProjectDir().toPath();
        String upstreamUrl = stringProperty(project, "bopUpstreamUrl", DEFAULT_UPSTREAM_URL);
        String upstreamBranch = stringProperty(project, "bopUpstreamBranch", DEFAULT_UPSTREAM_BRANCH);
        String officialJarUrl = stringProperty(project, "bopOfficialJarUrl", DEFAULT_OFFICIAL_JAR_URL);
        String officialJarName = stringProperty(project, "bopOfficialJarName", DEFAULT_OFFICIAL_JAR_NAME);
        return new BopPatchSettings(projectDir, upstreamUrl, upstreamBranch, officialJarUrl, officialJarName);
    }

    private static String stringProperty(Project project, String name, String defaultValue) {
        Object value = project.findProperty(name);
        return value == null ? defaultValue : value.toString().trim();
    }

    public Path getProjectDir() {
        return projectDir;
    }

    public String getUpstreamUrl() {
        return upstreamUrl;
    }

    public String getUpstreamBranch() {
        return upstreamBranch;
    }

    public String getOfficialJarUrl() {
        return officialJarUrl;
    }

    public String getOfficialJarName() {
        return officialJarName;
    }

    public List<String> getWorkspaceRoots() {
        return WORKSPACE_ROOTS;
    }

    public List<String> getJavaWorkspaceRoots() {
        return JAVA_WORKSPACE_ROOTS;
    }

    public List<String> getResourceWorkspaceRoots() {
        return RESOURCE_WORKSPACE_ROOTS;
    }

    public Path getMetadataRoot() {
        return projectDir.resolve(".bop");
    }

    public Path getUpstreamDir() {
        return getMetadataRoot().resolve("upstream");
    }

    public Path getCacheDir() {
        return getMetadataRoot().resolve("cache");
    }

    public Path getOfficialJarPath() {
        return getCacheDir().resolve(officialJarName);
    }

    public Path getWorkspaceDir() {
        return projectDir.resolve("bop-src");
    }

    public Path getSourceOverridesDir() {
        return projectDir.resolve("patches").resolve("source-overrides");
    }

    public Path getDeletionsFile() {
        return projectDir.resolve("patches").resolve("deletions.txt");
    }
}
