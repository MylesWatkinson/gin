package gin.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.io.Files;
import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.pmw.tinylog.Logger;

import gin.Patch;
import gin.SourceFile;
import gin.test.ExternalTestRunner;
import gin.test.InternalTestRunner;
import gin.test.UnitTest;
import gin.test.UnitTestResult;
import gin.test.UnitTestResultSet;

/**
 * Handy class for mutating and running tests on mutated code.
 *
 * Each subclass can access project data through the methodData structure.
 *
 * Required input: projectDirectory, methodFile, projectName (Gradle)
 * Required input: projectDirectory, methodFile, projectName, mavenHome (Maven)
 * Required input: projectDirectory, methodFile, classPath (otherwise)
 * methodFile is assumed to be the output file of gin.util.Profiler, though only Method and Tests columns are required
 * Contains an option of running tests in a separate jvm. 
 *
 */
public abstract class Sampler implements Serializable {

    /*============== Required  ==============*/

    private static final long serialVersionUID = -567446476194791424L;

    @Argument(alias = "d", description = "Project directory, required", required = true)
    protected File projectDirectory;
    
    @Argument(alias = "m", description = "Method file, required", required = true)
    protected File methodFile;
    
    /*============== Optional (required only for certain types of projects, ignored otherwise)  ==============*/

    @Argument(alias = "p", description = "Project name, required for maven and gradle projects")
    protected String projectName = null;
    
    @Argument(alias = "c", description = "Classpath, required for non-maven and non-gradle projects")
    protected String classPath = null;
    
    // Only needed for extracting dependencies using mvn in Project class
    @Argument(alias = "h", description = "Path to maven bin directory, e.g. /usr/local, required for maven projects")
    protected File mavenHome = null;

    /*============== Optional (setup)  ==============*/

    @Argument(alias = "o", description = "Output CSV file")
    protected File outputFile = new File("sampler_results.csv");
    protected CSVWriter outputFileWriter;

    @Argument(alias = "to", description = "Output file for storing the execution time")
    protected File timingOutputFile = new File("sampler_timing.csv");

    @Argument(alias = "x", description = "Timeout in milliseconds")
    protected Long timeoutMS = 10000L;

    @Argument(alias = "r", description = "Repeat each test r times")
    protected Integer reps = 1;

    @Argument(alias = "nm", description = "Number of methods from the method data to sample from. 0 for all.")
    protected Integer numberOfMethodsToSample = 0;

    @Argument(alias = "j", description = "Run tests in a separate jvm")
    protected Boolean inSubprocess = false;
    
    @Argument(alias = "jj", description = "Run every repetition in a new jvm. Includes option '-j'")
    protected Boolean eachRepetitionInNewSubprocess = false; 
    
    @Argument(alias = "J", description = "Run every test in a new jvm. Includes options '-j' and '-jj'")
    protected Boolean eachTestInNewSubprocess = false;  
    
    @Argument(alias = "ff", description = "Fail fast. "
            + "If set to true, the tests will stop at the first failure and the next patch will be executed. "
            + "You probably don't want to set this to true for Automatic Program Repair.")
    protected Boolean failFast = false;
    
    // Unused at the moment, thus commented out
    //@Argument(alias = "b", description = "Buffer time for test cases to be run on modified code, set only if > -1 and when -inSubprocess is false")
    //private Integer bufferTimeMS = -1;  // test case timeout: timeout on unmodified code + bufferTime

    /*============== Other  ==============*/

    // This will only be instantiated with Gradle and Maven projects, used for getting classpath
    protected Project project = null; 

    // Arguments used in the method file
    private static final String TEST_SEPARATOR = ",";
    private static final String METHOD_SEPARATOR = ".";
        
    // Used for writing data to outputFile
    private static final String[] OUT_HEADER = {"PatchIndex", "PatchSize", "Patch", "MethodIndex", "TestIndex", "UnitTest", "RepNumber",
            "PatchValid", "PatchCompiled", "TestPassed", "TestExecutionTime(ns)", "TestCPUTime(ns)",
            "TestTimedOut", "TestExceptionType", "TestExceptionMessage", "AssertionExpectedValue", 
            "AssertionActualValue", "NoOp", "EditsValid"};
    private static final Integer DEFAULT_ID = 0; // default id for MethodIndex

    private int patchCount = 0;

    /*============== Structures holding all project data  ==============*/

    protected List<TargetMethod> methodData = new ArrayList<>();
    
    protected Set<UnitTest> testData = new LinkedHashSet<>();


    /*============== Constructors ==============*/

    public Sampler(String[] args) {

        Args.parseOrExit(this, args);
        printCommandlineArguments();
        setUp();
    }

