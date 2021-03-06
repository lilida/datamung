package org.cyclopsgroup.datamung.swf.flows;

import org.cyclopsgroup.datamung.api.types.ExportInstanceRequest;
import org.cyclopsgroup.datamung.api.types.ExportSnapshotRequest;
import org.cyclopsgroup.datamung.swf.interfaces.CheckWaitWorkflowClientFactory;
import org.cyclopsgroup.datamung.swf.interfaces.CheckWaitWorkflowClientFactoryImpl;
import org.cyclopsgroup.datamung.swf.interfaces.Constants;
import org.cyclopsgroup.datamung.swf.interfaces.ControlActivitiesClient;
import org.cyclopsgroup.datamung.swf.interfaces.ControlActivitiesClientImpl;
import org.cyclopsgroup.datamung.swf.interfaces.ExportInstanceWorkflow;
import org.cyclopsgroup.datamung.swf.interfaces.ExportSnapshotWorkflowClientFactory;
import org.cyclopsgroup.datamung.swf.interfaces.ExportSnapshotWorkflowClientFactoryImpl;
import org.cyclopsgroup.datamung.swf.interfaces.JobWorkflowClientFactory;
import org.cyclopsgroup.datamung.swf.interfaces.JobWorkflowClientFactoryImpl;
import org.cyclopsgroup.datamung.swf.interfaces.RdsActivitiesClient;
import org.cyclopsgroup.datamung.swf.interfaces.RdsActivitiesClientImpl;
import org.cyclopsgroup.datamung.swf.types.CheckAndWait;
import org.cyclopsgroup.datamung.swf.types.DatabaseInstance;
import org.cyclopsgroup.datamung.swf.types.MySQLDumpJob;
import org.cyclopsgroup.datamung.swf.types.RunJobRequest;

import com.amazonaws.services.simpleworkflow.flow.DecisionContextProvider;
import com.amazonaws.services.simpleworkflow.flow.DecisionContextProviderImpl;
import com.amazonaws.services.simpleworkflow.flow.annotations.Asynchronous;
import com.amazonaws.services.simpleworkflow.flow.core.Promise;
import com.amazonaws.services.simpleworkflow.flow.core.TryCatch;
import com.amazonaws.services.simpleworkflow.flow.core.TryCatchFinally;

