package org.openmetadata.service.apps.scheduler;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.apps.scheduler.AppScheduler.APP_NAME;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.openmetadata.schema.entity.app.App;
import org.openmetadata.schema.entity.app.AppExtension;
import org.openmetadata.schema.entity.app.AppRunRecord;
import org.openmetadata.schema.entity.app.FailureContext;
import org.openmetadata.schema.entity.app.SuccessContext;
import org.openmetadata.service.apps.ApplicationHandler;
import org.openmetadata.service.jdbi3.AppRepository;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.socket.WebSocketManager;
import org.openmetadata.service.util.JsonUtils;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;

@Slf4j
public abstract class AbstractOmAppJobListener implements JobListener {
  private final CollectionDAO collectionDAO;
  private final AppRepository repository;
  private static final String SCHEDULED_APP_RUN_EXTENSION = "AppScheduleRun";
  public static final String WEBSOCKET_STATUS_CHANNEL = "WebsocketStatusUpdateExtension";

  public static final String APP_RUN_STATS = "AppRunStats";
  public static final String JOB_LISTENER_NAME = "OM_JOB_LISTENER";

  protected AbstractOmAppJobListener(CollectionDAO dao) {
    this.collectionDAO = dao;
    this.repository = new AppRepository();
  }

  @Override
  public String getName() {
    return JOB_LISTENER_NAME;
  }

  @Override
  public void jobToBeExecuted(JobExecutionContext jobExecutionContext) {
    try {
      String runType =
          (String) jobExecutionContext.getJobDetail().getJobDataMap().get("triggerType");
      String appName = (String) jobExecutionContext.getJobDetail().getJobDataMap().get(APP_NAME);
      App jobApp = collectionDAO.applicationDAO().findEntityByName(appName);
      ApplicationHandler.getInstance().setAppRuntimeProperties(jobApp);
      JobDataMap dataMap = jobExecutionContext.getJobDetail().getJobDataMap();
      long jobStartTime = System.currentTimeMillis();
      AppRunRecord runRecord =
          new AppRunRecord()
              .withAppId(jobApp.getId())
              .withAppName(jobApp.getName())
              .withStartTime(jobStartTime)
              .withTimestamp(jobStartTime)
              .withRunType(runType)
              .withStatus(AppRunRecord.Status.RUNNING)
              .withScheduleInfo(jobApp.getAppSchedule())
              .withConfig(JsonUtils.getMap(jobApp.getAppConfiguration()));

      boolean update = false;
      if (jobExecutionContext.isRecovering()) {
        AppRunRecord latestRunRecord =
            repository.getLatestExtensionById(
                jobApp, AppRunRecord.class, AppExtension.ExtensionType.STATUS);
        if (latestRunRecord != null) {
          runRecord = latestRunRecord;
        }
        update = true;
      }
      // Put the Context in the Job Data Map
      dataMap.put(SCHEDULED_APP_RUN_EXTENSION, JsonUtils.pojoToJson(runRecord));

      // Insert new Record Run
      pushApplicationStatusUpdates(jobExecutionContext, runRecord, update);
      this.doJobToBeExecuted(jobExecutionContext);
    } catch (Exception e) {
      LOG.info("Error while setting up the job context", e);
    }
  }

  @Override
  public void jobExecutionVetoed(JobExecutionContext jobExecutionContext) {}

  @Override
  public void jobWasExecuted(
      JobExecutionContext jobExecutionContext, JobExecutionException jobException) {
    AppRunRecord runRecord =
        JsonUtils.readOrConvertValue(
            jobExecutionContext.getJobDetail().getJobDataMap().get(SCHEDULED_APP_RUN_EXTENSION),
            AppRunRecord.class);
    Object jobStats = jobExecutionContext.getJobDetail().getJobDataMap().get(APP_RUN_STATS);
    long endTime = System.currentTimeMillis();
    runRecord.withEndTime(endTime);
    runRecord.setExecutionTime(endTime - runRecord.getStartTime());

    if (jobException == null
        && !(runRecord.getStatus() == AppRunRecord.Status.FAILED
            || runRecord.getStatus() == AppRunRecord.Status.ACTIVE_ERROR)) {
      runRecord.withStatus(AppRunRecord.Status.SUCCESS);
      SuccessContext context = new SuccessContext();
      if (runRecord.getSuccessContext() != null) {
        context = runRecord.getSuccessContext();
      }
      context.getAdditionalProperties().put("stats", JsonUtils.getMap(jobStats));
      runRecord.setSuccessContext(context);
    } else {
      runRecord.withStatus(AppRunRecord.Status.FAILED);
      FailureContext context = new FailureContext();
      if (runRecord.getFailureContext() != null) {
        context = runRecord.getFailureContext();
      }
      if (jobException != null) {
        Map<String, Object> failure = new HashMap<>();
        failure.put("message", jobException.getMessage());
        failure.put("jobStackTrace", ExceptionUtils.getStackTrace(jobException));
        context.withAdditionalProperty("failure", failure);
      }
      runRecord.setFailureContext(context);
    }

    // Push Update on WebSocket
    String webSocketChannelName =
        (String) jobExecutionContext.getJobDetail().getJobDataMap().get(WEBSOCKET_STATUS_CHANNEL);
    if (!nullOrEmpty(webSocketChannelName) && WebSocketManager.getInstance() != null) {
      WebSocketManager.getInstance()
          .broadCastMessageToAll(webSocketChannelName, JsonUtils.pojoToJson(runRecord));
    }

    // Update App Run Record
    pushApplicationStatusUpdates(jobExecutionContext, runRecord, true);

    this.doJobWasExecuted(jobExecutionContext, jobException);
  }

  public AppRunRecord getAppRunRecordForJob(JobExecutionContext context) {
    JobDataMap dataMap = context.getJobDetail().getJobDataMap();
    return JsonUtils.readOrConvertValue(
        dataMap.get(SCHEDULED_APP_RUN_EXTENSION), AppRunRecord.class);
  }

  public void pushApplicationStatusUpdates(
      JobExecutionContext context, AppRunRecord runRecord, boolean update) {
    JobDataMap dataMap = context.getJobDetail().getJobDataMap();
    if (dataMap.containsKey(SCHEDULED_APP_RUN_EXTENSION)) {
      // Update the Run Record in Data Map
      dataMap.put(SCHEDULED_APP_RUN_EXTENSION, JsonUtils.pojoToJson(runRecord));

      // Push Updates to the Database
      String appName = (String) context.getJobDetail().getJobDataMap().get(APP_NAME);
      UUID appId = collectionDAO.applicationDAO().findEntityByName(appName).getId();
      updateStatus(appId, runRecord, update);
    }
  }

  private void updateStatus(UUID appId, AppRunRecord appRunRecord, boolean update) {
    if (update) {
      collectionDAO
          .appExtensionTimeSeriesDao()
          .update(
              appId.toString(),
              JsonUtils.pojoToJson(appRunRecord),
              appRunRecord.getTimestamp(),
              AppExtension.ExtensionType.STATUS.toString());
    } else {
      collectionDAO
          .appExtensionTimeSeriesDao()
          .insert(JsonUtils.pojoToJson(appRunRecord), AppExtension.ExtensionType.STATUS.toString());
    }
  }

  protected void doJobWasExecuted(
      JobExecutionContext jobExecutionContext, JobExecutionException jobException) {}

  protected void doJobToBeExecuted(JobExecutionContext jobExecutionContext) {}
}
