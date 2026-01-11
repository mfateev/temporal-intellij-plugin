package io.temporal.intellij.replay;

import io.temporal.testing.WorkflowReplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for replaying Temporal workflows.
 * This class is executed with the user's project classpath to access their workflow implementations.
 */
public class WorkflowReplayRunner {

    public static void main(String[] args) throws Exception {
        String workflowClassName = null;
        String historyFile = null;
        List<String> additionalClasses = new ArrayList<>();

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workflow-class":
                    workflowClassName = args[++i];
                    break;
                case "--history-file":
                    historyFile = args[++i];
                    break;
                case "--additional-class":
                    additionalClasses.add(args[++i]);
                    break;
            }
        }

        // Validate required arguments
        if (workflowClassName == null || workflowClassName.isEmpty()) {
            System.err.println("Error: --workflow-class is required");
            System.exit(1);
        }
        if (historyFile == null || historyFile.isEmpty()) {
            System.err.println("Error: --history-file is required");
            System.exit(1);
        }

        // Load workflow class
        Class<?> workflowClass;
        try {
            workflowClass = Class.forName(workflowClassName);
        } catch (ClassNotFoundException e) {
            System.err.println("Error: Could not find workflow class: " + workflowClassName);
            System.err.println("Make sure your project is compiled and the class is on the classpath.");
            System.exit(1);
            return;
        }

        // Load additional classes if specified
        Class<?>[] additional = additionalClasses.stream()
            .map(name -> {
                try {
                    return Class.forName(name);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Could not find class: " + name, e);
                }
            })
            .toArray(Class[]::new);

        // Load history JSON
        String historyJson;
        try {
            historyJson = Files.readString(Path.of(historyFile));
        } catch (Exception e) {
            System.err.println("Error: Could not read history file: " + historyFile);
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        // Print header
        System.out.println("============================================================");
        System.out.println("Temporal Workflow Replay");
        System.out.println("============================================================");
        System.out.println("Workflow Class: " + workflowClassName);
        System.out.println("History File:   " + historyFile);
        System.out.println();

        // Execute replay
        try {
            // Notify debugger that replay is starting
            DebugReplayMarker.onReplayStarted(historyFile, workflowClassName);

            long startTime = System.currentTimeMillis();
            WorkflowReplayer.replayWorkflowExecution(historyJson, workflowClass, additional);
            long duration = System.currentTimeMillis() - startTime;

            // Notify debugger that replay finished successfully
            DebugReplayMarker.onReplayFinished(historyFile);

            System.out.println();
            System.out.println("============================================================");
            System.out.println("REPLAY SUCCESSFUL (" + duration + "ms)");
            System.out.println("============================================================");
            System.out.println();
            System.out.println("The workflow implementation is compatible with the recorded history.");

        } catch (Exception e) {
            // Notify debugger that replay failed
            DebugReplayMarker.onReplayFailed(historyFile, e.getMessage());

            System.err.println();
            System.err.println("============================================================");
            System.err.println("REPLAY FAILED");
            System.err.println("============================================================");
            System.err.println();
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            e.printStackTrace();
            System.exit(1);
        }
    }
}
