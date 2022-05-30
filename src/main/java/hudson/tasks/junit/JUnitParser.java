/*
 * The MIT License
 *
 * Copyright (c) 2009, Yahoo!, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.tasks.junit;

import hudson.model.TaskListener;
import hudson.tasks.test.PipelineTestDetails;
import hudson.tasks.test.TestResultParser;
import hudson.*;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;
import io.jenkins.plugins.junit.storage.JunitTestResultStorage;

import io.jenkins.plugins.junit.storage.JunitTestResultStorage.RemotePublisher;
import java.io.IOException;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.MasterToSlaveFileCallable;

import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.DirectoryScanner;

/**
 * Parse some JUnit xml files and generate a TestResult containing all the
 * results parsed.
 */
@Extension // see TestResultParser.all
public class JUnitParser extends TestResultParser {

    private static final Logger LOGGER = Logger.getLogger(JUnitParser.class.getName());

    private final boolean keepLongStdio;
    private final boolean allowEmptyResults;

    private final boolean skipOldReports;

    /** Generally unused, but present for extension compatibility. */
    @Deprecated
    public JUnitParser() {
        this(false, false);
    }

    /**
     * @param keepLongStdio if true, retain a suite's complete stdout/stderr even if this is huge and the suite passed
     * @since 1.358
     */
    @Deprecated
    public JUnitParser(boolean keepLongStdio) {
        this(keepLongStdio , false, false);
    }

    /**
     * @param keepLongStdio if true, retain a suite's complete stdout/stderr even if this is huge and the suite passed
     * @param allowEmptyResults if true, empty results are allowed
     * @since 1.10
     */
    @Deprecated
    public JUnitParser(boolean keepLongStdio, boolean allowEmptyResults) {
        this(keepLongStdio , allowEmptyResults, false);
    }

    public JUnitParser(boolean keepLongStdio, boolean allowEmptyResults, boolean skipOldReports) {
        this.keepLongStdio = keepLongStdio;
        this.allowEmptyResults = allowEmptyResults;
        this.skipOldReports = skipOldReports;
    }

    @Override
    public String getDisplayName() {
        return Messages.JUnitParser_DisplayName();
    }

    @Override
    public String getTestResultLocationMessage() {
        return Messages.JUnitParser_TestResultLocationMessage();
    }

    @Deprecated
    @Override public TestResult parse(String testResultLocations, AbstractBuild build, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        return (TestResult) super.parse(testResultLocations, build, launcher, listener);
    }

    @Deprecated
    @Override
    public TestResult parseResult(String testResultLocations, Run<?,?> build, FilePath workspace,
                                  Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        return parseResult(testResultLocations, build, null, workspace, launcher, listener);
    }

    @Override
    public TestResult parseResult(String testResultLocations, Run<?,?> build, PipelineTestDetails pipelineTestDetails,
                                  FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        return workspace.act(new DirectParseResultCallable(testResultLocations, build, keepLongStdio, allowEmptyResults,
                pipelineTestDetails, listener, skipOldReports));
    }

    public TestResultSummary summarizeResult(String testResultLocations, Run<?,?> build, PipelineTestDetails pipelineTestDetails,
                                  FilePath workspace, Launcher launcher, TaskListener listener, JunitTestResultStorage storage)
            throws InterruptedException, IOException {
        return workspace.act(new StorageParseResultCallable(testResultLocations, build, keepLongStdio, allowEmptyResults,
                pipelineTestDetails, listener, storage.createRemotePublisher(build), skipOldReports));
    }

    private static abstract class ParseResultCallable<T> extends MasterToSlaveFileCallable<T> {

        private static final Logger LOGGER = Logger.getLogger(ParseResultCallable.class.getName());

        private final long buildStartTimeInMillis;

        private final long buildTimeInMillis;
        private final String testResults;
        private final long nowMaster;
        private final boolean keepLongStdio;
        private final boolean allowEmptyResults;
        private final PipelineTestDetails pipelineTestDetails;
        private final TaskListener listener;

        private boolean skipOldReports;

        private ParseResultCallable(String testResults, Run<?,?> build,
                                    boolean keepLongStdio, boolean allowEmptyResults,
                                    PipelineTestDetails pipelineTestDetails, TaskListener listener,
                                    boolean skipOldReports) {
            this.buildStartTimeInMillis = build.getStartTimeInMillis();
            this.buildTimeInMillis = build.getTimeInMillis();
            this.testResults = testResults;
            this.nowMaster = System.currentTimeMillis();
            this.keepLongStdio = keepLongStdio;
            this.allowEmptyResults = allowEmptyResults;
            this.pipelineTestDetails = pipelineTestDetails;
            this.listener = listener;
            this.skipOldReports = skipOldReports;
        }

        @Override
        public T invoke(File ws, VirtualChannel channel) throws IOException {
            final long nowSlave = System.currentTimeMillis();
            FileSet fs = Util.createFileSet(ws, testResults);
            DirectoryScanner ds = fs.getDirectoryScanner();
            TestResult result;
            String[] files = ds.getIncludedFiles();
            if (files.length > 0) {
                // not sure we can rely seriously on those timestamp so let's take the smaller one...
                long filesTimestamp = Math.min(buildStartTimeInMillis,buildTimeInMillis);
                // previous mode buildStartTimeInMillis + (nowSlave - nowMaster);
                if(LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("buildStartTimeInMillis:" + buildStartTimeInMillis
                            + ",buildTimeInMillis:" + buildTimeInMillis + ",filesTimestamp:" + filesTimestamp + ",nowSlave:"
                            + nowSlave + ",nowMaster:" + nowMaster);
                }
                result = new TestResult(filesTimestamp, ds, keepLongStdio, pipelineTestDetails, skipOldReports);
                result.tally();
            } else {
                if (this.allowEmptyResults) {
                    listener.getLogger().println(Messages.JUnitResultArchiver_NoTestReportFound());
                    result = new TestResult();
                } else {
                    // no test result. Most likely a configuration error or fatal problem
                    throw new AbortException(Messages.JUnitResultArchiver_NoTestReportFound());
                }
            }
            return handle(result);
        }

        protected abstract T handle(TestResult result) throws IOException;

    }

    private static final class DirectParseResultCallable extends ParseResultCallable<TestResult> {

        DirectParseResultCallable(String testResults, Run<?,?> build, boolean keepLongStdio, boolean allowEmptyResults,
                                  PipelineTestDetails pipelineTestDetails, TaskListener listener, boolean skipOldReports) {
            super(testResults, build, keepLongStdio, allowEmptyResults, pipelineTestDetails, listener, skipOldReports);
        }

        @Override
        protected TestResult handle(TestResult result) throws IOException {
            return result;
        }

    }

    private static final class StorageParseResultCallable extends ParseResultCallable<TestResultSummary> {

        private final RemotePublisher publisher;

        StorageParseResultCallable(String testResults, Run<?,?> build, boolean keepLongStdio, boolean allowEmptyResults,
                                   PipelineTestDetails pipelineTestDetails, TaskListener listener, RemotePublisher publisher, boolean skipOldReports) {
            super(testResults, build, keepLongStdio, allowEmptyResults, pipelineTestDetails, listener, skipOldReports);
            this.publisher = publisher;
        }

        @Override
        protected TestResultSummary handle(TestResult result) throws IOException {
            publisher.publish(result, super.listener);
            return new TestResultSummary(result);
        }

    }

}
