package org.cyclopsgroup.datamung.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cyclopsgroup.datamung.api.types.S3DataArchive;
import org.cyclopsgroup.datamung.swf.interfaces.AgentActivities;
import org.cyclopsgroup.datamung.swf.types.CommandLineJob;
import org.cyclopsgroup.datamung.swf.types.Job;
import org.cyclopsgroup.datamung.swf.types.JobResult;
import org.cyclopsgroup.datamung.swf.types.MySQLDumpJob;
import org.cyclopsgroup.kaufman.logging.InvocationLoggingDecorator;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

public class AgentActivitiesImpl
    implements AgentActivities
{
    private static final Log LOG =
        LogFactory.getLog( AgentActivitiesImpl.class );

    private static Callable<String> collectOutput( final InputStream in )
    {
        return new Callable<String>()
        {
            @Override
            public String call()
                throws Exception
            {
                try
                {
                    return IOUtils.toString( in );
                }
                finally
                {
                    IOUtils.closeQuietly( in );
                }
            }
        };
    }

    private static String read( Future<String> out )
        throws InterruptedException, ExecutionException
    {
        String s = out.get();
        s = StringUtils.removeEnd( s, SystemUtils.LINE_SEPARATOR );
        return StringUtils.right( s, 80 );
    }

    private int runCommandLine( String commandLine, ExecutorService executor,
                                JobResult result )
        throws IOException, InterruptedException, ExecutionException
    {
        System.out.println( "Running command [" + commandLine + "] with sh" );
        Process proc =
            Runtime.getRuntime().exec( new String[] { "sh", "-c", commandLine } );
        Future<String> standardOutput =
            executor.submit( collectOutput( proc.getInputStream() ) );
        Future<String> errorOutput =
            executor.submit( collectOutput( proc.getErrorStream() ) );
        result.setExitCode( proc.waitFor() );
        result.setErrorOutput( read( errorOutput ) );
        result.setStandardOutput( read( standardOutput ) );
        return result.getExitCode();
    }

    /**
     * @inheritDoc
     */
    @Override
    public JobResult runJob( Job job )
    {
        ExecutorService executor = Executors.newFixedThreadPool( 2 );
        JobResult result = new JobResult();
        result.setStarted( System.currentTimeMillis() );
        try
        {
            switch ( job.getJobType() )
            {
                case COMMAND_LINE:
                    runCommandLine( ( (CommandLineJob) job ).getCommand(),
                                    executor, result );
                    break;
                case MYSQLDUMP:
                    runMySQLDumpJob( (MySQLDumpJob) job, executor, result );
                    break;
                default:
                    throw new IllegalArgumentException( "Unexpected job type "
                        + job.getJobType() );
            }
        }
        catch ( Throwable e )
        {
            result.setStackTrace( ExceptionUtils.getStackTrace( e ) );
            LOG.error( "Execution failed " + e.getMessage(), e );
        }
        finally
        {
            result.setElapsedMillis( System.currentTimeMillis()
                - result.getStarted() );
            executor.shutdownNow();
        }
        return result;
    }

    private void runMySQLDumpJob( MySQLDumpJob job, ExecutorService executor,
                                  JobResult result )
        throws IOException, InterruptedException, ExecutionException
    {
        S3DataArchive dataArchive = (S3DataArchive) job.getDataArchive();
        AWSCredentials creds =
            new BasicAWSCredentials( job.getIdentity().getAwsAccessKeyId(),
                                     job.getIdentity().getAwsSecretKey() );
        AmazonS3 s3 =
            InvocationLoggingDecorator.decorate( AmazonS3.class,
                                                 new AmazonS3Client( creds ) );

        StringBuilder command = new StringBuilder( "/usr/bin/mysqldump" );
        command.append( " -h " ).append( job.getDatabaseInstance().getPublicHostName() );
        int port = job.getDatabaseInstance().getPort();
        if ( port > 0 && port != 3306 )
        {
            command.append( " -P " ).append( job.getDatabaseInstance().getPort() );
        }
        command.append( " -u " ).append( job.getDatabaseInstance().getMasterUser() );
        command.append( " --password=" ).append( job.getMasterPassword() );
        command.append( " --all-databases -q --compact --protocol=tcp | gzip" );

        File tempFile = File.createTempFile( "datamung-data-", ".gz" );
        try
        {
            LOG.info( "Dumping database to file " + tempFile );
            String cmd =
                command.append( " > " ).append( tempFile.getAbsolutePath() ).toString();
            int exitCode = runCommandLine( cmd, executor, result );
            if ( exitCode != 0 )
            {
                throw new IOException( "mysqldump command [" + cmd
                    + "] failed with exit code " + exitCode );
            }
            s3.putObject( dataArchive.getBucketName(),
                          dataArchive.getObjectKey(), tempFile );
        }
        finally
        {
            LOG.info( "Deleting temporary file " + tempFile );
            tempFile.delete();
        }
    }
}
