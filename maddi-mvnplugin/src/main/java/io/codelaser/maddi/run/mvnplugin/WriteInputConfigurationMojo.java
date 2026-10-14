package io.codelaser.maddi.run.mvnplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.ProjectDependenciesResolver;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.resource.BuildFilesCapture;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = WriteInputConfigurationMojo.WRITE_INPUT_CONFIGURATION_GOAL,
        defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class WriteInputConfigurationMojo extends CommonMojo {
    public static final String WRITE_INPUT_CONFIGURATION_GOAL = "write-input-configuration";

    @Parameter(property = "outputFile", defaultValue = "${project.build.directory}/inputConfiguration.json")
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // Create output directory if it doesn't exist
            if (outputFile.getParentFile().mkdirs()) {
                getLog().info("Created directories for " + outputFile.getAbsolutePath());
            }
            InputConfiguration inputConfiguration = makeInputConfiguration().withBuildFiles(buildFiles());
            ObjectMapper mapper = JsonStreaming.objectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, inputConfiguration);
            getLog().info("Input configuration exported to: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to write input configuration", e);
        }
    }

    /*
     The files Maven read to configure this build, hashed now: every pom.xml under the reactor's top-level directory,
     .mvn/**, and module-info (BuildFiles.MAVEN_GLOBS); and the parent POMs read from disk outside it.
     */
    private BuildFiles buildFiles() {
        MavenProject top = getSession().getTopLevelProject();
        Path root = (top != null ? top : getProject()).getBasedir().toPath();
        List<Path> parents = new ArrayList<>();
        for (MavenProject parent = getProject().getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getFile() != null) parents.add(parent.getFile().toPath());
        }
        return buildFiles(root, parents);
    }

    static BuildFiles buildFiles(Path root, List<Path> parentPoms) {
        return BuildFilesCapture.capture(BuildFiles.MAVEN_PLUGIN, root, BuildFiles.MAVEN_GLOBS, parentPoms);
    }
}