    public Sampler(File projectDir, File methodFile) {
        
        this.projectDirectory = projectDir;
        this.methodFile = methodFile;
    }

    protected void setUp() {
        
        if (this.classPath == null) {
            this.project = new Project(projectDirectory, projectName);
            if (mavenHome != null) {
                this.project.setMavenHome(mavenHome);
            } else if(this.project.isMavenProject()) {
                // In case it is indeed a Maven project, tries to find maven in
                // the System's evironment variables and set the path to it.
                Logger.info("I'm going to try and find your maven home, but make sure to set mavenHome for maven projects in the future.");
                this.project.setMavenHome(MavenUtils.findMavenHomeFile());
            }
            Logger.info("Calculating classpath..");
            this.classPath = project.classpath();
            Logger.info("Classpath: " + this.classPath);
        }
        this.methodData = processMethodFile();
        if (methodData.isEmpty()) {
            Logger.info("No methods to process.");
            System.exit(0);
        } else if (numberOfMethodsToSample > 0 && this.numberOfMethodsToSample < this.methodData.size()) {
            this.methodData = this.methodData.subList(0, this.numberOfMethodsToSample);
        }
    }

    /*============== the following is used to store method information  ==============*/

    protected static class TargetMethod {

        private File source;
        private String className;
        
        private String methodName;
        private List<UnitTest> tests;

        private Integer methodID;

        protected TargetMethod(File source, String className, String methodName, List<UnitTest> tests, Integer methodID) {
            this.source = source;
            this.className = className;
            this.methodName = methodName;
            this.tests = tests;
            this.methodID = methodID;
        }

        public File getFileSource() {
            return source;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public List<UnitTest> getGinTests() {
            return tests;
        }

        protected Integer getMethodID() {
            return methodID;
        }

        @Override
        public String toString() {
            return className + "." + methodName;
        }
        
        @Override
        public boolean equals(Object obj) {
                return ( (obj instanceof TargetMethod) && methodID.equals(((TargetMethod)obj).methodID) );
        }
        
        @Override
        public int hashCode() {
            return methodID.hashCode();
        }
    }
    
    /*============== sampleMethods calls the hook abstract method  ==============*/
    protected final void sampleMethods(){
        try {
            StopWatch stopWatch = StopWatch.createStarted();
            this.sampleMethodsHook();
            stopWatch.stop();
            if (this.timingOutputFile != null){
                FileUtils.forceMkdirParent(this.timingOutputFile);
                FileUtils.writeStringToFile(this.timingOutputFile, Long.toString(stopWatch.getTime()), Charset.defaultCharset());
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            this.close();
        }
    }

    /*============== sampleMethodsHook should be overriden in each subclass of Sampler  ==============*/

    protected abstract void sampleMethodsHook(); 

    /*============== methods for running tests  ==============*/
    
    protected UnitTestResultSet testEmptyPatch(String targetClass, Collection<UnitTest> tests, SourceFile sourceFile) {

        Logger.debug("Testing the empty patch..");

        patchCount ++;

        UnitTestResultSet resultSet = null;

        if (!inSubprocess && !eachRepetitionInNewSubprocess && !eachTestInNewSubprocess) {
            resultSet = testPatchInternally(targetClass, new ArrayList<>(tests), new Patch(sourceFile));
        } else {
            resultSet = testPatchInSubprocess(targetClass, new ArrayList<>(tests), new Patch(sourceFile));
        }

        if (!resultSet.allTestsSuccessful()) {
            if (!resultSet.getCleanCompile()) {
                Logger.error("Original code failed to compile");
            } else {
                Logger.error("Original code failed to pass unit tests");
                Logger.error("Valid: " + resultSet.getValidPatch());
                Logger.error("Compiled: " + resultSet.getCleanCompile());
                Logger.error("Failed results follow: ");
                List<UnitTestResult> failingTests = resultSet.getResults().stream()
                        .filter(res -> !res.getPassed())
                        .collect(Collectors.toList());
                for (UnitTestResult failedResult: failingTests) {
                    Logger.error(failedResult);
                }
            }
        } else {
            Logger.debug("Successfully passed all tests on the unmodified code.");
        }

        //// Set timeout for test cases to: max time on original code + bufferTime, if bufferTime > -1
        //if ((!inSubprocess) && (bufferTimeMS > -1)) {
        //    Map<UnitTest, long[]> runtimes = resultSet.getUnitTestTimes();
        //    if (runtimes.size() > 0) {
        //        for (UnitTest test: runtimes.keySet()){
        //            long[] values = runtimes.get(test);
        //            long maxvalue = 0;
        //            for(long newmax : values) {
        //                if (newmax > maxvalue) {
        //                    maxvalue = newmax;
        //                }
        //            }
        //            test.setTimeoutMS(maxvalue / 1000000 + bufferTimeMS);
        //        } 
        //    } else {
        //        Logger.error("Error extracting unit test case runtimes.");
        //        System.exit(-1);
        //    }
        //}

        return resultSet;
    }

    protected UnitTestResultSet testPatch(String targetClass, List<UnitTest> tests, Patch patch) {
        
        Logger.debug("Testing patch: " + patch);

        patchCount ++;

        UnitTestResultSet resultSet = null;

        if (!inSubprocess && !eachTestInNewSubprocess) {
            resultSet = testPatchInternally(targetClass, tests, patch);
        } else {
            resultSet = testPatchInSubprocess(targetClass, tests, patch);
        }
        
        return resultSet;

    }

    private UnitTestResultSet testPatchInternally(String targetClass, List<UnitTest> tests, Patch patch) {

        InternalTestRunner testRunner = new InternalTestRunner(targetClass, classPath, tests, failFast);
        return testRunner.runTests(patch, reps);
    }

    private UnitTestResultSet testPatchInSubprocess(String targetClass, List<UnitTest> tests, Patch patch) {

        ExternalTestRunner testRunner = new ExternalTestRunner(targetClass, classPath, tests, eachRepetitionInNewSubprocess, eachTestInNewSubprocess, failFast);

        UnitTestResultSet results = null;

        try {
            results = testRunner.runTests(patch, reps);
        } catch (IOException e) {
            Logger.error(e);
            System.exit(-1);
        } catch (InterruptedException e) {
            Logger.error(e);
            System.exit(-1);
        }

        return results;

    }

    /*============== the following process input arguments  ==============*/

    private void printCommandlineArguments() {

        try {
            Field[] fields = Sampler.class.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(Argument.class)) {
                    Argument argument = field.getAnnotation(Argument.class);
                    String name = argument.description();
                    Object value = field.get(this);
                    if (value instanceof File) {
                        Logger.info(name + ": " + ((File) value).getPath());
                    } else if (value == null) {
                        Logger.info(name + ": ");
                    } else {
                        Logger.info(name + ": " + value);
                    }
                }
            }
        } catch (IllegalAccessException e) {
            Logger.error("Error printing commandline arguments.");
            System.exit(-1);
        }

    }

