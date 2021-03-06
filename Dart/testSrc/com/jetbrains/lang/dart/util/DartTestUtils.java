package com.jetbrains.lang.dart.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Computable;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkGlobalLibUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class DartTestUtils {

  public static final String BASE_TEST_DATA_PATH = findTestDataPath();

  private static String findTestDataPath() {
    final File f = new File("testData"); // launched from 'Dart-plugin' project
    if (f.isDirectory()) return f.getAbsolutePath();

    return PathManager.getHomePath() + "/contrib/Dart/testData";
  }

  public static void configureDartSdk(final @NotNull Module module) {
    final String dartSdkGlobalLibName;
    final DartSdk sdk = DartSdk.getGlobalDartSdk();
    if (sdk != null) {
      dartSdkGlobalLibName = sdk.getGlobalLibName();
    }
    else {
      dartSdkGlobalLibName = ApplicationManager.getApplication().runWriteAction(new Computable<String>() {
        public String compute() {
          return DartSdkGlobalLibUtil.createDartSdkGlobalLib(module.getProject(), BASE_TEST_DATA_PATH + "/sdk");
        }
      });
    }

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        DartSdkGlobalLibUtil.configureDependencyOnGlobalLib(module, dartSdkGlobalLibName);
      }
    });
  }
}
