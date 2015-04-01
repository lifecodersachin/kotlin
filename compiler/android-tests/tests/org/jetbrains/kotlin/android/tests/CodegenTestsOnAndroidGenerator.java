/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.tests;

import com.google.common.collect.Lists;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.testFramework.UsefulTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection;
import org.jetbrains.kotlin.cli.common.output.outputUtils.OutputUtilsPackage;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.codegen.CodegenTestFiles;
import org.jetbrains.kotlin.codegen.GenerationUtils;
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime;
import org.jetbrains.kotlin.generators.tests.generator.TestGeneratorUtil;
import org.jetbrains.kotlin.idea.JetFileType;
import org.jetbrains.kotlin.psi.JetFile;
import org.jetbrains.kotlin.test.ConfigurationKind;
import org.jetbrains.kotlin.test.JetTestUtils;
import org.jetbrains.kotlin.utils.Printer;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodegenTestsOnAndroidGenerator extends UsefulTestCase {

    private final PathManager pathManager;
    private static final String testClassPackage = "org.jetbrains.kotlin.android.tests";
    private static final String testClassName = "CodegenTestCaseOnAndroid";
    private static final String baseTestClassPackage = "org.jetbrains.kotlin.android.tests";
    private static final String baseTestClassName = "AbstractCodegenTestCaseOnAndroid";
    private static final String generatorName = "CodegenTestsOnAndroidGenerator";

    private final Pattern packagePattern = Pattern.compile("package (.*)");

    private final List<String> generatedTestNames = Lists.newArrayList();

    public static void generate(PathManager pathManager) throws Throwable {
        new CodegenTestsOnAndroidGenerator(pathManager).generateOutputFiles();
    }

    private CodegenTestsOnAndroidGenerator(PathManager pathManager) {
        this.pathManager = pathManager;
    }

    private void generateOutputFiles() throws Throwable {
        prepareAndroidModule();
        generateAndSave();
    }

    private void prepareAndroidModule() throws IOException {
        System.out.println("Copying kotlin-runtime.jar in android module...");
        copyKotlinRuntimeJar();

        System.out.println("Check \"libs\" folder in tested android module...");
        File libsFolderInTestedModule = new File(pathManager.getLibsFolderInAndroidTestedModuleTmpFolder());
        if (!libsFolderInTestedModule.exists()) {
            libsFolderInTestedModule.mkdirs();
        }
    }

    private void copyKotlinRuntimeJar() throws IOException {
        FileUtil.copy(
                ForTestCompileRuntime.runtimeJarForTests(),
                new File(pathManager.getLibsFolderInAndroidTmpFolder() + "/kotlin-runtime.jar")
        );
    }

    private void generateAndSave() throws Throwable {
        System.out.println("Generating test files...");
        StringBuilder out = new StringBuilder();
        Printer p = new Printer(out);

        p.print(FileUtil.loadFile(new File("license/LICENSE.txt")));
        p.println("package " + testClassPackage + ";");
        p.println();
        p.println("import ", baseTestClassPackage, ".", baseTestClassName, ";");
        p.println();
        p.println("/* This class is generated by " + generatorName + ". DO NOT MODIFY MANUALLY */");
        p.println("public class ", testClassName, " extends ", baseTestClassName, " {");
        p.pushIndent();

        generateTestMethodsForDirectories(p, new File("compiler/testData/codegen/box"), new File("compiler/testData/codegen/boxWithStdlib"));

        p.popIndent();
        p.println("}");

        String testSourceFilePath =
                pathManager.getSrcFolderInAndroidTmpFolder() + "/" + testClassPackage.replace(".", "/") + "/" + testClassName + ".java";
        FileUtil.writeToFile(new File(testSourceFilePath), out.toString());
    }

    private void generateTestMethodsForDirectories(Printer p, File... dirs) throws IOException {
        FilesWriter holderMock = new FilesWriter(false);
        FilesWriter holderFull = new FilesWriter(true);

        for (File dir : dirs) {
            File[] files = dir.listFiles();
            Assert.assertNotNull("Folder with testData is empty: " + dir.getAbsolutePath(), files);
            processFiles(p, files, holderFull, holderMock);
        }

        holderFull.writeFilesOnDisk();
        holderMock.writeFilesOnDisk();
    }

    private class FilesWriter {
        private final boolean isFullJdk;

        public List<JetFile> files = new ArrayList<JetFile>();
        private KotlinCoreEnvironment environment;

        private FilesWriter(boolean isFullJdk) {
            this.isFullJdk = isFullJdk;
            environment = createEnvironment(isFullJdk);
        }

        private KotlinCoreEnvironment createEnvironment(boolean isFullJdk) {
            return isFullJdk
                    ? JetTestUtils.createEnvironmentWithFullJdk(myTestRootDisposable)
                    : JetTestUtils.createEnvironmentWithMockJdkAndIdeaAnnotations(myTestRootDisposable, ConfigurationKind.JDK_AND_ANNOTATIONS);
        }

        public boolean shouldWriteFilesOnDisk() {
            return files.size() > 300;
        }

        public void writeFilesOnDiskIfNeeded() {
            if (shouldWriteFilesOnDisk()) {
                writeFilesOnDisk();
            }
        }

        public void writeFilesOnDisk() {
            writeFiles(files);
            files = new ArrayList<JetFile>();
            environment = createEnvironment(isFullJdk);
        }

        private void writeFiles(List<JetFile> filesToCompile) {
            System.out.println("Generating " + filesToCompile.size() + " files...");
            OutputFileCollection outputFiles;
            try {
                outputFiles = GenerationUtils
                        .compileManyFilesGetGenerationStateForTest(filesToCompile.iterator().next().getProject(), filesToCompile).getFactory();
            }
            catch (Throwable e) {
                throw new RuntimeException(e);
            }

            File outputDir = new File(pathManager.getOutputForCompiledFiles());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            Assert.assertTrue("Cannot create directory for compiled files", outputDir.exists());

            OutputUtilsPackage.writeAllTo(outputFiles, outputDir);
        }
    }

    private void processFiles(
            @NotNull Printer printer,
            @NotNull File[] files,
            @NotNull FilesWriter holderFull,
            @NotNull FilesWriter holderMock)
            throws IOException
    {

        holderFull.writeFilesOnDiskIfNeeded();
        holderMock.writeFilesOnDiskIfNeeded();

        for (File file : files) {
            if (SpecialFiles.getExcludedFiles().contains(file.getName())) {
                continue;
            }
            if (file.isDirectory()) {
                File[] listFiles = file.listFiles();
                if (listFiles != null) {
                    processFiles(printer, listFiles, holderFull, holderMock);
                }
            }
            else if (!FileUtilRt.getExtension(file.getName()).equals(JetFileType.INSTANCE.getDefaultExtension())) {
                // skip non kotlin files
            }
            else {
                String text = FileUtil.loadFile(file, true);

                if (hasBoxMethod(text)) {
                    String generatedTestName = generateTestName(file.getName());
                    String packageName = file.getPath().replaceAll("\\\\|-|\\.|/", "_");
                    text = changePackage(packageName, text);

                    if (!file.getCanonicalPath().contains("boxWithStdlib")) {
                        CodegenTestFiles codegenFile = CodegenTestFiles.create(file.getName(), text, holderMock.environment.getProject());
                        holderMock.files.add(codegenFile.getPsiFile());
                    }
                    else {
                        CodegenTestFiles codegenFile = CodegenTestFiles.create(file.getName(), text, holderFull.environment.getProject());
                        holderFull.files.add(codegenFile.getPsiFile());
                    }

                    generateTestMethod(printer, generatedTestName, StringUtil.escapeStringCharacters(file.getPath()));
                }
            }
        }
    }

    private static boolean hasBoxMethod(String text) {
        return text.contains("fun box()");
    }

    private String changePackage(String testName, String text) {
        if (text.contains("package ")) {
            Matcher matcher = packagePattern.matcher(text);
            return matcher.replaceAll("package " + testName);
        }
        else {
            return "package " + testName + ";\n" + text;
        }
    }

    private static void generateTestMethod(Printer p, String testName, String packageName) {
        p.println("public void test" + testName + "() throws Exception {");
        p.pushIndent();
        p.println("invokeBoxMethod(\"" + packageName + "\", \"OK\");");
        p.popIndent();
        p.println("}");
        p.println();
    }

    private String generateTestName(String fileName) {
        String result = TestGeneratorUtil.escapeForJavaIdentifier(FileUtil.getNameWithoutExtension(StringUtil.capitalize(fileName)));

        int i = 0;
        while (generatedTestNames.contains(result)) {
            result += "_" + i++;
        }
        generatedTestNames.add(result);
        return result;
    }
}