    // only Tests and Method fields are required, be careful thus if supplying files with multiple projects, this is not yet handled
    private List<TargetMethod> processMethodFile() {

        try {
            CSVReaderHeaderAware reader = new CSVReaderHeaderAware(new FileReader(methodFile));
            Map<String, String> data = reader.readMap();
            if ( (!data.containsKey("Method")) || (!data.containsKey("Tests")) ) {
                throw new ParseException("Both \"Method\" and \"Tests\" fields are required in the method file.", 0);
            }

            List<TargetMethod> methods = new ArrayList<>();

            Integer idx = 0;

            while (data != null) {

                String[] tests = data.get("Tests").split(TEST_SEPARATOR);
                List<UnitTest> ginTests = new ArrayList();        
                for (String test : tests) {
                    UnitTest ginTest = null;        
                    ginTest = UnitTest.fromString(test);
                    ginTest.setTimeoutMS(timeoutMS);
                    ginTests.add(ginTest);
                    testData.add(ginTest);
                }

                String method = data.get("Method");

                String className = StringUtils.substringBefore(method, "("); // method arguments can have dots, so need to get data without arguments first
                className = StringUtils.substringBeforeLast(className, METHOD_SEPARATOR);
                
                File source = (project != null) ? project.findSourceFile(className) : findSourceFile(className);
                if ( (source == null) || (!source.isFile()) ) {
                    throw new FileNotFoundException("Cannot find source for class: " + className);
                }

                // now using fully qualified names...
                //String methodName = StringUtils.substringAfterLast(method, className + METHOD_SEPARATOR);
                
                idx++;
                Integer methodID = (data.containsKey("MethodIndex")) ? Integer.valueOf(data.get("MethodIndex")) : idx;

                TargetMethod targetMethod = new TargetMethod(source, className, method, ginTests, methodID);

                if (methods.contains(targetMethod)) {
                    throw new ParseException("Duplicate method IDs in the input file.", 0);
                }
                methods.add(targetMethod);

                data = reader.readMap();
            }        
            reader.close();

            return methods;

        } catch (CsvValidationException e) {
            Logger.error(e.getMessage());
            Logger.trace(e);
        } catch (ParseException e) {
            Logger.error(e.getMessage());
            Logger.trace(e);
        } catch (FileNotFoundException e) {
            Logger.error(e.getMessage());
            Logger.trace(e);
        } catch (IOException e) {
            Logger.error("Error reading method file: " + methodFile);
            Logger.trace(e);
        }
        return new ArrayList<>();

    }

