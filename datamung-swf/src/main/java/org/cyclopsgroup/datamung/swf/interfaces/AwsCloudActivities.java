package org.cyclopsgroup.datamung.swf.interfaces;

import org.cyclopsgroup.datamung.api.types.Identity;

import com.amazonaws.services.simpleworkflow.flow.annotations.Activities;
import com.amazonaws.services.simpleworkflow.flow.annotations.ActivityRegistrationOptions;
import com.amazonaws.services.simpleworkflow.flow.annotations.ExponentialRetry;

@Activities(version = "1.0")
@ActivityRegistrationOptions(defaultTaskStartToCloseTimeoutSeconds = 600, defaultTaskScheduleToStartTimeoutSeconds = 600, defaultTaskList = Constants.ACTIVITY_TASK_LIST)
public interface AwsCloudActivities {

	@ExponentialRetry(initialRetryIntervalSeconds = 30, maximumAttempts = 5)
	String createSnapshotName(String instanceName);

	@ExponentialRetry(initialRetryIntervalSeconds = 30, maximumAttempts = 5)
	void createSnapshot(String snapshotName, String instanceName,
			Identity identity);

	@ExponentialRetry(initialRetryIntervalSeconds = 30, maximumAttempts = 5)
	String getSnapshotStatus(String snapshotName, Identity identity);
}
