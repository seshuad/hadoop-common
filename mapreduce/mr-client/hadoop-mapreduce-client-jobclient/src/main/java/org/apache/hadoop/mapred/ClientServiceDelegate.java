/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.v2.api.MRClientProtocol;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.FailTaskAttemptRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetCountersRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetDiagnosticsRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetJobReportRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskAttemptCompletionEventsRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskReportsRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillJobRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillTaskAttemptRequest;
import org.apache.hadoop.mapreduce.v2.api.records.JobReport;
import org.apache.hadoop.mapreduce.v2.api.records.JobState;
import org.apache.hadoop.mapreduce.v2.jobhistory.JHConfig;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityInfo;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationMaster;
import org.apache.hadoop.yarn.api.records.ApplicationState;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.RPCUtil;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.ApplicationTokenIdentifier;
import org.apache.hadoop.yarn.security.SchedulerSecurityInfo;
import org.apache.hadoop.yarn.security.client.ClientRMSecurityInfo;

public class ClientServiceDelegate {
  private static final Log LOG = LogFactory.getLog(ClientServiceDelegate.class);

  private Configuration conf;
  private ApplicationId currentAppId;
  private ApplicationState currentAppState
    = ApplicationState.PENDING;
  private final ResourceMgrDelegate rm;
  private MRClientProtocol realProxy = null;
  private String serviceAddr = "";
  private String serviceHttpAddr = "";
  private RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);

  ClientServiceDelegate(Configuration conf, ResourceMgrDelegate rm) {
    this.conf = conf;
    this.rm = rm;
  }

  private MRClientProtocol getProxy(JobID jobId) throws YarnRemoteException {
    return getProxy(TypeConverter.toYarn(jobId).getAppId(), false);
  }

  private MRClientProtocol getRefreshedProxy(JobID jobId) throws YarnRemoteException {
    return getProxy(TypeConverter.toYarn(jobId).getAppId(), true);
  }

  private MRClientProtocol getProxy(ApplicationId appId, 
      boolean forceRefresh) throws YarnRemoteException {
    if (!appId.equals(currentAppId) || forceRefresh || realProxy == null) {
      currentAppId = appId;
      refreshProxy();
    }
    return realProxy;
  }

  private void refreshProxy() throws YarnRemoteException {
    ApplicationMaster appMaster = rm.getApplicationMaster(currentAppId);
    while (!ApplicationState.COMPLETED.equals(appMaster.getState()) &&
        !ApplicationState.FAILED.equals(appMaster.getState()) && 
        !ApplicationState.KILLED.equals(appMaster.getState()) &&
        !ApplicationState.ALLOCATING.equals(appMaster.getState())) {
      try {
        if (appMaster.getHost() == null || "".equals(appMaster.getHost())) {
          LOG.debug("AM not assigned to Job. Waiting to get the AM ...");
          Thread.sleep(2000);
   
          LOG.debug("Application state is " + appMaster.getState());
          appMaster = rm.getApplicationMaster(currentAppId);
          continue;
        }
        serviceAddr = appMaster.getHost() + ":" + appMaster.getRpcPort();
        serviceHttpAddr = appMaster.getTrackingUrl();
        currentAppState = appMaster.getState();
        if (UserGroupInformation.isSecurityEnabled()) {
          String clientTokenEncoded = appMaster.getClientToken();
          Token<ApplicationTokenIdentifier> clientToken =
            new Token<ApplicationTokenIdentifier>();
          clientToken.decodeFromUrlString(clientTokenEncoded);
          clientToken.setService(new Text(appMaster.getHost() + ":"
              + appMaster.getRpcPort()));
          UserGroupInformation.getCurrentUser().addToken(clientToken);
        }
        LOG.info("Connecting to " + serviceAddr);
        instantiateAMProxy(serviceAddr);
        return;
      } catch (Exception e) {
        //possibly
        //possibly the AM has crashed
        //there may be some time before AM is restarted
        //keep retrying by getting the address from RM
        LOG.info("Could not connect to " + serviceAddr + 
        ". Waiting for getting the latest AM address...");
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e1) {
        }
        appMaster = rm.getApplicationMaster(currentAppId);
      }
    }

    currentAppState = appMaster.getState();
    /** we just want to return if its allocating, so that we dont 
     * block on it. This is to be able to return job status 
     * on a allocating Application.
     */
    
    if (currentAppState == ApplicationState.ALLOCATING) {
      realProxy = null;
      return;
    }
    
    if (currentAppState == ApplicationState.COMPLETED
        || currentAppState == ApplicationState.FAILED
        || currentAppState == ApplicationState.KILLED) {
      serviceAddr = conf.get(JHConfig.HS_BIND_ADDRESS,
          JHConfig.DEFAULT_HS_BIND_ADDRESS);
      LOG.info("Application state is completed. " +
          "Redirecting to job history server " + serviceAddr);
      //TODO:
      serviceHttpAddr = "";
      try {
        instantiateHistoryProxy(serviceAddr);
        return;
      } catch (IOException e) {
        throw new YarnException(e);
      }
    }
  }

  private void instantiateAMProxy(final String serviceAddr) throws IOException {
    UserGroupInformation currentUser = UserGroupInformation.getCurrentUser();
    LOG.trace("Connecting to ApplicationMaster at: " + serviceAddr);
    realProxy = currentUser.doAs(new PrivilegedAction<MRClientProtocol>() {
      @Override
      public MRClientProtocol run() {
        Configuration myConf = new Configuration(conf);
        myConf.setClass(
            CommonConfigurationKeysPublic.HADOOP_SECURITY_INFO_CLASS_NAME,
            SchedulerSecurityInfo.class, SecurityInfo.class); 
        YarnRPC rpc = YarnRPC.create(myConf);
        return (MRClientProtocol) rpc.getProxy(MRClientProtocol.class,
            NetUtils.createSocketAddr(serviceAddr), myConf);
      }
    });
    LOG.trace("Connected to ApplicationMaster at: " + serviceAddr);
  }

  private void instantiateHistoryProxy(final String serviceAddr)
  throws IOException {
    LOG.trace("Connecting to HistoryServer at: " + serviceAddr);
    Configuration myConf = new Configuration(conf);
    //TODO This should ideally be using it's own class (instead of ClientRMSecurityInfo)
    myConf.setClass(CommonConfigurationKeys.HADOOP_SECURITY_INFO_CLASS_NAME,
        ClientRMSecurityInfo.class, SecurityInfo.class);
    YarnRPC rpc = YarnRPC.create(myConf);
    realProxy = (MRClientProtocol) rpc.getProxy(MRClientProtocol.class,
        NetUtils.createSocketAddr(serviceAddr), myConf);
    LOG.trace("Connected to HistoryServer at: " + serviceAddr);
  }

  public org.apache.hadoop.mapreduce.Counters getJobCounters(JobID arg0) throws IOException,
  InterruptedException {
    org.apache.hadoop.mapreduce.v2.api.records.JobId jobID = TypeConverter.toYarn(arg0);
    try {
      GetCountersRequest request = recordFactory.newRecordInstance(GetCountersRequest.class);
      request.setJobId(jobID);
      MRClientProtocol protocol = getProxy(arg0);
      if (protocol == null) {
        /* no AM to connect to, fake counters */
        return new org.apache.hadoop.mapreduce.Counters();
      }
      return TypeConverter.fromYarn(protocol.getCounters(request).getCounters());
    } catch(YarnRemoteException yre) {//thrown by remote server, no need to redirect
      LOG.warn(RPCUtil.toString(yre));
      throw yre;
    } catch(Exception e) {
      LOG.debug("Failing to contact application master", e);
      try {
        GetCountersRequest request = recordFactory.newRecordInstance(GetCountersRequest.class);
        request.setJobId(jobID);
        return TypeConverter.fromYarn(getRefreshedProxy(arg0).getCounters(request).getCounters());
      } catch(YarnRemoteException yre) {
        LOG.warn(RPCUtil.toString(yre));
        throw yre;
      }
    }
  }

  public String getJobHistoryDir() throws IOException, InterruptedException {
    //TODO fix this
    return "";
  }

  public TaskCompletionEvent[] getTaskCompletionEvents(JobID arg0, int arg1,
      int arg2) throws IOException, InterruptedException {
    org.apache.hadoop.mapreduce.v2.api.records.JobId jobID = TypeConverter.toYarn(arg0);
    List<org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptCompletionEvent> list = null;
    GetTaskAttemptCompletionEventsRequest request = recordFactory.newRecordInstance(GetTaskAttemptCompletionEventsRequest.class);
    MRClientProtocol protocol;
    try {
      request.setJobId(jobID);
      request.setFromEventId(arg1);
      request.setMaxEvents(arg2);
      protocol = getProxy(arg0);
      /** This is hack to get around the issue of faking jobstatus while the AM
       * is coming up.
       */
      if (protocol == null) {
        return new TaskCompletionEvent[0];
      }
      list = getProxy(arg0).getTaskAttemptCompletionEvents(request).getCompletionEventList();
    } catch(YarnRemoteException yre) {//thrown by remote server, no need to redirect
      LOG.warn(RPCUtil.toString(yre));
      throw yre;
    } catch(Exception e) {
      LOG.debug("Failed to contact application master ", e);
      try {
        request.setJobId(jobID);
        request.setFromEventId(arg1);
        request.setMaxEvents(arg2);
        protocol = getRefreshedProxy(arg0);
        if (protocol == null) {
          return new TaskCompletionEvent[0];
        }
        list = getRefreshedProxy(arg0).getTaskAttemptCompletionEvents(request).getCompletionEventList();
      } catch(YarnRemoteException yre) {
        LOG.warn(RPCUtil.toString(yre));
        throw yre;
      }
    }
    return TypeConverter.fromYarn(
        list.toArray(new org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptCompletionEvent[0]));
  }

  public String[] getTaskDiagnostics(org.apache.hadoop.mapreduce.TaskAttemptID
      arg0)
  throws IOException,
  InterruptedException {

    List<String> list = null;
    org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId attemptID = TypeConverter.toYarn(arg0);
    GetDiagnosticsRequest request = recordFactory.newRecordInstance(GetDiagnosticsRequest.class);
    try {
      request.setTaskAttemptId(attemptID);
      list = getProxy(arg0.getJobID()).getDiagnostics(request).getDiagnosticsList();
    } catch(YarnRemoteException yre) {//thrown by remote server, no need to redirect
      LOG.warn(RPCUtil.toString(yre));
      throw yre;
    } catch(Exception e) {
      LOG.debug("Failed to contact application master ", e);
      try {
        request.setTaskAttemptId(attemptID);
        list = getRefreshedProxy(arg0.getJobID()).getDiagnostics(request).getDiagnosticsList();
      } catch(YarnRemoteException yre) {
        LOG.warn(RPCUtil.toString(yre));
        throw yre;
      }
    }
    String[] result = new String[list.size()];
    int i = 0;
    for (String c : list) {
      result[i++] = c.toString();
    }
    return result;
  }
  
  private JobStatus createFakeJobReport(ApplicationState state, 
      org.apache.hadoop.mapreduce.v2.api.records.JobId jobId, String jobFile) {
    JobReport jobreport = recordFactory.newRecordInstance(JobReport.class);
    jobreport.setCleanupProgress(0);
    jobreport.setFinishTime(0);
    jobreport.setJobId(jobId);
    jobreport.setMapProgress(0);
    /** fix this, the start time should be fixed */
    jobreport.setStartTime(0);
    jobreport.setReduceProgress(0);
    jobreport.setSetupProgress(0);

    if (currentAppState == ApplicationState.ALLOCATING) {
      /* the protocol wasnt instantiated because the applicaton wasnt launched
       * return a fake report.
       */
      jobreport.setJobState(JobState.INITED); 
    } else if (currentAppState == ApplicationState.KILLED) {
      jobreport.setJobState(JobState.KILLED);
    } else if (currentAppState == ApplicationState.FAILED) {
      jobreport.setJobState(JobState.FAILED);
    }
    return  TypeConverter.fromYarn(jobreport, jobFile, serviceHttpAddr);
  }

  public JobStatus getJobStatus(JobID oldJobID) throws YarnRemoteException,
  YarnRemoteException {
    org.apache.hadoop.mapreduce.v2.api.records.JobId jobId = 
      TypeConverter.toYarn(oldJobID);
    String stagingDir = conf.get("yarn.apps.stagingDir");
    String jobFile = stagingDir + "/" + jobId.toString();
    JobReport report = null;
    MRClientProtocol protocol;
    GetJobReportRequest request = recordFactory.newRecordInstance(GetJobReportRequest.class);
    try {
      request.setJobId(jobId);
      protocol = getProxy(oldJobID);
      
      if (protocol == null) {
        return createFakeJobReport(currentAppState, jobId, jobFile);
      }
      report = getProxy(oldJobID).getJobReport(request).getJobReport();
    } catch(YarnRemoteException yre) {//thrown by remote server, no need to redirect
      LOG.warn(RPCUtil.toString(yre));
      throw yre;
    } catch (Exception e) {
      try {
        request.setJobId(jobId);
        protocol = getRefreshedProxy(oldJobID);
        /* this is possible if an application that was running is killed */
        if (protocol == null)  {
          return createFakeJobReport(currentAppState, jobId, jobFile);
        }
        report = getRefreshedProxy(oldJobID).getJobReport(request).getJobReport();
      } catch(YarnRemoteException yre) {
        LOG.warn(RPCUtil.toString(yre));
        throw yre;
      }
    }
    return TypeConverter.fromYarn(report, jobFile, serviceHttpAddr);
  }

  public org.apache.hadoop.mapreduce.TaskReport[] getTaskReports(JobID jobID, TaskType taskType)
  throws YarnRemoteException, YarnRemoteException {
    List<org.apache.hadoop.mapreduce.v2.api.records.TaskReport> taskReports = null;
    org.apache.hadoop.mapreduce.v2.api.records.JobId nJobID = TypeConverter.toYarn(jobID);
    GetTaskReportsRequest request = recordFactory.newRecordInstance(GetTaskReportsRequest.class);
    try {
      request.setJobId(nJobID);
      request.setTaskType(TypeConverter.toYarn(taskType));
      taskReports = getProxy(jobID).getTaskReports(request).getTaskReportList();
    } catch(YarnRemoteException yre) {//thrown by remote server, no need to redirect
      LOG.warn(RPCUtil.toString(yre));
      throw yre;
    } catch(Exception e) {
      LOG.debug("Failed to contact application master ", e);
      try {
        request.setJobId(nJobID);
        request.setTaskType(TypeConverter.toYarn(taskType));
        taskReports = getRefreshedProxy(jobID).getTaskReports(request).getTaskReportList();
      } catch(YarnRemoteException yre) {
        LOG.warn(RPCUtil.toString(yre));
        throw yre;
      }
    }
    return TypeConverter.fromYarn
    (taskReports).toArray(new org.apache.hadoop.mapreduce.TaskReport[0]);
  }

  public boolean killTask(TaskAttemptID taskAttemptID, boolean fail)
  throws YarnRemoteException {
    org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId attemptID 
    = TypeConverter.toYarn(taskAttemptID);
    KillTaskAttemptRequest killRequest = recordFactory.newRecordInstance(KillTaskAttemptRequest.class);
    FailTaskAttemptRequest failRequest = recordFactory.newRecordInstance(FailTaskAttemptRequest.class);
    try {
      if (fail) {
        failRequest.setTaskAttemptId(attemptID);
        getProxy(taskAttemptID.getJobID()).failTaskAttempt(failRequest);
      } else {
        killRequest.setTaskAttemptId(attemptID);
        getProxy(taskAttemptID.getJobID()).killTaskAttempt(killRequest);
      }
    } catch(YarnRemoteException yre) {//thrown by remote server, no need to redirect
      LOG.warn(RPCUtil.toString(yre));
      throw yre;
    } catch(Exception e) {
      LOG.debug("Failed to contact application master ", e);
      MRClientProtocol proxy = getRefreshedProxy(taskAttemptID.getJobID());
      if (proxy == null) {
        return false;
      }
      try {
        if (fail) {
          failRequest.setTaskAttemptId(attemptID);
          proxy.failTaskAttempt(failRequest);
        } else {
          killRequest.setTaskAttemptId(attemptID);
          proxy.killTaskAttempt(killRequest);
        }
      } catch(YarnRemoteException yre) {
        LOG.warn(RPCUtil.toString(yre));
        throw yre;
      }
    }
    return true;
  }
}
