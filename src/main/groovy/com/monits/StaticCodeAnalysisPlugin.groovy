package com.monits

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.tasks.compile.JavaCompile

class StaticCodeAnalysisPlugin implements Plugin<Project> {

    private final static String PMD_TOOL_VERSION = '5.3.3'
    private final static String GRADLE_VERSION = '2.5'
    private final static String CHECKSTYLE_VERSION = '6.7'
    private final static String FINDBUGS_ANNOTATIONS_VERSION = '3.0.0'
    private final static String FINDBUGS_TOOL_VERSION = '3.0.1'
    private final static String FINDBUGS_MONITS_VERSION = '0.2.0-SNAPSHOT'
    private final static String FB_CONTRIB_VERSION = '6.2.1'

    private String checkstyleRules;
    private List<String> pmdRules;
    private File findbugsExclude;

    private StaticCodeAnalysisExtension extension;

    private Project project;

    def void apply(Project project) {
        this.project = project

        String currentGradleVersion = project.gradle.gradleVersion

        if (currentGradleVersion < GRADLE_VERSION) {
            throw new GradleException('Gradle version should be ' + GRADLE_VERSION + ' or higher. '
                + 'Current version: ' + currentGradleVersion)

        }

        extension = new StaticCodeAnalysisExtension(project);
        project.extensions.add(StaticCodeAnalysisExtension.NAME, extension);

        project.configurations {
            archives {
                extendsFrom project.configurations.default
            }
            provided {
                dependencies.all { dep ->
                    project.configurations.default.exclude group: dep.group, module: dep.name
                }
            }
            compile.extendsFrom provided
        }

        project.afterEvaluate {

            checkstyleRules = extension.checkstyleRules;
            pmdRules = extension.pmdRules;
            findbugsExclude = extension.findbugsExclude;

            if (extension.findbugs) {
                findbugs();
            }

            if (extension.checkstyle) {
                checkstyle();
            }

            if (extension.pmd) {
                pmd();
            }

            if (extension.cpd) {
                cpd();
            }
        }
    }

    private void cpd() {
        project.plugins.apply 'pmd'

        project.task("cpd", type: CPDTask) {
            FileTree srcDir = project.fileTree("$project.projectDir/src/");
            srcDir.include '**/*.java'
            srcDir.exclude '**/gen/**'

            FileCollection collection = project.files(srcDir.getFiles());

            toolVersion = PMD_TOOL_VERSION
            inputFiles = collection
            outputFile = new File("$project.buildDir/reports/pmd/cpd.xml")
        }

        project.tasks.check.dependsOn project.tasks.cpd
    }

    private void pmd() {

        project.plugins.apply 'pmd'

        project.pmd {
             toolVersion = PMD_TOOL_VERSION
        }

        project.task("pmd", type: Pmd) {
            ignoreFailures = true

            source 'src'
            include '**/*.java'
            exclude '**/gen/**'

            reports {
                xml.enabled = true
                html.enabled = false
            }

            ruleSets = pmdRules

        }

        project.tasks.check.dependsOn project.tasks.pmd
    }

    private void checkstyle() {
        project.plugins.apply 'checkstyle'

        project.dependencies {
            checkstyle 'com.puppycrawl.tools:checkstyle:' + CHECKSTYLE_VERSION
        }

        boolean remoteLocation;
        File configSource;
        if (checkstyleRules.startsWith("http://")
                || checkstyleRules.startsWith("https://")) {

            remoteLocation = true;
            project.task("downloadCheckstyleXml") {
                File directory = new File("${project.rootDir}/config/checkstyle/");
                directory.mkdirs();
                configSource = new File(directory, "checkstyle.xml");
                ant.get(src: checkstyleRules, dest: configSource.getAbsolutePath());
            }
        } else {
            remoteLocation = false;
            configSource = new File(checkstyleRules);
            configSource.parentFile.mkdirs();
        }


        project.task("checkstyle", type: Checkstyle) {

            showViolations false

            if (remoteLocation) {
                dependsOn project.tasks.downloadCheckstyleXml
            }

            configFile configSource

            source 'src'
            include '**/*.java'
            exclude '**/gen/**'

            classpath = project.configurations.compile
        }

        project.tasks.check.dependsOn project.tasks.checkstyle
    }

    private void findbugs() {
        project.plugins.apply 'findbugs'

        project.dependencies {
            provided 'com.google.code.findbugs:annotations:' + FINDBUGS_ANNOTATIONS_VERSION

            findbugs 'com.google.code.findbugs:findbugs:' + FINDBUGS_TOOL_VERSION
            findbugs project.configurations.findbugsPlugins.dependencies

            // To keep everything tidy, we set these apart
            findbugsPlugins('com.monits:findbugs-plugin:' + FINDBUGS_MONITS_VERSION) {
                transitive = false
            }
            findbugsPlugins 'com.mebigfatguy.fb-contrib:fb-contrib:' + FB_CONTRIB_VERSION
        }

        project.task("findbugs", type: FindBugs) {
            dependsOn project.tasks.withType(JavaCompile)
            ignoreFailures = true
            effort = "max"

            FileTree tree = project.fileTree(dir: "${project.buildDir}/intermediates/classes/")

            tree.exclude '**/R.class' //exclude generated R.java
            tree.exclude '**/R$*.class' //exclude generated R.java inner classes
            tree.exclude '**/Manifest.class' //exclude generated Manifest.java
            tree.exclude '**/Manifest$*.class' //exclude generated Manifest.java inner classes
            tree.exclude '**/BuildConfig.class' //exclude generated BuildConfig.java
            tree.exclude '**/BuildConfig$*.class' //exclude generated BuildConfig.java inner classes
            classes = tree

            source 'src'
            include '**/*.java'
            exclude '**/gen/**'

            excludeFilter = findbugsExclude

            reports {
                xml {
                    destination "$project.buildDir/reports/findbugs/findbugs.xml"
                    xml.withMessages true
                }
            }

            pluginClasspath = project.configurations.findbugsPlugins

        }

        /*
         * For best results, Findbugs needs ALL classes, including Android's SDK,
         * but the task is created dynamically, so we need to set it afterEvaluate
         */
        project.tasks.withType(FindBugs).each {
            def t = project.tasks.findByName('mockableAndroidJar');
            if (t != null) {
                it.dependsOn project.tasks.findByName('mockableAndroidJar')
            }
            it.classpath = project.configurations.compile + project.configurations.testCompile +
                    project.fileTree(dir: "${project.buildDir}/intermediates/exploded-aar/", include: '**/*.jar') +
                    project.fileTree(dir: "${project.buildDir}/intermediates/", include: 'mockable-android-*.jar')
        }

        project.tasks.check.dependsOn project.tasks.findbugs
    }
}