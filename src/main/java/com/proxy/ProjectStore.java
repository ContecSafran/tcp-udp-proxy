package com.proxy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores each proxy project as {@code <name>.json} inside a {@code projects}
 * folder next to the jar, and remembers the currently open project so it can be
 * reopened at startup.
 */
public final class ProjectStore {

    public static final String DEFAULT_PROJECT = "default";
    private static final String CURRENT_MARKER = ".current-project";
    private static final String OPEN_MARKER = ".open-projects";

    private ProjectStore() {
    }

    public static File projectsDir() {
        File dir = new File(ProxyConfig.baseDir(), "projects");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File projectFile(String name) {
        return new File(projectsDir(), sanitize(name) + ".json");
    }

    /** Removes characters that are invalid in file names. */
    public static String sanitize(String name) {
        if (name == null) {
            return DEFAULT_PROJECT;
        }
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isEmpty() ? DEFAULT_PROJECT : cleaned;
    }

    public static List<String> listProjects() {
        List<String> names = new ArrayList<>();
        File[] files = projectsDir().listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                names.add(n.substring(0, n.length() - ".json".length()));
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static boolean exists(String name) {
        return projectFile(name).exists();
    }

    public static void save(String name, ProxyConfig config) throws IOException {
        String safe = sanitize(name);
        Files.write(projectFile(safe).toPath(), config.toJson().getBytes(StandardCharsets.UTF_8));
    }

    public static ProxyConfig load(String name) throws IOException {
        byte[] bytes = Files.readAllBytes(projectFile(name).toPath());
        return ProxyConfig.fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Names of the projects that were open (one tab each) when the app last
     * ran. Falls back to the single legacy "current project" marker.
     */
    public static List<String> openProjects() {
        File marker = new File(ProxyConfig.baseDir(), OPEN_MARKER);
        if (marker.exists()) {
            try {
                List<String> names = new ArrayList<>();
                for (String line : Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8)) {
                    String name = line.trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
                if (!names.isEmpty()) {
                    return names;
                }
            } catch (IOException ignored) {
                // fall through to legacy marker
            }
        }
        return new ArrayList<>(Collections.singletonList(currentProject()));
    }

    public static void setOpenProjects(List<String> names) {
        File marker = new File(ProxyConfig.baseDir(), OPEN_MARKER);
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append(sanitize(name)).append(System.lineSeparator());
        }
        try {
            Files.write(marker.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // best effort
        }
    }

    public static String currentProject() {
        File marker = new File(ProxyConfig.baseDir(), CURRENT_MARKER);
        if (marker.exists()) {
            try {
                String name = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            } catch (IOException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_PROJECT;
    }

    public static void setCurrentProject(String name) {
        File marker = new File(ProxyConfig.baseDir(), CURRENT_MARKER);
        try {
            Files.write(marker.toPath(), sanitize(name).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
