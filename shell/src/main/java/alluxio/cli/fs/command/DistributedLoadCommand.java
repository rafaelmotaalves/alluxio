/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.cli.fs.command;

import alluxio.AlluxioURI;
import alluxio.ClientContext;
import alluxio.annotation.PublicApi;
import alluxio.cli.CommandUtils;
import alluxio.cli.fs.FileSystemShellUtils;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.URIStatus;
import alluxio.client.job.JobMasterClient;
import alluxio.exception.AlluxioException;
import alluxio.exception.status.InvalidArgumentException;
import alluxio.job.load.LoadConfig;
import alluxio.job.JobConfig;

import alluxio.job.wire.JobInfo;
import alluxio.job.wire.Status;
import alluxio.retry.CountingRetry;
import alluxio.retry.RetryPolicy;
import alluxio.worker.job.JobMasterClientContext;

import com.google.common.collect.Lists;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Loads a file or directory in Alluxio space, makes it resident in memory.
 */
@ThreadSafe
@PublicApi
public final class DistributedLoadCommand extends AbstractFileSystemCommand {
  private static final Logger LOG = LoggerFactory.getLogger(DistributedLoadCommand.class);
  private static final int DEFAULT_REPLICATION = 1;
  private static final Option REPLICATION_OPTION =
      Option.builder()
          .longOpt("replication")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .type(Number.class)
          .argName("replicas")
          .desc("Number of block replicas of each loaded file, default: " + DEFAULT_REPLICATION)
          .build();

  private static final int DEFAULT_ACTIVE_JOBS = 1000;
  private static final Option ACTIVE_JOBS_OPTION =
      Option.builder()
          .longOpt("active_jobs")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .type(Number.class)
          .argName("jobs")
          .desc("Maximum number of active outgoing jobs, default: " + DEFAULT_ACTIVE_JOBS)
          .build();

  private class JobAttempt {
    private final JobConfig mJobConfig;
    private final RetryPolicy mRetryPolicy;
    private final JobMasterClient mClient;

    private Long mJobId;

    private JobAttempt(JobConfig jobConfig, RetryPolicy retryPolicy, ClientContext clientContext) {
      mJobConfig = jobConfig;
      mRetryPolicy = retryPolicy;
      mClient = JobMasterClient.Factory.create(
          JobMasterClientContext.newBuilder(clientContext).build());
    }

    private boolean run() {
      if (mRetryPolicy.attempt()) {
        mJobId = null;
        try {
          mJobId = mClient.run(mJobConfig);
        } catch (IOException e) {
          LOG.warn("Failed to get status for job (jobId={})", mJobId, e);
          // Do nothing. This will be counted as a failed attempt
        }
        return true;
      }
      LOG.warn("Failed to complete job after retries: {}", mJobConfig);
      return false;
    }

    /**
     * Returns the status of the job attempt.
     * @return True if finished successfully or cancelled, False if FAILED and should be retried,
     *              null if the status should be checked again later
     */
    private Status check() {
      if (mJobId == null) {
        return Status.FAILED;
      }

      JobInfo jobInfo;
      try {
        jobInfo = mClient.getStatus(mJobId);
      } catch (IOException e) {
        LOG.warn("Failed to get status for job (jobId={})", mJobId, e);
        return Status.FAILED;
      }

      return jobInfo.getStatus();
    }

    private void close() throws IOException {
      // propagate failing to close up to prevent potential memory leak
      mClient.close();
    }
  }

  private final List<JobAttempt> mSubmittedJobAttempts;
  private int mActiveJobs;

  /**
   * Constructs a new instance to load a file or directory in Alluxio space.
   *
   * @param fsContext the filesystem of Alluxio
   */
  public DistributedLoadCommand(FileSystemContext fsContext) {
    super(fsContext);
    mSubmittedJobAttempts = Lists.newArrayList();
  }

  @Override
  public String getCommandName() {
    return "distributedLoad";
  }

  @Override
  public Options getOptions() {
    return new Options().addOption(REPLICATION_OPTION).addOption(ACTIVE_JOBS_OPTION);
  }

  @Override
  public void validateArgs(CommandLine cl) throws InvalidArgumentException {
    CommandUtils.checkNumOfArgsEquals(this, cl, 1);
  }

