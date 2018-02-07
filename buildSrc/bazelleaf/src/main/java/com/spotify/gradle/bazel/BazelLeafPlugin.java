package com.spotify.gradle.bazel;

import com.spotify.gradle.bazel.strategies.Factory;
import com.spotify.gradle.bazel.strategies.Strategy;
import com.spotify.gradle.bazel.tasks.BazelCleanTask;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.model.IdeaModule;
import org.gradle.plugins.ide.idea.model.Module;
import org.gradle.plugins.ide.idea.model.ModuleDependency;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public class BazelLeafPlugin implements Plugin<Project> {

    public void apply(final Project project) {
        project.getExtensions().create("bazel", BazelLeafConfig.class);

        project.afterEvaluate(BazelLeafPlugin::configurePlugin);
    }

    private static void configurePlugin(Project project) {
        final BazelLeafConfig.Decorated config = project.getExtensions().getByType(BazelLeafConfig.class).decorate(project);

        final Project rootProject = project.getRootProject();

        final AspectRunner aspectRunner = new AspectRunner(config);
        final Strategy strategy = Factory.buildStrategy(aspectRunner.getAspectResult("get_rule_kind.bzl", config.targetName).stream().findFirst().orElse("java_library"), config);
        /*
         * creating a Bazel-Build task
         */
        final Task bazelBuildTask = strategy.createBazelExecTask(project);

        /*
         * Adding build configurations
         */
        final Configuration defaultConfiguration = project.getConfigurations().create(Dependency.DEFAULT_CONFIGURATION);
        defaultConfiguration.setCanBeConsumed(true);
        defaultConfiguration.setCanBeResolved(true);

        /*
         * Adding build artifacts
         */
        strategy.getBazelArtifacts(aspectRunner, project, bazelBuildTask)
                .forEach(bazelPublishArtifact ->
                        defaultConfiguration.getOutgoing().getArtifacts().add(bazelPublishArtifact)
                );

        /*
         * Exclude bazel build directories for IntelliJ's indexing
         * Adds <exclude .../> to the root project iml file
         */
        if (!rootProject.getPlugins().hasPlugin(IdeaPlugin.class)) {
            IdeaPlugin rootIdeaPlugin = (IdeaPlugin) rootProject.getPlugins().apply("idea");

            Set<File> set = new HashSet<>();
            set.add(new File(config.workspaceRootFolder, "bazel-out"));
            set.add(new File(config.workspaceRootFolder, "build/bazel_aspects"));
            set.add(new File(config.buildOutputDir));
            set.addAll(rootIdeaPlugin.getModel().getModule().getExcludeDirs());
            // set is required to override the existing values
            rootIdeaPlugin.getModel().getModule().setExcludeDirs(set);
        }

        /*
         * Applying IDEA plugin, so IntelliJ will index the source files
         */
        IdeaPlugin ideaPlugin = (IdeaPlugin) project.getPlugins().apply("idea");
        final IdeaModule ideaModule = ideaPlugin.getModel().getModule();
        ideaModule.setSourceDirs(getSourceFoldersFromBazelAspect(rootProject, aspectRunner, config.targetName));

        /*
         * Creating a CLEAN task in the root project
         */
        if (rootProject.getTasksByName("bazelClean", false/*only search the root project*/).isEmpty()) {
            final BazelCleanTask bazelCleanTask = (BazelCleanTask) rootProject.task(Collections.singletonMap("type", BazelCleanTask.class), "bazelClean");
            bazelCleanTask.setBazelConfig(config);
            rootProject.getTasks().findByPath(":clean").dependsOn(bazelCleanTask);
        }

        /*
         * Adding tests
         */
        if (config.testTargetName != null && config.testTargetName.length() > 0) {
            final Strategy testStrategy = Factory.buildStrategy(aspectRunner.getAspectResult("get_rule_kind.bzl", config.testTargetName).stream().findFirst().orElse("java_test"), config);
            final Task bazelTestTask = testStrategy.createBazelExecTask(project);
            ideaModule.setTestSourceDirs(getSourceFoldersFromBazelAspect(rootProject, aspectRunner, config.testTargetName));
        }

        // HACK HACK COUGH COUGH
        // DANGER!! HARDCODED AREA BEGINS
        if (project.getName().equals("andlib")) {

            // add the project dependency to the default configuration
            Map projectMap = new HashMap();
            projectMap.put("path", ":andlib:innerandlib");
            Dependency projectDependency = project.getDependencies().project(projectMap);
            // doesn't seem to have any effect for bazel-leaf projects
            defaultConfiguration.getDependencies().add(projectDependency);

            // add the ModuleDependency for the orderEntry to the module directly to the IML file
            // this works at the CLI level using `./gradlew idea`
            // Android Studio ends up removing the added module when it refreshes
            // the IML file /bazel-leaf/andlib/andlib.iml
            ideaModule.getIml().getWhenMerged().add(new Action<Module>() {
                @Override
                public void execute(Module imlModule) {
                    System.out.println("[" + project.getName() + "] whenMerged called");
                    imlModule.getDependencies().add(new ModuleDependency("innerandlib", null));
                }
            });
        }
        // DANGER!! HARDCODED AREA ENDS

    }

    private static List<String> getModuleDepsFromBazel(AspectRunner aspectRunner, String targetName) {
        final Pattern pattern = Pattern.compile("^<target\\s*(.+)\\s*>$");
        return aspectRunner.getAspectResult("get_deps.bzl", targetName).stream()
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .collect(Collectors.toList());
    }

    private static Set<File> getSourceFoldersFromBazelAspect(Project rootProject, AspectRunner runner, String targetName) {
        final Map<File, String> packageByFolder = new HashMap<>();

        return runner.getAspectResult("get_source_files.bzl", targetName).stream()
                .map(File::new)
                //we need the root-project since the WORKSPACE file is there.
                .map(rootProject::file)
                .map(sourceFile -> {
                    File parent = sourceFile.getParentFile();
                    String packageInFolder = packageByFolder.computeIfAbsent(parent, fileNotUsedHere -> parseDeclaredPackage(sourceFile));
                    final String parentFullPath = parent.getPath();
                    //removing the package folders, we only want the root folder
                    return new File(parentFullPath.substring(0, parentFullPath.length() - packageInFolder.length()));
                })
                .distinct()
                .collect(Collectors.toSet());
    }

    //taken from https://github.com/bazelbuild/intellij/blob/master/aspect/tools/src/com/google/idea/blaze/aspect/PackageParser.java#L163
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w\\.]+);$");

    @Nullable
    private static String parseDeclaredPackage(File sourceFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher packageMatch = PACKAGE_PATTERN.matcher(line);
                if (packageMatch.find()) {
                    return packageMatch.group(1);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse java package for " + sourceFile, e);
        }
        return null;
    }
}
