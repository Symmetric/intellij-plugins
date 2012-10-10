package com.google.jstestdriver.idea.debug;

import com.google.jstestdriver.idea.TestRunner;
import com.google.jstestdriver.idea.execution.JstdRunConfiguration;
import com.google.jstestdriver.idea.execution.JstdRunConfigurationVerifier;
import com.google.jstestdriver.idea.execution.JstdTestRunnerCommandLineState;
import com.google.jstestdriver.idea.server.ui.JstdToolWindowPanel;
import com.intellij.chromeConnector.connection.V8Connection;
import com.intellij.chromeConnector.debugger.ChromeDebuggerEngine;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.javascript.debugger.engine.JSDebugEngine;
import com.intellij.javascript.debugger.impl.DebuggableFileFinder;
import com.intellij.javascript.debugger.impl.JSDebugProcess;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.Consumer;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.chromium.sdk.JavascriptVm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.PrintWriter;

/**
 * @author Sergey Simonchik
 */
public class JstdDebugProgramRunner extends GenericProgramRunner {
  private static final String DEBUG_RUNNER_ID = JstdDebugProgramRunner.class.getSimpleName();

  @NotNull
  @Override
  public String getRunnerId() {
    return DEBUG_RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof JstdRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull Project project,
                                           @NotNull Executor executor,
                                           RunProfileState state,
                                           @Nullable RunContentDescriptor contentToReuse,
                                           @NotNull ExecutionEnvironment env) throws ExecutionException {
    JstdRunConfiguration runConfiguration = (JstdRunConfiguration) env.getRunProfile();
    if (runConfiguration.getRunSettings().isExternalServerType()) {
      throw new ExecutionException("Debug is available only for local browsers captured by a local JsTestDriver server.");
    }
    JstdRunConfigurationVerifier.checkJstdServerAndBrowserEnvironment(project, runConfiguration.getRunSettings(), true);
    return startSession(project, contentToReuse, env, executor, runConfiguration);
  }

  @Nullable
  private <Connection> RunContentDescriptor startSession(@NotNull Project project,
                                                         @Nullable RunContentDescriptor contentToReuse,
                                                         @NotNull ExecutionEnvironment env,
                                                         @NotNull Executor executor,
                                                         @NotNull JstdRunConfiguration runConfiguration) throws ExecutionException {
    JstdDebugBrowserInfo<Connection> debugBrowserInfo = JstdDebugBrowserInfo.build(runConfiguration.getRunSettings());
    if (debugBrowserInfo == null) {
      throw new ExecutionException("Can not find a browser that supports debugging.");
    }
    FileDocumentManager.getInstance().saveAllDocuments();

    final JSDebugEngine<Connection> debugEngine = debugBrowserInfo.getDebugEngine();
    if (!debugEngine.prepareDebugger(project)) return null;

    final String url;
    final Connection connection = debugEngine.openConnection(false);
    if (debugEngine instanceof ChromeDebuggerEngine) {
      url = "http://localhost:" + JstdToolWindowPanel.serverPort + debugBrowserInfo.getCapturedBrowserUrl();
    }
    else {
      url = null;
    }

    JstdTestRunnerCommandLineState runState = runConfiguration.getState(env, null, true);
    final ExecutionResult executionResult = runState.execute(executor, this);
    debugBrowserInfo.fixIfChrome(executionResult.getProcessHandler());

    File configFile = new File(runConfiguration.getRunSettings().getConfigFile());
    final DebuggableFileFinder fileFinder = JstdDebuggableFileFinderProvider.createFileFinder(project, configFile);

    XDebuggerManager xDebuggerManager = XDebuggerManager.getInstance(project);
    XDebugSession xDebugSession = xDebuggerManager.startSession(this, env, contentToReuse, new XDebugProcessStarter() {
      @NotNull
      public XDebugProcess start(@NotNull final XDebugSession session) {
        JSDebugProcess debugProcess = debugEngine.createDebugProcess(session, fileFinder, connection, url, executionResult);
        ((V8Connection)debugProcess.getBrowserConnection()).queueRequest(new Consumer<JavascriptVm>() {
          @Override
          public void consume(JavascriptVm javascriptVm) {
            resumeJstdClientRunning(executionResult.getProcessHandler());
          }
        });
        return debugProcess;
      }
    });


    return xDebugSession.getRunContentDescriptor();
  }

  private static void resumeJstdClientRunning(@NotNull ProcessHandler processHandler) {
    // process's input stream will be closed on process termination
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    PrintWriter writer = new PrintWriter(processHandler.getProcessInput());
    writer.println(TestRunner.DEBUG_SESSION_STARTED);
    writer.flush();
  }
}