public class ExportInstanceWorkflowImpl
    implements ExportInstanceWorkflow
{
    private final DecisionContextProvider contextProvider =
        new DecisionContextProviderImpl();

    private final ControlActivitiesClient controlActivities =
        new ControlActivitiesClientImpl();

    private final ExportSnapshotWorkflowClientFactory exportSnapshotFlowFactory =
        new ExportSnapshotWorkflowClientFactoryImpl();

    private final JobWorkflowClientFactory jobFlowFactory =
        new JobWorkflowClientFactoryImpl();

    private final RdsActivitiesClient rdsActivities =
        new RdsActivitiesClientImpl();

    private ExportInstanceRequest request;

    private final CheckWaitWorkflowClientFactory waitFlowFactory =
        new CheckWaitWorkflowClientFactoryImpl();

    private String workflowId;

    @Asynchronous
    private Promise<ExportSnapshotRequest> createExportSnapshotRequest( Promise<String> snapshotName,
                                                                        Promise<DatabaseInstance> database )
    {
        ExportSnapshotRequest snapshotRequest = new ExportSnapshotRequest();
        snapshotRequest.setDatabaseMasterPassword( request.getDatabaseMasterPassword() );
        snapshotRequest.setDestinationArchive( request.getDestinationArchive() );
        snapshotRequest.setIdentity( request.getIdentity() );
        snapshotRequest.setSnapshotName( snapshotName.get() );
        snapshotRequest.setSnapshotRestoreTimeoutSeconds( request.getSnapshotCreationTimeoutSeconds() );
        snapshotRequest.setSubnetGroupName( database.get().getSubnetGroupName() );
        snapshotRequest.setWorkerOptions( request.getWorkerOptions() );
        return Promise.asPromise( snapshotRequest );
    }

    @Asynchronous
    private void doExport( Promise<?>... waitFor )
    {
        if ( request.isLiveInstanceTouched() )
        {
            Promise<DatabaseInstance> db =
                rdsActivities.describeInstance( request.getInstanceName(),
                                                request.getIdentity() );
            exportLiveInstance( db );
        }
        else
        {
            exportWithSnapshot();
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public void export( final ExportInstanceRequest request )
    {
        this.request = request;
        this.workflowId =
            contextProvider.getDecisionContext().getWorkflowContext().getWorkflowExecution().getWorkflowId();

        Promise<Void> started = controlActivities.notifyJobStarted();
        doExport( started );
    }

    @Asynchronous
    private void exportLiveInstance( final Promise<DatabaseInstance> db )
    {
        new TryCatch()
        {
            @Override
            protected void doCatch( Throwable cause )
                throws Throwable
            {
                controlActivities.notifyJobFailed( cause );
            }

            @Override
            protected void doTry()
                throws Throwable
            {
                MySQLDumpJob job = new MySQLDumpJob();
                job.setDataArchive( request.getDestinationArchive() );
                job.setDatabaseInstance( db.get() );
                job.setIdentity( request.getIdentity() );
                job.setMasterPassword( request.getDatabaseMasterPassword() );

                RunJobRequest runJob = new RunJobRequest();
                runJob.setJob( job );
                runJob.setIdentity( request.getIdentity() );
                runJob.setWorkerOptions( request.getWorkerOptions() );
                Promise<Void> done =
                    jobFlowFactory.getClient( workflowId
                                                  + Constants.JOB_WORKFLOW_ID_SUFFIX ).executeCommand( runJob );
                controlActivities.notifyJobCompleted( done );
            }
        };
    }

    @Asynchronous
    private void exportWithSnapshot()
    {
        final Promise<DatabaseInstance> database =
            rdsActivities.describeInstance( request.getInstanceName(),
                                            request.getIdentity() );
        final Promise<String> snapshotName =
            controlActivities.createSnapshotName( request.getInstanceName() );
        Promise<Void> done =
            rdsActivities.createSnapshot( snapshotName,
                                          Promise.asPromise( request.getInstanceName() ),
                                          Promise.asPromise( request.getIdentity() ),
                                          database );
        new TryCatchFinally( done )
        {
            @Override
            protected void doCatch( Throwable cause )
            {
                controlActivities.notifyJobFailed( cause );
            }

            @Override
            protected void doFinally()
                throws Throwable
            {
                rdsActivities.deleteSnapshot( snapshotName,
                                              Promise.asPromise( request.getIdentity() ) );
            }

            @Override
            protected void doTry()
            {
                Promise<Void> done = waitUntilSnapshotAvailable( snapshotName );
                Promise<ExportSnapshotRequest> snapshotRequest =
                    createExportSnapshotRequest( snapshotName, database );
                done =
                    exportSnapshotFlowFactory.getClient( workflowId
                                                             + "-snapshot" ).export( snapshotRequest,
                                                                                     done );
                controlActivities.notifyJobCompleted( done );
            }
        };
    }

    @Asynchronous
    private Promise<Void> waitUntilSnapshotAvailable( Promise<String> snapshotName,
                                                      Promise<?>... waitFor )
    {
        CheckAndWait check = new CheckAndWait();
        check.setCheckType( CheckAndWait.Type.SNAPSHOT_CREATION );
        // Hardcoded 1 hour wait for now
        check.setExpireOn( contextProvider.getDecisionContext().getWorkflowClock().currentTimeMillis()
            + request.getSnapshotCreationTimeoutSeconds() * 1000L );
        check.setIdentity( request.getIdentity() );
        check.setObjectName( snapshotName.get() );
        return waitFlowFactory.getClient( workflowId + "-snapshot-creation" ).checkAndWait( check );
    }
}