    // used for non-maven and non-gradle projects only
    private File findSourceFile(String className) {

        String pathToSource = className.replace(".", File.separator) + ".java";
        String filename;
        File moduleDir;
        if (className.contains(".")) {
            filename = StringUtils.substringAfterLast(pathToSource, File.separator);
            moduleDir = new File(projectDirectory, StringUtils.substringBeforeLast(pathToSource, File.separator));
        } else {
            filename = pathToSource;
            moduleDir = projectDirectory;
        }
        if (!moduleDir.isDirectory()) {
            return null;
        }        
        File[] files = moduleDir.listFiles((dir, name) -> name.equals(filename));
        if (files.length == 0) {
            return null;
        }
        if (files.length > 1) {
            Logger.error("Two files found with the same name in: " + projectDirectory);
            return null;
        }
        return files[0];

    }

    /*============== the following write results to the output file ==============*/


    protected void writeHeader() {

        String parentDirName = outputFile.getParent();
        if (parentDirName == null) {
                parentDirName = "."; // assume outputFile is in the current directory
        }
        File parentDir = new File(parentDirName);
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        try {
            outputFileWriter = new CSVWriter(new FileWriter(outputFile));
            outputFileWriter.writeNext(OUT_HEADER);
        } catch (IOException e) {
            Logger.error(e, "Exception writing header to the output file: " + outputFile.getAbsolutePath());
            Logger.trace(e);
            System.exit(-1);
        }
    }

    protected void writeResults(UnitTestResultSet resultSet) {
        
        writeResults(resultSet, patchCount, DEFAULT_ID);
    }

    protected void writeResults(UnitTestResultSet resultSet, Integer methodID) {
        
        writeResults(resultSet, patchCount, methodID);
    }

    protected void writeResults(UnitTestResultSet testResultSet, int patchCount, Integer methodID) {
        int testIdx = 1;
            for (UnitTestResult result : testResultSet.getResults()) {
            writeResult(patchCount, testIdx++, testResultSet.getPatch(), testResultSet.getValidPatch(), testResultSet.getCleanCompile(), result, methodID, testResultSet.getNoOp(), testResultSet.getEditsValid());
        }
    }

    private void writeResult(int patchCount, int testNameIdx, Patch patch, boolean patchValid, boolean compiledOK, UnitTestResult testResult, Integer methodID, boolean patchNoOp, List<Boolean> editsValid) {

        String patchIndex = Integer.toString(patchCount);
        String methodIndex = Integer.toString(methodID);
        String testIndex = Integer.toString(testNameIdx);
        String testName = testResult.getTest().toString();
        String rep = Integer.toString(testResult.getRepNumber());
        String patchSize = Integer.toString(patch.size());
        String patchDetails = patch.toString();
        String valid = Boolean.toString(patchValid);
        String cleanCompile = Boolean.toString(compiledOK);
        String testPassed = Boolean.toString(testResult.getPassed());
        String testExecutionTime = Long.toString(testResult.getExecutionTime());
        String testCPUTime = Long.toString(testResult.getCPUTime());
        String testTimedOut = Boolean.toString(testResult.getTimedOut());
        String testExceptionType = testResult.getExceptionType();
        String testExceptionMessage = testResult.getExceptionMessage();
        String testAssertionExpectedValue = testResult.getAssertionExpectedValue();
        String testAssertionActualValue = testResult.getAssertionActualValue();
        String noOp = Boolean.toString(patchNoOp);
        StringBuilder editsValidStr = new StringBuilder();
        for (Boolean b : editsValid) {
            editsValidStr.append(b ? 1 : 0);
        }

        String[] entry = {
                patchIndex,
                patchSize,
                patchDetails,
                methodIndex,
                testIndex,
                testName,
                rep,
                valid,
                cleanCompile,
                testPassed,
                testExecutionTime,
                testCPUTime,
                testTimedOut,
                testExceptionType,
                testExceptionMessage,
                testAssertionExpectedValue,
                testAssertionActualValue,
                noOp,
                editsValidStr.toString()
        };

        outputFileWriter.writeNext(entry);
    }

    protected void close() {
        try {
            if(this.outputFileWriter != null){
                this.outputFileWriter.close();
            }
        } catch(IOException ex){
            Logger.error(ex, "Exception closing the output file: " + outputFile.getAbsolutePath());
            Logger.trace(ex);
            System.exit(-1);
        }
    }

}
