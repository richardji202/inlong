/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.core.task;

import static org.apache.inlong.agent.constants.CommonConstants.POSITION_SUFFIX;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.inlong.agent.common.AbstractDaemon;
import org.apache.inlong.agent.conf.JobProfile;
import org.apache.inlong.agent.core.AgentManager;
import org.apache.inlong.agent.db.JobProfileDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * used to store task position to db, task position is stored as properties in JobProfile.
 * where key is task read file name and value is task sink position
 * note that this class is generated
 */
public class TaskPositionManager extends AbstractDaemon {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskPositionManager.class);
    public static final int DEFAULT_FLUSH_TIMEOUT = 30;

    private final AgentManager agentManager;
    private final JobProfileDb jobConfDb;
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> jobTaskPositionMap;

    private static volatile TaskPositionManager taskPositionManager = null;

    private TaskPositionManager(AgentManager agentManager) {
        this.agentManager = agentManager;
        this.jobConfDb = agentManager.getJobManager().getJobConfDb();
        this.jobTaskPositionMap = new ConcurrentHashMap<>();
    }

    /**
     * task position manager singleton, can only generated by agent manager
     * @param agentManager
     * @return
     */
    public static TaskPositionManager getTaskPositionManager(AgentManager agentManager) {
        if (taskPositionManager == null) {
            synchronized (TaskPositionManager.class) {
                if (taskPositionManager == null) {
                    taskPositionManager = new TaskPositionManager(agentManager);
                }
            }
        }
        return taskPositionManager;
    }

    /**
     * get taskPositionManager singleton
     * @return
     */
    public static TaskPositionManager getTaskPositionManager() {
        if (taskPositionManager == null) {
            throw new RuntimeException("task position manager has not been initialized by agentManager");
        }
        return taskPositionManager;
    }

    @Override
    public void start() throws Exception {
        submitWorker(taskPositionFlushThread());
    }

    private Runnable taskPositionFlushThread() {
        return () -> {
            while (isRunnable()) {
                try {
                    // check pending jobs and try to submit again.
                    for (String jobId : jobTaskPositionMap.keySet()) {
                        JobProfile jobProfile = jobConfDb.getJobProfile(jobId);
                        if (jobProfile == null) {
                            LOGGER.warn("jobProfile {} cannot be found in db, "
                                + "might be deleted by standalone mode, now delete job position in memory", jobId);
                            deleteJobPosition(jobId);
                            continue;
                        }
                        flushJobProfile(jobId, jobProfile);
                    }
                    TimeUnit.SECONDS.sleep(DEFAULT_FLUSH_TIMEOUT);
                } catch (Exception ex) {
                    LOGGER.error("error caught", ex);
                }
            }
        };
    }

    private void flushJobProfile(String jobId, JobProfile jobProfile) {
        jobTaskPositionMap.get(jobId).forEach(
            (fileName, position) -> jobProfile.setLong(fileName + POSITION_SUFFIX, position)
        );
        if (jobConfDb.checkJobfinished(jobProfile)) {
            LOGGER.info("Cannot update job profile {}, delete memory job in jobTaskPosition", jobId);
            deleteJobPosition(jobId);
        } else {
            jobConfDb.updateJobProfile(jobProfile);
        }
    }

    private void deleteJobPosition(String jobId) {
        jobTaskPositionMap.remove(jobId);
    }

    @Override
    public void stop() throws Exception {
        waitForTerminate();
    }

    public void updateFileSinkPosition(String jobInstanceId, String sourceFilePath, long size) {
        ConcurrentHashMap<String, Long> filePositionTemp = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Long> filePosition = jobTaskPositionMap.putIfAbsent(jobInstanceId, filePositionTemp);
        if (filePosition == null) {
            filePosition = filePositionTemp;
        }
        Long beforePosition = filePosition.getOrDefault(sourceFilePath, 1L);
        filePosition.put(sourceFilePath, beforePosition + size);
    }

    public ConcurrentHashMap<String, Long> getTaskPositionMap(String jobId) {
        return jobTaskPositionMap.get(jobId);
    }

    public ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> getJobTaskPosition() {
        return jobTaskPositionMap;
    }
}
