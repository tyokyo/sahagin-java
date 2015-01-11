package org.sahagin.runlib.runresultsgen;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.junit.Test;
import org.sahagin.TestBase;
import org.sahagin.share.CommonPath;
import org.sahagin.share.Config;
import org.sahagin.share.yaml.YamlConvertException;
import org.sahagin.share.yaml.YamlUtils;

// This test must be executed by Maven,
// or executed with the system property maven.home or set environment value M2_HOME
public class RunResultGenerateHookTest extends TestBase {

    private static File getTestCapturePath(File capturesDir, int counter) {
        return new File(capturesDir, counter + ".png");
    }

    private class MavenInvokeResult {
        private String invokerName;
        private final List<String> stdOuts = new ArrayList<String>(1024);
        private final List<String> stdErrs = new ArrayList<String>(1024);
        private boolean succeeded = false;

        private MavenInvokeResult(String invokerName) {
            this.invokerName = invokerName;
        }

        private void printStdOursAndErrs() {
            System.out.println("---- Maven Invoker [" + invokerName + "] std out ----");
            for (String stdOut : stdOuts) {
                System.out.println(stdOut);
            }
            System.err.println("---- Maven Invoker [" + invokerName + "] std error ----");
            for (String stdErr : stdErrs) {
                System.err.println(stdErr);
            }
            System.err.println("-------------------------------------------");
        }
    }

    // returns stdOut and stdErr pair
    // - output and error handler will be set to the request
    private MavenInvokeResult mavenInvoke(InvocationRequest request, String name) {
        final MavenInvokeResult result = new MavenInvokeResult(name);
        request.setOutputHandler(new InvocationOutputHandler() {

            @Override
            public void consumeLine(String arg) {
                result.stdOuts.add(arg);
            }
        });
        request.setErrorHandler(new InvocationOutputHandler() {

            @Override
            public void consumeLine(String arg) {
                result.stdErrs.add(arg);
            }
        });

        Invoker invoker = new DefaultInvoker();
        try {
            result.succeeded = (invoker.execute(request).getExitCode() == 0);
        } catch (MavenInvocationException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private void captureAssertion(String subDirName, String className, String methodName,
            File reportInputDir, int counterMax) {
        File capturesDir = new File(mkWorkDir(subDirName), "captures");
        File testMainCaptureDir
        = new File(CommonPath.inputCaptureRootDir(reportInputDir), className);
        for (int i = 1; i <= counterMax; i++) {
            assertFileByteContentsEquals(getTestCapturePath(capturesDir, i),
                    new File(testMainCaptureDir, String.format("%s/00%d.png", methodName, i)));
        }
        // TODO assert file for counterMax + 1 does not exist
    }

    private void testResultAssertion(String className, String methodName,
            File reportInputDir) throws YamlConvertException {
        File testMainResultDir
        = new File(CommonPath.runResultRootDir(reportInputDir), className);
        Map<String, Object> actualYamlObj = YamlUtils.load(new File(testMainResultDir, methodName));
        Map<String, Object> expectedYamlObj = YamlUtils.load(
                new File(new File(testResourceDir("expected"), className),  methodName));
        assertYamlEquals(expectedYamlObj, actualYamlObj);
    }

    private void generateTempJar() {
        // generate sahagin temp jar for test from the already generated class files
        InvocationRequest jarGenRequest = new DefaultInvocationRequest();
        jarGenRequest.setProfiles(Arrays.asList("sahagin-temp-jar-gen"));
        jarGenRequest.setGoals(Arrays.asList("jar:jar"));
        MavenInvokeResult jarGenResult = mavenInvoke(jarGenRequest, "jarGen");
        if (!jarGenResult.succeeded) {
            jarGenResult.printStdOursAndErrs();
            fail("fail to generate jar");
        }
    }

    private Pair<MavenInvokeResult, Config> invokeChildTest(String subDirName, String additionalProfile)
            throws IOException {
        // set up working directory
        clearWorkDir(subDirName);
        File workDir = mkWorkDir(subDirName).getAbsoluteFile();
        Config conf = new Config(workDir);
        conf.setTestDir(new File(workDir, "src/test/java"));
        conf.setRunTestOnly(true);
        YamlUtils.dump(conf.toYamlObject(), new File(workDir, "sahagin.yml"));
        FileUtils.copyFile(new File("pom.xml"), new File(workDir, "pom.xml"));
        FileUtils.copyDirectory(testResourceDir(subDirName + "/src"), new File(workDir, "src"));
        FileUtils.copyDirectory(testResourceDir("expected/captures"), new File(workDir, "captures"));

        // execute test
        InvocationRequest testRequest = new DefaultInvocationRequest();
        testRequest.setGoals(Arrays.asList("clean", "test"));
        if (additionalProfile == null) {
            testRequest.setProfiles(Arrays.asList("sahagin-jar-test"));
        } else {
            testRequest.setProfiles(Arrays.asList("sahagin-jar-test", additionalProfile));
        }
        String jarPathOpt = "-Dsahagin.temp.jar="
                + new File("target/sahagin-temp.jar").getAbsolutePath();
        testRequest.setMavenOpts(jarPathOpt);
        testRequest.setBaseDirectory(workDir);
        MavenInvokeResult testResult = mavenInvoke(testRequest, "test");

        return Pair.of(testResult, conf);
    }

    @Test
    public void java6() throws MavenInvocationException, YamlConvertException, IOException {
        String subDirName = "java6";
        generateTempJar();
        Pair<MavenInvokeResult, Config> pair = invokeChildTest(subDirName, null);

        // check test output
        File reportInputDir = pair.getRight().getRootBaseReportInputDataDir();
        try {
            String normalTest = "normal.TestMain";
            captureAssertion(subDirName, normalTest, "noTestDocMethodFailTest", reportInputDir, 1);
            captureAssertion(subDirName, normalTest, "stepInCaptureTest", reportInputDir, 4);
            captureAssertion(subDirName, normalTest, "successTest", reportInputDir, 2);
            captureAssertion(subDirName, normalTest, "testDocMethodFailTest", reportInputDir, 1);
            testResultAssertion(normalTest, "noTestDocMethodFailTest", reportInputDir);
            testResultAssertion(normalTest, "stepInCaptureTest", reportInputDir);
            testResultAssertion(normalTest, "successTest", reportInputDir);
            testResultAssertion(normalTest, "testDocMethodFailTest", reportInputDir);

            String extendsTest = "extendstest.ExtendsTest";
            captureAssertion(subDirName, extendsTest, "extendsTest", reportInputDir, 5);
            testResultAssertion(extendsTest, "extendsTest", reportInputDir);

            String implementsTest = "implementstest.ImplementsTest";
            captureAssertion(subDirName, implementsTest, "implementsTest", reportInputDir, 3);
            testResultAssertion(implementsTest, "implementsTest", reportInputDir);
        } catch (AssertionError e) {
            pair.getLeft().printStdOursAndErrs();
            throw e;
        }
    }

    public void java8() throws IOException, YamlConvertException {
        String subDirName = "java8";
        generateTempJar();
        Pair<MavenInvokeResult, Config> pair = invokeChildTest(subDirName, "java8-compile");

     // check test output
        File reportInputDir = pair.getRight().getRootBaseReportInputDataDir();
        try {
            String lambdaTest = "lambda.TestMain";
            testResultAssertion(lambdaTest, "streamApiCall", reportInputDir);
        } catch (AssertionError e) {
            pair.getLeft().printStdOursAndErrs();
            throw e;
        }
    }

}