  @Override
  public int run(CommandLine cl) throws AlluxioException, IOException {
    String[] args = cl.getArgs();
    AlluxioURI path = new AlluxioURI(args[0]);
    int replication = FileSystemShellUtils.getIntArg(cl, REPLICATION_OPTION, DEFAULT_REPLICATION);
    mActiveJobs = FileSystemShellUtils.getIntArg(cl, ACTIVE_JOBS_OPTION, DEFAULT_ACTIVE_JOBS);
    distributedLoad(path, replication);
    return 0;
  }

  /**
   * Creates a new job to load a file in Alluxio space, makes it resident in memory.
   *
   * @param filePath The {@link AlluxioURI} path to load into Alluxio memory
   * @param replication The replication of file to load into Alluxio memory
   */
  private JobAttempt newJob(AlluxioURI filePath, int replication) {
    JobAttempt jobAttempt = new JobAttempt(new LoadConfig(filePath.getPath(), replication),
        new CountingRetry(3), ClientContext.create(mFsContext.getPathConf(filePath)));

    jobAttempt.run();

    return jobAttempt;
  }

  /**
   * Waits one job to complete.
   */
  private void waitJob() throws IOException {
    boolean removed = false;
    while (true) {
      Iterator<JobAttempt> iterator = mSubmittedJobAttempts.iterator();

      while (iterator.hasNext()) {
        JobAttempt jobAttempt = iterator.next();
        Status check = jobAttempt.check();

        switch (check) {
          case CREATED:
          case RUNNING:
            continue;
          case CANCELED:
          case COMPLETED:
            removed = true;
            jobAttempt.close();
            iterator.remove();
            continue;
          case FAILED:
            if (!jobAttempt.run()) {
              removed = true;
              iterator.remove();
            }
            continue;
          default:
            throw new IllegalStateException(String.format("Unexpected Status: %s", check));
        }
      }
      if (removed) {
        return;
      }
    }
  }

  /**
   * Add one job.
   */
  private void addJob(URIStatus status, int replication) throws IOException {
    AlluxioURI filePath = new AlluxioURI(status.getPath());
    if (status.getInAlluxioPercentage() == 100) {
      // The file has already been fully loaded into Alluxio.
      System.out.println(filePath + " is already fully loaded in Alluxio");
      return;
    }
    if (mSubmittedJobAttempts.size() >= mActiveJobs) {
      // Wait one job to complete.
      waitJob();
    }
    mSubmittedJobAttempts.add(newJob(filePath, replication));
    System.out.println(filePath + " loading");
  }

  /**
   * Distributed loads a file or directory in Alluxio space, makes it resident in memory.
   *
   * @param filePath The {@link AlluxioURI} path to load into Alluxio memory
   * @param replication The replication of file to load into Alluxio memory
   */
  private void distributedLoad(AlluxioURI filePath, int replication)
      throws AlluxioException, IOException {
    load(filePath, replication);
    // Wait remaining jobs to complete.
    while (!mSubmittedJobAttempts.isEmpty()) {
      waitJob();
    }
  }

  /**
   * Loads a file or directory in Alluxio space, makes it resident in memory.
   *
   * @param filePath The {@link AlluxioURI} path to load into Alluxio memory
   * @throws AlluxioException when Alluxio exception occurs
   * @throws IOException      when non-Alluxio exception occurs
   */
  private void load(AlluxioURI filePath, int replication)
      throws IOException, AlluxioException {
    URIStatus status = mFileSystem.getStatus(filePath);
    if (status.isFolder()) {
      List<URIStatus> statuses = mFileSystem.listStatus(filePath);
      for (URIStatus uriStatus : statuses) {
        if (uriStatus.isFolder()) {
          AlluxioURI subPath = new AlluxioURI(uriStatus.getPath());
          load(subPath, replication);
        } else {
          addJob(uriStatus, replication);
        }
      }
    } else {
      addJob(status, replication);
    }
  }

  @Override
  public String getUsage() {
    return "distributedLoad [--replication <num>] [--parallelism <num>] <path>";
  }

  @Override
  public String getDescription() {
    return "Loads a file or all files in a directory into Alluxio space.";
  }
}
